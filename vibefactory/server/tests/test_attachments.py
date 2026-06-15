import base64
import sys
import types

import pytest
from fastapi import HTTPException

from server import (
    AttachmentPayload,
    MAX_EXTRACTED_ATTACHMENT_TEXT_CHARS,
    attachment_text_context,
    normalize_attachments_payload,
)


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def test_normalizes_single_text_attachment_and_extracts_text():
    raw_text = "첫 화면은 목록입니다."
    raw_bytes = raw_text.encode()
    attachments = normalize_attachments_payload(
        [
            AttachmentPayload(
                type="text",
                mime_type="text/plain",
                name="../notes.txt",
                base64=b64(raw_bytes),
            )
        ],
        reference_image_name="",
        reference_image_base64="",
    )

    assert attachments == [
        {
            "type": "text",
            "mime_type": "text/plain",
            "name": "notes.txt",
            "base64": b64(raw_bytes),
            "size_bytes": len(raw_bytes),
            "extracted_text": raw_text,
        }
    ]
    assert "notes.txt" in attachment_text_context(attachments)
    assert "첫 화면은 목록입니다." in attachment_text_context(attachments)


def test_rejects_more_than_one_attachment():
    payload = AttachmentPayload(type="text", mime_type="text/plain", name="a.txt", base64=b64(b"a"))
    with pytest.raises(HTTPException) as exc:
        normalize_attachments_payload([payload, payload], reference_image_name="", reference_image_base64="")

    assert exc.value.status_code == 400


def test_rejects_wrong_pdf_mime_type():
    with pytest.raises(HTTPException) as exc:
        normalize_attachments_payload(
            [AttachmentPayload(type="pdf", mime_type="text/plain", name="a.pdf", base64=b64(b"%PDF"))],
            reference_image_name="",
            reference_image_base64="",
        )

    assert exc.value.status_code == 400


def test_extracts_pdf_text_with_pypdf_text_layer_only(monkeypatch):
    class FakePage:
        def extract_text(self):
            return "PDF 텍스트 레이어"

    class FakeReader:
        def __init__(self, _stream):
            self.pages = [FakePage()]

    fake_pypdf = types.SimpleNamespace(PdfReader=FakeReader)
    monkeypatch.setitem(sys.modules, "pypdf", fake_pypdf)

    attachments = normalize_attachments_payload(
        [
            AttachmentPayload(
                type="pdf",
                mime_type="application/pdf",
                name="spec.pdf",
                base64=b64(b"%PDF fake"),
            )
        ],
        reference_image_name="",
        reference_image_base64="",
    )

    assert attachments[0]["extracted_text"] == "PDF 텍스트 레이어"


def test_extracted_text_is_capped():
    text = "가" * (MAX_EXTRACTED_ATTACHMENT_TEXT_CHARS + 10)
    attachments = normalize_attachments_payload(
        [AttachmentPayload(type="text", mime_type="text/plain", name="long.txt", base64=b64(text.encode()))],
        reference_image_name="",
        reference_image_base64="",
    )

    assert len(attachments[0]["extracted_text"]) == MAX_EXTRACTED_ATTACHMENT_TEXT_CHARS
