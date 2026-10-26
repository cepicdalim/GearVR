import AppKit
import CoreMedia
import Foundation
import Network
import ScreenCaptureKit
import VideoToolbox

struct DisplayChoice: Identifiable {
    let displayID: CGDirectDisplayID
    let name: String

    var id: CGDirectDisplayID { displayID }
}

@MainActor
final class StreamSender: NSObject, ObservableObject {
    @Published var displays: [DisplayChoice] = []
    @Published var selectedDisplayID: CGDirectDisplayID = CGMainDisplayID()
    @Published private(set) var status = "Idle"
    @Published private(set) var receiverStatus = "Starting sender..."
    @Published private(set) var isStreaming = false
    @Published private(set) var encodedFrameCount = 0
    @Published private(set) var encodedByteCount = 0
    @Published var errorMessage: String?

    private var capture: DisplayCapture?
    private var server: VideoServer?

    override init() {
        super.init()
    }

    func refreshDisplays() async {
        do {
            let shareable = try await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: true
            )
            displays = shareable.displays.map { display in
                let name = NSScreen.screens.first(where: {
                    $0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID == display.displayID
                })?.localizedName ?? "Display \(display.displayID)"
                return DisplayChoice(displayID: display.displayID, name: name)
            }
            if !displays.contains(where: { $0.displayID == selectedDisplayID }) {
                selectedDisplayID = displays.first?.displayID ?? CGMainDisplayID()
            }
            errorMessage = nil
        } catch {
            errorMessage = "Screen capture permission is required. Allow it in System Settings > Privacy & Security > Screen Recording, then reopen the app. (\(error.localizedDescription))"
        }
    }

    func startServer() {
        guard server == nil else { return }
        receiverStatus = "Starting sender..."
        let server = VideoServer { [weak self] status in
            Task { @MainActor in self?.receiverStatus = status }
        }
        self.server = server
        server.start()
    }

    func stopServer() {
        server?.stop()
        server = nil
        receiverStatus = "Server stopped"
    }

    func startStreaming() {
        guard !isStreaming else { return }
        errorMessage = nil
        Task {
            await beginCapture()
        }
    }

    func stopStreaming() {
        capture?.stop()
        capture = nil
        isStreaming = false
        status = "Idle"
    }

    func reportServerStatus(_ status: String) {
        receiverStatus = status
    }

    private func beginCapture() async {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: true
            )
            guard let display = content.displays.first(where: { $0.displayID == selectedDisplayID }) else {
                throw CaptureError.displayUnavailable
            }

            let stream = DisplayCapture(display: display, server: server) { [weak self] result in
                Task { @MainActor in
                    switch result {
                    case .success(let progress):
                        let (frameCount, byteCount) = progress
                        self?.encodedFrameCount = frameCount
                        self?.encodedByteCount = byteCount
                        self?.status = "H.264 active - \(frameCount) frames, \(byteCount / 1_000_000) MB"
                    case .failure(let error):
                        self?.errorMessage = error.localizedDescription
                        self?.stopStreaming()
                    }
                }
            }
            capture = stream
            try await stream.start()
            isStreaming = true
            status = "Capturing selected display"
        } catch {
            errorMessage = error.localizedDescription
            status = "Capture failed"
        }
    }

    private enum CaptureError: LocalizedError {
        case displayUnavailable

        var errorDescription: String? { "The selected display is no longer available." }
    }
}

private final class DisplayCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private let display: SCDisplay
    private weak var server: VideoServer?
    private let onStatus: (Result<(Int, Int), Error>) -> Void
    private let sampleQueue = DispatchQueue(label: "local.gearvr.stream.capture", qos: .userInteractive)
    private var stream: SCStream?
    private var encoder: H264Encoder?
    private var encodedFrameCount = 0
    private var encodedByteCount = 0

    init(display: SCDisplay, server: VideoServer?, onStatus: @escaping (Result<(Int, Int), Error>) -> Void) {
        self.display = display
        self.server = server
        self.onStatus = onStatus
    }

    func start() async throws {
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        configuration.width = 1920
        configuration.height = 1080
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        configuration.queueDepth = 3
        configuration.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        configuration.capturesAudio = false
        configuration.showsCursor = true

        let encoder = try H264Encoder { [weak self] result in
            switch result {
            case .success(let sampleBuffer):
                guard let self else { return }
                let byteCount = CMSampleBufferGetTotalSampleSize(sampleBuffer)
                self.server?.send(sampleBuffer)
                self.encodedFrameCount += 1
                self.encodedByteCount += byteCount
                    if self.encodedFrameCount.isMultiple(of: 60) {
                    self.onStatus(.success((self.encodedFrameCount, self.encodedByteCount)))
                }
            case .failure(let error):
                self?.onStatus(.failure(error))
            }
        }
        self.encoder = encoder
        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: sampleQueue)
        self.stream = stream
        try await stream.startCapture()
    }

    func stop() {
        guard let stream else { return }
        Task {
            try? await stream.stopCapture()
        }
        self.stream = nil
        encoder?.invalidate()
        encoder = nil
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of outputType: SCStreamOutputType) {
        guard outputType == .screen,
              sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer,
                                         createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let frameStatusValue = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: frameStatusValue) == .complete,
              CMSampleBufferGetImageBuffer(sampleBuffer) != nil else { return }
        do {
            try encoder?.encode(sampleBuffer)
        } catch {
            onStatus(.failure(error))
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStatus(.failure(error))
    }
}

