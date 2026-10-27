import Foundation

@main
enum StreamProtocolTests {
    static func main() throws {
        let displays = [
            StreamProtocol.InputDisplay(bounds: CGRect(x: -1000, y: 100, width: 1000, height: 800), width: 2000, height: 1600),
            StreamProtocol.InputDisplay(bounds: CGRect(x: 0, y: 0, width: 1200, height: 1000), width: 1200, height: 1000)
        ]
        let left = StreamProtocol.desktopPoint(x: 0, y: 0, displays: displays)!
        assert(left == CGPoint(x: -1000, y: 100))
        assert(StreamProtocol.desktopPoint(x: 0.8, y: 0, displays: displays) == nil)
        let right = StreamProtocol.desktopPoint(x: 1, y: 0.8125, displays: displays)!
        assert(right == CGPoint(x: 1199, y: 999))
        assert(StreamProtocol.desktopPoint(x: .nan, y: 0, displays: displays) == nil)
        let decoder = JSONDecoder()
        let click = try decoder.decode(StreamProtocol.InputEvent.self,
            from: Data(#"{"type":"pointer","action":"down","x":0.5,"y":0.5}"#.utf8))
        assert(click.isValid)
        let restart = try decoder.decode(StreamProtocol.InputEvent.self,
            from: Data(#"{"type":"restart"}"#.utf8))
        assert(restart.isValid)
        for json in [
            #"{"type":"touchpad","action":"down"}"#,
            #"{"type":"touchpad","action":"up"}"#,
            #"{"type":"touchpad","action":"move","deltaX":-10,"deltaY":5}"#
        ] {
            let touchpad = try decoder.decode(StreamProtocol.InputEvent.self, from: Data(json.utf8))
            assert(touchpad.isValid)
        }
        let scroll = try decoder.decode(StreamProtocol.InputEvent.self,
            from: Data(#"{"type":"scroll","deltaX":-12,"deltaY":20}"#.utf8))
        assert(scroll.isValid)
        let pose = try decoder.decode(StreamProtocol.InputEvent.self,
            from: Data(#"{"type":"pose","timestampNs":123,"head":[0,0,0,1]}"#.utf8))
        assert(pose.isValid)
        let invalid = try decoder.decode(StreamProtocol.InputEvent.self,
            from: Data(#"{"type":"pose","timestampNs":123,"head":[0,0,0,0]}"#.utf8))
        assert(!invalid.isValid)
        for json in [
            #"{"type":"touchpad","action":"move","deltaX":101,"deltaY":0}"#,
            #"{"type":"scroll","deltaX":101,"deltaY":0}"#,
            #"{"type":"scroll","deltaX":1}"#,
            #"{"type":"pointer","action":"down","x":1.1,"y":0.5}"#,
            #"{"type":"pointer","action":"scroll","x":0.5,"y":0.5}"#,
            #"{"type":"pose","timestampNs":123,"head":[0,0,0,1],"controller":[0,0,0]}"#,
            #"{"type":"pose","timestampNs":123,"head":[0,0,0,1],"angularVelocity":[0,0]}"#
        ] {
            let invalidEvent = try decoder.decode(StreamProtocol.InputEvent.self, from: Data(json.utf8))
            assert(!invalidEvent.isValid)
        }
        let fullPose = try decoder.decode(StreamProtocol.InputEvent.self,
            from: Data(#"{"type":"pose","timestampNs":123,"head":[0,0,0,1],"controller":[0,0,0,1],"angularVelocity":[0.1,0.2,0.3]}"#.utf8))
        assert(fullPose.isValid)
        let roundTrip = try decoder.decode(StreamProtocol.InputEvent.self, from: JSONEncoder().encode(fullPose))
        assert(roundTrip.isValid)
        print("Input protocol and multi-display coordinate tests passed")
    }
}