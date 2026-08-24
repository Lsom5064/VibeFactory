package kr.ac.kangwon.hai.vibefactory

import com.google.gson.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.PUT

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
        @Query("phone_number") phoneNumber: String? = null,
        @Query("include_logs") includeLogs: Boolean = false,
        @Query("include_timeline") includeTimeline: Boolean = true,
        @Query("timeline_after_event_id") timelineAfterEventId: String? = null
    ): StatusResponse

    @POST("/tasks/{task_id}/cancel")
    suspend fun cancelTask(
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

    @GET("/tasks/{task_id}/revisions")
    suspend fun getTaskRevisions(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): TaskRevisionsResponse

    @GET("/tasks/{task_id}/revisions/{revision_label}/ui/layouts")
    suspend fun getRevisionUiLayouts(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): UiLayoutsResponse

    @GET("/tasks/{task_id}/ui/editor-context")
    suspend fun getTaskUiEditorContext(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): UiEditorContextResponseDto

    @GET("/tasks/{task_id}/revisions/{revision_label}/ui/layouts/{layout_name}")
    suspend fun getRevisionUiLayout(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Path("layout_name") layoutName: String,
        @Query("configuration") configuration: String = "layout",
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): UiLayoutDocumentResponse

    @GET("/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{layout_name}")
    suspend fun getUiEditorDraft(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Path("layout_name") layoutName: String,
        @Query("configuration") configuration: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): UiEditorDraftDto

    @PUT("/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{layout_name}")
    suspend fun saveUiEditorDraft(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Path("layout_name") layoutName: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null,
        @Body request: UiEditorDraftRequestDto
    ): UiEditorDraftDto

    @POST("/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{draft_id}/images")
    suspend fun uploadUiEditorImage(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Path("draft_id") draftId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null,
        @Body request: UiEditorImageUploadRequestDto
    ): UiEditorImageResponseDto

    @Streaming
    @GET("/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{draft_id}/images/{image_id}")
    suspend fun getUiEditorImage(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Path("draft_id") draftId: String,
        @Path("image_id") imageId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): ResponseBody

    @POST("/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{draft_id}/confirm")
    suspend fun confirmUiEditorDraft(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Path("draft_id") draftId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null,
        @Body request: UiEditorSaveRequestDto
    ): UiEditorSaveResponseDto

    @POST("/tasks/{task_id}/revisions/{revision_label}/branch")
    suspend fun branchTaskRevision(
        @Path("task_id") taskId: String,
        @Path("revision_label") revisionLabel: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null
    ): StatusResponse

    @PATCH("/tasks/{task_id}")
    suspend fun renameTask(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null,
        @Body request: TaskRenameRequest
    ): TaskSummaryDto

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

    @Streaming
    @GET("/download/{task_id}")
    suspend fun downloadApk(
        @Path("task_id") taskId: String,
        @Query("device_id") deviceId: String,
        @Query("user_id") userId: String? = null,
        @Query("phone_number") phoneNumber: String? = null,
        @Query("artifact_path") artifactPath: String? = null,
        @Header("Range") range: String? = null
    ): Response<ResponseBody>
}
