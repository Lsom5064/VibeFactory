from __future__ import annotations

import base64
import binascii
import hashlib
import re
import uuid
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Any, Optional

from PIL import Image, ImageOps, UnidentifiedImageError  # type: ignore[import-untyped]
from pydantic import BaseModel


REFERENCE_IMAGE_MAX_SOURCE_BYTES = 20 * 1024 * 1024
REFERENCE_IMAGE_MAX_STORED_BYTES = 2 * 1024 * 1024
REFERENCE_IMAGE_MAX_DIMENSION = 1600
REFERENCE_IMAGE_JPEG_QUALITIES = (88, 82, 76, 68, 60, 52, 44)
REFERENCE_IMAGE_DIMENSION_STEPS = (1600, 1400, 1200, 1024, 800)
REFERENCE_PDF_MAX_BYTES = 10 * 1024 * 1024
REFERENCE_TEXT_MAX_BYTES = 2 * 1024 * 1024
REFERENCE_TEXT_SUFFIXES = {".txt", ".md", ".json", ".csv", ".xml", ".yaml", ".yml"}


def _normalize_whitespace(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def _sanitize_component(value: str) -> str:
    safe = "".join(
        ch if (ch.isascii() and (ch.isalnum() or ch in ("-", "_", "."))) else "_"
        for ch in value.strip()
    )
    return safe.strip("._") or "unknown"


def _utc_now_compact() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def ensure_within_root(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def resolve_workspace_path(workspace_root: Path, candidate: str) -> Path:
    path = Path(candidate)
    resolved = path.resolve() if path.is_absolute() else (workspace_root / path).resolve()
    if not ensure_within_root(resolved, workspace_root):
        raise ValueError("path escapes workspace")
    return resolved


def resolve_task_artifact_path(
    workspace_root: Path,
    candidate: str,
    project_root: Optional[Path] = None,
) -> Path:
    path = Path(candidate)
    if path.is_absolute():
        resolved = path.resolve()
        if not ensure_within_root(resolved, workspace_root):
            raise ValueError("path escapes workspace")
        return resolved

    candidates: list[Path] = []
    if project_root is not None:
        resolved_project_root = project_root.resolve()
        candidates.append((resolved_project_root.parent / path).resolve())
        candidates.append((resolved_project_root / path).resolve())
    candidates.append((workspace_root / path).resolve())

    seen: set[Path] = set()
    fallback: Optional[Path] = None
    for candidate_path in candidates:
        if candidate_path in seen:
            continue
        seen.add(candidate_path)
        if not ensure_within_root(candidate_path, workspace_root):
            continue
        if fallback is None:
            fallback = candidate_path
        if candidate_path.exists():
            return candidate_path

    if fallback is None:
        raise ValueError("path escapes workspace")
    return fallback


def normalize_reference_image_name(value: Optional[str]) -> str:
    normalized = _normalize_whitespace(value)
    if not normalized:
        return ""
    normalized = normalized.replace("\\", "/").split("/")[-1].strip()
    return normalized[:120] if normalized else ""


def normalize_reference_image_base64(value: Optional[str]) -> str:
    normalized = str(value or "").strip()
    if not normalized:
        return ""
    if normalized.startswith("data:"):
        _, _, normalized = normalized.partition(",")
    return "".join(normalized.split())


def infer_reference_image_suffix(reference_image_name: str) -> str:
    suffix = Path(reference_image_name).suffix.lower()
    return suffix if suffix in {".png", ".jpg", ".jpeg", ".webp", ".gif"} else ".png"


def build_reference_image_summary(reference_image_name: str) -> str:
    if not reference_image_name:
        return ""
    return (
        f"참고 이미지 `{reference_image_name}`를 함께 전달받았어요. "
        "앱 구조, UI, 스타일, 콘텐츠 맥락을 이 이미지를 참고해 해석합니다."
    )


def reference_attachment_file_metadata(
    workspace_root: Path,
    workspace_path: str,
) -> Optional[dict[str, Any]]:
    normalized_path = _normalize_whitespace(workspace_path)
    if not normalized_path:
        return None
    resolved_root = workspace_root.resolve()
    candidate = (resolved_root / normalized_path).resolve()
    if not ensure_within_root(candidate, resolved_root) or not candidate.is_file():
        return None
    try:
        data = candidate.read_bytes()
    except OSError:
        return None
    return {
        "workspace_path": str(candidate.relative_to(resolved_root)),
        "absolute_path": str(candidate),
        "size_bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def optimize_reference_image_bytes(image_bytes: bytes) -> dict[str, Any]:
    original_size_bytes = len(image_bytes)
    if original_size_bytes > REFERENCE_IMAGE_MAX_SOURCE_BYTES:
        return {
            "status": "failed",
            "error_message": (
                "image exceeds source size limit "
                f"({original_size_bytes} > {REFERENCE_IMAGE_MAX_SOURCE_BYTES} bytes)"
            ),
        }

    try:
        with Image.open(BytesIO(image_bytes)) as opened_image:
            opened_image.load()
            transposed_image = ImageOps.exif_transpose(opened_image)
            original_width, original_height = transposed_image.size
            if "A" in transposed_image.getbands():
                rgba_image = transposed_image.convert("RGBA")
                prepared_image = Image.new("RGB", rgba_image.size, "white")
                prepared_image.paste(rgba_image, mask=rgba_image.getchannel("A"))
                rgba_image.close()
            else:
                prepared_image = transposed_image.convert("RGB")
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        return {"status": "failed", "error_message": f"invalid or unsupported image: {exc}"}

    smallest_candidate: Optional[bytes] = None
    stored_width = prepared_image.width
    stored_height = prepared_image.height
    try:
        for max_dimension in REFERENCE_IMAGE_DIMENSION_STEPS:
            candidate_image = prepared_image.copy()
            candidate_image.thumbnail((max_dimension, max_dimension), Image.Resampling.LANCZOS)
            try:
                for quality in REFERENCE_IMAGE_JPEG_QUALITIES:
                    output = BytesIO()
                    candidate_image.save(output, format="JPEG", quality=quality, optimize=True)
                    candidate = output.getvalue()
                    if smallest_candidate is None or len(candidate) < len(smallest_candidate):
                        smallest_candidate = candidate
                        stored_width, stored_height = candidate_image.size
                    if len(candidate) <= REFERENCE_IMAGE_MAX_STORED_BYTES:
                        return {
                            "status": "optimized",
                            "data": candidate,
                            "mime_type": "image/jpeg",
                            "suffix": ".jpg",
                            "original_size_bytes": original_size_bytes,
                            "size_bytes": len(candidate),
                            "original_width": original_width,
                            "original_height": original_height,
                            "stored_width": candidate_image.width,
                            "stored_height": candidate_image.height,
                            "optimized": (
                                candidate != image_bytes
                                or original_width != candidate_image.width
                                or original_height != candidate_image.height
                            ),
                        }
            finally:
                candidate_image.close()
    finally:
        prepared_image.close()

    return {
        "status": "failed",
        "error_message": (
            "image could not be compressed below storage limit "
            f"({len(smallest_candidate or b'')} > {REFERENCE_IMAGE_MAX_STORED_BYTES} bytes, "
            f"last dimensions {stored_width}x{stored_height})"
        ),
    }


def save_reference_image_attachment_result(
    workspace_root: Path,
    *,
    reference_image_name: str,
    reference_image_base64: str,
) -> dict[str, Any]:
    normalized_name = normalize_reference_image_name(reference_image_name)
    normalized_base64 = normalize_reference_image_base64(reference_image_base64)
    if not normalized_name or not normalized_base64:
        return {"status": "failed", "error_message": "missing image name or base64 payload"}
    try:
        image_bytes = base64.b64decode(normalized_base64, validate=False)
    except (ValueError, binascii.Error):
        return {"status": "failed", "error_message": "invalid base64 image payload"}
    if not image_bytes:
        return {"status": "failed", "error_message": "empty decoded image payload"}

    optimized = optimize_reference_image_bytes(image_bytes)
    if optimized.get("status") != "optimized":
        return optimized
    stored_bytes = bytes(optimized.get("data") or b"")
    if not stored_bytes:
        return {"status": "failed", "error_message": "image optimization produced an empty payload"}

    image_dir = workspace_root / "reference_images"
    image_dir.mkdir(parents=True, exist_ok=True)
    safe_stem = _sanitize_component(Path(normalized_name).stem or "reference_image")
    filename = f"{_utc_now_compact()}_{safe_stem}_{uuid.uuid4().hex[:8]}.jpg"
    image_path = image_dir / filename
    image_path.write_bytes(stored_bytes)
    return {
        "status": "saved",
        "workspace_path": str(image_path.relative_to(workspace_root)),
        "absolute_path": str(image_path),
        "mime_type": "image/jpeg",
        "size_bytes": len(stored_bytes),
        "sha256": hashlib.sha256(stored_bytes).hexdigest(),
        "original_size_bytes": int(optimized.get("original_size_bytes") or len(image_bytes)),
        "original_width": int(optimized.get("original_width") or 0),
        "original_height": int(optimized.get("original_height") or 0),
        "stored_width": int(optimized.get("stored_width") or 0),
        "stored_height": int(optimized.get("stored_height") or 0),
        "optimized": bool(optimized.get("optimized")),
    }


def save_reference_file_attachment_result(
    workspace_root: Path,
    *,
    attachment_type: str,
    attachment_name: str,
    attachment_mime_type: str,
    attachment_base64: str,
) -> dict[str, Any]:
    normalized_type = _normalize_whitespace(attachment_type).lower()
    normalized_name = normalize_reference_image_name(attachment_name)
    normalized_base64 = normalize_reference_image_base64(attachment_base64)
    if normalized_type not in {"pdf", "text"}:
        return {"status": "failed", "error_message": "unsupported reference file type"}
    if not normalized_name or not normalized_base64:
        return {"status": "failed", "error_message": "missing reference file name or base64 payload"}
    try:
        file_bytes = base64.b64decode(normalized_base64, validate=False)
    except (ValueError, binascii.Error):
        return {"status": "failed", "error_message": "invalid base64 reference file payload"}
    if not file_bytes:
        return {"status": "failed", "error_message": "empty decoded reference file payload"}

    if normalized_type == "pdf":
        if len(file_bytes) > REFERENCE_PDF_MAX_BYTES:
            return {
                "status": "failed",
                "error_message": (
                    f"PDF exceeds size limit ({len(file_bytes)} > {REFERENCE_PDF_MAX_BYTES} bytes)"
                ),
            }
        if not file_bytes.startswith(b"%PDF-"):
            return {"status": "failed", "error_message": "invalid PDF payload"}
        suffix = ".pdf"
        mime_type = "application/pdf"
    else:
        if len(file_bytes) > REFERENCE_TEXT_MAX_BYTES:
            return {
                "status": "failed",
                "error_message": (
                    f"text file exceeds size limit ({len(file_bytes)} > {REFERENCE_TEXT_MAX_BYTES} bytes)"
                ),
            }
        try:
            file_bytes.decode("utf-8")
        except UnicodeDecodeError:
            return {"status": "failed", "error_message": "text attachment must be UTF-8 encoded"}
        requested_suffix = Path(normalized_name).suffix.lower()
        suffix = requested_suffix if requested_suffix in REFERENCE_TEXT_SUFFIXES else ".txt"
        mime_type = (
            attachment_mime_type
            if attachment_mime_type.lower().startswith("text/")
            else "text/plain"
        )

    attachment_dir = workspace_root / "reference_files"
    attachment_dir.mkdir(parents=True, exist_ok=True)
    safe_stem = _sanitize_component(Path(normalized_name).stem or f"reference_{normalized_type}")
    filename = f"{_utc_now_compact()}_{safe_stem}_{uuid.uuid4().hex[:8]}{suffix}"
    attachment_path = attachment_dir / filename
    attachment_path.write_bytes(file_bytes)
    return {
        "status": "saved",
        "workspace_path": str(attachment_path.relative_to(workspace_root)),
        "absolute_path": str(attachment_path),
        "mime_type": mime_type,
        "size_bytes": len(file_bytes),
        "sha256": hashlib.sha256(file_bytes).hexdigest(),
        "original_size_bytes": len(file_bytes),
        "original_width": 0,
        "original_height": 0,
        "stored_width": 0,
        "stored_height": 0,
        "optimized": False,
    }


def classify_reference_attachment_type(attachment_type: str, mime_type: str, name: str) -> str:
    normalized_type = _normalize_whitespace(attachment_type).lower()
    normalized_mime = _normalize_whitespace(mime_type).lower()
    suffix = Path(name).suffix.lower()
    if normalized_type == "image" or normalized_mime.startswith("image/"):
        return "image"
    if normalized_type == "pdf" or normalized_mime == "application/pdf" or suffix == ".pdf":
        return "pdf"
    if normalized_type == "text" or normalized_mime.startswith("text/") or suffix in REFERENCE_TEXT_SUFFIXES:
        return "text"
    return ""


def pydantic_model_to_dict(model: BaseModel) -> dict[str, Any]:
    model_dump = getattr(model, "model_dump", None)
    payload = model_dump() if callable(model_dump) else model.dict()
    return dict(payload) if isinstance(payload, dict) else {}


def normalize_reference_attachments(value: Any) -> list[dict[str, str]]:
    if not isinstance(value, list):
        return []
    normalized: list[dict[str, str]] = []
    for item in value[:8]:
        if isinstance(item, BaseModel):
            raw = pydantic_model_to_dict(item)
        elif isinstance(item, dict):
            raw = item
        else:
            continue
        attachment_type = _normalize_whitespace(raw.get("type") or raw.get("payload_type")).lower()
        mime_type = _normalize_whitespace(raw.get("mime_type") or raw.get("mimeType"))
        name = normalize_reference_image_name(
            raw.get("name") or raw.get("displayName") or raw.get("reference_image_name")
        )
        base64_value = normalize_reference_image_base64(
            raw.get("base64") or raw.get("reference_image_base64")
        )
        workspace_path = _normalize_whitespace(
            raw.get("workspace_path") or raw.get("reference_image_workspace_path")
        )
        normalized_type = classify_reference_attachment_type(attachment_type, mime_type, name)
        if not normalized_type:
            continue
        if not name:
            name = f"reference_{normalized_type}"
        if not base64_value and not workspace_path:
            continue
        if normalized_type == "image":
            normalized_mime_type = mime_type or f"image/{infer_reference_image_suffix(name).lstrip('.')}"
        elif normalized_type == "pdf":
            normalized_mime_type = "application/pdf"
        else:
            normalized_mime_type = mime_type if mime_type.lower().startswith("text/") else "text/plain"
        normalized.append(
            {
                "type": normalized_type,
                "mime_type": normalized_mime_type,
                "name": name,
                "base64": base64_value,
                "workspace_path": workspace_path,
            }
        )
    return normalized


def request_reference_attachments(request: Any) -> list[dict[str, str]]:
    attachments = normalize_reference_attachments(getattr(request, "attachments", None))
    legacy_name = normalize_reference_image_name(getattr(request, "reference_image_name", None))
    legacy_base64 = normalize_reference_image_base64(getattr(request, "reference_image_base64", None))
    if legacy_name and legacy_base64:
        legacy = {
            "type": "image",
            "mime_type": f"image/{infer_reference_image_suffix(legacy_name).lstrip('.')}",
            "name": legacy_name,
            "base64": legacy_base64,
            "workspace_path": "",
        }
        if not any(item.get("base64") == legacy_base64 for item in attachments):
            attachments.insert(0, legacy)
    return attachments[:8]


def first_reference_image_attachment(attachments: list[dict[str, str]]) -> dict[str, str]:
    return next(
        (
            item
            for item in attachments
            if item.get("type") == "image" and (item.get("base64") or item.get("workspace_path"))
        ),
        {},
    )


def save_reference_attachments(
    workspace_root: Path,
    attachments: list[dict[str, str]],
) -> list[dict[str, Any]]:
    saved: list[dict[str, Any]] = []
    for attachment in normalize_reference_attachments(attachments):
        workspace_path = attachment.get("workspace_path") or ""
        save_result: dict[str, Any] = {}
        existing_metadata = reference_attachment_file_metadata(workspace_root, workspace_path)
        if existing_metadata:
            save_result = {"status": "existing", **existing_metadata}
        if attachment.get("base64"):
            if not existing_metadata:
                if attachment.get("type") == "image":
                    save_result = save_reference_image_attachment_result(
                        workspace_root,
                        reference_image_name=attachment.get("name") or "reference_image",
                        reference_image_base64=attachment.get("base64") or "",
                    )
                else:
                    save_result = save_reference_file_attachment_result(
                        workspace_root,
                        attachment_type=attachment.get("type") or "",
                        attachment_name=attachment.get("name") or "reference_file",
                        attachment_mime_type=attachment.get("mime_type") or "",
                        attachment_base64=attachment.get("base64") or "",
                    )
            workspace_path = str(save_result.get("workspace_path") or workspace_path)
        elif not save_result:
            save_result = {
                "status": "pending",
                "error_message": (
                    "attachment payload is referenced by path only or has not been saved yet"
                ),
            }
        saved.append(
            {
                **attachment,
                "mime_type": str(save_result.get("mime_type") or attachment.get("mime_type") or ""),
                "workspace_path": workspace_path,
                "base64": "" if workspace_path else attachment.get("base64", ""),
                "save_status": str(save_result.get("status") or ""),
                "absolute_path": str(save_result.get("absolute_path") or ""),
                "size_bytes": str(save_result.get("size_bytes") or ""),
                "sha256": str(save_result.get("sha256") or ""),
                "original_size_bytes": str(save_result.get("original_size_bytes") or ""),
                "original_width": str(save_result.get("original_width") or ""),
                "original_height": str(save_result.get("original_height") or ""),
                "stored_width": str(save_result.get("stored_width") or ""),
                "stored_height": str(save_result.get("stored_height") or ""),
                "optimized": bool(save_result.get("optimized")),
                "error_message": str(save_result.get("error_message") or ""),
            }
        )
    return saved


def reference_attachment_event_payload(attachment: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": attachment.get("type") or "",
        "mime_type": attachment.get("mime_type") or "",
        "name": attachment.get("name") or "reference_attachment",
        "workspace_path": attachment.get("workspace_path") or "",
        "absolute_path": attachment.get("absolute_path") or "",
        "size_bytes": int(attachment.get("size_bytes") or 0),
        "sha256": attachment.get("sha256") or "",
        "original_size_bytes": int(attachment.get("original_size_bytes") or 0),
        "original_width": int(attachment.get("original_width") or 0),
        "original_height": int(attachment.get("original_height") or 0),
        "stored_width": int(attachment.get("stored_width") or 0),
        "stored_height": int(attachment.get("stored_height") or 0),
        "optimized": bool(attachment.get("optimized")),
        "status": attachment.get("save_status") or "",
        "error_message": attachment.get("error_message") or "",
    }


def reference_attachments_summary(attachments: list[dict[str, str]]) -> str:
    normalized = normalize_reference_attachments(attachments)
    if not normalized:
        return ""
    names = [item.get("name") or "reference_attachment" for item in normalized]
    image_count = sum(1 for item in normalized if item.get("type") == "image")
    file_count = len(normalized) - image_count
    kind_parts = []
    if image_count:
        kind_parts.append(f"이미지 {image_count}개")
    if file_count:
        kind_parts.append(f"파일 {file_count}개")
    kind_summary = ", ".join(kind_parts)
    if len(names) == 1:
        item = normalized[0]
        if item.get("type") == "image":
            return build_reference_image_summary(names[0])
        return f"참고 파일 `{names[0]}`을 함께 전달받았어요. 파일 내용을 사용자 요청과 함께 확인합니다."
    preview = ", ".join(names[:3])
    suffix = f" 외 {len(names) - 3}개" if len(names) > 3 else ""
    return (
        f"참고 첨부 {kind_summary}({preview}{suffix})를 함께 전달받았어요. "
        "이미지와 파일 내용을 사용자 요청과 함께 확인합니다."
    )
