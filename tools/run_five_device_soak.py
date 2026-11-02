"""Coordinate an opted-in physical host + five physical viewers. Python stdlib only."""
import argparse
import csv
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import uuid

PACKAGE = "dev.localstream"
RUNNER = "dev.localstream.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "dev.localstream.FiveDeviceSoakTest"


def validate_devices(host, clients, properties):
    devices = [host, *clients]
    if len(clients) != 5 or len(set(devices)) != 6:
        raise ValueError("Specify one host and five distinct clients")
    for serial in devices:
        if not re.fullmatch(r"[A-Za-z0-9_.:-]+", serial):
            raise ValueError("Invalid device serial")
        prop = properties[serial]
        if serial.startswith("emulator-") or prop["qemu"] == "1" or prop["hardware"] in {"ranchu", "goldfish"}:
            raise ValueError("All six devices must be physical; emulators are rejected")
        if int(prop["api"]) < 33:
            raise ValueError("Android 13 or later is required on every device")
    for package in ("appSha256", "testSha256"):
        if len({properties[d][package] for d in devices}) != 1:
            raise ValueError("Install the same application and instrumentation APKs on all six devices")


def number(value):
    return float(value) if value not in (None, "") else None


def percentile(values, fraction):
    values = sorted(values)
    return values[int((len(values) - 1) * fraction)] if values else None


def power_summary(rows):
    def values(name):
        return [n for row in rows if (n := number(row.get(name))) is not None]
    currents = values("currentUa")
    energy = values("energyNwh")
    unplugged = bool(rows) and all(row.get("plugged") == "0" for row in rows)
    return {
        "peakThermalStatus": max(values("thermalStatus"), default=None),
        "peakBatteryTempC": max(values("batteryTempC"), default=None),
        "medianSignedCurrentUa": statistics.median(currents) if currents else None,
        "batteryEnergyUsedWh": (energy[0] - energy[-1]) / 1e9 if unplugged and len(energy) > 1 and energy[0] >= energy[-1] else None,
        "energyWarning": "USB/charging biases current and energy. Battery temperature is not CPU temperature.",
    }


def viewer_summary(rows, minimum_fps):
    samples = [r for r in rows if r["phase"] != "ENDED"]
    fps = [number(r["receivedFps"]) for r in samples]
    above = sum(v >= minimum_fps for v in fps) / len(fps) if fps else 0
    gap = max((number(r["progressGapMs"]) for r in samples), default=None)
    duration = (number(rows[-1]["elapsedMs"]) - number(rows[0]["elapsedMs"])) / 1000 if len(rows) > 1 else 0
    power = power_summary(rows)
    thermal_ok = power["peakThermalStatus"] is not None and power["peakThermalStatus"] < 3
    return {
        "samples": len(samples), "sampleSpanSeconds": duration,
        "receivedFpsP05": percentile(fps, .05), "receivedFpsMedian": percentile(fps, .5),
        "fractionAtMinimumFps": above, "maxProgressGapMs": gap,
        "endedByServer": bool(rows) and rows[-1]["phase"] == "ENDED",
        "passed": bool(samples) and above >= .90 and gap <= 20_000 and rows[-1]["phase"] == "ENDED" and thermal_ok,
        **power,
    }


