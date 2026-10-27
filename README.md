# GearVR Stream

Local Mac-to-Gear-VR game streaming prototype for a Samsung Note 5 on Android 7.

Experimental software for discontinued Gear VR hardware. Requires an already
working Gear VR/Oculus runtime on the phone; proprietary runtime installers are
not distributed here. Compatibility with other phones is not certified.

## License and third-party materials

Original project code is available under the [MIT license](LICENSE).
Vendored JOML retains its [upstream license](joml-android/LICENSE).
Background panoramas and derived thumbnails retain the licenses in
[asset attribution](app/src/main/assets/backgrounds/ATTRIBUTION.md): Poly Haven
assets are CC0, and the two Wikimedia panoramas are CC BY-SA 4.0.
The `assets/layout-references` models and textures are Poly Haven CC0 assets
([projector screen](https://polyhaven.com/a/projector_screen),
[television](https://polyhaven.com/a/television_02)).
GearVR Framework and its Oculus backend are external dependencies with their own
licenses; MIT does not relicense those dependencies or proprietary device runtimes.

## Security

Use only a trusted private LAN. Streaming and reverse input are not authenticated
or encrypted. Remote mouse grants desktop control and should be disabled when not
in use. Camera passthrough is not stereo or safety-rated; do not use it for walking.

## Current milestone

- Android receiver supports API 24+, discovers the Mac sender over DNS-SD, connects over TCP, decodes H.264 with MediaCodec, and displays video in a head-tracked GearVR Framework scene.
- The Mac app advertises `_gearvrsender._tcp` over Bonjour, captures the selected display with ScreenCaptureKit, and sends low-delay 1080p H.264 hardware-encoded frames from VideoToolbox over TCP.
- The Android receiver includes optimized 4K x 2K equirectangular backgrounds under `app/src/main/assets/backgrounds`; a controller-operated thumbnail palette changes the environment while the video remains in front.
- A Camera environment shows the phone's live rear camera behind the stream, with menu-controlled screen transparency.

The stream uses length-prefixed TCP packets with H.264 SPS/PPS configuration and encoded access units. Test with both devices on the same non-isolated LAN and allow local network access in macOS privacy settings and the firewall. The same connection now carries pointer events and motion telemetry back to the Mac. Samsung's VR service recognizes the package, but headset rendering and controller input still require testing with the phone physically mounted in Gear VR.

Latency-first streaming keeps 1080p at up to 60 fps, uses hardware H.264 High profile with low-latency rate control at a 35 Mbps target, and reduces capture queue depth to two. Only one frame is admitted through encoding and network submission at a time; congestion skips unencoded captures rather than dropping encoded reference frames. TCP batching is disabled on both endpoints. The receiver drains output independently every 2 ms, presents only the newest available decoded frame, and retains every compressed input while active. This also lets the final frame appear when the desktop stops changing. Keyframes are requested when a connected receiver needs configuration, rather than periodically refreshing unchanged text. A maximum frame QP of 20 limits compression damage to fine text; under heavy motion, the encoder may drop captures to meet that quality and bitrate target. The virtual desktop is 4.2 x 2.3625 units at 2.5 units distance, with explicit linear texture filtering and unchanged world-locked tracking.

Acceptance checks: compare a moving desktop timer against the headset, leave text and other static content untouched to check flicker, then test scrolling, reconnect, and pause/resume. The 35 Mbps target needs adequate Wi-Fi throughput; TCP retransmissions can still increase latency, and actual end-to-end delay and visual stability require testing in the headset.

## Android receiver

Requirements: Java 21, Gradle 8.14.x, Android SDK platform 35, and Android build tools 35.0.0. There is no Gradle wrapper; install Gradle separately. The minimum device API is 24. The commands below use Homebrew paths on macOS; set equivalent Java/SDK paths on other systems. Install prerequisites with `brew install openjdk@21 gradle@8 android-commandlinetools` and install SDK packages using `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"` (accept the SDK licenses).

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

Desktop controls use the laser as the click target, the touchpad's physical press to click, and thumb slides to scroll. Laser hover does not move the Mac cursor. Press the touchpad to click at the laser target; hold the press and move the laser to select/drag. Releasing stops cursor tracking. A stationary press held for approximately 0.7 seconds releases the left button and sends a right-click for a context menu; movement of at least 10 desktop points cancels that long-press action. Enable Remote mouse on the Mac. Double-click the primary trigger within 300 ms to toggle the VR settings GUI; hold it over the top border to reposition the screen. Home and Back have no custom menu actions. While a VR menu is open, touchpad clicks select its controls (including zoom dragging), never the desktop. Native multi-finger gestures and pinch are not implemented.

Controller pose and button transitions come from GearVR Framework 4.0's Oculus backend, not raw Android motion handling. Video contact and grab activation share a ray intersection with the screen's transformed rectangle, preserving alignment after zoom and rotation. The beam stops at the screen with a bright contact dot and mint hover feedback. Flat GUI controls use the same menu-plane coordinates as the reticle, with fixed rectangular hit areas; open menus take priority, with screen contact still visible outside their bounds. The beam is world-locked to the sampled picking ray rather than independently inheriting a controller transform during rendering. The physical Gear VR controller takes priority over head-aligned gaze fallback. Disconnecting clears hover, pending clicks, and held-button state; pausing also dismisses both menus.

The settings menu opens automatically when the VR scene first initializes. Returning from a pause does not reopen it. Menus appear in front of the current head pose and remain world-locked while open.

The **Lock centered** checkbox keeps the video centered 2.5 units in front of a smoothed head pose, following head movement and rotation with fast exponential easing (80 ms time constant) to reduce small, rapid movements. Smoothing is frame-rate independent and resets on recentering or a tracking pause longer than 250 ms. Only the locked video is smoothed, not headset rendering, the controller laser, or transmitted tracking. It starts unchecked. While checked, the top border cannot grab the video; zoom and transparency still work. Unchecking leaves the screen at its last position and restores world-locked viewing. This setting lasts for the current app session. Responsiveness and comfort require headset testing.

- Touchpad click (`ENTER`, or `DPAD_CENTER` for fallback): click the laser's desktop target. Touchpad clicks and swipes do not open menus.
- With menus closed and Remote mouse enabled, slide a thumb on the controller touchpad to scroll vertically, horizontally, or diagonally on the Mac. The laser positions the cursor; thumb movement only scrolls. Stationary contact does not scroll; lifting stops the gesture. Scrolling is suppressed while pressing the touchpad/trigger or repositioning the screen.
- Settings: choose Environments, Center screen, Debug info, or Close with the touchpad press. Debug info shows the sender endpoint, decoder resolution/state, controller type, environment, and saved transparency, refreshing once per second while visible.
- Screen zoom slider: aim at the track in settings, hold the touchpad press, and drag the controller pointer left or right. Zoom updates continuously from 50% to 150%, with a live percentage and thumb position. Release to keep the size. Zoom preserves the stream aspect ratio and 2.5-unit viewing distance, survives recentering and resolution changes, and resets to 100% on app restart. Closing menus, canceled input, pause, or controller disconnect ends dragging.
- Camera tile: select with the touchpad press like any other background. Grant Android camera permission on first use; granting permission activates the Camera environment. Denying permission leaves the existing environment selected.
- Settings includes Camera screen transparency presets: 0%, 25%, 50%, 75%, and 100% transparency. The selected preset stays highlighted; 0% is fully opaque and 100% hides the video. It starts at 25% transparency (75% opacity) and is remembered when switching environments. Other environments keep the stream fully opaque. The old sliding transparency gesture is removed.
- Touchpad (`ENTER`/`DPAD_CENTER`): press a thumbnail, then release over the same thumbnail to select it and close the palette. Releasing elsewhere cancels. Primary trigger (`A`/`BUTTON_A`/`BUTTON_R2`) double-click toggles the GUI; a held trigger grabs only the screen's top bar.
- With both menus closed, aim at the video and press the touchpad to click the corresponding Mac desktop location. Hold the touchpad press and move the controller laser to drag; release ends it. Laser hover does not move the Mac cursor. Primary trigger does not generate desktop clicks; double-click it to toggle the GUI.
- To reposition the VR screen, aim at its top border and hold the primary trigger. Move the controller to orbit the screen around the viewer at a constant 2.5-unit radius, always facing inward, then release the trigger. Touchpad clicks do not grab the screen. The thin frame includes a dark top bar with a central grip and hover feedback. Header grabs never click the Mac, and screen movement works without Remote mouse enabled. The Move screen settings item is removed. Lock centered prevents header dragging. Opening a menu, recentering, canceled input, disconnecting, undocking, or pausing cancels the grab. An angular grab offset prevents snapping to the pointer.
- Video has a slim border and draggable top bar, without a large backing panel. Capture and video geometry preserve the selected display's aspect ratio within a 1920 x 1080 encoding budget, avoiding black letterbox padding in single-display mode. On initial scene entry, Samsung headset docking, proximity-sensor wear-on, resume, or focus return, the screen resets in front of the current head pose after a short tracking-settle interval. Proximity-based wear detection requires physical headset testing.
- Back/Home: no custom bindings. Oculus retains system button behavior.
- The palette is a four-column grid with readable labels and untinted previews on an opaque backing. Hover turns the border gold; holding the trigger turns it white. Target geometry stays fixed so hover cannot shift hit boundaries. Selected backgrounds and settings use mint borders. Menus render above the video, and only their controls are targeted while open. The beam and dot remain visible across menu gaps and cannot be hidden by the video. The pointer turns mint over controls and white while pressed. Slider dragging retains capture even when the pointer leaves the narrow track.

Zoom percentage textures are built before use and swapped without replacing images on live textures. Text textures are fully initialized before attaching them to materials, avoiding the GearVR 4.0 native `Texture::isReady()` crash seen during label updates. The slider fill is hidden at its minimum instead of using a singular zero-scale transform. Repeated endpoint-to-endpoint dragging and debug refresh still require hardware acceptance testing.

The controller pose is kept separate from beam-length scaling, so hovering a close tile cannot shorten the next frame's ray. No pose smoothing is added: the SDK owns tracking, while only hover feedback is animated to avoid extra aiming latency.

SDK decision: keep the existing GearVR-compatible backend. The [Oculus Mobile SDK mirror](https://github.com/lovr-org/ovr_sdk_mobile) is archived and its latest release targets Go/Quest; the [laser-pointer example](https://github.com/balataca/oculus-laser-pointer) requires Unity. [Unity Integration 50](https://developers.meta.com/vr/downloads/package/unity-integration/50.0/) is not a Java dependency or evidence of Note 5 compatibility. A Unity/native engine migration is not needed for these interaction improvements.

Hardware acceptance checks (still required on the mounted Note 5): verify beam/dot alignment on tile centers and edges, video occlusion, hover stability, fast touchpad-then-trigger input, held buttons, release-off-target cancellation, Back/system menus, and disconnect/reconnect plus pause/resume. Compilation alone does not verify native ray casting, gaze input, or headset comfort.

Camera acceptance checks: remove any cover blocking the rear camera, select Camera, verify preview orientation and focus, select every transparency preset, verify swiping no longer changes opacity, and test switching backgrounds and pause/resume. Also verify single/double-click routing, label legibility, selected borders, and debug updates. The camera is released when leaving Camera mode or pausing, and reopened on resume only if Camera mode is still selected. The feed is local and is not sent to the Mac. This is a single, head-mounted camera image shown to both eyes, not stereo or calibrated passthrough; it cannot provide reliable depth or a safe view for walking. Camera framing and latency require headset testing.

## macOS sender

Enable **Share two displays side by side** before starting the stream to capture both monitors simultaneously. The Display picker chooses the left monitor; Right display chooses the other monitor. Both appear on one wide VR surface, so existing zoom, transparency, and screen grabbing apply to the pair together. This is not two independently movable VR screens. Single-display mode remains available with the toggle off.

Dual-display capture composites the latest frame from each monitor using Core Image, vertically centers shorter monitors, and fits the combined image within the existing 1920 x 1080 encoding budget. This reduces pixels per monitor compared with sharing just one; zoom enlarges the image but cannot restore lost detail. The encoder and network retain the existing bounded in-flight frame behavior. Test motion on either display while the other is static, cursor visibility, differing monitor sizes, stop/restart, monitor disconnection, and text readability in the headset. Display selection is locked while streaming; a capture failure stops both captures.

Requires macOS 13 or newer, Xcode with the macOS SDK, and XcodeGen (`brew install xcodegen`). The Xcode project is generated locally and intentionally excluded from Git. Generate and build the project:

```sh
cd macOS
xcodegen generate
xcodebuild -project GearVRStreamSender.xcodeproj -scheme GearVRStreamSender -configuration Debug CODE_SIGNING_ALLOWED=NO build
open ~/Library/Developer/Xcode/DerivedData/GearVRStreamSender-*/Build/Products/Debug/GearVRStreamSender.app
```

The app requests Screen Recording access. Remote mouse additionally requires **System Settings > Privacy & Security > Accessibility** access for GearVRStreamSender. Turn on Remote mouse to request permission, grant access, then enable the toggle again. Mouse control and simulator forwarding default to off. Stopping capture, disconnecting, disabling mouse control, and a one-second pointer timeout release a held mouse button. The Xcode build produces an unsigned debug app; use signing settings appropriate to the local Mac for routine launch and permissions.

## Bidirectional input and simulator integration

Install the updated Android APK and rebuild/reopen the Mac sender. Reverse traffic uses the existing TCP socket on port 43129: a four-byte unsigned big-endian JSON payload length, followed by UTF-8 JSON (maximum 4096 bytes). Video framing is unchanged in the other direction. Pointer messages contain `type: "pointer"`, `action: "move" | "down" | "up"`, and normalized `x`/`y` in `[0,1]`, measured from the video's top-left corner. Mapping follows the captured display order, Retina scaling, display desktop origins, and vertically centered dual-display composition. Black padding around shorter monitors is not clickable.

Input writing runs independently of the VR render loop. Mouse button transitions have a bounded reliable queue; pointer movement and motion keep only their newest unsent sample. Queue overflow closes the connection to avoid leaving a button stuck. Moving off the screen while holding retains the last valid position until release. No smoothing is added to tracking; TCP can still accumulate latency during Wi-Fi stalls.

On each receiver connection (including app launch and resume), Android sends `{"type":"restart"}` before other input. If capture is active, the Mac releases held remote input, recreates its H.264 encoder, clears cached composite frames, and sends fresh SPS/PPS configuration with a keyframe. The listener and TCP connection stay open; display selection and capture settings are preserved. If capture is idle, its next start already creates a fresh encoder. This resets the video session, not the Mac process, and does not guarantee recovery from every decoder or Wi-Fi fault.

Enable **Simulator motion feed** in the Mac app while streaming. Validated pose messages are forwarded as one UTF-8 JSON UDP datagram per sample to **127.0.0.1:43130**, at up to approximately 60 Hz. Your future simulator should bind that loopback endpoint and read the newest sample rather than accumulating a queue. A lightweight monitor is included:

```sh
node macOS/Tools/motion-monitor.mjs
```

Example datagram:

```json
{"type":"pose","timestampNs":123456789000,"head":[0,0,0,1],"controller":[0,0,0,1],"angularVelocity":[0.01,-0.02,0.03]}
```

`head` is the headset's SDK-tracked world orientation; `controller` is the controller's world orientation (or head-aligned gaze fallback) and is omitted when unavailable. Both are normalized quaternions in **[x,y,z,w]** order, using GearVR's right-handed scene coordinates: +X right, +Y up, forward -Z. They include SDK recentering. `angularVelocity` is optional phone gyroscope data in radians/second in Android device sensor axes (+X right, +Y toward the phone's top, +Z out of its face), not scene/controller axes. Samples older than 100 ms are omitted. `timestampNs` is the phone's monotonic elapsed-realtime clock, not Mac wall time; clock synchronization is not implemented.

For camera synchronization, use the `head` quaternion, not raw gyro integration. Save a neutral orientation when the simulator session starts and apply the relative rotation, converting axes/quaternion convention for the simulator's engine. This is an explicit simulator data interface, **not** an emulated macOS HID gamepad or automatic camera control in arbitrary games. The monitor and simulator cannot both bind the same port normally; stop the monitor before starting the simulator.

The LAN session is not authenticated or encrypted. Enable remote mouse only on a trusted, non-public LAN and turn it off when finished; the loopback motion destination does not expose telemetry to the LAN. Pairing/authentication is not part of this prototype.

Validation: both platform builds and standalone input tests can be run with the existing build commands and:

```sh
swiftc macOS/Sources/StreamProtocol.swift macOS/Tests/StreamProtocolTests.swift -o /tmp/gearvr-input-tests
/tmp/gearvr-input-tests
```

Hardware acceptance remains required: click display corners and edges after zoom/rotation, click both monitors with differing sizes and desktop arrangement, verify padding rejection, desktop dragging and double-clicks, menu isolation, top-border screen dragging then return to clicking, disconnect/pause while held, and verify head/controller/gyro changes in the monitor. Check camera axes and neutral calibration in the actual simulator before gaming.