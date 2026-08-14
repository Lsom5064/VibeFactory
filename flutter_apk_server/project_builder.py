from __future__ import annotations

import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Protocol


GENERATED_APK_SIDELOAD_VERSION_CODE = 2_100_000_000
NATIVE_RELEASE_APK_RELATIVE = Path("app/build/outputs/apk/release/app-release.apk")
NATIVE_DEBUG_APK_RELATIVE = Path("app/build/outputs/apk/debug/app-debug.apk")
NATIVE_OUTPUT_METADATA_RELATIVE = Path("app/build/outputs/apk/release/output-metadata.json")

CACHE_DIRECTORY_NAMES = {
    "build",
    ".gradle",
    ".tooling",
    ".kotlin",
    "__pycache__",
}
PROJECT_ROOT_RUNNER_DIRECTORY_NAMES = {".codex_result", "logs"}
RUNTIME_CONTRACT_RELATIVE_PATHS = (
    Path("app/src/main/kotlin/kr/ac/kangwon/hai/generated/GeneratedApplication.kt"),
    Path("app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeCrashReporter.kt"),
    Path("app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeDataClient.kt"),
    Path("app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeHttpClient.kt"),
    Path("app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeLlmClient.kt"),
)


@dataclass(frozen=True)
class BuildStep:
    key: str
    label: str
    command: tuple[str, ...]


class ProjectBuilder(Protocol):
    expected_apk_relative: Path

    def copy_project(self, source: Path, destination: Path) -> None: ...

    def restore_runtime_contracts(self, template_root: Path, project_root: Path) -> tuple[str, ...]: ...

    def validate_project_structure(self, project_root: Path) -> None: ...

    def apply_identity(
        self,
        project_root: Path,
        *,
        task_id: str,
        app_name: str,
        application_id: str,
        version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
    ) -> bool: ...

    def identity_issues(
        self,
        project_root: Path,
        *,
        task_id: str,
        application_id: str,
        version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
    ) -> list[str]: ...

    def ensure_version(
        self,
        project_root: Path,
        version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
    ) -> bool: ...

    def build_steps(self, project_root: Path) -> tuple[BuildStep, ...]: ...

    def resolve_apk(self, project_root: Path) -> Path | None: ...

    def clear_artifacts(self, project_root: Path) -> None: ...

    def prune_caches_preserving_release(self, project_root: Path) -> None: ...

    def validate_apk(
        self,
        project_root: Path,
        apk_path: Path,
        *,
        expected_application_id: str,
        expected_version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
        env: Mapping[str, str] | None = None,
    ) -> None: ...

    def looks_like_placeholder(self, project_root: Path) -> bool: ...

    def infer_application_id(self, project_root: Path) -> str: ...

    def infer_app_name(self, project_root: Path) -> str: ...


