from __future__ import annotations

import hashlib
import difflib
import json
import mimetypes
import re
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Optional


REVISION_LABEL_PATTERN = re.compile(r"^rev_\d{4}$")
LAYOUT_NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")
LAYOUT_CONFIGURATION_PATTERN = re.compile(r"^layout(?:-[A-Za-z0-9]+)*$")
RESOURCE_DIRECTORY_PATTERN = re.compile(
    r"^(?:values|layout|drawable|mipmap|color|font)(?:-[A-Za-z0-9]+)*$"
)
RESOURCE_REFERENCE_PATTERN = re.compile(
    r"@(?P<framework>android:)?(?P<type>[A-Za-z_][A-Za-z0-9_]*)/"
    r"(?P<name>[A-Za-z0-9_.]+)"
)
UNSAFE_XML_PATTERN = re.compile(br"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)
MAX_XML_BYTES = 2 * 1024 * 1024
MAX_BINARY_RESOURCE_BYTES = 8 * 1024 * 1024
MAX_RESOURCE_DISCOVERY_DEPTH = 8
MAX_CODEX_CONTEXT_BYTES = 16 * 1024 * 1024
ALLOWED_BINARY_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"


class UiEditorInputError(ValueError):
    pass


class UiEditorSecurityError(ValueError):
    pass


class UiEditorXmlError(ValueError):
    pass


class UiEditorFileTooLargeError(ValueError):
    pass


@dataclass(frozen=True)
class RevisionSource:
    available: bool
    project_root: Optional[Path]
    reason: str = ""


def is_within(candidate: Path, root: Path) -> bool:
    try:
        candidate.relative_to(root)
        return True
    except ValueError:
        return False


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_android_xml(content: bytes, *, source_name: str) -> ElementTree.Element:
    if len(content) > MAX_XML_BYTES:
        raise UiEditorFileTooLargeError(f"XML exceeds {MAX_XML_BYTES} bytes")
    if UNSAFE_XML_PATTERN.search(content):
        raise UiEditorSecurityError("DTD and entity declarations are not allowed")
    try:
        return ElementTree.fromstring(content)
    except ElementTree.ParseError as exc:
        raise UiEditorXmlError(f"invalid XML in {source_name}: {exc}") from exc


def resolve_revision_source(
    *,
    workspaces_root: Path,
    task: dict[str, Any],
    snapshot: dict[str, Any],
    revision_label: str,
) -> RevisionSource:
    if not REVISION_LABEL_PATTERN.fullmatch(revision_label):
        raise UiEditorInputError("invalid revision label")

    workspace_value = str(snapshot.get("workspace_path") or task.get("workspace_path") or "").strip()
    project_value = str(snapshot.get("project_path") or task.get("project_path") or "").strip()
    if not workspace_value or not project_value:
        return RevisionSource(False, None, "Revision source is not available.")

    root = workspaces_root.expanduser().resolve()
    workspace = Path(workspace_value).expanduser().resolve()
    project = Path(project_value).expanduser().resolve()
    if not is_within(workspace, root) or not is_within(project, workspace):
        return RevisionSource(False, None, "Revision source is outside the server workspace.")
    if not workspace.is_dir() or not project.is_dir():
        return RevisionSource(False, None, "Revision source has been archived or removed.")
    return RevisionSource(True, project)


def safe_layout_path(project_root: Path, layout_name: str, configuration: str) -> Path:
    if not LAYOUT_NAME_PATTERN.fullmatch(layout_name):
        raise UiEditorInputError("invalid layout name")
    if not LAYOUT_CONFIGURATION_PATTERN.fullmatch(configuration):
        raise UiEditorInputError("invalid layout configuration")

    resource_root = (project_root / "app" / "src" / "main" / "res").resolve()
    candidate = (resource_root / configuration / f"{layout_name}.xml").resolve()
    if not is_within(candidate, resource_root):
        raise UiEditorSecurityError("layout path escapes the resource root")
    return candidate


def list_layout_documents(project_root: Path) -> list[dict[str, Any]]:
    resource_root = (project_root / "app" / "src" / "main" / "res").resolve()
    if not resource_root.is_dir():
        return []

    layouts: list[dict[str, Any]] = []
    for configuration_dir in sorted(resource_root.glob("layout*"), key=lambda item: item.name):
        if not configuration_dir.is_dir() or not LAYOUT_CONFIGURATION_PATTERN.fullmatch(configuration_dir.name):
            continue
        resolved_configuration = configuration_dir.resolve()
        if not is_within(resolved_configuration, resource_root):
            continue
        for layout_path in sorted(configuration_dir.glob("*.xml"), key=lambda item: item.name):
            resolved_layout = layout_path.resolve()
            if not is_within(resolved_layout, resolved_configuration) or not resolved_layout.is_file():
                continue
            content = resolved_layout.read_bytes()
            root = parse_android_xml(content, source_name=layout_path.name)
            layouts.append(
                {
                    "layout_name": layout_path.stem,
                    "configuration": configuration_dir.name,
                    "resource_path": f"res/{configuration_dir.name}/{layout_path.name}",
                    "root_tag": local_name(root.tag),
                    "sha256": sha256_bytes(content),
                    "size_bytes": len(content),
                }
            )
    return layouts


def extract_resource_references(xml_fragments: Iterable[bytes]) -> set[tuple[str, str]]:
    references: set[tuple[str, str]] = set()
    for fragment in xml_fragments:
        text = fragment.decode("utf-8", errors="replace")
        for match in RESOURCE_REFERENCE_PATTERN.finditer(text):
            if match.group("framework"):
                continue
            references.add((match.group("type"), match.group("name")))
    return references


def resource_definition(child: ElementTree.Element) -> Optional[tuple[str, str]]:
    name = str(child.attrib.get("name") or "").strip()
    if not name:
        return None
    resource_type = local_name(child.tag)
    if resource_type == "item":
        resource_type = str(child.attrib.get("type") or "").strip()
    if not resource_type:
        return None
    return resource_type, name


def resource_file_payload(path: Path, resource_root: Path) -> dict[str, Any]:
    resolved_path = path.resolve()
    if not is_within(resolved_path, resource_root) or not resolved_path.is_file():
        raise UiEditorSecurityError("resource path escapes the resource root")
    content = resolved_path.read_bytes()
    is_xml = resolved_path.suffix.lower() == ".xml"
    size_limit = MAX_XML_BYTES if is_xml else MAX_BINARY_RESOURCE_BYTES
    if len(content) > size_limit:
        raise UiEditorFileTooLargeError(f"resource exceeds {size_limit} bytes")
    if is_xml:
        parse_android_xml(content, source_name=resolved_path.name)
    relative_path = resolved_path.relative_to(resource_root).as_posix()
    media_type = "application/xml" if is_xml else (mimetypes.guess_type(resolved_path.name)[0] or "application/octet-stream")
    payload: dict[str, Any] = {
        "resource_path": f"res/{relative_path}",
        "kind": "xml" if is_xml else "binary",
        "media_type": media_type,
        "sha256": sha256_bytes(content),
        "size_bytes": len(content),
    }
    if is_xml:
        payload["content"] = content.decode("utf-8")
    return payload


def discover_resource_files(
    project_root: Path,
    initial_references: set[tuple[str, str]],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    resource_root = (project_root / "app" / "src" / "main" / "res").resolve()
    if not resource_root.is_dir():
        return [], [
            {"type": resource_type, "name": name}
            for resource_type, name in sorted(initial_references)
        ]

    pending = set(initial_references)
    processed: set[tuple[str, str]] = set()
    found_files: dict[str, dict[str, Any]] = {}
    resolved_references: set[tuple[str, str]] = set()

    for _ in range(MAX_RESOURCE_DISCOVERY_DEPTH):
        current = pending - processed
        if not current:
            break
        processed.update(current)
        nested_fragments: list[bytes] = []

        for values_dir in sorted(resource_root.glob("values*"), key=lambda item: item.name):
            if not values_dir.is_dir() or not RESOURCE_DIRECTORY_PATTERN.fullmatch(values_dir.name):
                continue
            for values_file in sorted(values_dir.glob("*.xml"), key=lambda item: item.name):
                content = values_file.read_bytes()
                root = parse_android_xml(content, source_name=values_file.name)
                matched_children = [
                    child
                    for child in list(root)
                    if resource_definition(child) in current
                ]
                if not matched_children:
                    continue
                payload = resource_file_payload(values_file, resource_root)
                found_files[payload["resource_path"]] = payload
                for child in matched_children:
                    definition = resource_definition(child)
                    if definition is not None:
                        resolved_references.add(definition)
                    nested_fragments.append(ElementTree.tostring(child, encoding="utf-8"))

        for resource_type, resource_name in current:
            for resource_dir in sorted(resource_root.glob(f"{resource_type}*"), key=lambda item: item.name):
                if not resource_dir.is_dir() or not RESOURCE_DIRECTORY_PATTERN.fullmatch(resource_dir.name):
                    continue
                for candidate in sorted(resource_dir.glob(f"{resource_name}.*"), key=lambda item: item.name):
                    if candidate.suffix.lower() != ".xml" and candidate.suffix.lower() not in ALLOWED_BINARY_SUFFIXES:
                        continue
                    payload = resource_file_payload(candidate, resource_root)
                    found_files[payload["resource_path"]] = payload
                    resolved_references.add((resource_type, resource_name))
                    if candidate.suffix.lower() == ".xml":
                        nested_fragments.append(candidate.read_bytes())

        pending.update(extract_resource_references(nested_fragments))

    unresolved = [
        {"type": resource_type, "name": name}
        for resource_type, name in sorted(processed - resolved_references)
    ]
    return [found_files[key] for key in sorted(found_files)], unresolved


def load_layout_document(
    project_root: Path,
    *,
    layout_name: str,
    configuration: str,
) -> dict[str, Any]:
    layout_path = safe_layout_path(project_root, layout_name, configuration)
    if not layout_path.is_file():
        raise FileNotFoundError(layout_path.name)
    content = layout_path.read_bytes()
    root = parse_android_xml(content, source_name=layout_path.name)
    references = extract_resource_references([content])
    resources, unresolved = discover_resource_files(project_root, references)
    return {
        "layout_name": layout_name,
        "configuration": configuration,
        "resource_path": f"res/{configuration}/{layout_name}.xml",
        "root_tag": local_name(root.tag),
        "xml": content.decode("utf-8"),
        "sha256": sha256_bytes(content),
        "size_bytes": len(content),
        "resource_references": [
            {"type": resource_type, "name": name}
            for resource_type, name in sorted(references)
        ],
        "resource_files": resources,
        "unresolved_resources": unresolved,
    }


def safe_resource_path(project_root: Path, resource_path: str) -> Path:
    pure_path = PurePosixPath(resource_path)
    parts = pure_path.parts
    if pure_path.is_absolute() or ".." in parts or len(parts) != 3 or parts[0] != "res":
        raise UiEditorInputError("invalid resource path")
    if not RESOURCE_DIRECTORY_PATTERN.fullmatch(parts[1]):
        raise UiEditorInputError("resource directory is not allowed")
    suffix = Path(parts[2]).suffix.lower()
    if suffix != ".xml" and suffix not in ALLOWED_BINARY_SUFFIXES:
        raise UiEditorInputError("resource type is not allowed")

    resource_root = (project_root / "app" / "src" / "main" / "res").resolve()
    candidate = (resource_root / parts[1] / parts[2]).resolve()
    if not is_within(candidate, resource_root):
        raise UiEditorSecurityError("resource path escapes the resource root")
    return candidate


def load_resource_bytes(project_root: Path, resource_path: str) -> tuple[bytes, str]:
    candidate = safe_resource_path(project_root, resource_path)
    if not candidate.is_file():
        raise FileNotFoundError(candidate.name)
    content = candidate.read_bytes()
    is_xml = candidate.suffix.lower() == ".xml"
    size_limit = MAX_XML_BYTES if is_xml else MAX_BINARY_RESOURCE_BYTES
    if len(content) > size_limit:
        raise UiEditorFileTooLargeError(f"resource exceeds {size_limit} bytes")
    if is_xml:
        parse_android_xml(content, source_name=candidate.name)
    media_type = "application/xml" if is_xml else (mimetypes.guess_type(candidate.name)[0] or "application/octet-stream")
    return content, media_type


def validate_ui_draft_xml(xml_text: str, *, source_name: str) -> bytes:
    content = xml_text.encode("utf-8")
    parse_android_xml(content, source_name=source_name)
    return content


def _element_identity(element: ElementTree.Element, path: str) -> str:
    android_id = element.attrib.get(f"{{{ANDROID_NAMESPACE}}}id", "")
    resource_name = android_id.rsplit("/", 1)[-1] if "/" in android_id else ""
    return f"id:{resource_name}" if resource_name else f"path:{path}"


def _flatten_elements(root: ElementTree.Element) -> dict[str, dict[str, Any]]:
    flattened: dict[str, dict[str, Any]] = {}

    def visit(element: ElementTree.Element, path: str) -> None:
        identity = _element_identity(element, path)
        flattened[identity] = {
            "tag": element.tag,
            "attributes": dict(sorted(element.attrib.items())),
            "text": (element.text or "").strip(),
            "path": path,
        }
        child_indexes: dict[str, int] = {}
        for child in list(element):
            tag = local_name(child.tag)
            child_index = child_indexes.get(tag, 0)
            child_indexes[tag] = child_index + 1
            visit(child, f"{path}/{tag}[{child_index}]")

    visit(root, f"/{local_name(root.tag)}[0]")
    return flattened


def structural_xml_diff(original_xml: str, edited_xml: str) -> dict[str, Any]:
    original_root = parse_android_xml(original_xml.encode("utf-8"), source_name="original.xml")
    edited_root = parse_android_xml(edited_xml.encode("utf-8"), source_name="edited.xml")
    original_nodes = _flatten_elements(original_root)
    edited_nodes = _flatten_elements(edited_root)
    changes: list[dict[str, Any]] = []

    for identity in sorted(original_nodes.keys() - edited_nodes.keys()):
        changes.append({"operation": "remove", "element": identity, "before": original_nodes[identity]})
    for identity in sorted(edited_nodes.keys() - original_nodes.keys()):
        changes.append({"operation": "add", "element": identity, "after": edited_nodes[identity]})
    for identity in sorted(original_nodes.keys() & edited_nodes.keys()):
        before = original_nodes[identity]
        after = edited_nodes[identity]
        if before == after:
            continue
        changed_fields = {
            field_name: {"before": before[field_name], "after": after[field_name]}
            for field_name in ("tag", "attributes", "text", "path")
            if before[field_name] != after[field_name]
        }
        changes.append({"operation": "change", "element": identity, "fields": changed_fields})

    unified = "\n".join(
        difflib.unified_diff(
            original_xml.splitlines(),
            edited_xml.splitlines(),
            fromfile="before.xml",
            tofile="edited.xml",
            lineterm="",
        )
    )
    return {
        "changed": bool(changes),
        "operation_count": len(changes),
        "operations": changes,
        "unified_diff": unified,
    }


def collect_ui_editor_source_context(project_root: Path) -> list[dict[str, str]]:
    resolved_project = project_root.resolve()
    source_roots = (
        resolved_project / "app" / "src" / "main" / "java",
        resolved_project / "app" / "src" / "main" / "kotlin",
        resolved_project / "app" / "src" / "main" / "res",
    )
    files: list[Path] = []
    for source_root in source_roots:
        if not source_root.is_dir():
            continue
        for candidate in source_root.rglob("*"):
            if not candidate.is_file() or candidate.is_symlink():
                continue
            if candidate.suffix.lower() not in {".kt", ".java", ".xml"}:
                continue
            resolved = candidate.resolve()
            if is_within(resolved, resolved_project):
                files.append(resolved)

    context: list[dict[str, str]] = []
    total_bytes = 0
    for source_file in sorted(set(files), key=lambda path: path.relative_to(resolved_project).as_posix()):
        content = source_file.read_bytes()
        total_bytes += len(content)
        if total_bytes > MAX_CODEX_CONTEXT_BYTES:
            raise UiEditorFileTooLargeError(
                f"Kotlin and XML context exceeds {MAX_CODEX_CONTEXT_BYTES} bytes"
            )
        context.append(
            {
                "path": source_file.relative_to(resolved_project).as_posix(),
                "content": content.decode("utf-8", errors="strict"),
            }
        )
    return context


def build_ui_editor_codex_prompt(
    *,
    task_id: str,
    base_revision_label: str,
    generated_revision_label: str,
    layout_name: str,
    configuration: str,
    original_xml: str,
    edited_xml: str,
    descriptions: dict[str, str],
    images: list[dict[str, Any]],
    preview_workspace_path: str,
    package_name: str,
    app_name: str,
    source_context: list[dict[str, str]],
) -> tuple[str, dict[str, Any]]:
    diff = structural_xml_diff(original_xml, edited_xml)
    source_sections = "\n\n".join(
        f"### `{entry['path']}`\n\n```{Path(entry['path']).suffix.lstrip('.')}\n{entry['content']}\n```"
        for entry in source_context
    )
    image_lines = "\n".join(
        "- element `{element}`: `{path}` (resource `{resource}`, SHA-256 `{sha}`)".format(
            element=image.get("element_stable_id") or "",
            path=image.get("workspace_path") or "",
            resource=image.get("resource_name") or "",
            sha=image.get("sha256") or "",
        )
        for image in images
    ) or "- 없음"
    prompt = f"""
## XML UI 편집 Revision 요청

이 요청은 사용자가 호스트 앱의 XML UI 편집기에서 확정한 변경이다. 단순 변경이라도 반드시 실제 프로젝트 코드를 수정한다.

### 고정 계약

- Task ID: `{task_id}`
- 기준 Revision: `{base_revision_label}`
- 작업 Revision: `{generated_revision_label}`
- 대상 layout: `app/src/main/res/{configuration}/{layout_name}.xml`
- 앱 이름: `{app_name}`
- package name: `{package_name}`
- 편집 후 XML을 UI 의도의 최우선 기준으로 사용한다.
- 사용자가 요구하지 않은 재디자인을 하지 않는다.
- 기준 Revision은 수정하지 않는다. 현재 `project` 디렉터리만 수정한다.
- 기존 View ID와 Kotlin 동작, package name, Task ID, 런타임 LLM·데이터 API·오류 보고 계약을 유지한다.
- 편집 후 구조가 다양한 Android 화면 크기, 회전, 키보드, 긴 텍스트에서 깨지지 않도록 constraint와 scroll 구조를 보정한다.
- 요소 추가·삭제에 맞춰 Kotlin 참조와 이벤트를 갱신한다.
- 첨부 이미지는 아래 workspace 상대 경로에서 읽어 적절한 Android drawable 리소스로 복사한다.
- 변경을 마치면 XML parse/resource linking, Kotlin compile, lint와 release APK build가 통과해야 한다.
- 결과와 제한을 `.codex_result/task_result.json`에 전문 기록한다.

### 변경 전 XML 전문

```xml
{original_xml}
```

### 사용자가 편집한 XML 전문

```xml
{edited_xml}
```

### 구조적 XML diff

```json
{json.dumps(diff['operations'], ensure_ascii=False, indent=2)}
```

### Unified diff

```diff
{diff['unified_diff']}
```

### 요소별 설명 전문

```json
{json.dumps(descriptions, ensure_ascii=False, indent=2, sort_keys=True)}
```

### 편집 화면 미리보기

`{preview_workspace_path or '(없음)'}`

### 추가 이미지

{image_lines}

## 현재 Kotlin/Java/XML 소스 전문

{source_sections}
""".strip() + "\n"
    payload = {
        "task_id": task_id,
        "base_revision_label": base_revision_label,
        "generated_revision_label": generated_revision_label,
        "layout_name": layout_name,
        "configuration": configuration,
        "original_xml": original_xml,
        "edited_xml": edited_xml,
        "diff": diff,
        "descriptions": descriptions,
        "images": images,
        "preview_workspace_path": preview_workspace_path,
        "package_name": package_name,
        "app_name": app_name,
        "source_context": source_context,
        "prompt": prompt,
    }
    return prompt, payload


def build_ui_editor_chat_context(
    *,
    task_id: str,
    base_revision_label: str,
    generated_revision_label: str,
    user_prompt: str,
    drafts: list[dict[str, Any]],
) -> tuple[str, dict[str, Any]]:
    draft_payloads: list[dict[str, Any]] = []
    sections: list[str] = []
    for index, draft in enumerate(drafts, start=1):
        original_xml = str(draft.get("original_xml") or "")
        edited_xml = str(draft.get("edited_xml") or "")
        descriptions = draft.get("descriptions")
        if not isinstance(descriptions, dict):
            descriptions = {}
        images = draft.get("images")
        if not isinstance(images, list):
            images = []
        diff = structural_xml_diff(original_xml, edited_xml)
        image_lines = "\n".join(
            "- element `{element}`: `{path}` (resource `{resource}`, SHA-256 `{sha}`)".format(
                element=image.get("element_stable_id") or "",
                path=image.get("workspace_path") or "",
                resource=image.get("resource_name") or "",
                sha=image.get("sha256") or "",
            )
            for image in images
            if isinstance(image, dict)
        ) or "- 없음"
        sections.append(
            f"""### 저장 UI {index}: `app/src/main/res/{draft.get('configuration') or 'layout'}/{draft.get('layout_name') or ''}.xml`

#### 사용자가 저장한 XML 전문

```xml
{edited_xml}
```

#### 기준 XML과의 구조적 차이

```json
{json.dumps(diff['operations'], ensure_ascii=False, indent=2)}
```

#### 요소별 설명

```json
{json.dumps(descriptions, ensure_ascii=False, indent=2, sort_keys=True)}
```

#### 편집 화면 미리보기

`{draft.get('preview_workspace_path') or '(없음)'}`

#### UI 편집에 첨부된 이미지

{image_lines}"""
        )
        draft_payloads.append(
            {
                "draft_id": draft.get("draft_id") or "",
                "version": int(draft.get("version") or 0),
                "layout_name": draft.get("layout_name") or "",
                "configuration": draft.get("configuration") or "layout",
                "original_xml": original_xml,
                "edited_xml": edited_xml,
                "diff": diff,
                "descriptions": descriptions,
                "images": images,
                "preview_workspace_path": draft.get("preview_workspace_path") or "",
                "confirmed_at": draft.get("confirmed_at") or "",
            }
        )

    prompt = f"""## 채팅 요청에 선택된 저장 UI

사용자가 아래 UI 편집 내용을 현재 채팅 요청의 시각적 기준으로 선택했다.

- Task ID: `{task_id}`
- 기준 Revision: `{base_revision_label}`
- 작업 Revision: `{generated_revision_label}`
- 최신 채팅 요청: {user_prompt.strip()}
- 저장된 UI 구조와 요소 설명을 실제 앱 코드에 반영한다.
- 최신 채팅 요청은 기능과 세부 동작을 설명하며, 명시적으로 충돌하는 경우 최신 채팅 요청을 우선한다.
- 기존 View ID, Kotlin 동작, package name, Task ID, 런타임 API와 오류 보고 계약을 유지한다.
- 요소 추가·삭제·이동에 맞춰 Kotlin 참조와 이벤트 처리를 함께 수정한다.
- UI 편집 이미지는 명시된 workspace 상대 경로에서 읽어 Android 리소스로 반영한다.
- 이 섹션이 없는 후속 요청에서는 저장 UI 초안을 새 입력으로 간주하지 않는다.

{chr(10).join(sections)}
""".strip() + "\n"
    payload = {
        "task_id": task_id,
        "base_revision_label": base_revision_label,
        "generated_revision_label": generated_revision_label,
        "user_prompt": user_prompt,
        "drafts": draft_payloads,
        "prompt": prompt,
    }
    return prompt, payload
