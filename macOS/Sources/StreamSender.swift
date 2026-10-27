import AppKit
import CoreMedia
import CoreImage
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
    @Published var shareBothDisplays = false
    @Published var secondDisplayID: CGDirectDisplayID = 0
    @Published private(set) var status = "Idle"
    @Published private(set) var receiverStatus = "Starting sender..."
    @Published private(set) var isStreaming = false
    @Published private(set) var encodedFrameCount = 0
    @Published private(set) var encodedByteCount = 0
    @Published var errorMessage: String?
    @Published var remoteMouseEnabled = false {
        didSet {
            if remoteMouseEnabled && !AXIsProcessTrusted() {
                remoteMouseEnabled = false
                inputStatus = "Accessibility permission required"
                let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true]
                _ = AXIsProcessTrustedWithOptions(options as CFDictionary)
                return
            }
            if !remoteMouseEnabled { releaseRemoteMouse() }
            inputStatus = remoteMouseEnabled ? "Waiting for pointer input" : "Remote mouse disabled"
        }
    }
    @Published var motionForwardingEnabled = false
    @Published private(set) var inputStatus = "Remote mouse disabled"
    @Published private(set) var motionStatus = "No tracking data"
    private var inputDisplays: [StreamProtocol.InputDisplay] = []
    private var mouseHeld = false
    private var mouseDownTime = Date.distantPast
    private var mouseDownPoint = CGPoint.zero
    private var mouseDragStarted = false
    private var contextClickSent = false
    private var lastMousePoint = CGPoint.zero
    private var lastPointerTime = Date.distantPast
    private var inputWatchdog: Timer?
    private var lastClickTime = Date.distantPast
    private var lastClickPoint = CGPoint.zero
    private var clickCount: Int64 = 1
    private var lastMotionStatusTime = Date.distantPast
    private let motionConnection = NWConnection(host: "127.0.0.1",
        port: NWEndpoint.Port(rawValue: StreamProtocol.motionPort)!, using: .udp)
    private var motionSendPending = false

    private var capture: DisplayCapture?
    private var server: VideoServer?

    override init() {
        super.init()
        motionConnection.start(queue: DispatchQueue(label: "local.gearvr.motion"))
        inputWatchdog = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if Date().timeIntervalSince(self.lastPointerTime) > 1 { self.releaseRemoteMouse() }
                if self.mouseHeld && !self.mouseDragStarted && !self.contextClickSent
                    && Date().timeIntervalSince(self.mouseDownTime) >= 0.7 {
                    self.postMouse(.leftMouseUp, at: self.lastMousePoint)
                    self.contextClickSent = true
                    self.postMouse(.rightMouseDown, at: self.lastMousePoint)
                    self.postMouse(.rightMouseUp, at: self.lastMousePoint)
                }
                if Date().timeIntervalSince(self.lastMotionStatusTime) > 2 { self.motionStatus = "No tracking data" }
            }
        }
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
            if !displays.contains(where: { $0.displayID == secondDisplayID }) || secondDisplayID == selectedDisplayID {
                secondDisplayID = displays.first(where: { $0.displayID != selectedDisplayID })?.displayID ?? 0
            }
            errorMessage = nil
        } catch {
            errorMessage = "Screen capture permission is required. Allow it in System Settings > Privacy & Security > Screen Recording, then reopen the app. (\(error.localizedDescription))"
        }
    }

    func startServer() {
        guard server == nil else { return }
        receiverStatus = "Starting sender..."
        let server = VideoServer(onInput: { [weak self] event in
            Task { @MainActor in self?.handleInput(event) }
        }, onDisconnect: { [weak self] in
            Task { @MainActor in self?.releaseRemoteMouse() }
        }) { [weak self] status in
            Task { @MainActor in self?.receiverStatus = status }
        }
        self.server = server
        server.start()
    }

    func stopServer() {
        releaseRemoteMouse()
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
        releaseRemoteMouse()
        inputDisplays = []
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

            var selectedDisplays = [display]
            if shareBothDisplays {
                guard secondDisplayID != selectedDisplayID,
                      let secondDisplay = content.displays.first(where: { $0.displayID == secondDisplayID }) else {
                    throw CaptureError.displayUnavailable
                }
                selectedDisplays.append(secondDisplay)
            }
            let stream = DisplayCapture(displays: selectedDisplays, server: server) { [weak self] result in
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
            inputDisplays = selectedDisplays.map {
                StreamProtocol.InputDisplay(bounds: CGDisplayBounds($0.displayID),
                    width: Double($0.width), height: Double($0.height))
            }
            isStreaming = true
            status = selectedDisplays.count == 2 ? "Sharing two displays side by side" : "Capturing selected display"
        } catch {
            errorMessage = error.localizedDescription
            status = "Capture failed"
        }
    }

    private func handleInput(_ event: StreamProtocol.InputEvent) {
        if event.type == "touchpad" {
            guard remoteMouseEnabled, isStreaming, AXIsProcessTrusted(),
                  let currentPoint = CGEvent(source: nil)?.location else {
                releaseRemoteMouse()
                return
            }
            lastPointerTime = Date()
            if event.action == "up" {
                lastMousePoint = currentPoint
                releaseRemoteMouse()
            } else if event.action == "down" {
                guard !mouseHeld else { return }
                let now = Date()
                clickCount = now.timeIntervalSince(lastClickTime) < NSEvent.doubleClickInterval
                    && hypot(currentPoint.x - lastClickPoint.x, currentPoint.y - lastClickPoint.y) < 6
                    ? min(3, clickCount + 1) : 1
                lastClickTime = now
                lastClickPoint = currentPoint
                lastMousePoint = currentPoint
                mouseHeld = true
                postMouse(.leftMouseDown, at: currentPoint)
            } else if let deltaX = event.deltaX, let deltaY = event.deltaY {
                guard !mouseHeld else { return }
                let scroll = CGEvent(scrollWheelEvent2Source: CGEventSource(stateID: .hidSystemState),
                    units: .pixel, wheelCount: 2, wheel1: Int32(deltaY.rounded()),
                    wheel2: Int32(deltaX.rounded()), wheel3: 0)
                scroll?.post(tap: .cghidEventTap)
            }
            return
        }
        if event.type == "restart" {
            releaseRemoteMouse()
            capture?.restartEncoding()
            return
        }
        if event.type == "pose" {
            if mouseHeld { lastPointerTime = Date() }
            if Date().timeIntervalSince(lastMotionStatusTime) >= 1 {
                lastMotionStatusTime = Date()
                motionStatus = event.controller == nil ? "Head tracking active" : "Head + controller tracking active"
            }
            if motionForwardingEnabled && isStreaming && !motionSendPending,
               let data = try? JSONEncoder().encode(event) {
                motionSendPending = true
                motionConnection.send(content: data, completion: .contentProcessed { [weak self] error in
                    Task { @MainActor in
                        self?.motionSendPending = false
                        if let error { self?.motionStatus = "Motion feed: \(error.localizedDescription)" }
                    }
                })
            }
            return
        }
        guard remoteMouseEnabled, isStreaming, AXIsProcessTrusted() else {
            releaseRemoteMouse()
            return
        }
        if event.type == "scroll" {
            guard !mouseHeld, let deltaX = event.deltaX, let deltaY = event.deltaY else { return }
            let scroll = CGEvent(scrollWheelEvent2Source: CGEventSource(stateID: .hidSystemState),
                units: .pixel, wheelCount: 2, wheel1: Int32(deltaY.rounded()),
                wheel2: Int32(deltaX.rounded()), wheel3: 0)
            scroll?.post(tap: .cghidEventTap)
            return
        }
        if event.action == "up" {
            if let x = event.x, let y = event.y,
               let point = StreamProtocol.desktopPoint(x: x, y: y, displays: inputDisplays) {
                lastMousePoint = point
            }
            releaseRemoteMouse()
            return
        }
        guard let x = event.x, let y = event.y,
              let point = StreamProtocol.desktopPoint(x: x, y: y, displays: inputDisplays) else { return }
        let eventType: CGEventType
        if event.action == "down" {
            guard !mouseHeld else { return }
            let now = Date()
            let distance = hypot(point.x - lastClickPoint.x, point.y - lastClickPoint.y)
            clickCount = now.timeIntervalSince(lastClickTime) < NSEvent.doubleClickInterval && distance < 6
                ? min(3, clickCount + 1) : 1
            lastClickTime = now
            lastClickPoint = point
            mouseHeld = true
            mouseDownTime = now
            mouseDownPoint = point
            mouseDragStarted = false
            contextClickSent = false
            eventType = .leftMouseDown
        } else {
            guard mouseHeld && !contextClickSent else { return }
            if mouseHeld && hypot(point.x - mouseDownPoint.x, point.y - mouseDownPoint.y) >= 10 {
                mouseDragStarted = true
            }
            eventType = .leftMouseDragged
        }
        lastMousePoint = point
        lastPointerTime = Date()
        postMouse(eventType, at: point)
        inputStatus = "Remote mouse active"
    }

    private func postMouse(_ type: CGEventType, at point: CGPoint) {
        let event = CGEvent(mouseEventSource: CGEventSource(stateID: .hidSystemState),
            mouseType: type, mouseCursorPosition: point,
            mouseButton: type == .rightMouseDown || type == .rightMouseUp ? .right : .left)
        event?.setIntegerValueField(.mouseEventClickState, value: type == .mouseMoved || type == .leftMouseDragged ? 0 : clickCount)
        event?.post(tap: .cghidEventTap)
    }

    private func releaseRemoteMouse() {
        if mouseHeld && !contextClickSent { postMouse(.leftMouseUp, at: lastMousePoint) }
        mouseHeld = false
        contextClickSent = false
    }

    private enum CaptureError: LocalizedError {
        case displayUnavailable

        var errorDescription: String? { "The selected display is no longer available." }
    }
}

