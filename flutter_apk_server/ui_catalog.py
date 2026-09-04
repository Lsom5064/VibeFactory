from __future__ import annotations

import re
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any, Optional


ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
UI_CATALOG_RELATIVE_PATH = Path("app/src/main/res/xml/vf_ui_catalog.xml")
UI_CATALOG_SCHEMA_VERSION = "1"
MAX_UI_CATALOG_BYTES = 512 * 1024
ALLOWED_LAYOUT_KINDS = {"screen", "component", "dialog", "item"}
LAYOUT_CONFIGURATION_PATTERN = re.compile(r"^layout(?:-[A-Za-z0-9]+)*$")
LAYOUT_NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")
VIEW_ID_PATTERN = re.compile(r"^[a-z][A-Za-z0-9_]*$")
ACTIVITY_CLASS_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_.]*Activity$")
UNSAFE_XML_PATTERN = re.compile(br"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)
UNSAFE_GUIDE_TEXT_PATTERN = re.compile(
    r"(?:/Users/|/home/|app/src/|R\.id\.|\.xml\b|api[_ -]?key|password|token)",
    re.IGNORECASE,
)
ANDROID_ID_ATTRIBUTE = f"{{{ANDROID_NAMESPACE}}}id"
ANDROID_TEXT_ATTRIBUTE = f"{{{ANDROID_NAMESPACE}}}text"
ANDROID_HINT_ATTRIBUTE = f"{{{ANDROID_NAMESPACE}}}hint"
ANDROID_CONTENT_DESCRIPTION_ATTRIBUTE = f"{{{ANDROID_NAMESPACE}}}contentDescription"
ANDROID_CLICKABLE_ATTRIBUTE = f"{{{ANDROID_NAMESPACE}}}clickable"


class UiCatalogError(ValueError):
    pass


def _normalized_text(value: Any) -> str:
    return " ".join(str(value or "").split())


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _parse_xml(content: bytes, source_name: str) -> ElementTree.Element:
    if len(content) > MAX_UI_CATALOG_BYTES:
        raise UiCatalogError(f"{source_name} exceeds {MAX_UI_CATALOG_BYTES} bytes")
    if UNSAFE_XML_PATTERN.search(content):
        raise UiCatalogError(f"unsafe XML declaration in {source_name}")
    try:
        return ElementTree.fromstring(content)
    except ElementTree.ParseError as exc:
        raise UiCatalogError(f"invalid XML in {source_name}: {exc}") from exc


def _layout_documents(project_root: Path) -> list[tuple[str, str, Path]]:
    resource_root = project_root / "app" / "src" / "main" / "res"
    if not resource_root.is_dir():
        return []
    documents: list[tuple[str, str, Path]] = []
    for configuration_dir in sorted(resource_root.glob("layout*"), key=lambda item: item.name):
        if not configuration_dir.is_dir() or not LAYOUT_CONFIGURATION_PATTERN.fullmatch(configuration_dir.name):
            continue
        for layout_path in sorted(configuration_dir.glob("*.xml"), key=lambda item: item.name):
            if layout_path.is_file() and LAYOUT_NAME_PATTERN.fullmatch(layout_path.stem):
                documents.append((layout_path.stem, configuration_dir.name, layout_path))
    return documents


def fallback_layout_kind(layout_name: str) -> str:
    if (
        layout_name.startswith(("activity_", "fragment_", "screen_"))
        or layout_name.endswith(("_activity", "_fragment", "_screen"))
        or layout_name == "activity_main"
    ):
        return "screen"
    if layout_name.startswith(("dialog_", "sheet_", "bottom_sheet_")) or layout_name.endswith(
        ("_dialog", "_sheet")
    ):
        return "dialog"
    if layout_name.startswith(("item_", "row_", "cell_")) or layout_name.endswith(
        ("_item", "_row", "_cell")
    ):
        return "item"
    return "component"


def conventional_layout_kind(layout_name: str) -> Optional[str]:
    if layout_name.startswith("activity_") or layout_name.endswith("_activity"):
        return "screen"
    if layout_name.startswith(("dialog_", "sheet_", "bottom_sheet_")) or layout_name.endswith(
        ("_dialog", "_sheet")
    ):
        return "dialog"
    if layout_name.startswith(("item_", "row_", "cell_")) or layout_name.endswith(
        ("_item", "_row", "_cell")
    ):
        return "item"
    return None


def _humanized_stem(layout_name: str) -> str:
    type_tokens = {"activity", "fragment", "screen", "dialog", "sheet", "bottom", "item", "row", "cell"}
    parts = [part for part in layout_name.split("_") if part]
    while parts and parts[0] in type_tokens:
        parts.pop(0)
    while parts and parts[-1] in type_tokens:
        parts.pop()
    known_tokens = {
        "main": "메인",
        "home": "홈",
        "settings": "설정",
        "setting": "설정",
        "history": "기록",
        "detail": "상세",
        "list": "목록",
        "todo": "할 일",
        "task": "작업",
        "profile": "프로필",
        "calendar": "캘린더",
        "login": "로그인",
        "search": "검색",
        "result": "결과",
        "edit": "편집",
        "editor": "편집",
        "create": "등록",
    }
    words = [known_tokens.get(token, token.replace("-", " ").title()) for token in parts]
    return " ".join(words).strip() or "기본"


def fallback_display_name(layout_name: str, kind: Optional[str] = None) -> str:
    resolved_kind = kind or fallback_layout_kind(layout_name)
    suffix = {
        "screen": "화면",
        "dialog": "팝업",
        "item": "항목",
        "component": "구성요소",
    }[resolved_kind]
    return f"{_humanized_stem(layout_name)} {suffix}"


def _parse_catalog_entries(root: ElementTree.Element) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for layout in list(root):
        if _local_name(layout.tag) != "layout":
            continue
        elements: list[dict[str, Any]] = []
        for element in list(layout):
            if _local_name(element.tag) != "element":
                continue
            raw_order = _normalized_text(element.attrib.get("order"))
            try:
                order = int(raw_order)
            except ValueError:
                order = 0
            elements.append(
                {
                    "view_id": _normalized_text(element.attrib.get("viewId")),
                    "title": _normalized_text(element.attrib.get("title")),
                    "description": _normalized_text(element.attrib.get("description")),
                    "order": order,
                }
            )
        entries.append(
            {
                "layout_name": _normalized_text(layout.attrib.get("layoutName")),
                "configuration": _normalized_text(layout.attrib.get("configuration")) or "layout",
                "display_name": _normalized_text(layout.attrib.get("displayName")),
                "layout_kind": _normalized_text(layout.attrib.get("kind")),
                "activity_class": _normalized_text(layout.attrib.get("activityClass")),
                "elements": elements,
            }
        )
    return entries


def load_ui_catalog(project_root: Path) -> dict[str, Any]:
    path = project_root / UI_CATALOG_RELATIVE_PATH
    if not path.is_file():
        raise UiCatalogError("UI catalog is missing")
    root = _parse_xml(path.read_bytes(), path.name)
    if _local_name(root.tag) != "ui-catalog":
        raise UiCatalogError("UI catalog root must be ui-catalog")
    return {
        "schema_version": _normalized_text(root.attrib.get("schemaVersion")),
        "guide_version": _normalized_text(root.attrib.get("guideVersion")),
        "layouts": _parse_catalog_entries(root),
    }


def catalog_layout_metadata(project_root: Path) -> dict[tuple[str, str], dict[str, Any]]:
    try:
        catalog = load_ui_catalog(project_root)
    except (OSError, UiCatalogError):
        return {}
    metadata: dict[tuple[str, str], dict[str, Any]] = {}
    for entry in catalog["layouts"]:
        key = (str(entry["layout_name"]), str(entry["configuration"]))
        if (
            key in metadata
            or not LAYOUT_NAME_PATTERN.fullmatch(key[0])
            or not LAYOUT_CONFIGURATION_PATTERN.fullmatch(key[1])
        ):
            continue
        kind = str(entry["layout_kind"])
        expected_kind = conventional_layout_kind(key[0])
        if kind not in ALLOWED_LAYOUT_KINDS or (expected_kind and kind != expected_kind):
            kind = expected_kind or fallback_layout_kind(key[0])
        display_name = str(entry["display_name"])
        if (
            not display_name
            or len(display_name) > 60
            or UNSAFE_GUIDE_TEXT_PATTERN.search(display_name)
        ):
            display_name = fallback_display_name(key[0], kind)
        metadata[key] = {
            "display_name": display_name,
            "layout_kind": kind,
            "guide_available": bool(entry["elements"]),
            "guide_element_count": len(entry["elements"]),
        }
    return metadata


def _layout_ids(layout_path: Path) -> set[str]:
    root = _parse_xml(layout_path.read_bytes(), layout_path.name)
    identifiers: set[str] = set()
    for element in root.iter():
        value = _normalized_text(element.attrib.get(ANDROID_ID_ATTRIBUTE))
        if value.startswith(("@+id/", "@id/")):
            identifiers.add(value.rsplit("/", 1)[-1])
    return identifiers


def validate_ui_catalog(project_root: Path) -> dict[str, Any]:
    issues: list[str] = []
    documents = _layout_documents(project_root)
    document_paths = {(name, configuration): path for name, configuration, path in documents}
    try:
        catalog = load_ui_catalog(project_root)
    except (OSError, UiCatalogError) as exc:
        return {"valid": False, "issues": [str(exc)], "catalog": None}

    if catalog["schema_version"] != UI_CATALOG_SCHEMA_VERSION:
        issues.append(f"schemaVersion must be {UI_CATALOG_SCHEMA_VERSION}")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", str(catalog["guide_version"])):
        issues.append("guideVersion is missing or invalid")

    entries: list[dict[str, Any]] = catalog["layouts"]
    entry_keys: list[tuple[str, str]] = [
        (str(entry["layout_name"]), str(entry["configuration"])) for entry in entries
    ]
    duplicate_keys = sorted({key for key in entry_keys if entry_keys.count(key) > 1})
    for layout_name, configuration in duplicate_keys:
        issues.append(f"duplicate layout entry: {configuration}/{layout_name}")

    document_keys = set(document_paths)
    catalog_keys = set(entry_keys)
    for layout_name, configuration in sorted(document_keys - catalog_keys):
        issues.append(f"layout is not registered: {configuration}/{layout_name}")
    for layout_name, configuration in sorted(catalog_keys - document_keys):
        issues.append(f"catalog references missing layout: {configuration}/{layout_name}")

    ids_by_layout: dict[tuple[str, str], set[str]] = {}
    for key, path in document_paths.items():
        try:
            ids_by_layout[key] = _layout_ids(path)
        except (OSError, UiCatalogError) as exc:
            issues.append(str(exc))
            ids_by_layout[key] = set()

    for entry in entries:
        layout_name = str(entry["layout_name"])
        configuration = str(entry["configuration"])
        key = (layout_name, configuration)
        display_name = str(entry["display_name"])
        kind = str(entry["layout_kind"])
        activity_class = str(entry["activity_class"])
        if not LAYOUT_NAME_PATTERN.fullmatch(layout_name):
            issues.append(f"invalid layout name: {layout_name or '(empty)'}")
        if not LAYOUT_CONFIGURATION_PATTERN.fullmatch(configuration):
            issues.append(f"invalid layout configuration: {configuration or '(empty)'}")
        if not display_name or len(display_name) > 60 or UNSAFE_GUIDE_TEXT_PATTERN.search(display_name):
            issues.append(f"invalid display name: {configuration}/{layout_name}")
        if kind not in ALLOWED_LAYOUT_KINDS:
            issues.append(f"invalid layout kind: {configuration}/{layout_name}")
        expected_kind = conventional_layout_kind(layout_name)
        if expected_kind and kind in ALLOWED_LAYOUT_KINDS and kind != expected_kind:
            issues.append(
                f"layout kind does not match its role: {configuration}/{layout_name} "
                f"must be {expected_kind}"
            )
        if kind == "screen" and not ACTIVITY_CLASS_PATTERN.fullmatch(activity_class):
            issues.append(f"screen activityClass is missing or invalid: {configuration}/{layout_name}")

        elements: list[dict[str, Any]] = entry["elements"]
        if kind == "screen" and not elements:
            issues.append(f"screen has no guide elements: {configuration}/{layout_name}")
        if len(elements) > 50:
            issues.append(f"too many guide elements: {configuration}/{layout_name}")
        seen_ids: set[str] = set()
        seen_orders: set[int] = set()
        for element in elements:
            view_id = str(element["view_id"])
            title = str(element["title"])
            description = str(element["description"])
            order = int(element["order"])
            if not VIEW_ID_PATTERN.fullmatch(view_id):
                issues.append(f"invalid guide viewId: {configuration}/{layout_name}/{view_id or '(empty)'}")
            elif view_id not in ids_by_layout.get(key, set()):
                issues.append(f"guide viewId does not exist: {configuration}/{layout_name}/{view_id}")
            if view_id in seen_ids:
                issues.append(f"duplicate guide viewId: {configuration}/{layout_name}/{view_id}")
            seen_ids.add(view_id)
            if order <= 0 or order in seen_orders:
                issues.append(f"invalid guide order: {configuration}/{layout_name}/{view_id}")
            seen_orders.add(order)
            if not title or len(title) > 40 or UNSAFE_GUIDE_TEXT_PATTERN.search(title):
                issues.append(f"invalid guide title: {configuration}/{layout_name}/{view_id}")
            if not description or len(description) > 180 or UNSAFE_GUIDE_TEXT_PATTERN.search(description):
                issues.append(f"invalid guide description: {configuration}/{layout_name}/{view_id}")

    return {"valid": not issues, "issues": issues, "catalog": catalog}


def _string_resources(project_root: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    values_root = project_root / "app" / "src" / "main" / "res" / "values"
    if not values_root.is_dir():
        return values
    for path in sorted(values_root.glob("*.xml")):
        try:
            root = _parse_xml(path.read_bytes(), path.name)
        except (OSError, UiCatalogError):
            continue
        for child in list(root):
            if _local_name(child.tag) != "string":
                continue
            name = _normalized_text(child.attrib.get("name"))
            text = _normalized_text("".join(child.itertext()))
            if name and text:
                values[name] = text
    return values


def _resolve_text(value: str, strings: dict[str, str]) -> str:
    normalized = _normalized_text(value)
    if normalized.startswith("@string/"):
        return _normalized_text(strings.get(normalized.rsplit("/", 1)[-1], ""))
    return normalized if not normalized.startswith("@") else ""


def _view_title(element: ElementTree.Element, strings: dict[str, str]) -> str:
    candidates = (
        element.attrib.get(ANDROID_CONTENT_DESCRIPTION_ATTRIBUTE),
        element.attrib.get(ANDROID_HINT_ATTRIBUTE),
        element.attrib.get(ANDROID_TEXT_ATTRIBUTE),
    )
    for candidate in candidates:
        resolved = _resolve_text(str(candidate or ""), strings)
        if resolved and len(resolved) <= 40 and not UNSAFE_GUIDE_TEXT_PATTERN.search(resolved):
            return resolved
    class_name = _local_name(element.tag).lower()
    if any(token in class_name for token in ("edittext", "textfield", "autocomplete")):
        return "내용 입력"
    if any(token in class_name for token in ("switch", "checkbox", "radiobutton")):
        return "설정 선택"
    if any(token in class_name for token in ("spinner", "dropdown")):
        return "항목 선택"
    if any(token in class_name for token in ("button", "fab")):
        return "기능 버튼"
    return "정보 확인"


def _view_description(title: str, class_name: str) -> str:
    lowered = class_name.lower()
    if title in {"닫기", "취소"}:
        return "현재 화면을 닫는 버튼입니다."
    if any(token in lowered for token in ("edittext", "textfield", "autocomplete")):
        return f"{title} 내용을 입력하는 입력창입니다."
    if any(token in lowered for token in ("switch", "checkbox", "radiobutton")):
        return f"{title} 설정을 선택하거나 해제하는 항목입니다."
    if any(token in lowered for token in ("button", "imagebutton", "fab")):
        return f"{title} 기능을 실행하는 버튼입니다."
    if any(token in lowered for token in ("spinner", "dropdown")):
        return f"{title} 항목을 선택하는 메뉴입니다."
    return f"{title} 정보를 확인하는 영역입니다."


def _inferred_elements(layout_path: Path, project_root: Path) -> list[dict[str, Any]]:
    try:
        root = _parse_xml(layout_path.read_bytes(), layout_path.name)
    except (OSError, UiCatalogError):
        return []
    strings = _string_resources(project_root)
    interactive_tokens = (
        "button",
        "edittext",
        "textfield",
        "switch",
        "checkbox",
        "radiobutton",
        "spinner",
        "searchview",
        "fab",
    )
    selected: list[tuple[str, ElementTree.Element]] = []
    informative: list[tuple[str, ElementTree.Element]] = []
    for element in root.iter():
        raw_id = _normalized_text(element.attrib.get(ANDROID_ID_ATTRIBUTE))
        if not raw_id.startswith(("@+id/", "@id/")):
            continue
        view_id = raw_id.rsplit("/", 1)[-1]
        class_name = _local_name(element.tag)
        clickable = _normalized_text(element.attrib.get(ANDROID_CLICKABLE_ATTRIBUTE)).lower() == "true"
        if clickable or any(token in class_name.lower() for token in interactive_tokens):
            selected.append((view_id, element))
        elif element.attrib.get(ANDROID_TEXT_ATTRIBUTE) is not None:
            informative.append((view_id, element))
    if not selected and informative:
        selected.append(informative[0])
    elements: list[dict[str, Any]] = []
    for order, (view_id, element) in enumerate(selected[:20], start=1):
        title = _view_title(element, strings)
        elements.append(
            {
                "view_id": view_id,
                "title": title,
                "description": _view_description(title, _local_name(element.tag)),
                "order": order,
            }
        )
    return elements


def _infer_activity_class(project_root: Path, layout_name: str) -> str:
    binding_name = "".join(part[:1].upper() + part[1:] for part in layout_name.split("_")) + "Binding"
    source_root = project_root / "app" / "src" / "main"
    class_pattern = re.compile(r"\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^\n{]*)?")
    package_pattern = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)", re.MULTILINE)
    for path in sorted((*source_root.rglob("*.kt"), *source_root.rglob("*.java"))):
        try:
            source = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if binding_name not in source and f"R.layout.{layout_name}" not in source:
            continue
        class_match = next(
            (match for match in class_pattern.finditer(source) if match.group(1).endswith("Activity")),
            None,
        )
        if class_match:
            package_match = package_pattern.search(source)
            package_name = package_match.group(1) if package_match else ""
            return f"{package_name}.{class_match.group(1)}".strip(".")
    if layout_name == "activity_main":
        return "kr.ac.kangwon.hai.generated.MainActivity"
    return ""


def _safe_existing_entries(project_root: Path) -> dict[tuple[str, str], dict[str, Any]]:
    try:
        entries = load_ui_catalog(project_root)["layouts"]
    except (OSError, UiCatalogError):
        return {}
    result: dict[tuple[str, str], dict[str, Any]] = {}
    for entry in entries:
        key = (str(entry["layout_name"]), str(entry["configuration"]))
        if key not in result:
            result[key] = entry
    return result


def repair_ui_catalog(project_root: Path, *, guide_version: str) -> dict[str, Any]:
    documents = _layout_documents(project_root)
    existing = _safe_existing_entries(project_root)
    root = ElementTree.Element(
        "ui-catalog",
        {"schemaVersion": UI_CATALOG_SCHEMA_VERSION, "guideVersion": guide_version},
    )
    repaired_layouts: list[str] = []
    for layout_name, configuration, layout_path in documents:
        key = (layout_name, configuration)
        previous = existing.get(key, {})
        inferred_kind = fallback_layout_kind(layout_name)
        inferred_activity_class = _infer_activity_class(project_root, layout_name)
        kind = str(previous.get("layout_kind") or "")
        conventional_kind = conventional_layout_kind(layout_name)
        if kind not in ALLOWED_LAYOUT_KINDS or (conventional_kind and kind != conventional_kind):
            kind = conventional_kind or inferred_kind
            if kind == "component" and inferred_activity_class:
                kind = "screen"
        display_name = _normalized_text(previous.get("display_name"))
        if not display_name or len(display_name) > 60 or UNSAFE_GUIDE_TEXT_PATTERN.search(display_name):
            display_name = fallback_display_name(layout_name, kind)
        activity_class = _normalized_text(previous.get("activity_class"))
        if kind == "screen" and not ACTIVITY_CLASS_PATTERN.fullmatch(activity_class):
            activity_class = inferred_activity_class
        attributes = {
            "layoutName": layout_name,
            "configuration": configuration,
            "displayName": display_name,
            "kind": kind,
        }
        if activity_class:
            attributes["activityClass"] = activity_class
        layout_element = ElementTree.SubElement(root, "layout", attributes)
        valid_ids = _layout_ids(layout_path)
        elements: list[dict[str, Any]] = []
        used_ids: set[str] = set()
        for item in sorted(previous.get("elements") or [], key=lambda value: int(value.get("order") or 0)):
            view_id = _normalized_text(item.get("view_id"))
            title = _normalized_text(item.get("title"))
            description = _normalized_text(item.get("description"))
            if (
                view_id not in valid_ids
                or view_id in used_ids
                or not title
                or len(title) > 40
                or not description
                or len(description) > 180
                or UNSAFE_GUIDE_TEXT_PATTERN.search(title)
                or UNSAFE_GUIDE_TEXT_PATTERN.search(description)
            ):
                continue
            used_ids.add(view_id)
            elements.append({"view_id": view_id, "title": title, "description": description})
        if not elements:
            elements = _inferred_elements(layout_path, project_root)
        for order, item in enumerate(elements, start=1):
            ElementTree.SubElement(
                layout_element,
                "element",
                {
                    "viewId": str(item["view_id"]),
                    "title": str(item["title"]),
                    "description": str(item["description"]),
                    "order": str(order),
                },
            )
        if previous != {
            "layout_name": layout_name,
            "configuration": configuration,
            "display_name": display_name,
            "layout_kind": kind,
            "activity_class": activity_class,
            "elements": [
                {**item, "order": index} for index, item in enumerate(elements, start=1)
            ],
        }:
            repaired_layouts.append(f"{configuration}/{layout_name}")

    catalog_path = project_root / UI_CATALOG_RELATIVE_PATH
    catalog_path.parent.mkdir(parents=True, exist_ok=True)
    tree = ElementTree.ElementTree(root)
    ElementTree.indent(tree, space="    ")
    tree.write(catalog_path, encoding="utf-8", xml_declaration=True)
    validation = validate_ui_catalog(project_root)
    return {
        **validation,
        "repaired_layouts": repaired_layouts,
        "catalog_path": UI_CATALOG_RELATIVE_PATH.as_posix(),
    }


def ensure_valid_ui_catalog(project_root: Path, *, guide_version: str) -> dict[str, Any]:
    initial = validate_ui_catalog(project_root)
    if initial["valid"]:
        catalog = initial.get("catalog") or {}
        if str(catalog.get("guide_version") or "") == guide_version:
            return {**initial, "repaired": False, "repaired_layouts": []}
    repaired = repair_ui_catalog(project_root, guide_version=guide_version)
    return {**repaired, "repaired": True}


def catalog_prompt_contract() -> str:
    return """- 모든 `app/src/main/res/layout*/*.xml`을 `app/src/main/res/xml/vf_ui_catalog.xml`에 등록한다.
- catalog root는 `<ui-catalog schemaVersion=\"1\" guideVersion=\"...\">` 형식을 사용한다.
- 각 `<layout>`에는 `layoutName`, `configuration`, 자연스러운 한국어 `displayName`, `kind`를 기록한다.
- 화면 layout의 kind는 `screen`이고 이를 표시하는 Activity 전체 클래스명을 `activityClass`에 기록한다.
- 주요 버튼·입력창·선택 도구·탐색 요소에는 실제 XML에 존재하는 안정적인 ID를 부여하고 `<element viewId=\"...\" title=\"...\" description=\"...\" order=\"...\" />`로 설명한다.
- 장식용 View와 의미 없는 반복 View는 설명하지 않는다. 파일 경로, 변수명, View ID, API 키 같은 내부 정보는 사용자 설명에 쓰지 않는다.
- layout이나 View ID를 변경하면 catalog도 같은 작업에서 반드시 갱신한다.
- Activity 전체 화면은 런타임이 자동 감지한다. Dialog는 표시된 뒤 `UiGuideController.show(dialog, "layout_name")`, RecyclerView 항목이나 동적으로 삽입한 구성요소는 `UiGuideController.show(activity, "layout_name", rootView)`를 호출한다.
- 앱에 메뉴나 설정 화면이 있으면 `사용법 다시 보기` 항목을 제공하고 `UiGuideController.replay(activity)`를 호출한다.
- 안내 문구를 실제 화면 TextView로 추가하지 않는다. 안내는 `UiGuideController` 오버레이로만 표시한다.
- `UiGuideController.kt`와 `GeneratedApplication.kt`의 사용 설명 런타임 계약을 제거하거나 우회하지 않는다."""