private final class H264Encoder {
    private let onFrameEncoded: (Result<CMSampleBuffer, Error>) -> Void
    private var session: VTCompressionSession?

    init(onFrameEncoded: @escaping (Result<CMSampleBuffer, Error>) -> Void) throws {
        self.onFrameEncoded = onFrameEncoded
        var createdSession: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: 1920,
            height: 1080,
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: [kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true] as CFDictionary,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: { refcon, _, status, _, sampleBuffer in
                guard let refcon else { return }
                let encoder = Unmanaged<H264Encoder>.fromOpaque(refcon).takeUnretainedValue()
                guard status == noErr, let sampleBuffer, CMSampleBufferDataIsReady(sampleBuffer) else {
                    encoder.onFrameEncoded(.failure(EncoderError.encodingFailed(status)))
                    return
                }
                encoder.onFrameEncoded(.success(sampleBuffer))
            },
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &createdSession
        )
        guard status == noErr, let createdSession else {
            throw EncoderError.creationFailed(status)
        }
        session = createdSession
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_RealTime,
                             value: kCFBooleanTrue)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_AllowFrameReordering,
                             value: kCFBooleanFalse)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_MaxKeyFrameInterval,
                             value: 60 as CFNumber)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_AverageBitRate,
                             value: 18_000_000 as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(createdSession)
    }

    func encode(_ sampleBuffer: CMSampleBuffer) throws {
        guard let session, let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let duration = CMSampleBufferGetDuration(sampleBuffer)
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: imageBuffer,
            presentationTimeStamp: presentationTime,
            duration: duration,
            frameProperties: nil,
            sourceFrameRefcon: nil,
            infoFlagsOut: nil
        )
        guard status == noErr else { throw EncoderError.encodingFailed(status) }
    }

    func invalidate() {
        guard let session else { return }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        VTCompressionSessionInvalidate(session)
        self.session = nil
    }

    private enum EncoderError: LocalizedError {
        case creationFailed(OSStatus)
        case encodingFailed(OSStatus)

        var errorDescription: String? {
            switch self {
            case .creationFailed(let status): "Could not create the H.264 encoder (\(status))."
            case .encodingFailed(let status): "H.264 encoding failed (\(status))."
            }
        }
    }
}

private final class VideoServer {
    private let queue = DispatchQueue(label: "local.gearvr.stream.server")
    private let onStatus: (String) -> Void
    private var listener: NWListener?
    private var connection: NWConnection?
    private var sentConfiguration = false
    private var sendInProgress = false

    init(onStatus: @escaping (String) -> Void) {
        self.onStatus = onStatus
    }

