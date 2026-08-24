package kr.ac.kangwon.hai.vibefactory

import com.google.gson.JsonElement

data class DeviceInfo(
    val model: String,
    val sdk: Int,
    val width: Int,
    val height: Int,
    val sensors: List<String>
)

data class BuildRequest(
    val task_id: String? = null,
    val prompt: String,
    val display_prompt: String? = null,
    val device_info: DeviceInfo,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null,
    val interview_consent: Boolean? = null,
    val request_action: String? = null,
    val reference_image_path: String? = null,
    val reference_image_name: String? = null,
    val reference_image_base64: String? = null,
    val attachments: List<AttachmentPayload>? = null,
    val use_ui_editor_draft: Boolean = false
)

data class AttachmentPayload(
    val type: String,
    val mime_type: String,
    val name: String,
    val base64: String
)

data class BuildResponse(
    val task_id: String,
    val status: String? = null,
    val tool: String? = null,
    val message: String? = null,
    val summary: String? = null,
    val app_name: String? = "",
    val generated_app_name: String? = "",
    val package_name: String? = "",
    val questions: List<String>? = null,
    val confirmation_action: String? = null,
    val confirmation_payload: String? = null,
    val interaction_type: String? = null,
    val request_scope: String? = null,
    val render_mode: String? = null,
    val requires_user_input: Boolean? = null,
    val requires_confirmation: Boolean? = null,
    val pending_decision_reason: String? = null,
    val suppress_assistant_bubble: Boolean? = null,
    val missing_fields: List<String>? = null,
    val reason: String? = null,
    val policy_category: String? = null,
    val image_reference_summary: String? = null,
    val image_conflict_note: String? = null,
    val prepared_prompt: String? = null
)

data class RuntimeErrorReportRequest(
    val package_name: String,
    val summary: String,
    val stack_trace: String,
    val error_message: String? = null,
    val report_kind: String? = null
)

data class StatusResponse(
    val task_id: String = "",
    val status: String,
    val status_display_text: String? = "",
    val app_name: String? = "",
    val generated_app_name: String? = "",
    val package_name: String? = "",
    val apk_url: String? = "",
    val apk_path: String? = "",
    val apk_size_bytes: Long? = null,
    val build_success: Boolean = false,
    val build_attempts: Int = 0,
    val conversation_state: JsonElement? = null,
    val log: String? = "",
    val full_log: String? = "",
    val log_lines: JsonElement? = null,
    val latest_log: String? = "",
    val status_message: String? = "",
    val current_build_stage: String? = "",
    val current_build_stage_detail: String? = "",
    val timeline_events: JsonElement? = null,
    val timeline_cursor: String? = null,
    val raw_log_sections: JsonElement? = null,
    val progress_mode: String? = "",
    val latest_assistant_message: String? = "",
    val latest_assistant_message_type: String? = "",
    val latest_failure_message: String? = "",
    val recent_messages: JsonElement? = null,
    val interaction_type: String? = "",
    val render_mode: String? = "",
    val requires_user_input: Boolean? = null,
    val requires_confirmation: Boolean? = null,
    val pending_decision_reason: String? = "",
    val suppress_assistant_bubble: Boolean? = null,
    val retry_allowed: Boolean? = null,
    val cancel_allowed: Boolean? = null,
    val allowed_next_actions: List<String>? = null,
    val retry_block_reason: String? = null,
    val prepared_prompt: String? = "",
    val created_at: String = "",
    val updated_at: String = ""
)

data class TaskSummaryDto(
    val task_id: String = "",
    val status: String = "",
    val status_display_text: String = "",
    val app_name: String = "",
    val generated_app_name: String = "",
    val package_name: String = "",
    val initial_user_prompt: String = "",
    val apk_url: String = "",
    val build_success: Boolean = false,
    val created_at: String = "",
    val updated_at: String = "",
    val last_bubble_at: String = "",
    val conversation_state: JsonElement? = null
)

data class TasksEnvelope(
    val tasks: List<TaskSummaryDto>? = null
)

data class TokenUsageWindowDto(
    val window_label: String? = null,
    val used_percent: Int? = null,
    val remaining_percent: Int? = null,
    val resets_at: Long? = null,
    val window_duration_mins: Int? = null
)

data class TokenUsageStatsDto(
    val input_tokens: Int? = null,
    val cached_input_tokens: Int? = null,
    val output_tokens: Int? = null,
    val cached_output_tokens: Int? = null,
    val reasoning_output_tokens: Int? = null,
    val total_tokens: Int? = null
)

data class TokenUsageResponse(
    val task_id: String = "",
    val limit_name: String? = null,
    val primary_window: TokenUsageWindowDto? = null,
    val secondary_window: TokenUsageWindowDto? = null,
    val usage: TokenUsageStatsDto? = null,
    val status: String? = null,
    val status_message: String? = null
)

