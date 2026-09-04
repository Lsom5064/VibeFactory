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

try:
    from .ui_catalog import (
        catalog_layout_metadata,
        fallback_display_name,
        fallback_layout_kind,
    )
except ImportError:
    from ui_catalog import (  # type: ignore[no-redef]
        catalog_layout_metadata,
        fallback_display_name,
        fallback_layout_kind,
    )


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
UI_ANNOTATION_NAMESPACE = "urn:vibefactory:ui-annotations"
UI_ANNOTATION_SCHEMA_VERSION = "1"
UI_ANNOTATION_ACTIONS = {"delete", "move", "behavior"}
MAX_UI_ANNOTATIONS = 500
MAX_UI_ANNOTATION_IMAGES = 5


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

    catalog_metadata = catalog_layout_metadata(project_root)
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
            metadata = catalog_metadata.get((layout_path.stem, configuration_dir.name), {})
            layout_kind = str(metadata.get("layout_kind") or fallback_layout_kind(layout_path.stem))
            layouts.append(
                {
                    "layout_name": layout_path.stem,
                    "configuration": configuration_dir.name,
                    "display_name": str(
                        metadata.get("display_name")
                        or fallback_display_name(layout_path.stem, layout_kind)
                    ),
                    "layout_kind": layout_kind,
                    "guide_available": bool(metadata.get("guide_available")),
                    "guide_element_count": int(metadata.get("guide_element_count") or 0),
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


def infer_layout_preview_hints(
    project_root: Path,
    layout_xml: bytes,
) -> dict[str, Any]:
    root = parse_android_xml(layout_xml, source_name="preview-layout.xml")
    android_id_key = f"{{{ANDROID_NAMESPACE}}}id"
    layout_ids = {
        value.rsplit("/", 1)[-1]
        for element in root.iter()
        if (value := str(element.attrib.get(android_id_key) or ""))
    }
    preview_children: dict[tuple[str, str], dict[str, Any]] = {}
    dynamic_text_ids: set[str] = set()
    conditional_empty_ids: set[str] = set()
    source_root = (project_root / "app" / "src" / "main").resolve()
    if not source_root.is_dir() or not is_within(source_root, project_root.resolve()):
        return {
            "preview_children": [],
            "preview_dynamic_text_view_ids": [],
            "preview_hidden_view_ids": [],
        }

    inflate_pattern = re.compile(
        r"layoutInflater\s*\.\s*inflate\(\s*R\.layout\.([A-Za-z0-9_]+)\s*,\s*binding\.([A-Za-z0-9_]+)\s*,\s*false",
        re.MULTILINE,
    )
    text_pattern = re.compile(r"binding\.([A-Za-z0-9_]+)\.text\s*=", re.MULTILINE)
    empty_visibility_pattern = re.compile(
        r"binding\.([A-Za-z0-9_]+)\.visibility\s*=\s*if\s*\([^\n]*?\.isEmpty\(\)[^\n]*?\)\s*View\.VISIBLE\s*else\s*View\.GONE",
        re.MULTILINE,
    )
    for source_file in sorted(source_root.rglob("*")):
        if not source_file.is_file() or source_file.is_symlink() or source_file.suffix.lower() not in {".kt", ".java"}:
            continue
        if source_file.stat().st_size > 2 * 1024 * 1024:
            continue
        source = source_file.read_text(encoding="utf-8", errors="replace")
        for item_layout, container_id in inflate_pattern.findall(source):
            if container_id not in layout_ids:
                continue
            item_path = project_root / "app" / "src" / "main" / "res" / "layout" / f"{item_layout}.xml"
            if item_path.is_file():
                preview_children[(container_id, item_layout)] = {
                    "container_id": container_id,
                    "layout_name": item_layout,
                    "sample_count": 1,
                }
        dynamic_text_ids.update(item for item in text_pattern.findall(source) if item in layout_ids)
        conditional_empty_ids.update(item for item in empty_visibility_pattern.findall(source) if item in layout_ids)

    return {
        "preview_children": list(preview_children.values()),
        "preview_dynamic_text_view_ids": sorted(dynamic_text_ids),
        "preview_hidden_view_ids": sorted(conditional_empty_ids) if preview_children else [],
    }


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
    preview_hints = infer_layout_preview_hints(project_root, content)
    metadata = catalog_layout_metadata(project_root).get((layout_name, configuration), {})
    layout_kind = str(metadata.get("layout_kind") or fallback_layout_kind(layout_name))
    references.update(
        ("layout", str(item["layout_name"]))
        for item in preview_hints["preview_children"]
    )
    resources, unresolved = discover_resource_files(project_root, references)
    return {
        "layout_name": layout_name,
        "configuration": configuration,
        "display_name": str(
            metadata.get("display_name") or fallback_display_name(layout_name, layout_kind)
        ),
        "layout_kind": layout_kind,
        "guide_available": bool(metadata.get("guide_available")),
        "guide_element_count": int(metadata.get("guide_element_count") or 0),
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
        **preview_hints,
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


def validate_ui_annotation_xml(
    xml_text: str,
    *,
    task_id: str,
    revision_label: str,
    layout_name: str,
    configuration: str,
    base_xml_sha256: str,
) -> list[dict[str, Any]]:
    content = xml_text.encode("utf-8")
    root = parse_android_xml(content, source_name=f"{layout_name}.annotations.xml")
    expected_root = f"{{{UI_ANNOTATION_NAMESPACE}}}ui-annotations"
    if root.tag != expected_root or root.attrib.get("schemaVersion") != UI_ANNOTATION_SCHEMA_VERSION:
        raise UiEditorInputError("unsupported UI annotation document")
    expected_metadata = {
        "taskId": task_id,
        "revisionLabel": revision_label,
        "layoutName": layout_name,
        "configuration": configuration,
        "baseXmlSha256": base_xml_sha256,
    }
    for key, expected in expected_metadata.items():
        if root.attrib.get(key) != expected:
            raise UiEditorInputError(f"UI annotation {key} does not match its revision")

    annotation_tag = f"{{{UI_ANNOTATION_NAMESPACE}}}annotation"
    target_tag = f"{{{UI_ANNOTATION_NAMESPACE}}}target"
    destination_tag = f"{{{UI_ANNOTATION_NAMESPACE}}}destination"
    point_tag = f"{{{UI_ANNOTATION_NAMESPACE}}}destination-point"
    instruction_tag = f"{{{UI_ANNOTATION_NAMESPACE}}}instruction"
    image_ref_tag = f"{{{UI_ANNOTATION_NAMESPACE}}}image-ref"
    parsed: list[dict[str, Any]] = []
    annotation_ids: set[str] = set()
    children = list(root)
    if len(children) > MAX_UI_ANNOTATIONS:
        raise UiEditorFileTooLargeError(f"UI annotations exceed {MAX_UI_ANNOTATIONS} entries")
    for annotation in children:
        if annotation.tag != annotation_tag:
            raise UiEditorInputError("unexpected element in UI annotation document")
        annotation_id = str(annotation.attrib.get("id") or "")
        action = str(annotation.attrib.get("action") or "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", annotation_id) or annotation_id in annotation_ids:
            raise UiEditorInputError("invalid or duplicate UI annotation ID")
        if action not in UI_ANNOTATION_ACTIONS:
            raise UiEditorInputError("invalid UI annotation action")
        annotation_ids.add(annotation_id)
        targets = [child for child in annotation if child.tag == target_tag]
        destinations = [child for child in annotation if child.tag == destination_tag]
        points = [child for child in annotation if child.tag == point_tag]
        instructions = [child for child in annotation if child.tag == instruction_tag]
        image_refs = [child for child in annotation if child.tag == image_ref_tag]
        if len(targets) != 1 or len(destinations) > 1 or len(points) > 1 or len(instructions) != 1:
            raise UiEditorInputError("invalid UI annotation structure")
        if len(image_refs) > MAX_UI_ANNOTATION_IMAGES:
            raise UiEditorFileTooLargeError(
                f"UI annotation images exceed {MAX_UI_ANNOTATION_IMAGES} entries"
            )
        image_ids = [str(image.attrib.get("id") or "") for image in image_refs]
        if any(not re.fullmatch(r"[A-Za-z0-9_-]{1,120}", image_id) for image_id in image_ids):
            raise UiEditorInputError("invalid UI annotation image reference")
        if len(set(image_ids)) != len(image_ids):
            raise UiEditorInputError("duplicate UI annotation image reference")
        allowed_children = {target_tag, destination_tag, point_tag, instruction_tag, image_ref_tag}
        if any(child.tag not in allowed_children for child in annotation):
            raise UiEditorInputError("unexpected UI annotation child")
        if action == "move" and not destinations and not points:
            raise UiEditorInputError("move annotation requires a destination")
        if action != "move" and (destinations or points):
            raise UiEditorInputError("only move annotations may include a destination")
        instruction = instructions[0].text or ""
        if len(instruction.encode("utf-8")) > 16 * 1024:
            raise UiEditorFileTooLargeError("UI annotation instruction is too large")
        if action == "behavior" and not instruction.strip():
            raise UiEditorInputError("behavior annotation requires an instruction")

        def target_payload(element: ElementTree.Element) -> dict[str, Any]:
            fields = {
                key: str(element.attrib.get(key) or "")
                for key in (
                    "stableId", "resourceId", "hierarchyPath", "className", "text",
                    "contentDescription", "previousSibling", "nextSibling",
                )
            }
            if not fields["stableId"] or len(fields["stableId"]) > 500:
                raise UiEditorInputError("invalid UI annotation target")
            coordinates: dict[str, float] = {}
            for key in ("left", "top", "right", "bottom"):
                try:
                    value = float(element.attrib[key])
                except (KeyError, ValueError) as exc:
                    raise UiEditorInputError("invalid UI annotation bounds") from exc
                if not 0.0 <= value <= 1.0:
                    raise UiEditorInputError("UI annotation bounds are outside the screen")
                coordinates[key] = value
            if coordinates["right"] < coordinates["left"] or coordinates["bottom"] < coordinates["top"]:
                raise UiEditorInputError("invalid UI annotation bounds")
            return {**fields, "bounds": coordinates}

        destination_point: Optional[dict[str, float]] = None
        if points:
            try:
                destination_point = {key: float(points[0].attrib[key]) for key in ("x", "y")}
            except (KeyError, ValueError) as exc:
                raise UiEditorInputError("invalid UI annotation destination") from exc
            if any(not 0.0 <= value <= 1.0 for value in destination_point.values()):
                raise UiEditorInputError("UI annotation destination is outside the screen")
        parsed.append(
            {
                "annotation_id": annotation_id,
                "action": action,
                "created_at": str(annotation.attrib.get("createdAt") or ""),
                "target": target_payload(targets[0]),
                "destination": target_payload(destinations[0]) if destinations else None,
                "destination_point": destination_point,
                "instruction": instruction,
                "image_ids": image_ids,
            }
        )
    return parsed


def linked_ui_annotation_images(
    annotations: list[dict[str, Any]],
    images: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    image_by_id = {str(image.get("image_id") or ""): image for image in images}
    linked: list[dict[str, Any]] = []
    for annotation in annotations:
        annotation_id = str(annotation.get("annotation_id") or "")
        for image_id in annotation.get("image_ids") or []:
            image = image_by_id.get(str(image_id))
            if image is None:
                raise UiEditorInputError("referenced UI annotation image is missing")
            if str(image.get("element_stable_id") or "") != annotation_id:
                raise UiEditorInputError("UI annotation image belongs to a different change marker")
            linked.append({**image, "annotation_id": annotation_id})
    return linked


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
    annotation_xml: str,
    annotations: list[dict[str, Any]],
    preview_workspace_path: str,
    package_name: str,
    app_name: str,
    source_context: list[dict[str, str]],
    images: Optional[list[dict[str, Any]]] = None,
) -> tuple[str, dict[str, Any]]:
    linked_images = list(images or [])
    source_sections = "\n\n".join(
        f"### `{entry['path']}`\n\n```{Path(entry['path']).suffix.lstrip('.')}\n{entry['content']}\n```"
        for entry in source_context
    )
    prompt = f"""
## 시각적 UI 변경 표시 Revision 요청

사용자는 실행 화면을 직접 편집하지 않고 원본 화면 위에 변경 의도를 표시했다. 원본 XML, 주석 전용 XML, 주석이 보이는 스크린샷을 함께 해석해 실제 프로젝트 코드를 수정한다.

### 고정 계약

- Task ID: `{task_id}`
- 기준 Revision: `{base_revision_label}`
- 작업 Revision: `{generated_revision_label}`
- 대상 layout: `app/src/main/res/{configuration}/{layout_name}.xml`
- 앱 이름: `{app_name}`
- package name: `{package_name}`
- 원본 XML은 사용자가 수정한 결과물이 아니다. 주석 전용 XML의 `delete`, `move`, `behavior` 표시와 사용자 설명을 변경 의도의 최우선 기준으로 사용한다.
- 대상은 View ID만으로 판단하지 않는다. `stableId`, `resourceId`, `hierarchyPath`, View class, 표시 텍스트, 정규화 좌표, 앞뒤 형제 정보를 함께 사용한다.
- `delete`는 대상을 제거하고 관련 Kotlin/Java 참조와 이벤트를 안전하게 정리한다.
- `move`의 `destination_point`는 사용자가 화살표 끝을 놓은 정확한 정규화 좌표이며 이동 위치의 최우선 기준이다.
- 이동 후 대상 View의 중심이 `destination_point`와 일치하도록 배치한다. `destination` View 정보는 주변 구조와 제약을 파악하기 위한 참고일 뿐이며, 그 View의 중심으로 좌표를 바꾸지 않는다.
- 절대 좌표에 고정하지 말고 ConstraintLayout 제약, 형제 순서, margin 등을 사용해 표시된 위치와 방향을 다양한 화면 크기에서도 최대한 유지한다.
- `behavior`는 UI 모양만 바꾸지 말고 설명에 적힌 실제 동작과 상태 처리를 구현한다.
- 사용자가 요구하지 않은 재디자인을 하지 않는다.
- 기준 Revision은 수정하지 않는다. 현재 `project` 디렉터리만 수정한다.
- 기존 View ID와 Kotlin 동작, package name, Task ID, 런타임 LLM·데이터 API·오류 보고 계약을 유지한다.
- 변경 구조가 다양한 Android 화면 크기, 회전, 키보드, 긴 텍스트에서 깨지지 않도록 constraint와 scroll 구조를 보정한다.
- 주석 스크린샷은 위치 확인용 근거이며 원본 화면의 픽셀을 앱 리소스로 복사하지 않는다.
- 주석이 모호하거나 서로 충돌하면 사용자 설명을 우선하고, 임의로 기능을 제거하지 않는다.
- 변경을 마치면 XML parse/resource linking, Kotlin compile, lint와 release APK build가 통과해야 한다.
- 결과와 제한을 `.codex_result/task_result.json`에 전문 기록한다.

### 변경 전 XML 전문

```xml
{original_xml}
```

### 주석 전용 XML 전문

```xml
{annotation_xml}
```

### 검증된 주석 데이터

```json
{json.dumps(annotations, ensure_ascii=False, indent=2)}
```

### 부연 설명에 첨부된 참고 이미지

각 항목의 `annotation_id`를 주석 데이터와 연결해 해석한다. 이미지는 요구사항의 근거이며 앱 리소스로 그대로 복사하라는 의미가 아니다.

```json
{json.dumps(linked_images, ensure_ascii=False, indent=2)}
```

### 주석이 표시된 전체 화면 스크린샷

`{preview_workspace_path or '(없음)'}`

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
        "annotation_xml": annotation_xml,
        "annotations": annotations,
        "images": linked_images,
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
        annotation_xml = str(draft.get("annotation_xml") or "")
        annotations = draft.get("annotations")
        if not isinstance(annotations, list):
            annotations = []
        images = draft.get("images")
        if not isinstance(images, list):
            images = []
        sections.append(
            f"""### 저장된 UI 변경 표시 {index}: `app/src/main/res/{draft.get('configuration') or 'layout'}/{draft.get('layout_name') or ''}.xml`

#### 원본 XML 전문

```xml
{original_xml}
```

#### 주석 전용 XML 전문

```xml
{annotation_xml}
```

#### 검증된 변경 표시

```json
{json.dumps(annotations, ensure_ascii=False, indent=2)}
```

#### 부연 설명에 첨부된 참고 이미지

```json
{json.dumps(images, ensure_ascii=False, indent=2)}
```

#### 주석이 표시된 화면

`{draft.get('preview_workspace_path') or '(없음)'}`
"""
        )
        draft_payloads.append(
            {
                "draft_id": draft.get("draft_id") or "",
                "version": int(draft.get("version") or 0),
                "layout_name": draft.get("layout_name") or "",
                "configuration": draft.get("configuration") or "layout",
                "original_xml": original_xml,
                "annotation_xml": annotation_xml,
                "annotations": annotations,
                "images": images,
                "preview_workspace_path": draft.get("preview_workspace_path") or "",
                "confirmed_at": draft.get("confirmed_at") or "",
            }
        )

    prompt = f"""## 채팅 요청에 선택된 저장 UI

사용자가 아래 시각적 변경 표시를 현재 채팅 요청의 UI 기준으로 선택했다.

- Task ID: `{task_id}`
- 기준 Revision: `{base_revision_label}`
- 작업 Revision: `{generated_revision_label}`
- 최신 채팅 요청: {user_prompt.strip()}
- 원본 XML을 직접 편집한 결과로 취급하지 말고, 주석 XML과 스크린샷을 해석해 실제 앱 코드에 반영한다.
- delete는 빨강, move는 파랑, behavior는 보라 표시이며 대상 식별 정보와 사용자 설명을 모두 사용한다.
- move의 `destination_point`는 사용자가 지정한 화살표 끝의 정확한 이동 위치다. 대상 View의 중심을 이 좌표에 맞추고, 함께 기록된 `destination` View의 중심으로 대체하지 않는다.
- 이동 결과는 고정 픽셀 좌표 대신 제약, 형제 순서와 margin으로 표현해 화면 크기가 바뀌어도 사용자가 지정한 방향과 상대 위치를 유지한다.
- 최신 채팅 요청은 기능과 세부 동작을 설명하며, 명시적으로 충돌하는 경우 최신 채팅 요청을 우선한다.
- 기존 View ID, Kotlin 동작, package name, Task ID, 런타임 API와 오류 보고 계약을 유지한다.
- 삭제·이동·동작 변경에 맞춰 XML, Kotlin/Java 참조와 이벤트 처리를 함께 수정한다.
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
