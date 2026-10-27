import Foundation
import CoreGraphics

enum StreamProtocol {
    static let signalingPort = 43129
    static let senderServiceType = "_gearvrsender._tcp"
    static let width = 1920
    static let height = 1080
    static let motionPort: UInt16 = 43130
    static let maximumInputPacketSize = 4096

    struct InputEvent: Codable {
        let type: String
        let action: String?
        let x: Double?
        let y: Double?
        let timestampNs: UInt64?
        let head: [Double]?
        let controller: [Double]?
        let angularVelocity: [Double]?
        let deltaX: Double?
        let deltaY: Double?

        var isValid: Bool {
            if type == "restart" { return true }
            if type == "touchpad" {
                if action == "down" || action == "up" { return true }
                guard action == "move", let deltaX, let deltaY,
                      deltaX.isFinite, deltaY.isFinite else { return false }
                return abs(deltaX) <= 100 && abs(deltaY) <= 100
            }
            if type == "scroll" {
                guard let deltaX, let deltaY, deltaX.isFinite, deltaY.isFinite else { return false }
                return abs(deltaX) <= 100 && abs(deltaY) <= 100
            }
            if type == "pointer" {
                guard let action, ["down", "up", "move"].contains(action),
                      let x, let y, x.isFinite, y.isFinite else { return false }
                return (0...1).contains(x) && (0...1).contains(y)
            }
            guard type == "pose", timestampNs != nil, let head,
                  validQuaternion(head) else { return false }
                if let angularVelocity,
                    (angularVelocity.count != 3 || !angularVelocity.allSatisfy(\.isFinite)) { return false }
                return controller.map(validQuaternion) ?? true
        }

        private func validQuaternion(_ values: [Double]) -> Bool {
            values.count == 4 && values.allSatisfy(\.isFinite)
                && abs(values.reduce(0) { $0 + $1 * $1 } - 1) < 0.05
        }
    }

    struct InputDisplay {
        let bounds: CGRect
        let width: Double
        let height: Double
    }

    static func desktopPoint(x: Double, y: Double, displays: [InputDisplay]) -> CGPoint? {
        guard x.isFinite, y.isFinite, (0...1).contains(x), (0...1).contains(y),
              !displays.isEmpty else { return nil }
        let totalWidth = displays.reduce(0) { $0 + $1.width }
        let totalHeight = displays.map(\.height).max() ?? 0
        guard totalWidth > 0, totalHeight > 0 else { return nil }
        let pixelX = x * totalWidth
        let pixelY = y * totalHeight
        var originX = 0.0
        for (index, display) in displays.enumerated() {
            let top = (totalHeight - display.height) / 2
            if pixelX < originX + display.width || index == displays.count - 1 {
                guard display.width > 0, display.height > 0,
                      pixelY >= top, pixelY <= top + display.height else { return nil }
                return CGPoint(
                    x: display.bounds.minX + min(1, (pixelX - originX) / display.width) * max(0, display.bounds.width - 1),
                    y: display.bounds.minY + (pixelY - top) / display.height * max(0, display.bounds.height - 1))
            }
            originX += display.width
        }
        return nil
    }
}