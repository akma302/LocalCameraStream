# Five-device soak preparation — 2026-11-02

Prepared an opted-in host + five physical-viewer instrumentation test and Python
coordinator. The host waits for five connected/encoding peers, then samples
per-peer outbound stats and Android thermal/battery/UID counters for 30 minutes.
Clients use the app ViewModel and video UI and measure received-frame progress.
The coordinator verifies six distinct physical devices and matching APK hashes;
an optional Wi-Fi fault requires explicit opt-in. Private USB pairing data is
deleted and excluded from results. Reports preserve partial failures and unknown
counters. See docs/five-device-soak.md for commands, criteria and limitations.

Validation without device launches:
- 2 JVM stats-parser tests passed, including unsigned counters and missing data.
- 8 Python tests passed: physical inventory/build checks, no-ADB dry run and
  Wi-Fi opt-in, generation counter resets, insufficient clients/traffic,
  frozen frames, thermal threshold, charging/energy, recovery sample analysis,
  and stopping all six roles even when desktop CSV writes fail with disk full.
- 7 existing TLS/server JVM tests passed.
- Rebased onto main after PRs #1 and #2 were merged; integrated validation also
  passed all 17 client JVM tests (26 JVM tests total).
- App APK, instrumentation APK and lint passed (0 errors, 7 warnings).

Only Xiaomi and one emulator were present in the ADB inventory. Neither was
launched or reconfigured. Six-physical-device orchestration, battery/thermal
readings and actual sustained performance remain UNVERIFIED. This is test
infrastructure, not a passing five-device benchmark. No supported quality or
adaptive bitrate policy is inferred without those results.
