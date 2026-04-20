from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest import mock

from tools.devctl.kotlin_quality_gate import (
    Finding,
    _gradle_subprocess_env,
    _find_supported_jdk_home,
    _parse_java_major_version,
    _resolve_gradle_java_home,
    collect_findings,
    run_gate,
)


class KotlinQualityGateTest(unittest.TestCase):
    def test_collect_findings_parses_detekt_and_ktlint_reports(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            detekt_report = root / "apps/mobile-android/build/reports/detekt/detekt.xml"
            detekt_report.parent.mkdir(parents=True, exist_ok=True)
            detekt_report.write_text(
                """
                <smell-baseline>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/A.kt">
                    <error line="12" source="style:MagicNumber" message="Magic number." />
                  </file>
                </smell-baseline>
                """.strip(),
                encoding="utf-8",
            )
            ktlint_report = root / "packages/core-domain/build/reports/ktlint/ktlintMainSourceSetCheck.xml"
            ktlint_report.parent.mkdir(parents=True, exist_ok=True)
            ktlint_report.write_text(
                """
                <checkstyle>
                  <file name="packages/core-domain/src/commonMain/kotlin/com/example/B.kt">
                    <error line="3" source="standard:max-line-length" message="Too long." />
                  </file>
                </checkstyle>
                """.strip(),
                encoding="utf-8",
            )

            findings = collect_findings(root)

            self.assertEqual(2, len(findings))
            self.assertEqual(
                Finding(
                    tool="detekt",
                    path=Path("apps/mobile-android/src/main/kotlin/com/example/A.kt"),
                    line_number=12,
                    rule_id="style:MagicNumber",
                    message="Magic number.",
                ),
                findings[0],
            )
            self.assertEqual(
                Finding(
                    tool="ktlint",
                    path=Path("packages/core-domain/src/commonMain/kotlin/com/example/B.kt"),
                    line_number=3,
                    rule_id="standard:max-line-length",
                    message="Too long.",
                ),
                findings[1],
            )

    def test_run_gate_filters_findings_to_changed_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            detekt_report = root / "apps/mobile-android/build/reports/detekt/detekt.xml"
            detekt_report.parent.mkdir(parents=True, exist_ok=True)
            detekt_report.write_text(
                """
                <smell-baseline>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/A.kt">
                    <error line="12" source="style:MagicNumber" message="Magic number." />
                  </file>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/C.kt">
                    <error line="22" source="complexity:LongMethod" message="Long method." />
                  </file>
                </smell-baseline>
                """.strip(),
                encoding="utf-8",
            )

            with mock.patch(
                "tools.devctl.kotlin_quality_gate._git_changed_files",
                return_value=[Path("apps/mobile-android/src/main/kotlin/com/example/A.kt")],
            ):
                findings, changed_findings, changed_files = run_gate(
                    root,
                    strict_changed_only=True,
                    skip_gradle=True,
                )

            self.assertEqual(2, len(findings))
            self.assertEqual(1, len(changed_findings))
            self.assertEqual(Path("apps/mobile-android/src/main/kotlin/com/example/A.kt"), changed_files[0])

    def test_run_gate_keeps_changed_findings_empty_when_changed_files_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            detekt_report = root / "apps/mobile-android/build/reports/detekt/detekt.xml"
            detekt_report.parent.mkdir(parents=True, exist_ok=True)
            detekt_report.write_text(
                """
                <smell-baseline>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/A.kt">
                    <error line="12" source="style:MagicNumber" message="Magic number." />
                  </file>
                </smell-baseline>
                """.strip(),
                encoding="utf-8",
            )

            with mock.patch("tools.devctl.kotlin_quality_gate._git_changed_files", return_value=[]):
                findings, changed_findings, changed_files = run_gate(
                    root,
                    strict_changed_only=True,
                    skip_gradle=True,
                )

            self.assertEqual(1, len(findings))
            self.assertEqual(0, len(changed_findings))
            self.assertEqual([], changed_files)

    def test_parse_java_major_version_handles_modern_and_legacy_formats(self) -> None:
        self.assertEqual(25, _parse_java_major_version('openjdk version "25.0.1" 2025-09-16'))
        self.assertEqual(21, _parse_java_major_version('openjdk version "21.0.10" 2026-01-20'))
        self.assertEqual(8, _parse_java_major_version('java version "1.8.0_412"'))
        self.assertIsNone(_parse_java_major_version("not a java version string"))

    def test_resolve_gradle_java_home_keeps_current_jdk_when_compatible(self) -> None:
        with mock.patch.dict(os.environ, {"JAVA_HOME": "/fake/jdk-21"}, clear=True), mock.patch(
            "tools.devctl.kotlin_quality_gate._java_major_version",
            return_value=21,
        ) as version_mock, mock.patch("tools.devctl.kotlin_quality_gate._find_supported_jdk_home") as find_mock:
            self.assertIsNone(_resolve_gradle_java_home())
            version_mock.assert_called_once()
            find_mock.assert_not_called()

    def test_resolve_gradle_java_home_uses_jdk21_fallback_for_java25(self) -> None:
        fallback_home = Path("/opt/fallback/jdk-21")
        with mock.patch.dict(os.environ, {"JAVA_HOME": "/fake/jdk-25"}, clear=True), mock.patch(
            "tools.devctl.kotlin_quality_gate._java_major_version",
            return_value=25,
        ), mock.patch(
            "tools.devctl.kotlin_quality_gate._find_supported_jdk_home",
            return_value=fallback_home,
        ):
            self.assertEqual(fallback_home, _resolve_gradle_java_home())

    def test_find_supported_jdk_home_prefers_jdk21_when_available(self) -> None:
        jdk21 = Path("/opt/jdk-21")

        def fake_find(major: int) -> Path | None:
            return {21: jdk21}.get(major)

        with mock.patch("tools.devctl.kotlin_quality_gate._find_jdk_home", side_effect=fake_find):
            self.assertEqual(jdk21, _find_supported_jdk_home())

    def test_find_supported_jdk_home_returns_none_without_jdk21(self) -> None:
        def fake_find(major: int) -> Path | None:
            return None

        with mock.patch("tools.devctl.kotlin_quality_gate._find_jdk_home", side_effect=fake_find):
            self.assertIsNone(_find_supported_jdk_home())

    def test_gradle_env_injects_fallback_java_home(self) -> None:
        fallback_home = Path("/opt/fallback/jdk-21")
        captured: dict[str, object] = {}

        def fake_run(*args, **kwargs):
            captured["args"] = args
            captured["kwargs"] = kwargs
            return CompletedProcess(args=args[0], returncode=0, stdout="", stderr="")

        with mock.patch.dict(os.environ, {"JAVA_HOME": "/fake/jdk-25"}, clear=True), mock.patch(
            "tools.devctl.kotlin_quality_gate._resolve_gradle_java_home",
            return_value=fallback_home,
        ), mock.patch(
            "tools.devctl.kotlin_quality_gate.subprocess.run",
            side_effect=fake_run,
        ):
            from tools.devctl.kotlin_quality_gate import _run_gradle_analysis

            _run_gradle_analysis(Path("/repo"))

        self.assertEqual(
            str(fallback_home),
            captured["kwargs"]["env"]["JAVA_HOME"],
        )
        self.assertEqual(
            str(fallback_home),
            captured["kwargs"]["env"]["ORG_GRADLE_JAVA_HOME"],
        )

    def test_gradle_env_keeps_existing_java_home_without_fallback(self) -> None:
        with mock.patch.dict(os.environ, {"JAVA_HOME": "/fake/jdk-21"}, clear=True), mock.patch(
            "tools.devctl.kotlin_quality_gate._resolve_gradle_java_home",
            return_value=None,
        ):
            env = _gradle_subprocess_env()

        self.assertEqual("/fake/jdk-21", env["JAVA_HOME"])
        self.assertNotIn("ORG_GRADLE_JAVA_HOME", env)


if __name__ == "__main__":
    unittest.main()
