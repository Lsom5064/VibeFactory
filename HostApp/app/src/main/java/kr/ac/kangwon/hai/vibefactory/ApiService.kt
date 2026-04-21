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
    val prompt: String,
    val device_info: DeviceInfo,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null,
    val interview_consent: Boolean? = null,
    val reference_image_path: String? = null,
    val reference_image_name: String? = null,
    val reference_image_base64: String? = null
)

data class ContinueRequest(
    val task_id: String,
    val user_message: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null
)

data class BuildResponse(
    val task_id: String,
    val status: String? = null,
    val tool: String? = null,
    val message: String? = null,
    val summary: String? = null,
    val questions: List<String>? = null,
    val missing_fields: List<String>? = null,
    val reason: String? = null,
    val policy_category: String? = null,
    val image_reference_summary: String? = null,
    val image_conflict_note: String? = null
)

data class TaskAcceptedResponse(
    val task_id: String,
    val status: String? = null,
    val image_reference_summary: String? = null,
    val image_conflict_note: String? = null
)

data class StatusResponse(
    val task_id: String = "",
    val status: String,
    val status_display_text: String = "",
    val app_name: String = "",
    val generated_app_name: String = "",
    val package_name: String = "",
    val apk_url: String = "",
    val build_success: Boolean = false,
    val build_attempts: Int = 0,
    val conversation_state: JsonElement? = null,
    val log: String = "",
    val full_log: String = "",
    val log_lines: JsonElement? = null,
    val latest_log: String = "",
    val status_message: String = "",
    val progress_mode: String = "",
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

data class RefineRequest(
    val task_id: String,
    val feedback: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null,
    val reference_image_path: String? = null,
    val reference_image_name: String? = null,
    val reference_image_base64: String? = null
)

data class RetryRequest(
    val task_id: String,
    val feedback: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null
)

data class CrashReportRequest(
    val task_id: String,
    val package_name: String,
    val stack_trace: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null
)

data class RuntimeErrorContextDto(
    val package_name: String,
    val stack_trace: String,
    val summary: String,
    val awaiting_user_confirmation: Boolean
)

data class FeedbackRouteRequest(
    val task_id: String,
    val user_message: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null,
    val runtime_error: RuntimeErrorContextDto? = null
)

data class FeedbackRouteResponse(
    val task_id: String = "",
    val action: String = "",
    val target_endpoint: String = "",
    val current_status: String = "",
    val assistant_message: String = "",
    val reason: String = ""
)

data class RuntimeErrorSummaryRequest(
    val task_id: String,
    val package_name: String,
    val stack_trace: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null
)

data class RuntimeErrorSummaryResponse(
    val task_id: String = "",
    val summary: String = "",
    val assistant_message: String = ""
)

data class RuntimeErrorReportRequest(
    val task_id: String,
    val package_name: String,
    val stack_trace: String,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null
)

data class RuntimeErrorReportResponse(
    val status: String = "",
    val task_id: String = ""
)

data class InteractionEventRequest(
    val task_id: String? = null,
    val event_type: String,
    val source: String = "android_host",
    val action: String? = null,
    val message_id: String? = null,
    val message_type: String? = null,
    val content: String? = null,
    val payload: Map<String, String?>? = null,
    val device_id: String,
    val user_id: String? = null,
    val phone_number: String? = null
)

data class InteractionEventResponse(
    val status: String = "",
    val task_id: String? = null
)

data class CrashReportResponse(
    val status: String = "",
    val task_id: String = "",
    val lookup_source: String = ""
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

    @POST("/generate/continue")
    suspend fun continueGenerate(@Body request: ContinueRequest): BuildResponse

    @GET("/status/{task_id}")
    suspend fun getStatus(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): StatusResponse

    @POST("/refine/plan")
    suspend fun planRefinement(@Body request: RefineRequest): JsonElement

    @POST("/refine")
    suspend fun refineApp(@Body request: RefineRequest): TaskAcceptedResponse

    @POST("/retry")
    suspend fun retryApp(@Body request: RetryRequest): TaskAcceptedResponse

    @POST("/feedback/route")
    suspend fun routeFeedback(@Body request: FeedbackRouteRequest): FeedbackRouteResponse

    @POST("/runtime/error/summary")
    suspend fun summarizeRuntimeError(@Body request: RuntimeErrorSummaryRequest): RuntimeErrorSummaryResponse

    @POST("/runtime/error/report")
    suspend fun reportRuntimeError(@Body request: RuntimeErrorReportRequest): RuntimeErrorReportResponse

    @POST("/crash")
    suspend fun reportCrash(@Body report: CrashReportRequest): Response<CrashReportResponse>

    @POST("/interaction/event")
    suspend fun recordInteractionEvent(@Body request: InteractionEventRequest): InteractionEventResponse

    @GET("/download/{task_id}")
    suspend fun downloadApk(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): Response<ResponseBody>
}