private final class DisplayCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private let displays: [SCDisplay]
    private weak var server: VideoServer?
    private let onStatus: (Result<(Int, Int), Error>) -> Void
    private let sampleQueue = DispatchQueue(label: "local.gearvr.stream.capture", qos: .userInteractive)
    private var stream: SCStream?
    private var secondaryStream: SCStream?
    private var latestFrames: [CGDirectDisplayID: CVPixelBuffer] = [:]
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private var compositePool: CVPixelBufferPool?
    private var compositeWidth = 0
    private var compositeHeight = 0
    private var encoder: H264Encoder?
    private var encodedFrameCount = 0
    private var encodedByteCount = 0

    init(displays: [SCDisplay], server: VideoServer?, onStatus: @escaping (Result<(Int, Int), Error>) -> Void) {
        self.displays = displays
        self.server = server
        self.onStatus = onStatus
    }

    func start() async throws {
        let display = displays[0]
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        let totalWidth = displays.reduce(0) { $0 + $1.width }
        let totalHeight = displays.map(\.height).max() ?? display.height
        let scale = min(1920.0 / Double(totalWidth), 1080.0 / Double(totalHeight))
        compositeWidth = max(16, Int(Double(totalWidth) * scale) / 16 * 16)
        compositeHeight = max(2, Int(Double(totalHeight) * scale) / 2 * 2)
        configuration.width = displays.count == 1 ? compositeWidth : max(16, Int(Double(display.width) * scale) / 16 * 16)
        configuration.height = displays.count == 1 ? compositeHeight : max(2, Int(Double(display.height) * scale) / 2 * 2)
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        configuration.queueDepth = 2
        configuration.pixelFormat = displays.count == 1 ? kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange : kCVPixelFormatType_32BGRA
        configuration.capturesAudio = false
        configuration.showsCursor = true

        if displays.count == 2 {
            let poolStatus = CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey: compositeWidth,
                kCVPixelBufferHeightKey: compositeHeight,
                kCVPixelBufferIOSurfacePropertiesKey: [:]
            ] as CFDictionary, &compositePool)
            guard poolStatus == kCVReturnSuccess else { throw CompositionError.bufferUnavailable }
        }
        self.encoder = try makeEncoder()
        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: sampleQueue)
        self.stream = stream
        do {
            try await stream.startCapture()
            if displays.count == 2 {
                let secondDisplay = displays[1]
                let secondConfiguration = SCStreamConfiguration()
                secondConfiguration.width = max(16, Int(Double(secondDisplay.width) * scale) / 16 * 16)
                secondConfiguration.height = max(2, Int(Double(secondDisplay.height) * scale) / 2 * 2)
                secondConfiguration.minimumFrameInterval = configuration.minimumFrameInterval
                secondConfiguration.queueDepth = 2
                secondConfiguration.pixelFormat = kCVPixelFormatType_32BGRA
                secondConfiguration.capturesAudio = false
                secondConfiguration.showsCursor = true
                let secondStream = SCStream(filter: SCContentFilter(display: secondDisplay, excludingWindows: []),
                                            configuration: secondConfiguration, delegate: self)
                try secondStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: sampleQueue)
                secondaryStream = secondStream
                try await secondStream.startCapture()
            }
        } catch {
            stop()
            throw error
        }
    }

    func restartEncoding() {
        sampleQueue.async { [weak self] in
            guard let self, self.encoder != nil else { return }
            self.encoder?.invalidate()
            self.encoder = nil
            self.latestFrames.removeAll()
            self.server?.resetVideoConfiguration()
            do {
                self.encoder = try self.makeEncoder()
            } catch {
                self.onStatus(.failure(error))
            }
        }
    }

    private func makeEncoder() throws -> H264Encoder {
        try H264Encoder(width: compositeWidth, height: compositeHeight) { [weak self] result, completion in
            switch result {
            case .success(let sampleBuffer):
                guard let self, let server = self.server else {
                    completion()
                    return
                }
                let byteCount = CMSampleBufferGetTotalSampleSize(sampleBuffer)
                server.send(sampleBuffer, completion: completion)
                self.encodedFrameCount += 1
                self.encodedByteCount += byteCount
                if self.encodedFrameCount.isMultiple(of: 60) {
                    self.onStatus(.success((self.encodedFrameCount, self.encodedByteCount)))
                }
            case .failure(let error):
                completion()
                self?.onStatus(.failure(error))
            }
        }
    }

    func stop() {
        let captures = [stream, secondaryStream].compactMap { $0 }
        stream = nil
        secondaryStream = nil
        Task {
            for capture in captures { try? await capture.stopCapture() }
            sampleQueue.sync {
                encoder?.invalidate()
                encoder = nil
                latestFrames.removeAll()
            }
        }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of outputType: SCStreamOutputType) {
          guard stream === self.stream || stream === secondaryStream,
              outputType == .screen,
              sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer,
                                         createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let frameStatusValue = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: frameStatusValue) == .complete,
              CMSampleBufferGetImageBuffer(sampleBuffer) != nil else { return }
        do {
            if displays.count == 1 {
                try encoder?.encode(sampleBuffer, forceKeyFrame: server?.needsKeyFrame ?? false)
            } else {
                guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
                let displayID = stream === self.stream ? displays[0].displayID : displays[1].displayID
                latestFrames[displayID] = buffer
                guard latestFrames.count == displays.count else { return }
                let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                guard let composite = try compositeSample(at: timestamp) else { return }
                try encoder?.encode(composite, forceKeyFrame: server?.needsKeyFrame ?? false)
            }
        } catch {
            onStatus(.failure(error))
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStatus(.failure(error))
    }

    private func compositeSample(at timestamp: CMTime) throws -> CMSampleBuffer? {
        guard let compositePool else { throw CompositionError.bufferUnavailable }
        var output: CVPixelBuffer?
        let allocationStatus = CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(kCFAllocatorDefault, compositePool,
            [kCVPixelBufferPoolAllocationThresholdKey: 4] as CFDictionary, &output)
        if allocationStatus == kCVReturnWouldExceedAllocationThreshold { return nil }
        guard allocationStatus == kCVReturnSuccess,
              let output else { throw CompositionError.bufferUnavailable }
        let bounds = CGRect(x: 0, y: 0, width: compositeWidth, height: compositeHeight)
        var image = CIImage(color: .black).cropped(to: bounds)
        let totalWidth = displays.reduce(0) { $0 + $1.width }
        let totalHeight = displays.map(\.height).max() ?? 1
        var originX = 0.0
        for display in displays {
            guard let frame = latestFrames[display.displayID] else { throw CompositionError.bufferUnavailable }
            let width = Double(compositeWidth) * Double(display.width) / Double(totalWidth)
            let height = Double(compositeHeight) * Double(display.height) / Double(totalHeight)
            let source = CIImage(cvPixelBuffer: frame)
            let tile = source.transformed(by: CGAffineTransform(scaleX: width / source.extent.width,
                                                                 y: height / source.extent.height))
                .transformed(by: CGAffineTransform(translationX: originX, y: (Double(compositeHeight) - height) / 2))
            image = tile.composited(over: image)
            originX += width
        }
        imageContext.render(image, to: output, bounds: bounds, colorSpace: CGColorSpaceCreateDeviceRGB())
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
                imageBuffer: output, formatDescriptionOut: &format) == noErr,
              let format else { throw CompositionError.bufferUnavailable }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 60),
                                        presentationTimeStamp: timestamp, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: output,
                formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { throw CompositionError.bufferUnavailable }
        return sample
    }

    private enum CompositionError: LocalizedError {
        case bufferUnavailable
        var errorDescription: String? { "Unable to compose the shared displays." }
    }
}

