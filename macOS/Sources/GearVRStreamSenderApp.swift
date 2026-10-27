import SwiftUI

@main
struct GearVRStreamSenderApp: App {
    @StateObject private var sender = StreamSender()

    var body: some Scene {
        WindowGroup {
            SenderView(sender: sender)
                .frame(minWidth: 520, minHeight: 500)
        }
        .windowResizability(.contentSize)
    }
}

private struct SenderView: View {
    @ObservedObject var sender: StreamSender

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .firstTextBaseline) {
                Text("GearVR Stream")
                    .font(.system(size: 26, weight: .semibold, design: .rounded))
                Spacer()
                Circle()
                    .fill(sender.isStreaming ? Color.green : Color.secondary.opacity(0.5))
                    .frame(width: 9, height: 9)
                Text(sender.status)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            Text("LOCAL DISPLAY SENDER")
                .font(.caption.weight(.semibold))
                .tracking(1.2)
                .foregroundStyle(.secondary)

            Picker("Display", selection: $sender.selectedDisplayID) {
                ForEach(sender.displays, id: \.displayID) { display in
                    Text(display.name).tag(display.displayID)
                }
            }
            .pickerStyle(.menu)
            .disabled(sender.isStreaming)

            Toggle("Share two displays side by side", isOn: $sender.shareBothDisplays)
                .disabled(sender.isStreaming || sender.displays.count < 2)

            if sender.shareBothDisplays {
                Picker("Right display", selection: $sender.secondDisplayID) {
                    ForEach(sender.displays.filter { $0.displayID != sender.selectedDisplayID }) { display in
                        Text(display.name).tag(display.displayID)
                    }
                }
                .pickerStyle(.menu)
                .disabled(sender.isStreaming)
            }

            HStack {
                Label("1080p", systemImage: "rectangle.inset.filled")
                Spacer()
                Label("60 fps", systemImage: "arrow.clockwise")
                Spacer()
                Label("H.264", systemImage: "cpu")
            }
            .font(.callout)
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .background(.quaternary.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))

            HStack {
                Text("Gear VR receiver")
                    .font(.headline)
                Spacer()
                Text(sender.receiverStatus)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 4)

            Toggle("Remote mouse", isOn: $sender.remoteMouseEnabled)
            Text(sender.inputStatus)
                .font(.caption)
                .foregroundStyle(.secondary)
            Toggle("Simulator motion feed", isOn: $sender.motionForwardingEnabled)
            HStack {
                Text(sender.motionStatus)
                Spacer()
                Text("UDP 127.0.0.1:43130")
                    .monospaced()
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Text(sender.isStreaming
                 ? "Encoded frames: \(sender.encodedFrameCount)  |  Data: \(sender.encodedByteCount / 1_000_000) MB"
                 : "Start the receiver app, then start the stream.")
                .font(.callout)
                .foregroundStyle(.secondary)

            Spacer(minLength: 8)

            if let error = sender.errorMessage {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                Spacer()
                Button {
                    sender.isStreaming ? sender.stopStreaming() : sender.startStreaming()
                } label: {
                    Label(sender.isStreaming ? "Stop Stream" : "Start Stream",
                          systemImage: sender.isStreaming ? "stop.fill" : "play.fill")
                        .frame(minWidth: 140)
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!sender.isStreaming && sender.displays.isEmpty)
            }
        }
        .padding(24)
        .onChange(of: sender.selectedDisplayID) { displayID in
            if sender.secondDisplayID == displayID {
                sender.secondDisplayID = sender.displays.first(where: { $0.displayID != displayID })?.displayID ?? 0
            }
        }
        .task {
            await sender.refreshDisplays()
            sender.startServer()
        }
        .onDisappear {
            sender.stopStreaming()
            sender.stopServer()
        }
    }
}