data class TaskRevisionDto(
    val snapshot_id: String = "",
    val task_id: String = "",
    val revision_label: String = "",
    val version_name: String = "",
    val source: String = "",
    val created_at: String = "",
    val request_summary: String = "",
    val apk_path: String? = "",
    val apk_url: String? = "",
    val apk_size_bytes: Long? = null,
    val has_apk: Boolean = false,
    val can_branch: Boolean = false,
    val is_current: Boolean = false
)

data class TaskRevisionsResponse(
    val task_id: String = "",
    val revisions: List<TaskRevisionDto> = emptyList()
)

data class UiLayoutSummaryDto(
    val layout_name: String = "",
    val configuration: String = "layout",
    val resource_path: String = "",
    val root_tag: String = "",
    val sha256: String = "",
    val size_bytes: Long = 0
)

data class UiLayoutsResponse(
    val task_id: String = "",
    val revision_label: String = "",
    val source_available: Boolean = false,
    val unavailable_reason: String = "",
    val layouts: List<UiLayoutSummaryDto> = emptyList()
)

data class UiResourceReferenceDto(
    val type: String = "",
    val name: String = ""
)

data class UiResourceFileDto(
    val resource_path: String = "",
    val kind: String = "",
    val media_type: String = "",
    val sha256: String = "",
    val size_bytes: Long = 0,
    val content: String? = null
)

data class UiLayoutDocumentResponse(
    val task_id: String = "",
    val revision_label: String = "",
    val source_available: Boolean = false,
    val layout_name: String = "",
    val configuration: String = "layout",
    val resource_path: String = "",
    val root_tag: String = "",
    val xml: String = "",
    val sha256: String = "",
    val size_bytes: Long = 0,
    val resource_references: List<UiResourceReferenceDto> = emptyList(),
    val resource_files: List<UiResourceFileDto> = emptyList(),
    val unresolved_resources: List<UiResourceReferenceDto> = emptyList()
)

data class UiEditorImageMetadataDto(
    val original_size_bytes: Long = 0,
    val stored_size_bytes: Long = 0,
    val original_width: Int = 0,
    val original_height: Int = 0,
    val stored_width: Int = 0,
    val stored_height: Int = 0,
    val original_sha256: String = "",
    val stored_sha256: String = ""
)

data class UiEditorImageDto(
    val image_id: String = "",
    val draft_id: String = "",
    val element_stable_id: String = "",
    val original_name: String = "",
    val mime_type: String = "image/jpeg",
    val resource_name: String = "",
    val workspace_path: String = "",
    val size_bytes: Long = 0,
    val sha256: String = "",
    val metadata: UiEditorImageMetadataDto? = null,
    val created_at: String = ""
)

data class UiEditorDraftDto(
    val draft_id: String = "",
    val task_id: String = "",
    val base_revision_label: String = "",
    val layout_name: String = "",
    val configuration: String = "layout",
    val base_xml_sha256: String = "",
    val original_xml: String = "",
    val edited_xml: String = "",
    val descriptions: Map<String, String> = emptyMap(),
    val status: String = "draft",
    val version: Int = 0,
    val is_new_layout: Boolean = false,
    val preview_workspace_path: String? = null,
    val generated_revision_label: String? = null,
    val confirmed_at: String? = null,
    val created_at: String = "",
    val updated_at: String = "",
    val submitted_at: String? = null,
    val images: List<UiEditorImageDto> = emptyList()
)

data class UiEditorDraftRequestDto(
    val draft_id: String? = null,
    val configuration: String,
    val base_xml_sha256: String,
    val original_xml: String,
    val edited_xml: String,
    val descriptions: Map<String, String>,
    val expected_version: Int? = null,
    val is_new_layout: Boolean
)

data class UiEditorImageUploadRequestDto(
    val image_id: String,
    val element_stable_id: String,
    val original_name: String,
    val mime_type: String,
    val resource_name: String,
    val base64: String
)

data class UiEditorImageResponseDto(
    val image: UiEditorImageDto
)

data class UiEditorSaveRequestDto(
    val expected_version: Int,
    val preview_image_base64: String? = null,
    val preview_mime_type: String = "image/jpeg"
)

data class UiEditorSaveResponseDto(
    val task_id: String = "",
    val status: String = "",
    val message: String = "",
    val draft: UiEditorDraftDto
)

data class UiEditorContextResponseDto(
    val task_id: String = "",
    val revision_label: String = "",
    val source_available: Boolean = false,
    val has_saved_ui: Boolean = false,
    val saved_draft_count: Int = 0,
    val saved_at: String = ""
)

data class TaskRenameRequest(
    val app_name: String
)
