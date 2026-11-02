# Camera frame orientation — 2026-11-02

The prior path forwarded the SurfaceTexture buffer unchanged while assigning
sensorOrientation as VideoFrame.rotation. Camera2 already embeds sensor rotation
in the texture matrix, so that transform was applied twice. Display rotation
was never read. A fixed 16:9 UI pane also ignored rotated frame dimensions.

The shared CameraVideoSource now composes a centered inverse sensor transform
(and the front-camera mirror correction) with the existing producer matrix,
then sets WebRTC rotation metadata from sensor/facing/current display rotation.
This follows the upstream WebRTC Camera2Session/CameraSession transform path:
https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/Camera2Session.java
https://raw.githubusercontent.com/webrtc-sdk/webrtc/main/sdk/android/src/java/org/webrtc/CameraSession.java

The display listener belongs to capture, survives Activity replacement, and is
unregistered on close. A separately owned transformed buffer is released after
forwarding; the input stays owned by SurfaceTextureHelper. Preview and receiver
publish their actual rotated aspect ratios through VideoOutput. UI renderers
preserve those proportions rather than forcing every stream into a 16:9 pane.
No camera implementation depends on WebRTC or handles display/UI state.

Validation:
- All 28 JVM tests passed: 4 new orientation tests plus 24 existing tests.
  Explicit front/back tables cover every 0/90/180/270 sensor/display combination;
  asymmetric texture corners, center preservation and invalid angles are checked.
- Debug application, app instrumentation APK, media instrumentation APK and lint
  passed (0 errors, 7 warnings).
- Synthetic Android matrix/reference-count test was compiled, not run. It covers
  32 sensor/display/facing combinations, preserves a producer crop/vertical flip,
  checks rotated dimensions/timestamps and balances texture ownership.
- At the user's request the new app APK was installed and server preview opened
  on Xiaomi a2ce53c4. The user visually checked it and confirmed acceptance.
  No screenshots or camera footage were captured/saved by the agent.
- No new emulator was launched. The Xiaomi application was left running.

The user's local preview check does not establish all front-camera, NDK,
180-degree, receiver-side or foldable configurations. The Android synthetic test
and two-device visual checks remain follow-up coverage, not claimed results.
Display rotation follows the display/auto-rotate setting, not free physical
rotation while the UI is locked. External cameras with independent mounting and
multi-display alignment need their own explicit orientation policy.
