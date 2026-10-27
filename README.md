# GearVR Stream

Local Mac-to-Gear-VR game streaming prototype for a Samsung Note 5 on Android 7.

## Current milestone

- Android receiver supports API 24+, discovers the Mac sender over DNS-SD, connects over TCP, decodes H.264 with MediaCodec, and displays video in a head-tracked GearVR Framework scene.
- The Mac app advertises `_gearvrsender._tcp` over Bonjour, captures the selected display with ScreenCaptureKit, and sends low-delay 1080p H.264 hardware-encoded frames from VideoToolbox over TCP.
- The Android receiver includes optimized 4K x 2K equirectangular backgrounds under `app/src/main/assets/backgrounds`; a controller-operated thumbnail palette changes the environment while the video remains in front.
- A Camera environment shows the phone's live rear camera behind the stream, with touchpad-adjustable screen transparency.

The stream uses length-prefixed TCP packets with H.264 SPS/PPS configuration and encoded access units. Test with both devices on the same non-isolated LAN and allow local network access in macOS privacy settings and the firewall. Samsung's VR service recognizes the package, but headset rendering and controller input still require testing with the phone physically mounted in Gear VR; input is not yet sent back to macOS.

Latency-first streaming keeps 1080p at up to 60 fps, uses hardware H.264 High profile with low-latency rate control at a 35 Mbps target, and reduces capture queue depth to two. Only one frame is admitted through encoding and network submission at a time; congestion skips unencoded captures rather than dropping encoded reference frames. TCP batching is disabled on both endpoints. The receiver drains output independently every 2 ms, presents only the newest available decoded frame, and retains every compressed input while active. This also lets the final frame appear when the desktop stops changing. Keyframes are requested when a connected receiver needs configuration, rather than periodically refreshing unchanged text. A maximum frame QP of 20 limits compression damage to fine text; under heavy motion, the encoder may drop captures to meet that quality and bitrate target. The virtual desktop is 4.2 x 2.3625 units at 2.5 units distance, with explicit linear texture filtering and unchanged world-locked tracking.

Acceptance checks: compare a moving desktop timer against the headset, leave text and other static content untouched to check flicker, then test scrolling, reconnect, and pause/resume. The 35 Mbps target needs adequate Wi-Fi throughput; TCP retransmissions can still increase latency, and actual end-to-end delay and visual stability require testing in the headset.

## Android receiver

Requirements: Java 21, Gradle 8.14+, Android SDK platforms 24 and 35, and Android build tools 35.0.0.

```sh
export JAVA_HOME="$(/opt/homebrew/bin/brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$(/opt/homebrew/bin/brew --prefix gradle@8)/bin:$PATH"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n local.gearvr.stream/.MainActivity
```

## VR interaction

Controller pose and button transitions come from GearVR Framework 4.0's Oculus backend, not raw Android motion handling. Surface targeting uses its `GVRPicker` and mesh colliders rather than handwritten plane intersections. The physical Gear VR controller takes priority over head-aligned gaze fallback. Disconnecting clears hover and held-button state; pausing also dismisses the palette.

The background palette opens automatically when the VR scene first initializes. Returning from a pause does not reopen it.

- Touchpad click (`ENTER`, or `DPAD_CENTER` for fallback): toggle the background palette once per press. Touching or swiping alone does not select or change backgrounds.
- Camera tile: select with the trigger like any other background. Grant Android camera permission on first use; granting permission activates the Camera environment. Denying permission leaves the existing environment selected.
- In Camera mode with the palette closed, slide right on the controller touchpad to make the stream more opaque, or left to make it more transparent. Opacity is clamped to 0-100%, starts at 75%, and is remembered when switching environments. Lift your finger to end a slide; clicking the pad still opens the palette. Other environments keep the stream fully opaque.
- Trigger (`A`, with `BUTTON_A`/`BUTTON_R2` aliases): press a thumbnail, then release over the same thumbnail to select it and close the palette. Releasing elsewhere cancels. Held/repeated input does not retrigger actions.
- Back: dismiss the palette when a Back event reaches the SDK listener. Oculus retains control of system Back/Home behavior; Home, volume, and recenter gestures are not overridden.
- Hover animates thumbnail enlargement; a held trigger changes the hovered thumbnail tint. The laser and small, distance-scaled dot are visible only while the palette is open. The beam stops at the nearest palette/video/frame surface, and the dot is hidden when no surface is hit.

The controller pose is kept separate from beam-length scaling, so hovering a close tile cannot shorten the next frame's ray. No pose smoothing is added: the SDK owns tracking, while only hover feedback is animated to avoid extra aiming latency.

SDK decision: keep the existing GearVR-compatible backend. The [Oculus Mobile SDK mirror](https://github.com/lovr-org/ovr_sdk_mobile) is archived and its latest release targets Go/Quest; the [laser-pointer example](https://github.com/balataca/oculus-laser-pointer) requires Unity. [Unity Integration 50](https://developers.meta.com/vr/downloads/package/unity-integration/50.0/) is not a Java dependency or evidence of Note 5 compatibility. A Unity/native engine migration is not needed for these interaction improvements.

Hardware acceptance checks (still required on the mounted Note 5): verify beam/dot alignment on tile centers and edges, video occlusion, hover stability, fast touchpad-then-trigger input, held buttons, release-off-target cancellation, Back/system menus, and disconnect/reconnect plus pause/resume. Compilation alone does not verify native ray casting, gaze input, or headset comfort.

Camera acceptance checks: remove any cover blocking the rear camera, select Camera, verify preview orientation and focus, swipe to both opacity limits, and test switching backgrounds and pause/resume. The camera is released when leaving Camera mode or pausing, and reopened on resume only if Camera mode is still selected. The feed is local and is not sent to the Mac. This is a single, head-mounted camera image shown to both eyes, not stereo or calibrated passthrough; it cannot provide reliable depth or a safe view for walking. Camera framing, touch sensitivity, and latency require headset testing.

## macOS sender

Requires macOS 13 or newer and Xcode with the macOS SDK. Generate and build the project:

```sh
cd macOS
xcodegen generate
xcodebuild -project GearVRStreamSender.xcodeproj -scheme GearVRStreamSender -configuration Debug CODE_SIGNING_ALLOWED=NO build
open ~/Library/Developer/Xcode/DerivedData/GearVRStreamSender-*/Build/Products/Debug/GearVRStreamSender.app
```

The app requests Screen Recording access. The Xcode build produces an unsigned debug app; use signing settings appropriate to the local Mac for routine launch and permissions.