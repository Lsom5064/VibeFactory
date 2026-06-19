package kr.ac.kangwon.hai.vibefactory

import com.google.gson.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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
    val device_info: DeviceInfo,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null,
    val interview_consent: Boolean? = null,
    val reference_image_path: String? = null,
    val reference_image_name: String? = null,
    val reference_image_base64: String? = null,
    val attachments: List<AttachmentPayload>? = null
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
    val image_conflict_note: String? = null
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
    val allowed_next_actions: List<String>? = null,
    val retry_block_reason: String? = null
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

interface VibeApiService {

    @GET("/tasks")
    suspend fun getTasks(
        @Query("device_id") deviceId: String? = null,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): JsonElement

    @POST("/generate")
    suspend fun generateApp(@Body request: BuildRequest): BuildResponse

    @GET("/status/{task_id}")
    suspend fun getStatus(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): StatusResponse

    @GET("/tasks/{task_id}/usage")
    suspend fun getTaskUsage(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): TokenUsageResponse

    @GET("/usage/codex")
    suspend fun getCodexUsage(
        @Query("device_id") deviceId: String? = null,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): TokenUsageResponse

    @POST("/tasks/{task_id}/runtime-error")
    suspend fun reportRuntimeError(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("phone_number") phoneNumber: String? = null,
        @Body request: RuntimeErrorReportRequest
    ): JsonElement

    @GET("/download/{task_id}")
    suspend fun downloadApk(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null,
        @Query("artifact_path") artifactPath: String? = null
    ): Response<ResponseBody>
}