def host_summary(rows):
    samples = {}
    previous = {}
    rates = {}
    for row in rows:
        stamp = int(row["elapsedMs"])
        samples.setdefault(stamp, []).append(row)
        key = (row["peer"], row["generation"])
        current = number(row["bytesSent"])
        old = previous.get(key)
        if current is not None and old and stamp > old[0] and current >= old[1]:
            rates.setdefault(stamp, []).append((current - old[1]) * 8 / (stamp - old[0]) / 1000)
        if current is not None:
            previous[key] = (stamp, current)
    # Only aggregate samples with five measured deltas. New generations have no delta yet.
    mbps = [sum(group) for group in rates.values() if len(group) == 5]
    five = sum(len(group) == 5 and all(r["connection"] == "CONNECTED" for r in group) for group in samples.values())
    fraction = five / len(samples) if samples else 0
    power = power_summary(rows)
    thermal_ok = power["peakThermalStatus"] is not None and power["peakThermalStatus"] < 3
    return {
        "samples": len(samples), "fiveConnectedFraction": fraction,
        "totalOutboundRtpMbpsMedian": statistics.median(mbps) if mbps else None,
        "totalOutboundRtpMbpsPeak": max(mbps, default=None),
        "qualityLimits": sorted({r["qualityLimit"] for r in rows if r["qualityLimit"]}),
        "passed": bool(samples) and fraction >= .95 and bool(mbps) and thermal_ok,
        **power,
    }


def recovery_sample_ms(rows, enabled_elapsed_ms):
    """First fresh-frame observation after Wi-Fi enable; two-second sampling resolution."""
    return next((int(r["elapsedMs"]) - enabled_elapsed_ms for r in rows
                 if int(r["elapsedMs"]) >= enabled_elapsed_ms and number(r["receivedFps"]) > 0
                 and number(r["progressGapMs"]) == 0), None)


