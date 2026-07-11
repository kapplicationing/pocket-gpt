import json
import os
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from validate_compose_hotpath_reports import ReportValidationError, validate_reports


EXPECTED_COMPOSABLES = ("PocketAgentApp", "ComposerInputRow", "ModelSheet")


class ValidateComposeHotpathReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.report_dir = self.root / "compose-reports" / "benchmark"
        self.metrics_dir = self.root / "compose-metrics" / "benchmark"
        self.report_dir.mkdir(parents=True)
        self.metrics_dir.mkdir(parents=True)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_valid_reports(self, *, generated_at_epoch=2_000_000_000):
        composables = self.report_dir / "mobile-android_benchmark-composables.txt"
        composables.write_text(
            "\n".join(
                f"restartable skippable scheme(\"[androidx.compose.ui.UiComposable]\") fun {name}()"
                for name in EXPECTED_COMPOSABLES
            ),
            encoding="utf-8",
        )
        classes = self.report_dir / "mobile-android_benchmark-classes.txt"
        classes.write_text("stable class Example {\n}\n", encoding="utf-8")
        csv_path = self.report_dir / "mobile-android_benchmark-composables.csv"
        csv_path.write_text(
            "name,composable,skippable,restartable,readonly,inline,lambda,params,stableParams,unstableParams\n"
            "PocketAgentApp,1,1,1,0,0,0,1,1,0\n",
            encoding="utf-8",
        )
        module = self.metrics_dir / "mobile-android_benchmark-module.json"
        module.write_text(
            json.dumps(
                {
                    "skippableComposables": 8,
                    "restartableComposables": 9,
                    "readonlyComposables": 1,
                    "totalComposables": 10,
                }
            ),
            encoding="utf-8",
        )
        for path in (composables, classes, csv_path, module):
            os.utime(path, (generated_at_epoch, generated_at_epoch))

    def validate(self, **overrides):
        arguments = {
            "report_dir": self.report_dir,
            "metrics_dir": self.metrics_dir,
            "variant": "benchmark",
            "expected_composables": EXPECTED_COMPOSABLES,
            "not_before_epoch": 0,
        }
        arguments.update(overrides)
        return validate_reports(**arguments)

    def test_accepts_fresh_nonempty_benchmark_reports_with_all_expected_composables(self):
        self.write_valid_reports()

        result = self.validate()

        self.assertEqual(result["total_composables"], 10)
        self.assertEqual(result["expected_composables"], list(EXPECTED_COMPOSABLES))

    def test_rejects_empty_module_metrics_instead_of_reporting_a_false_green(self):
        self.write_valid_reports()
        module = next(self.metrics_dir.glob("*-module.json"))
        module.write_text(
            json.dumps({field: 0 for field in ("skippableComposables", "restartableComposables", "readonlyComposables", "totalComposables")}),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ReportValidationError, "totalComposables"):
            self.validate()

    def test_rejects_incomplete_metric_bundle(self):
        self.write_valid_reports()
        next(self.report_dir.glob("*-composables.csv")).unlink()

        with self.assertRaisesRegex(ReportValidationError, "composables CSV"):
            self.validate()

    def test_rejects_report_files_older_than_the_current_build(self):
        self.write_valid_reports(generated_at_epoch=1_000)

        with self.assertRaisesRegex(ReportValidationError, "stale"):
            self.validate(not_before_epoch=2_000)

    def test_rejects_missing_expected_hotpath_composable(self):
        self.write_valid_reports()
        composables = next(self.report_dir.glob("*-composables.txt"))
        composables.write_text(
            "fun PocketAgentApp()\nfun ComposerInputRowState()\nfun ModelSheet()\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ReportValidationError, "ComposerInputRow"):
            self.validate()

    def test_rejects_reports_older_than_a_source_input(self):
        self.write_valid_reports(generated_at_epoch=2_000)
        source = self.root / "Changed.kt"
        source.write_text("fun changed() = Unit\n", encoding="utf-8")
        os.utime(source, (3_000, 3_000))

        with self.assertRaisesRegex(ReportValidationError, "source/config"):
            self.validate(freshness_inputs=(source,))

    def test_rejects_a_nonbenchmark_report_bundle(self):
        self.write_valid_reports()

        with self.assertRaisesRegex(ReportValidationError, "benchmark"):
            self.validate(variant="debug")


if __name__ == "__main__":
    unittest.main()
