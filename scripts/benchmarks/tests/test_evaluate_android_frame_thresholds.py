import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from evaluate_android_frame_thresholds import ValidationError, evaluate_samples


class EvaluateAndroidFrameThresholdsTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_sample(self, index, **overrides):
        artifact_dir = self.root / f"sample-{index}"
        artifact_dir.mkdir()
        payload = {
            "scenario": "settings-nav",
            "serial": "device-123",
            "package": "com.pocketagent.android",
            "build_source": "assembled-native-benchmark" if index == 1 else "preinstalled-nondebuggable",
            "build_variant": "benchmark" if index == 1 else "unverified-nondebuggable",
            "native_runtime_packaged": True if index == 1 else None,
            "debuggable": False,
            "version_code": "1",
            "version_name": "0.1.0",
            "last_update_time": "2026-07-11 10:00:00",
            "installed_apk_path": "/data/app/example/base.apk",
            "started_at_utc": f"2026-07-11T03:00:0{index}Z",
            "completed_at_utc": f"2026-07-11T03:00:1{index}Z",
            "total_frames": 120,
            "janky_pct": [10.0, 12.0, 14.0][index - 1],
            "p50_ms": [10.0, 11.0, 12.0][index - 1],
            "p90_ms": [20.0, 21.0, 22.0][index - 1],
            "p99_ms": [28.0, 29.0, 30.0][index - 1],
            "artifact_dir": str(artifact_dir),
            "device_state": self.sample_device_state(),
        }
        payload.update(overrides)
        path = artifact_dir / "summary.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def three_samples(self, **third_overrides):
        return [self.write_sample(1), self.write_sample(2), self.write_sample(3, **third_overrides)]

    def sample_device_state(self):
        return {
            "manufacturer": "Google",
            "model": "Pixel 8",
            "android_release": "16",
            "api_level": 36,
            "refresh_rate_hz": 120.0,
            "refresh_rate_source": "dumpsys-display-active-mode",
            "refresh_rate_evidence_available": True,
            "thermal_status_before": 0,
            "thermal_status_after": 0,
            "battery_temperature_c_before": 31.2,
            "battery_temperature_c_after": 31.4,
            "compilation_filter": "speed-profile",
            "compilation_reason": "bg-dexopt",
            "compilation_evidence_available": True,
            "workload_condition_source": "operator-declared-not-observed",
            "declared_runtime_condition": "unloaded",
            "declared_download_condition": "idle",
            "declared_voice_condition": "inactive",
        }

    def test_passes_with_three_matching_native_benchmark_samples(self):
        report = evaluate_samples(self.three_samples(), expected_scenario="settings-nav")

        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["sample_count"], 3)
        self.assertEqual(report["medians"]["janky_pct"], 12.0)
        self.assertEqual(report["build_variant"], "benchmark")
        self.assertTrue(report["native_runtime_packaged"])

    def test_fails_when_a_metric_median_exceeds_threshold(self):
        paths = [
            self.write_sample(1, janky_pct=19.0),
            self.write_sample(2, janky_pct=21.0),
            self.write_sample(3, janky_pct=40.0),
        ]

        report = evaluate_samples(paths)

        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["medians"]["janky_pct"], 21.0)

    def test_rejects_mixed_scenarios(self):
        with self.assertRaisesRegex(ValidationError, "mixed scenario"):
            evaluate_samples(self.three_samples(scenario="model-sheet"))

    def test_rejects_unknown_scenario(self):
        paths = [
            self.write_sample(1, scenario="unknown"),
            self.write_sample(2, scenario="unknown"),
            self.write_sample(3, scenario="unknown"),
        ]
        with self.assertRaisesRegex(ValidationError, "unsupported scenario"):
            evaluate_samples(paths)

    def test_rejects_mixed_devices(self):
        with self.assertRaisesRegex(ValidationError, "mixed serial"):
            evaluate_samples(self.three_samples(serial="device-456"))

    def test_rejects_mixed_build_identity(self):
        with self.assertRaisesRegex(ValidationError, "mixed last_update_time"):
            evaluate_samples(self.three_samples(last_update_time="2026-07-11 10:01:00"))

    def test_rejects_reinstalled_package_path(self):
        with self.assertRaisesRegex(ValidationError, "mixed installed_apk_path"):
            evaluate_samples(self.three_samples(installed_apk_path="/data/app/reinstalled/base.apk"))

    def test_rejects_debuggable_sample(self):
        with self.assertRaisesRegex(ValidationError, "debuggable must be exactly false"):
            evaluate_samples(self.three_samples(debuggable=True))

    def test_rejects_run_group_without_native_benchmark_anchor(self):
        first = self.write_sample(
            1,
            build_source="preinstalled-nondebuggable",
            build_variant="unverified-nondebuggable",
            native_runtime_packaged=None,
        )
        paths = [first, self.write_sample(2), self.write_sample(3)]

        with self.assertRaisesRegex(ValidationError, "exactly one assembled native benchmark"):
            evaluate_samples(paths)

    def test_rejects_invalid_percentile_order(self):
        with self.assertRaisesRegex(ValidationError, "p50 <= p90 <= p99"):
            evaluate_samples(self.three_samples(p50_ms=30.0, p90_ms=20.0))

    def test_rejects_non_numeric_metric(self):
        with self.assertRaisesRegex(ValidationError, "p90_ms must be a number"):
            evaluate_samples(self.three_samples(p90_ms="fast"))

    def test_rejects_zero_frame_samples(self):
        with self.assertRaisesRegex(ValidationError, "total_frames"):
            evaluate_samples(self.three_samples(total_frames=19))

    def test_rejects_thermal_drift_or_pressure(self):
        pressured = {
            **self.sample_device_state(),
            "thermal_status_after": 1,
        }
        with self.assertRaisesRegex(ValidationError, "thermal_status_after"):
            evaluate_samples(self.three_samples(device_state=pressured))

    def test_rejects_missing_refresh_or_compilation_provenance(self):
        incomplete = {
            **self.sample_device_state(),
            "compilation_filter": "unavailable",
            "compilation_reason": "unavailable",
            "compilation_evidence_available": False,
        }
        with self.assertRaisesRegex(ValidationError, "compilation evidence"):
            evaluate_samples(self.three_samples(device_state=incomplete))

    def test_rejects_missing_device_state_metadata(self):
        with self.assertRaisesRegex(ValidationError, "device_state"):
            evaluate_samples(self.three_samples(device_state=None))

    def test_rejects_mixed_declared_runtime_download_or_voice_conditions(self):
        mixed = {
            **self.sample_device_state(),
            "declared_runtime_condition": "loaded-idle",
        }
        with self.assertRaisesRegex(ValidationError, "declared_runtime_condition"):
            evaluate_samples(self.three_samples(device_state=mixed))

    def test_rejects_legacy_condition_fields_as_observed_truth(self):
        legacy = {
            key.replace("declared_", ""): value
            for key, value in self.sample_device_state().items()
        }
        with self.assertRaisesRegex(ValidationError, "declared_runtime_condition"):
            evaluate_samples(self.three_samples(device_state=legacy))

    def test_rejects_conditions_mislabeled_as_observed(self):
        mislabeled = {
            **self.sample_device_state(),
            "workload_condition_source": "observed",
        }
        with self.assertRaisesRegex(ValidationError, "operator declarations"):
            evaluate_samples(self.three_samples(device_state=mislabeled))

    def test_rejects_wrong_sample_count(self):
        with self.assertRaisesRegex(ValidationError, "expected exactly 3 summaries"):
            evaluate_samples([self.write_sample(1), self.write_sample(2)])

    def test_cli_writes_report_and_returns_two_for_threshold_failure(self):
        paths = [
            self.write_sample(1, p99_ms=33.0),
            self.write_sample(2, p99_ms=34.0),
            self.write_sample(3, p99_ms=35.0),
        ]
        output = self.root / "evaluation.json"
        script = Path(__file__).resolve().parents[1] / "evaluate_android_frame_thresholds.py"

        result = subprocess.run(
            [sys.executable, str(script), "--output", str(output), *map(str, paths)],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 2, msg=result.stderr)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["status"], "FAIL")
        self.assertIn("operator-declared-not-observed", result.stdout)
        self.assertIn("Overall: FAIL", result.stdout)


if __name__ == "__main__":
    unittest.main()