def _read_properties(path: Path) -> tuple[list[str], dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines() if path.is_file() else []
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return lines, values


def _write_properties(path: Path, updates: Mapping[str, str]) -> bool:
    lines, existing = _read_properties(path)
    occurrences = {key: 0 for key in updates}
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in line:
            key = line.split("=", 1)[0].strip()
            if key in occurrences:
                occurrences[key] += 1
    changed = any(
        existing.get(key) != value or occurrences[key] != 1
        for key, value in updates.items()
    )
    if not changed:
        return False

    remaining = dict(updates)
    written: set[str] = set()
    output: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in line:
            key = line.split("=", 1)[0].strip()
            if key in updates:
                if key not in written:
                    output.append(f"{key}={updates[key]}")
                    written.add(key)
                    remaining.pop(key, None)
                continue
        output.append(line)
    output.extend(f"{key}={value}" for key, value in remaining.items())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")
    return True


def _update_string_resource(path: Path, name: str, value: str) -> bool:
    if not path.is_file():
        raise RuntimeError(f"Android string resource does not exist: {path}")
    tree = ET.parse(path)
    root = tree.getroot()
    target = next(
        (child for child in root.findall("string") if child.attrib.get("name") == name),
        None,
    )
    if target is None:
        target = ET.SubElement(root, "string", {"name": name})
    if (target.text or "") == value:
        return False
    target.text = value
    ET.indent(tree, space="    ")
    tree.write(path, encoding="utf-8", xml_declaration=True)
    return True


class NativeAndroidProjectBuilder:
    expected_apk_relative = NATIVE_RELEASE_APK_RELATIVE

    @staticmethod
    def ignore_copy_entries(_path: str, names: list[str]) -> set[str]:
        return {name for name in names if name in CACHE_DIRECTORY_NAMES}

    def copy_project(self, source: Path, destination: Path) -> None:
        source_root = source.resolve()

        def ignore_entries(path: str, names: list[str]) -> set[str]:
            ignored = self.ignore_copy_entries(path, names)
            if Path(path).resolve() == source_root:
                ignored.update(name for name in names if name in PROJECT_ROOT_RUNNER_DIRECTORY_NAMES)
            return ignored

        shutil.copytree(source, destination, ignore=ignore_entries)

    def restore_runtime_contracts(self, template_root: Path, project_root: Path) -> tuple[str, ...]:
        restored: list[str] = []
        for relative_path in RUNTIME_CONTRACT_RELATIVE_PATHS:
            source = template_root / relative_path
            destination = project_root / relative_path
            if not source.is_file():
                raise RuntimeError(f"Native runtime contract is missing from BaseProject: {relative_path}")
            source_bytes = source.read_bytes()
            if destination.is_file() and destination.read_bytes() == source_bytes:
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            restored.append(relative_path.as_posix())
        return tuple(restored)

    def apply_identity(
        self,
        project_root: Path,
        *,
        task_id: str,
        app_name: str,
        application_id: str,
        version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
    ) -> bool:
        self.validate_project_structure(project_root)
        changed = _write_properties(
            project_root / "gradle.properties",
            {
                "GENERATED_APP_APPLICATION_ID": application_id,
                "GENERATED_APP_TASK_ID": task_id,
                "GENERATED_APP_VERSION_CODE": str(version_code),
                "GENERATED_APP_VERSION_NAME": "1.0.0",
            },
        )
        if _update_string_resource(
            project_root / "app" / "src" / "main" / "res" / "values" / "strings.xml",
            "app_name",
            app_name,
        ):
            changed = True
        return changed

    def identity_issues(
        self,
        project_root: Path,
        *,
        task_id: str,
        application_id: str,
        version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
    ) -> list[str]:
        try:
            self.validate_project_structure(project_root)
        except RuntimeError as exc:
            return [str(exc)]
        _, properties = _read_properties(project_root / "gradle.properties")
        expected = {
            "GENERATED_APP_APPLICATION_ID": application_id,
            "GENERATED_APP_TASK_ID": task_id,
            "GENERATED_APP_VERSION_CODE": str(version_code),
        }
        issues = [
            f"{key}={properties.get(key, '(missing)')}"
            for key, value in expected.items()
            if properties.get(key) != value
        ]
        runtime_contracts = {
            "app/src/main/kotlin/kr/ac/kangwon/hai/generated/GeneratedApplication.kt": (
                "VibeCrashReporter.initialize(this)",
            ),
            "app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeCrashReporter.kt": (
                "kr.ac.kangwon.hai.action.CRASH_REPORT",
                'putExtra("task_id", BuildConfig.VIBE_TASK_ID)',
                'putExtra("package_name", context.packageName)',
                'putExtra("stack_trace", stackTrace)',
            ),
            "app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeLlmClient.kt": (
                "/apps/$taskId/llm/respond",
                "BuildConfig.VIBE_SERVER_BASE_URL",
                "context.applicationContext.packageName",
                'payload.put("image_base64", imageBase64)',
            ),
            "app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeDataClient.kt": (
                "/apps/$taskId/data/",
                "BuildConfig.VIBE_SERVER_BASE_URL",
                '"POST",',
                '"PATCH",',
                '"DELETE",',
            ),
            "app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeHttpClient.kt": (
                "suspendCancellableCoroutine",
                "invokeOnCancellation { call.cancel() }",
            ),
            "app/src/main/AndroidManifest.xml": (
                'android:name=".GeneratedApplication"',
                'android:name=".MainActivity"',
                'android:name="android.permission.INTERNET"',
            ),
        }
        for relative_path, markers in runtime_contracts.items():
            contract_path = project_root / relative_path
            contract_text = (
                contract_path.read_text(encoding="utf-8", errors="replace")
                if contract_path.is_file()
                else ""
            )
            for marker in markers:
                if marker not in contract_text:
                    issues.append(f"{relative_path}:missing-runtime-contract:{marker}")
        return issues

    def ensure_version(
        self,
        project_root: Path,
        version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
    ) -> bool:
        return _write_properties(
            project_root / "gradle.properties",
            {
                "GENERATED_APP_VERSION_CODE": str(version_code),
                "GENERATED_APP_VERSION_NAME": "1.0.0",
            },
        )

    def validate_project_structure(self, project_root: Path) -> None:
        required = (
            project_root / "settings.gradle.kts",
            project_root / "build.gradle.kts",
            project_root / "gradlew",
            project_root / "app" / "build.gradle.kts",
            project_root / "app" / "src" / "main" / "AndroidManifest.xml",
            project_root / "app" / "src" / "main" / "kotlin" / "kr" / "ac" / "kangwon" / "hai" / "generated" / "MainActivity.kt",
            project_root / "app" / "src" / "main" / "res" / "layout" / "activity_main.xml",
        )
        missing = [str(path.relative_to(project_root)) for path in required if not path.is_file()]
        if missing:
            raise RuntimeError("Native Android project files are missing: " + ", ".join(missing))

        forbidden = [
            path.relative_to(project_root).as_posix()
            for path in (project_root / "pubspec.yaml", project_root / "lib")
            if path.exists()
        ]
        if forbidden:
            raise RuntimeError(
                "Flutter/Dart project content is not allowed: " + ", ".join(forbidden)
            )

        app_gradle = (project_root / "app" / "build.gradle.kts").read_text(
            encoding="utf-8",
            errors="replace",
        )
        required_gradle_markers = (
            'namespace = "kr.ac.kangwon.hai.generated"',
            "applicationId = generatedApplicationId",
            'buildConfigField("String", "VIBE_TASK_ID"',
            'buildConfigField("String", "VIBE_SERVER_BASE_URL"',
            'create("generatedRelease")',
            "signingConfig = signingConfigs.getByName(\"generatedRelease\")",
        )
        missing_markers = [marker for marker in required_gradle_markers if marker not in app_gradle]
        if missing_markers:
            raise RuntimeError(
                "Server-managed Android build contract was changed: "
                + ", ".join(missing_markers)
            )
        if "buildFeatures" in app_gradle and re.search(r"\bcompose\s*=\s*true", app_gradle):
            raise RuntimeError("Jetpack Compose is not allowed; use Android Views/XML")

        for kotlin_path in (project_root / "app" / "src" / "main").rglob("*.kt"):
            kotlin_text = kotlin_path.read_text(encoding="utf-8", errors="replace")
            if "androidx.compose" in kotlin_text or "@Composable" in kotlin_text:
                raise RuntimeError(
                    "Jetpack Compose source is not allowed: "
                    + kotlin_path.relative_to(project_root).as_posix()
                )

    def build_steps(self, project_root: Path) -> tuple[BuildStep, ...]:
        gradlew = str((project_root / "gradlew").resolve())
        return (
            BuildStep(
                key="lint",
                label="Android lint",
                command=(gradlew, ":app:lintDebug"),
            ),
            BuildStep(
                key="build",
                label="Android release APK 빌드",
                command=(gradlew, ":app:assembleRelease"),
            ),
        )

    def resolve_apk(self, project_root: Path) -> Path | None:
        candidate = project_root / self.expected_apk_relative
        return candidate.resolve() if candidate.is_file() and candidate.stat().st_size > 0 else None

    def clear_artifacts(self, project_root: Path) -> None:
        for relative in (NATIVE_RELEASE_APK_RELATIVE, NATIVE_DEBUG_APK_RELATIVE):
            path = project_root / relative
            if path.is_file():
                path.unlink()

    def prune_caches_preserving_release(self, project_root: Path) -> None:
        for relative in (
            ".gradle",
            ".kotlin",
            ".tooling",
            "build",
            *sorted(PROJECT_ROOT_RUNNER_DIRECTORY_NAMES),
        ):
            cache_path = project_root / relative
            if cache_path.is_dir():
                shutil.rmtree(cache_path)

        app_build_root = project_root / "app" / "build"
        if not app_build_root.is_dir():
            return
        for child in app_build_root.iterdir():
            if child.name != "outputs":
                if child.is_dir():
                    shutil.rmtree(child)
                else:
                    child.unlink()
        outputs_root = app_build_root / "outputs"
        if not outputs_root.is_dir():
            return
        for child in outputs_root.iterdir():
            if child.name != "apk":
                if child.is_dir():
                    shutil.rmtree(child)
                else:
                    child.unlink()
        apk_root = outputs_root / "apk"
        if not apk_root.is_dir():
            return
        for child in apk_root.iterdir():
            if child.name != "release":
                if child.is_dir():
                    shutil.rmtree(child)
                else:
                    child.unlink()

    def infer_application_id(self, project_root: Path) -> str:
        _, properties = _read_properties(project_root / "gradle.properties")
        return properties.get("GENERATED_APP_APPLICATION_ID", "").strip()

    def infer_app_name(self, project_root: Path) -> str:
        strings_path = project_root / "app" / "src" / "main" / "res" / "values" / "strings.xml"
        if not strings_path.is_file():
            return ""
        root = ET.parse(strings_path).getroot()
        app_name = next(
            (child for child in root.findall("string") if child.attrib.get("name") == "app_name"),
            None,
        )
        return (app_name.text or "").strip() if app_name is not None else ""

    def looks_like_placeholder(self, project_root: Path) -> bool:
        strings_path = project_root / "app" / "src" / "main" / "res" / "values" / "strings.xml"
        layout_path = project_root / "app" / "src" / "main" / "res" / "layout" / "activity_main.xml"
        strings_text = strings_path.read_text(encoding="utf-8", errors="replace") if strings_path.is_file() else ""
        layout_text = layout_path.read_text(encoding="utf-8", errors="replace") if layout_path.is_file() else ""
        return (
            "Native Android template ready" in strings_text
            and "@string/template_title" in layout_text
            and "@string/template_body" in layout_text
        )

    def validate_apk(
        self,
        project_root: Path,
        apk_path: Path,
        *,
        expected_application_id: str,
        expected_version_code: int = GENERATED_APK_SIDELOAD_VERSION_CODE,
        env: Mapping[str, str] | None = None,
    ) -> None:
        import json

        metadata_path = project_root / NATIVE_OUTPUT_METADATA_RELATIVE
        if not metadata_path.is_file():
            raise RuntimeError("Android APK output metadata is missing")
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        application_id = str(metadata.get("applicationId") or "")
        elements = metadata.get("elements") if isinstance(metadata.get("elements"), list) else []
        element = next(
            (
                item
                for item in elements
                if isinstance(item, dict) and str(item.get("outputFile") or "") == apk_path.name
            ),
            elements[0] if elements else {},
        )
        version_code = int(element.get("versionCode") or 0) if isinstance(element, dict) else 0
        if application_id != expected_application_id:
            raise RuntimeError(
                f"APK applicationId mismatch: {application_id} != {expected_application_id}"
            )
        if version_code != expected_version_code:
            raise RuntimeError(
                f"APK versionCode mismatch: {version_code} != {expected_version_code}"
            )
        if not apk_path.is_file() or apk_path.stat().st_size <= 0:
            raise RuntimeError("Android APK artifact is missing or empty")
        apksigner = self._find_apksigner(env or os.environ)
        completed = subprocess.run(
            [str(apksigner), "verify", "--verbose", str(apk_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout).strip()
            raise RuntimeError(f"APK signature verification failed: {detail}")

    @staticmethod
    def _find_apksigner(env: Mapping[str, str]) -> Path:
        sdk_root = str(env.get("ANDROID_SDK_ROOT") or env.get("ANDROID_HOME") or "").strip()
        if not sdk_root:
            default_sdk = Path.home() / "Library" / "Android" / "sdk"
            if default_sdk.is_dir():
                sdk_root = str(default_sdk)
        build_tools = Path(sdk_root) / "build-tools" if sdk_root else Path()
        candidates = sorted(
            (path for path in build_tools.glob("*/apksigner") if path.is_file()),
            key=lambda path: tuple(
                int(part) if part.isdigit() else 0
                for part in re.split(r"[.-]", path.parent.name)
            ),
            reverse=True,
        )
        if not candidates:
            resolved = shutil.which("apksigner")
            if resolved:
                return Path(resolved)
            raise RuntimeError("Android SDK apksigner was not found")
        return candidates[0]


native_android_project_builder: ProjectBuilder = NativeAndroidProjectBuilder()