private final class H264Encoder {
    private let onFrameEncoded: (Result<CMSampleBuffer, Error>, @escaping () -> Void) -> Void
    private let frameSlot = DispatchSemaphore(value: 1)
    private var session: VTCompressionSession?

    init(width: Int, height: Int, onFrameEncoded: @escaping (Result<CMSampleBuffer, Error>, @escaping () -> Void) -> Void) throws {
        self.onFrameEncoded = onFrameEncoded
        var createdSession: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(width),
            height: Int32(height),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: [
                kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true,
                kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true
            ] as CFDictionary,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: { refcon, _, status, flags, sampleBuffer in
                guard let refcon else { return }
                let encoder = Unmanaged<H264Encoder>.fromOpaque(refcon).takeUnretainedValue()
                let completion = { encoder.frameSlot.signal(); return }
                if status == noErr && flags.contains(.frameDropped) {
                    completion()
                    return
                }
                guard status == noErr, let sampleBuffer, CMSampleBufferDataIsReady(sampleBuffer) else {
                    encoder.onFrameEncoded(.failure(EncoderError.encodingFailed(status)), completion)
                    return
                }
                encoder.onFrameEncoded(.success(sampleBuffer), completion)
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
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_ExpectedFrameRate,
                     value: 60 as CFNumber)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_ProfileLevel,
                     value: kVTProfileLevel_H264_High_AutoLevel)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_AverageBitRate,
                     value: 35_000_000 as CFNumber)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_MaxAllowedFrameQP,
                     value: 20 as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(createdSession)
    }

    func encode(_ sampleBuffer: CMSampleBuffer, forceKeyFrame: Bool) throws {
        guard let session, let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard frameSlot.wait(timeout: .now()) == .success else { return }
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let duration = CMSampleBufferGetDuration(sampleBuffer)
        let frameProperties = forceKeyFrame
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: imageBuffer,
            presentationTimeStamp: presentationTime,
            duration: duration,
            frameProperties: frameProperties,
            sourceFrameRefcon: nil,
            infoFlagsOut: nil
        )
        guard status == noErr else {
            frameSlot.signal()
            throw EncoderError.encodingFailed(status)
        }
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
    private let onInput: (StreamProtocol.InputEvent) -> Void
    private let onDisconnect: () -> Void
    private var listener: NWListener?
    private var connection: NWConnection?
    private var sentConfiguration = false
    private var sentFormat: CMFormatDescription?

    var needsKeyFrame: Bool {
        queue.sync {
            guard let connection, case .ready = connection.state else { return false }
            return !sentConfiguration
        }
    }

    func resetVideoConfiguration() {
        queue.sync {
            sentConfiguration = false
            sentFormat = nil
        }
    }

    init(onInput: @escaping (StreamProtocol.InputEvent) -> Void,
         onDisconnect: @escaping () -> Void, onStatus: @escaping (String) -> Void) {
        self.onInput = onInput
        self.onDisconnect = onDisconnect
        self.onStatus = onStatus
    }

    func start() {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let port = NWEndpoint.Port(rawValue: UInt16(StreamProtocol.signalingPort))!
                let tcp = NWProtocolTCP.Options()
                tcp.noDelay = true
                let parameters = NWParameters(tls: nil, tcp: tcp)
                let listener = try NWListener(using: parameters, on: port)
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
            self?.onDisconnect()
            self?.connection?.cancel()
            self?.connection = nil
            self?.listener?.cancel()
            self?.listener = nil
            self?.onStatus("Server stopped")
        }
    }

    func send(_ sampleBuffer: CMSampleBuffer, completion: @escaping () -> Void) {
        queue.async { [weak self] in
            guard let self, let connection = self.connection,
                  case .ready = connection.state else {
                completion()
                return
            }
                        if let format = CMSampleBufferGetFormatDescription(sampleBuffer),
                             let sentFormat = self.sentFormat, !CFEqual(format, sentFormat) {
                                self.sentConfiguration = false
                        }
            if !self.sentConfiguration && !self.isKeyFrame(sampleBuffer) {
                completion()
                return
            }
            do {
                var packet = Data()
                if !self.sentConfiguration {
                    packet.append(try self.configurationPacket(for: sampleBuffer))
                    self.sentConfiguration = true
                    self.sentFormat = CMSampleBufferGetFormatDescription(sampleBuffer)
                }
                packet.append(try self.framePacket(for: sampleBuffer))
                connection.send(content: packet, completion: .contentProcessed { [weak self] error in
                    completion()
                    guard let self else { return }
                    self.queue.async {
                        guard self.connection === connection else { return }
                        if let error {
                            self.onStatus("Stream send failed: \(error.localizedDescription)")
                            connection.cancel()
                            self.connection = nil
                            self.onDisconnect()
                            self.sentConfiguration = false
                        }
                    }
                })
            } catch {
                completion()
                self.onStatus("Stream send failed: \(error.localizedDescription)")
                connection.cancel()
                self.connection = nil
                self.onDisconnect()
                self.sentConfiguration = false
            }
        }
    }

    private func accept(_ connection: NWConnection) {
        onDisconnect()
        self.connection?.cancel()
        self.connection = connection
        sentConfiguration = false
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            if case .ready = state {
                self.onStatus("Receiver connected: \(connection.endpoint)")
                self.receiveInputHeader(connection)
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
        onDisconnect()
        self.connection = nil
        self.sentConfiguration = false
        onStatus("Server ready; waiting for Gear VR receiver")
    }

    private func receiveInputHeader(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, complete, error in
            guard let self, self.connection === connection else { return }
            guard error == nil, !complete, let data, data.count == 4 else {
                connection.cancel()
                self.connectionEnded(connection)
                return
            }
            let length = data.reduce(0) { ($0 << 8) | Int($1) }
            guard length > 0, length <= StreamProtocol.maximumInputPacketSize else {
                connection.cancel()
                self.connectionEnded(connection)
                return
            }
            connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, complete, error in
                guard let self, self.connection === connection else { return }
                guard error == nil, !complete, let data, data.count == length,
                      let event = try? JSONDecoder().decode(StreamProtocol.InputEvent.self, from: data),
                      event.isValid else {
                    connection.cancel()
                    self.connectionEnded(connection)
                    return
                }
                self.onInput(event)
                if event.type == "restart" {
                    self.onStatus("Receiver requested a fresh video stream")
                }
                self.receiveInputHeader(connection)
            }
        }
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
        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        payload.append(contentsOf: UInt16(dimensions.width).bigEndianBytes)
        payload.append(contentsOf: UInt16(dimensions.height).bigEndianBytes)
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