    func start() {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let port = NWEndpoint.Port(rawValue: UInt16(StreamProtocol.signalingPort))!
                let listener = try NWListener(using: .tcp, on: port)
                    listener.service = NWListener.Service(name: "GearVR Stream", type: StreamProtocol.senderServiceType)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.accept(connection)
                }
                listener.stateUpdateHandler = { [weak self] state in
                    if case .failed(let error) = state {
                            self?.onStatus("Server error: \(error.localizedDescription)")
                    } else if case .ready = state {
                            self?.onStatus("Server ready; waiting for Gear VR receiver")
                    }
                }
                self.listener = listener
                listener.start(queue: self.queue)
            } catch {
                self.onStatus("Server error: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.connection?.cancel()
            self?.connection = nil
            self?.listener?.cancel()
            self?.listener = nil
            self?.sendInProgress = false
            self?.onStatus("Server stopped")
        }
    }

    func send(_ sampleBuffer: CMSampleBuffer) {
        queue.async { [weak self] in
            guard let self, let connection = self.connection,
                  case .ready = connection.state, !self.sendInProgress else { return }
            if !self.sentConfiguration && !self.isKeyFrame(sampleBuffer) { return }
            do {
                var packet = Data()
                if !self.sentConfiguration {
                    packet.append(try self.configurationPacket(for: sampleBuffer))
                    self.sentConfiguration = true
                }
                packet.append(try self.framePacket(for: sampleBuffer))
                self.sendInProgress = true
                connection.send(content: packet, completion: .contentProcessed { [weak self] error in
                    guard let self else { return }
                    self.queue.async {
                        guard self.connection === connection else { return }
                        self.sendInProgress = false
                        if let error {
                            self.onStatus("Stream send failed: \(error.localizedDescription)")
                            connection.cancel()
                            self.connection = nil
                            self.sentConfiguration = false
                        }
                    }
                })
            } catch {
                self.onStatus("Stream send failed: \(error.localizedDescription)")
                connection.cancel()
                self.connection = nil
                self.sentConfiguration = false
                self.sendInProgress = false
            }
        }
    }

    private func accept(_ connection: NWConnection) {
        self.connection?.cancel()
        self.connection = connection
        sentConfiguration = false
        sendInProgress = false
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            if case .ready = state {
                self.onStatus("Receiver connected: \(connection.endpoint)")
            } else if case .failed(_) = state {
                self.connectionEnded(connection)
            } else if case .cancelled = state {
                self.connectionEnded(connection)
            }
        }
        connection.start(queue: queue)
    }

    private func connectionEnded(_ connection: NWConnection) {
        guard self.connection === connection else { return }
        self.connection = nil
        self.sentConfiguration = false
        self.sendInProgress = false
        onStatus("Server ready; waiting for Gear VR receiver")
    }

    private func configurationPacket(for sampleBuffer: CMSampleBuffer) throws -> Data {
        guard let format = CMSampleBufferGetFormatDescription(sampleBuffer) else {
            throw VideoServerError.missingFormat
        }
        var parameterSets: [Data] = []
        for index in 0..<2 {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            var count: Int = 0
            var headerLength: Int32 = 0
            let status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size, parameterSetCountOut: &count,
                nalUnitHeaderLengthOut: &headerLength
            )
            guard status == noErr, let pointer else { throw VideoServerError.missingFormat }
            parameterSets.append(Data(bytes: pointer, count: size))
        }

        var payload = Data("GVR1".utf8)
        payload.append(contentsOf: UInt16(StreamProtocol.width).bigEndianBytes)
        payload.append(contentsOf: UInt16(StreamProtocol.height).bigEndianBytes)
        for parameterSet in parameterSets {
            payload.append(contentsOf: UInt16(parameterSet.count).bigEndianBytes)
            payload.append(parameterSet)
        }
        return try framedPacket(payload)
    }

    private func framePacket(for sampleBuffer: CMSampleBuffer) throws -> Data {
        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            throw VideoServerError.missingFrame
        }
        let length = CMBlockBufferGetDataLength(block)
        var frame = Data(count: length)
        let status = frame.withUnsafeMutableBytes { bytes in
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length,
                                       destination: bytes.baseAddress!)
        }
        guard status == kCMBlockBufferNoErr else { throw VideoServerError.missingFrame }
        var payload = Data()
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        payload.append(contentsOf: UInt64(max(0, Int64(CMTimeConvertScale(timestamp, timescale: 1_000_000, method: .default).value))).bigEndianBytes)
        payload.append(isKeyFrame(sampleBuffer) ? 1 : 0)
        payload.append(frame)
        return try framedPacket(payload)
    }

    private func isKeyFrame(_ sampleBuffer: CMSampleBuffer) -> Bool {
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]]
        return (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true
            || (attachments?.first?[kCMSampleAttachmentKey_DependsOnOthers] as? Bool == false)
    }

    private func framedPacket(_ payload: Data) throws -> Data {
        guard payload.count <= Int(UInt32.max) else { throw VideoServerError.packetTooLarge }
        var packet = Data(UInt32(payload.count).bigEndianBytes)
        packet.append(payload)
        return packet
    }

    private enum VideoServerError: LocalizedError {
        case missingFormat
        case missingFrame
        case packetTooLarge

        var errorDescription: String? {
            switch self {
            case .missingFormat: "The encoder did not provide H.264 configuration data."
            case .missingFrame: "The encoder produced an unreadable frame."
            case .packetTooLarge: "An encoded frame exceeded the network packet limit."
            }
        }
    }
}

private extension FixedWidthInteger {
    var bigEndianBytes: [UInt8] {
        withUnsafeBytes(of: bigEndian, Array.init)
    }
}