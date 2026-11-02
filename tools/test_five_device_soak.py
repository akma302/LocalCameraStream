import contextlib
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_five_device_soak as soak


class SoakSafetyAndAnalysisTest(unittest.TestCase):
    def properties(self):
        return {s: {"api": "33", "qemu": "0", "hardware": "physical", "appSha256": "same", "testSha256": "test"}
                for s in ["host", "a", "b", "c", "d", "e"]}

    def test_rejects_reused_physical_device_and_emulator(self):
        props = self.properties()
        with self.assertRaises(ValueError):
            soak.validate_devices("host", ["a", "b", "c", "d", "host"], props)
        props["e"]["qemu"] = "1"
        with self.assertRaises(ValueError):
            soak.validate_devices("host", ["a", "b", "c", "d", "e"], props)

    def test_rejects_different_installed_builds(self):
        props = self.properties()
        props["b"]["appSha256"] = "old-build"
        with self.assertRaises(ValueError):
            soak.validate_devices("host", ["a", "b", "c", "d", "e"], props)

    def test_dry_run_and_missing_wifi_opt_in_never_invoke_adb(self):
        argv = ["--host", "host", "--clients", "a", "b", "c", "d", "e", "--server-host", "192.168.0.2"]
        with patch.object(soak.subprocess, "run", side_effect=AssertionError("ADB invoked")):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, soak.main(argv + ["--dry-run"]))
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                soak.main(argv + ["--outage-client", "1"])

    def test_counter_reset_on_peer_recreation_does_not_create_negative_bitrate(self):
        rows = []
        for stamp, generation, count in [(0, "g1", 1000), (2000, "g1", 251000), (4000, "g2", 10), (6000, "g2", 250010)]:
            for peer in range(5):
                rows.append({"elapsedMs": str(stamp), "peer": str(peer), "generation": generation,
                             "bytesSent": str(count), "connection": "CONNECTED", "qualityLimit": "none", "thermalStatus": "0"})
        result = soak.host_summary(rows)
        self.assertEqual(5.0, result["totalOutboundRtpMbpsMedian"])
        self.assertEqual(1.0, result["fiveConnectedFraction"])
        self.assertTrue(result["passed"])
        rows[-1]["thermalStatus"] = "3"
        self.assertFalse(soak.host_summary(rows)["passed"])

    def test_no_traffic_or_four_clients_cannot_pass_as_five(self):
        self.assertFalse(soak.host_summary([])["passed"])
        rows = [{"elapsedMs": "2000", "peer": str(i), "generation": "g", "bytesSent": "",
                 "connection": "CONNECTED", "qualityLimit": ""} for i in range(4)]
        self.assertFalse(soak.host_summary(rows)["passed"])
        self.assertIsNone(soak.host_summary(rows)["totalOutboundRtpMbpsMedian"])

    def test_frozen_frames_are_included_and_charging_is_not_energy_measurement(self):
        rows = [{"phase": "PLAYING", "receivedFps": "30", "progressGapMs": "0", "elapsedMs": "2000",
                 "energyNwh": "1000000000", "plugged": "1"},
                {"phase": "RECONNECTING", "receivedFps": "0", "progressGapMs": "22000", "elapsedMs": "24000",
                 "energyNwh": "900000000", "plugged": "1"},
                {"phase": "ENDED", "receivedFps": "0", "progressGapMs": "24000", "elapsedMs": "26000",
                 "energyNwh": "800000000", "plugged": "1"}]
        result = soak.viewer_summary(rows, 15)
        self.assertFalse(result["passed"])
        self.assertEqual(.5, result["fractionAtMinimumFps"])
        self.assertIsNone(result["batteryEnergyUsedWh"])

    def test_recovery_uses_only_a_fresh_progress_sample_after_wifi_enable(self):
        rows = [{"elapsedMs": "1000", "receivedFps": "30", "progressGapMs": "0"},
                {"elapsedMs": "4000", "receivedFps": "0", "progressGapMs": "3000"},
                {"elapsedMs": "6000", "receivedFps": "30", "progressGapMs": "0"}]
        self.assertEqual(1500, soak.recovery_sample_ms(rows, 4500))
        self.assertIsNone(soak.recovery_sample_ms(rows, 6500))

    def test_full_desktop_disk_cannot_skip_stopping_other_phones(self):
        class FakeAdb:
            stopped = []
            removed = []

            def run(self, serial, *args, **kwargs):
                if "force-stop" in args:
                    self.stopped.append(serial)
                if args[:2] == ("shell", "getprop"):
                    return {"ro.kernel.qemu": "0", "ro.hardware": "physical", "ro.build.version.sdk": "33", "ro.product.model": "Fake"}[args[2]]
                return ""

            def command(self, serial, *args):
                return [serial, *args]

            def apk_hash(self, serial, package):
                return hashlib.sha256(b"apk").hexdigest()

            def read(self, serial, relative):
                return "header\nvalue" if relative.endswith(".csv") else ""

            def remove(self, serial, relative):
                self.removed.append((serial, relative))

        class FinishedProcess:
            def poll(self):
                return 0

            def wait(self, **kwargs):
                return 0

        original_write = Path.write_text

        def disk_full_for_csv(path, *args, **kwargs):
            if path.suffix == ".csv":
                raise OSError("Disk full")
            return original_write(path, *args, **kwargs)

        with tempfile.TemporaryDirectory(prefix=".soak-test-", dir=Path(__file__).resolve().parent) as directory:
            assert Path(directory).resolve().parent == Path(__file__).resolve().parent
            apk = Path(directory) / "app.apk"
            apk.write_bytes(b"apk")
            args = soak.parser().parse_args(["--host", "host", "--clients", "a", "b", "c", "d", "e",
                                            "--server-host", "192.168.0.2", "--output", directory,
                                            "--app-apk", str(apk), "--test-apk", str(apk)])
            adb = FakeAdb()
            with patch.object(soak, "Adb", return_value=adb), patch.object(soak.subprocess, "Popen", return_value=FinishedProcess()), \
                    patch.object(soak, "wait_file", side_effect=[{"accessCode": "123456", "fingerprint": "AA" * 32}, {}]), \
                    patch.object(Path, "write_text", new=disk_full_for_csv), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, soak.execute(args))
            self.assertEqual(["host", "a", "b", "c", "d", "e"], adb.stopped)
            report = json.loads(next(Path(directory).glob("*/summary.json")).read_text())
            self.assertFalse(report["passed"])
            self.assertEqual(6, len(report["roles"]))
            self.assertTrue(all("uncollectedDeviceCsv" in role for role in report["roles"].values()))
            self.assertTrue(all(not relative.endswith(".csv") for _, relative in adb.removed))
            self.assertTrue(all(role["collectionError"] == "OSError" for role in report["roles"].values()))


if __name__ == "__main__":
    unittest.main()
