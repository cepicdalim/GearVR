# GearVR Stream

Local Mac-to-Gear-VR game streaming prototype for a Samsung Note 5 on Android 7.

## Current milestone

- Android receiver targets API 24, discovers the Mac sender over DNS-SD, connects over TCP, decodes H.264 with MediaCodec, and displays video on the phone. Head tracking and controller diagnostics remain visible over the video.
- The Mac app advertises `_gearvrsender._tcp` over Bonjour, captures the selected display with ScreenCaptureKit, and sends low-delay 1080p H.264 hardware-encoded frames from VideoToolbox over TCP.

The stream uses length-prefixed TCP packets with H.264 SPS/PPS configuration and encoded access units. Test with both devices on the same non-isolated LAN and allow local network access in macOS privacy settings and the firewall. Samsung's VR service recognizes the package, but headset rendering and controller input still require testing with the phone physically mounted in Gear VR; input is not yet sent back to macOS.

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

## macOS sender

Requires macOS 13 or newer and Xcode with the macOS SDK. Generate and build the project:

```sh
cd macOS
xcodegen generate
xcodebuild -project GearVRStreamSender.xcodeproj -scheme GearVRStreamSender -configuration Debug CODE_SIGNING_ALLOWED=NO build
open ~/Library/Developer/Xcode/DerivedData/GearVRStreamSender-*/Build/Products/Debug/GearVRStreamSender.app
```

The app requests Screen Recording access. The Xcode build produces an unsigned debug app; use signing settings appropriate to the local Mac for routine launch and permissions.