class Adb:
    def __init__(self, executable):
        self.executable = executable

    def command(self, serial, *args):
        return [self.executable, "-s", serial, *args]

    def run(self, serial, *args, check=True):
        result = subprocess.run(self.command(serial, *args), capture_output=True, text=True, timeout=20,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if check and result.returncode:
            raise RuntimeError(f"ADB command failed on {serial}")
        return result.stdout.strip()

    def read(self, serial, relative):
        return self.run(serial, "shell", "run-as", PACKAGE, "cat", relative, check=False)

    def remove(self, serial, relative):
        self.run(serial, "shell", "run-as", PACKAGE, "rm", "-f", relative, check=False)

    def apk_hash(self, serial, package):
        path = self.run(serial, "shell", "pm", "path", package).removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+\.apk", path):
            raise RuntimeError(f"Install the single universal debug APK for {package} on {serial}")
        digest = self.run(serial, "shell", "sha256sum", path).split()[0]
        if not re.fullmatch("[a-fA-F0-9]{64}", digest):
            raise RuntimeError("Cannot verify installed APK digest")
        return digest


def wait_file(adb, serial, relative, process, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Host instrumentation ended before coordination was ready")
        raw = adb.read(serial, relative)
        if raw.startswith("{"):
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                pass  # The host may still be completing the tiny coordination file.
        time.sleep(1)
    raise TimeoutError("Timed out waiting for host coordination")


def execute(args):
    devices = [args.host, *args.clients]
    props = {}
    adb = Adb(args.adb)
    expected = {name: hashlib.sha256(Path(path).read_bytes()).hexdigest() for name, path in
                (("appSha256", args.app_apk), ("testSha256", args.test_apk))}
    # Read-only preflight completes before launching or changing any application.
    for serial in devices:
        adb.run(serial, "get-state")
        adb.run(serial, "shell", "run-as", PACKAGE, "id")
        props[serial] = {name: adb.run(serial, "shell", "getprop", prop) for name, prop in {
            "qemu": "ro.kernel.qemu", "hardware": "ro.hardware", "api": "ro.build.version.sdk", "model": "ro.product.model",
        }.items()}
        props[serial]["appSha256"] = adb.apk_hash(serial, PACKAGE)
        props[serial]["testSha256"] = adb.apk_hash(serial, PACKAGE + ".test")
    validate_devices(args.host, args.clients, props)
    if any(props[serial][key] != digest for serial in devices for key, digest in expected.items()):
        raise ValueError("Installed APKs differ from local build outputs; install the freshly built APKs on every device")
    run_id = uuid.uuid4().hex
    output = Path(args.output) / run_id
    output.mkdir(parents=True)
    processes = {}
    logs = {}
    changed_wifi = False
    error = None
    fault = None
    summaries = {}
    ready_path = f"files/load/{run_id}-ready.json"
    started_path = f"files/load/{run_id}-started.json"

    def launch(role, serial, parameters):
        log = (output / f"{role}.instrumentation.log").open("w", encoding="utf-8")
        logs[role] = log
        command = adb.command(serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST + ("#host" if role == "host" else "#viewer"))
        for key, value in {"soakOptIn": "true", "runId": run_id, "durationSeconds": args.duration,
                           "minimumFps": args.minimum_fps, **parameters}.items():
            command += ["-e", key, str(value)]
        command += [RUNNER]
        processes[role] = (serial, subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                                  creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))

    try:
        launch("host", args.host, {"backend": args.backend})
        credentials = wait_file(adb, args.host, ready_path, processes["host"][1], 90)
        code, pin = credentials["accessCode"], credentials["fingerprint"]
        if not re.fullmatch(r"\d{6}", code) or not re.fullmatch(r"[A-F0-9]{64}", pin):
            raise RuntimeError("Invalid private coordination data")
        adb.remove(args.host, ready_path)
        for index, serial in enumerate(args.clients):
            launch(f"client-{index + 1}", serial, {"serverHost": args.server_host, "accessCode": code, "serverFingerprint": pin})
        del credentials, code, pin
        wait_file(adb, args.host, started_path, processes["host"][1], 210)
        print("Five physical clients connected and encoding. Sustained measurement started.", flush=True)
        started = time.monotonic()
        deadline = started + args.duration + 210
        while any(process.poll() is None for _, process in processes.values()):
            if time.monotonic() > deadline:
                raise TimeoutError("Soak instrumentation exceeded the run deadline")
            for role, (_, process) in processes.items():
                if process.poll() is not None:
                    logs[role].flush()
                    log = (output / f"{role}.instrumentation.log").read_text(encoding="utf-8")
                    if "OK (1 test)" not in log:
                        raise RuntimeError(f"{role} instrumentation failed; collecting partial results")
            if args.outage_client is not None and fault is None and time.monotonic() - started >= args.outage_after:
                serial = args.clients[args.outage_client - 1]
                changed_wifi = True
                fault = {"client": args.outage_client, "disabledAtOffsetSeconds": time.monotonic() - started}
                adb.run(serial, "shell", "svc", "wifi", "disable")
                time.sleep(args.outage_seconds)
                fault["enableCommandElapsedMs"] = round(float(adb.run(serial, "shell", "cat", "/proc/uptime").split()[0]) * 1000)
                adb.run(serial, "shell", "svc", "wifi", "enable")
                changed_wifi = False
                fault["enabledAtOffsetSeconds"] = time.monotonic() - started
            time.sleep(1)
        if args.outage_client is not None and fault is None:
            raise RuntimeError("Requested outage was not injected")
    except (Exception, KeyboardInterrupt) as exc:
        error = type(exc).__name__ + ": " + str(exc)
    finally:
        cleanup_errors = []

        def best_effort(action, *parameters, **keywords):
            try:
                return action(*parameters, **keywords)
            except Exception as exc:
                cleanup_errors.append(type(exc).__name__)
                return ""

        if changed_wifi:
            best_effort(adb.run, args.clients[args.outage_client - 1], "shell", "svc", "wifi", "enable")
        # Stop every test before doing disk I/O or parsing any result. A failed pull,
        # malformed CSV or a full desktop disk must not leave another phone capturing.
        for role, (serial, process) in processes.items():
            best_effort(adb.run, serial, "shell", "am", "force-stop", PACKAGE)
            if process.poll() is None:
                best_effort(process.terminate)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                best_effort(process.kill)
                best_effort(process.wait, timeout=10)
            best_effort(logs[role].close)
        for role, (serial, _) in processes.items():
            summaries[role] = {"passed": False, "instrumentationPassed": False}
            name = "host.csv" if role == "host" else "viewer.csv"
            device_csv = f"files/load/{run_id}-{name}"
            collected = False
            try:
                raw = best_effort(adb.read, serial, device_csv)
                if raw:
                    (output / f"{role}.csv").write_text(raw + "\n", encoding="utf-8")
                    collected = True
                    rows = list(csv.DictReader(raw.splitlines()))
                    summaries[role] = host_summary(rows) if role == "host" else viewer_summary(rows, args.minimum_fps)
                    if fault and role == f"client-{args.outage_client}" and "enableCommandElapsedMs" in fault:
                        fault["firstProgressSampleAfterEnableMs"] = recovery_sample_ms(rows, fault["enableCommandElapsedMs"])
                log = (output / f"{role}.instrumentation.log").read_text(encoding="utf-8")
                passed = "OK (1 test)" in log and "FAILURES!!!" not in log and "INSTRUMENTATION_FAILED" not in log
                summaries[role]["instrumentationPassed"] = passed
            except Exception as exc:
                summaries[role] = {"passed": False, "collectionError": type(exc).__name__}
            finally:
                for suffix in ("ready.json", "started.json"):
                    best_effort(adb.remove, serial, f"files/load/{run_id}-{suffix}")
                if collected:
                    best_effort(adb.remove, serial, device_csv)
                else:
                    summaries[role]["uncollectedDeviceCsv"] = device_csv
        all_passed = len(summaries) == 6 and all(s.get("passed") and s.get("instrumentationPassed") for s in summaries.values())
        report = {"runId": run_id, "durationSeconds": args.duration, "minimumReceivedFps": args.minimum_fps,
                  "serverHost": args.server_host, "backend": args.backend, "devices": props, "fault": fault,
                  "error": error, "cleanupErrors": cleanup_errors,
                  "passed": all_passed and error is None and not cleanup_errors, "roles": summaries,
                  "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  "scope": "Six physical devices; received FPS, not renderer FPS. No footage, access codes, tokens or private keys in results."}
        (output / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Report: {output / 'summary.json'}", flush=True)
    return 0 if report["passed"] else 1


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default="adb")
    p.add_argument("--host", required=True)
    p.add_argument("--clients", nargs=5, required=True)
    p.add_argument("--server-host", required=True, type=lambda value: str(ipaddress.IPv4Address(value)))
    p.add_argument("--duration", type=int, default=1800)
    p.add_argument("--minimum-fps", type=int, default=15)
    p.add_argument("--backend", choices=["camera2", "ndk"], default="camera2")
    p.add_argument("--output", default="artifacts/validation/load")
    p.add_argument("--app-apk", default="app/build/outputs/apk/debug/app-debug.apk")
    p.add_argument("--test-apk", default="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    p.add_argument("--outage-client", type=int, choices=range(1, 6))
    p.add_argument("--outage-after", type=int, default=300)
    p.add_argument("--outage-seconds", type=int, default=4)
    p.add_argument("--allow-wifi-toggle", action="store_true")
    p.add_argument("--dry-run", action="store_true", help="Validate arguments and print the plan without invoking ADB")
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if len(set([args.host, *args.clients])) != 6 or not all(re.fullmatch(r"[A-Za-z0-9_.:-]+", s) for s in [args.host, *args.clients]):
        p.error("Six distinct, valid physical-device serials are required")
    if not 60 <= args.duration <= 7200 or not 1 <= args.minimum_fps <= 30:
        p.error("Duration must be 60..7200 seconds and minimum FPS 1..30")
    if args.outage_client is not None and (not args.allow_wifi_toggle or not 1 <= args.outage_seconds <= 10
                                          or not 10 <= args.outage_after < args.duration - 30):
        p.error("Outage requires --allow-wifi-toggle, 1..10 seconds, and 30 seconds of recovery before the end")
    if args.dry_run:
        print(f"Plan only: host={args.host}; five physical clients; backend={args.backend}; duration={args.duration}s; no ADB invoked.")
        return 0
    return execute(args)


if __name__ == "__main__":
    raise SystemExit(main())
