package kr.ac.kangwon.hai.generated

import android.content.Context
import org.json.JSONObject

data class VibeLlmResponse(
    val taskId: String,
    val message: String,
    val model: String,
    val provider: String,
    val usage: Map<String, Any?>,
    val dailyUsage: Map<String, Any?>,
)

class VibeLlmClient(
    context: Context,
    private val taskId: String = BuildConfig.VIBE_TASK_ID,
    serverBaseUrl: String = BuildConfig.VIBE_SERVER_BASE_URL,
    private val httpClient: VibeHttpClient = VibeHttpClient(),
) {
    private val packageName = context.applicationContext.packageName
    private val endpoint = "${serverBaseUrl.trimEnd('/')}/apps/$taskId/llm/respond"

    suspend fun respond(
        userMessage: String,
        context: String = "",
        imageBase64: String? = null,
        imageMimeType: String? = null,
    ): VibeLlmResponse {
        require(userMessage.isNotBlank()) { "userMessage must not be blank" }
        val payload = JSONObject()
            .put("package_name", packageName)
            .put("user_message", userMessage)
        if (context.isNotBlank()) payload.put("context", context)
        if (!imageBase64.isNullOrBlank()) {
            payload.put("image_base64", imageBase64)
            payload.put("image_mime_type", imageMimeType ?: "image/jpeg")
        }

        val response = httpClient.request("POST", endpoint, payload)
        return VibeLlmResponse(
            taskId = response.optString("task_id", taskId),
            message = response.optString("message"),
            model = response.optString("model"),
            provider = response.optString("provider"),
            usage = response.optJSONObject("usage")?.toMap().orEmpty(),
            dailyUsage = response.optJSONObject("daily_usage")?.toMap().orEmpty(),
        )
    }
}
