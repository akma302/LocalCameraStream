# Secure signaling validation

Implemented: TLS-only Netty server; exact SHA-256 certificate pin transferred
from the host screen, independent of NSD; expired/missing/wrong pins rejected;
cleartext disabled; global sliding-window JOIN limit (10/minute per broadcast).
RATE_LIMIT waits one minute; established media stays alive.
The certificate/key live in memory for a broadcast and expire after seven days.

Validation without devices (2026-11-02):
- 4 network JVM tests: mandatory full pin, replaced certificate, expiry, real TLS
  handshake with matching and mismatched pins; all passed.
- 3 server JVM tests: sliding-window budget and actual Netty WSS echo with a
  matching pin, rejection with a mismatched pin; all passed.
- Debug app APK, instrumentation APK and lint passed (0 errors, 7 warnings).
- Added Android negative-pin test and updated existing streaming tests to pin
  the actual host, including the transparent fault proxy. Compiled, not run.

Android Netty/TLS behavior and streaming must be checked on supported devices
before merge. Previous CIO/WS runtime reports do not validate this change.
The global limit deliberately also covers resume JOINs: a hostile LAN peer can
exhaust admission budget, but cannot interrupt already admitted sessions this way.
No claim of general denial-of-service resistance is made for the TLS listener.
