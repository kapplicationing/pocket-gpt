import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from build_android_perf_summary import MetadataError, collect_device_state, parse_args


class BuildAndroidPerfSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.write("device-properties.txt", "[ro.product.manufacturer]: [Google]\n[ro.product.model]: [Pixel 8]\n[ro.build.version.release]: [16]\n[ro.build.version.sdk]: [36]\n")
        self.write(
            "display-before.txt",
            "mActiveSfDisplayMode=DisplayMode{id=0, refreshRate=120.0}\n",
        )
        self.write("refresh-settings-before.txt", "peak_refresh_rate=120.0\n")
        self.write("thermal-before.txt", "Thermal Status: 0\n")
        self.write("thermal-after.txt", "1\n")
        self.write("battery-before.txt", "  temperature: 312\n")
        self.write("battery-after.txt", "  temperature: 318\n")
        self.write("package-dump.txt", "arm64: [status=speed-profile] [reason=bg-dexopt]\n")

    def tearDown(self):
        self.temp_dir.cleanup()

    def write(self, name, content):
        (self.root / name).write_text(content, encoding="utf-8")

    def collect(self):
        return collect_device_state(
            self.root,
            declared_runtime_condition="unloaded",
            declared_download_condition="idle",
            declared_voice_condition="inactive",
        )

    def test_collects_device_refresh_thermal_compilation_and_declared_conditions(self):
        state = self.collect()

        self.assertEqual(state["model"], "Pixel 8")
        self.assertEqual(state["refresh_rate_hz"], 120.0)
        self.assertEqual(state["refresh_rate_source"], "dumpsys-display-active-mode")
        self.assertEqual(state["thermal_status_after"], 1)
        self.assertEqual(state["battery_temperature_c_before"], 31.2)
        self.assertEqual(state["compilation_filter"], "speed-profile")
        self.assertEqual(state["workload_condition_source"], "operator-declared-not-observed")
        self.assertEqual(state["declared_runtime_condition"], "unloaded")
        self.assertNotIn("runtime_condition", state)

    def test_fails_closed_when_required_thermal_evidence_is_unparseable(self):
        self.write("thermal-after.txt", "service unavailable\n")

        with self.assertRaisesRegex(MetadataError, "thermal status"):
            self.collect()

    def test_records_unavailable_optional_refresh_and_compilation_evidence(self):
        self.write("display-before.txt", "no active mode exposed\n")
        self.write("refresh-settings-before.txt", "peak_refresh_rate=null\n")
        self.write("package-dump.txt", "Dexopt state unavailable\n")

        state = self.collect()

        self.assertIsNone(state["refresh_rate_hz"])
        self.assertFalse(state["refresh_rate_evidence_available"])
        self.assertEqual(state["compilation_filter"], "unavailable")
        self.assertFalse(state["compilation_evidence_available"])

    def test_condition_cli_flags_remain_compatible_but_use_declared_destinations(self):
        args = parse_args(
            [
                "--output", str(self.root / "summary.json"),
                "--artifact-dir", str(self.root),
                "--scenario", "settings-nav",
                "--serial", "device-123",
                "--package", "com.pocketagent.android",
                "--build-source", "preinstalled-nondebuggable",
                "--build-variant", "unverified-nondebuggable",
                "--version-code", "1",
                "--version-name", "0.1.0",
                "--last-update-time", "2026-07-11 10:00:00",
                "--installed-apk-path", "/data/app/base.apk",
                "--started-at-utc", "2026-07-11T10:00:00Z",
                "--completed-at-utc", "2026-07-11T10:00:10Z",
                "--runtime-condition", "unloaded",
                "--download-condition", "idle",
                "--voice-condition", "inactive",
                "--native-runtime-packaged", "null",
                "--debuggable", "false",
                "--total-frames", "120",
                "--janky-pct", "10",
                "--p50-ms", "10",
                "--p90-ms", "20",
                "--p99-ms", "30",
            ]
        )

        self.assertEqual(args.declared_runtime_condition, "unloaded")
        self.assertEqual(args.declared_download_condition, "idle")
        self.assertEqual(args.declared_voice_condition, "inactive")


if __name__ == "__main__":
    unittest.main()
