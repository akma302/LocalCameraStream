# Deterministic viewer recovery validation

2026-11-02: 17 client JVM tests passed (6 RecoveryPolicy + 11 ViewerRecovery).
The production loop is extracted into ViewerWorker and executes unchanged for
Android adapters and JVM fakes. Platform time, jitter, discovery, connectivity,
signaling and media are injectable; no Android methods or native WebRTC run in
these scenarios. Tests advance virtual time, not wall-clock sleeps.

Scenarios: live media with disconnected signaling and token resume; returned
network with changed discovery address; stalled video and ICE escalation;
FAILED without a network; stale socket epoch and ICE generation; pause/resume
and manual disconnect; FULL retries versus terminal AUTH/ENDED; cancellation
inside cleanup; transport initialization failure after runtime allocation;
RATE_LIMIT surviving network return; terminal identity verification failure.

Cleanup is now guarded from cancellation and attempts every resource release,
even when another adapter throws. Resource allocation occurs inside the guarded
region so a transport constructor failure cannot leak the already-created runtime.
The admission cooldown is independent of the normal network retry timer.

Integrated with fix/secure-signaling; its 7 TLS/server JVM tests also passed.
App APK, instrumentation APK and lint passed (0 errors, 7 warnings).
No devices or emulators launched. JVM fakes prove state transitions and ownership,
not Android callback behavior, codecs, renderer correctness or physical Wi-Fi.
The existing compiled instrumentation tests remain the runtime check for those.
