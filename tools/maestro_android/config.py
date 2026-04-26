from __future__ import annotations

from copy import deepcopy
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from tools.maestro_android.common import MaestroAndroidError, REPO_ROOT

try:
    import yaml  # type: ignore
except ModuleNotFoundError:  # pragma: no cover - validated at runtime
    yaml = None


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ProjectConfig(_StrictModel):
    apk_glob: str = "apps/mobile-android/build/outputs/apk/debug/*.apk"
    build_command: list[str] = Field(
        default_factory=lambda: ["./gradlew", "--no-daemon", ":apps:mobile-android:assembleDebug"]
    )
    install_command: list[str] = Field(
        default_factory=lambda: ["./gradlew", "--no-daemon", ":apps:mobile-android:installDebug"]
    )
    app_id: str = "com.pocketagent.android"
    test_app_id: str = "com.pocketagent.android.test"


class DoctorConfig(_StrictModel):
    delegate_command: list[str] = Field(
        default_factory=lambda: ["python3", "tools/devctl/main.py", "doctor"]
    )
    required_commands: list[str] | None = None
    optional_commands: list[str] | None = None
    require_gradlew: bool | None = None


class ArtifactsConfig(_StrictModel):
    scratch_root: str = ".maestro-android/runs"
    report_roots: list[str] = Field(
        default_factory=lambda: [".maestro-android/runs", "scripts/benchmarks/runs", "tmp/devctl-artifacts"]
    )
    lifecycle_root: str = "tmp/lifecycle-e2e-first-run"
    clean_roots: list[str] = Field(
        default_factory=lambda: [".maestro-android", "tmp/lifecycle-e2e-first-run"]
    )


class FlowConfig(_StrictModel):
    roots: list[str] = Field(default_factory=lambda: ["tests/maestro", "tests/maestro-cloud"])


class ScopedConfig(_StrictModel):
    command_prefix: list[str] = Field(default_factory=lambda: ["bash", "scripts/dev/scoped-repro.sh"])
    require_tmp_flow: bool = True
    require_title_description_comments: bool = True
    crash_signature_regex: str = (
        "FATAL EXCEPTION|Fatal signal|SIGSEGV|Abort message|Runtime: Error|nativeLoadModel|pocket_llama|UI-RUNTIME-001"
    )
    app_context_regex: str = (
        "com\\.pocketagent\\.android|PocketLlamaJNI|libpocket_llama|Cmdline: com\\.pocketagent\\.android|"
        "Process: com\\.pocketagent\\.android|UI-RUNTIME-001"
    )


class LaneConfig(_StrictModel):
    kind: Literal["devctl-lane", "command"]
    name: str | None = None
    argv: list[str] = Field(default_factory=list)


class MaestroAndroidConfig(_StrictModel):
    project: ProjectConfig = Field(default_factory=ProjectConfig)
    doctor: DoctorConfig = Field(default_factory=DoctorConfig)
    artifacts: ArtifactsConfig = Field(default_factory=ArtifactsConfig)
    flows: FlowConfig = Field(default_factory=FlowConfig)
    scoped: ScopedConfig = Field(default_factory=ScopedConfig)
    lanes: dict[str, LaneConfig] = Field(
        default_factory=lambda: {
            "smoke": LaneConfig(kind="devctl-lane", name="maestro"),
            "journey": LaneConfig(kind="devctl-lane", name="journey"),
            "screenshot-pack": LaneConfig(kind="devctl-lane", name="screenshot-pack"),
            "lifecycle": LaneConfig(kind="command", argv=["bash", "scripts/ci/run_lifecycle_e2e.sh", "local-manual"]),
            "cloud-smoke": LaneConfig(kind="command", argv=["bash", "scripts/dev/maestro-cloud-smoke.sh"]),
            "fast-smoke": LaneConfig(kind="devctl-lane", name="fast-smoke"),
            "valid-output": LaneConfig(kind="devctl-lane", name="valid-output"),
            "strict-journey": LaneConfig(kind="devctl-lane", name="strict-journey"),
        }
    )


DEFAULT_CONFIG = MaestroAndroidConfig().model_dump(mode="python")


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged = deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def _load_yaml(path: Path) -> dict[str, Any]:
    if yaml is None:
        raise MaestroAndroidError(
            "ENVIRONMENT_ERROR",
            "PyYAML is required. Run: python3 -m pip install -r tools/devctl/requirements.txt",
        )
    try:
        payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except Exception as exc:
        raise MaestroAndroidError("CONFIG_ERROR", f"Failed to parse {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise MaestroAndroidError("CONFIG_ERROR", f"Config root must be an object in {path}")
    return payload


def load_config(repo_root: Path = REPO_ROOT, explicit_path: Path | None = None) -> MaestroAndroidConfig:
    config_path = explicit_path or (repo_root / ".maestro-android.yaml")
    payload: dict[str, Any] = DEFAULT_CONFIG
    if explicit_path is not None and not config_path.exists():
        raise MaestroAndroidError("CONFIG_ERROR", f"Missing config file: {config_path}")
    if config_path.exists():
        payload = _deep_merge(payload, _load_yaml(config_path))
    try:
        return MaestroAndroidConfig.model_validate(payload)
    except ValidationError as exc:
        raise MaestroAndroidError("CONFIG_ERROR", f"Schema validation failed for {config_path}: {exc}") from exc
