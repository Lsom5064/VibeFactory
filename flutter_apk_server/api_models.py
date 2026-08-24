from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field


class DeviceInfoPayload(BaseModel):
    model: str = Field(..., min_length=1)
    sdk: int = Field(..., ge=1)
    width: int = Field(..., ge=1)
    height: int = Field(..., ge=1)
    sensors: list[str] = Field(default_factory=list)


class GenerateAttachmentPayload(BaseModel):
    type: str = ""
    mime_type: str = ""
    name: str = ""
    base64: str = ""


class GenerateRequest(BaseModel):
    task_id: Optional[str] = None
    device_id: str = Field(..., min_length=1)
    phone_number: Optional[str] = None
    prompt: str = ""
    display_prompt: Optional[str] = None
    request_action: Optional[str] = None
    device_info: Optional[DeviceInfoPayload] = None
    reference_image_path: Optional[str] = None
    reference_image_name: Optional[str] = None
    reference_image_base64: Optional[str] = None
    attachments: list[GenerateAttachmentPayload] = Field(default_factory=list)
    use_ui_editor_draft: bool = False


class UiEditorDraftRequest(BaseModel):
    draft_id: Optional[str] = None
    configuration: str = "layout"
    base_xml_sha256: str = Field(..., min_length=64, max_length=64)
    original_xml: str = Field(..., min_length=1)
    edited_xml: str = Field(..., min_length=1)
    descriptions: dict[str, str] = Field(default_factory=dict)
    expected_version: Optional[int] = Field(default=None, ge=1)
    is_new_layout: bool = False


class UiEditorImageRequest(BaseModel):
    image_id: str = Field(..., min_length=1, max_length=120)
    element_stable_id: str = Field(..., min_length=1, max_length=500)
    original_name: str = Field(default="ui_editor_image.jpg", max_length=255)
    mime_type: str = Field(default="image/jpeg", max_length=100)
    resource_name: str = Field(..., min_length=1, max_length=120)
    base64: str = Field(..., min_length=1)


class UiEditorSubmitRequest(BaseModel):
    expected_version: int = Field(..., ge=1)
    preview_image_base64: Optional[str] = None
    preview_mime_type: str = Field(default="image/jpeg", max_length=100)


class TaskUpdateRequest(BaseModel):
    app_name: str = Field(..., min_length=1, max_length=80)


class AppLlmConfigRequest(BaseModel):
    enabled: bool = True
    provider: str = Field(default="openai", min_length=1)
    model: str = Field(default="gpt-5.4-mini", min_length=1)
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    system_prompt: Optional[str] = None
    daily_request_limit: int = Field(default=100, ge=1)
    daily_token_limit: int = Field(default=50000, ge=1)
    max_output_tokens: int = Field(default=0, ge=0)
    temperature: float = Field(default=0.4, ge=0.0, le=2.0)


class GlobalAppLlmDefaultsRequest(AppLlmConfigRequest):
    apply_to_existing_tasks: bool = True


class AppLlmRuntimeRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    user_message: str = Field(..., min_length=1)
    context: Optional[str] = None
    image_base64: Optional[str] = None
    image_mime_type: Optional[str] = None


class AppDataCreateRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    owner_id: Optional[str] = None
    data: dict[str, Any] = Field(default_factory=dict)


class AppDataUpdateRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    owner_id: Optional[str] = None
    data: dict[str, Any] = Field(default_factory=dict)
    replace: bool = False


class RuntimeErrorReportRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    summary: str = Field(..., min_length=1)
    stack_trace: str = Field(..., min_length=1)
    error_message: Optional[str] = None
    report_kind: Optional[str] = None
