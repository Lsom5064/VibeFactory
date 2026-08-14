package kr.ac.kangwon.hai.generated

import android.content.Context
import org.json.JSONObject
import java.io.IOException

data class VibeLlmResponse(
    val taskId: String,
    val message: String,
    val model: String,
    val provider: String,
    val usage: Map<String, Any?>,
    val dailyUsage: Map<String, Any?>,
)

class VibeLlmRequestException(
    val userMessage: String,
    cause: Throwable,
) : Exception(userMessage, cause)

internal fun appLlmHttpErrorMessage(statusCode: Int): String = when (statusCode) {
    400 -> "AI 요청을 처리하지 못했습니다. 입력 내용과 첨부 이미지를 확인해 주세요."
    403, 404 -> "이 앱의 AI 기능을 사용할 수 없습니다. 앱 관리자에게 문의해 주세요."
    429 -> "오늘 사용할 수 있는 AI 한도를 초과했습니다. 다음 날 다시 시도해 주세요."
    502, 503, 504 -> "AI 서버를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
    else -> "AI 요청 중 서버 문제가 발생했습니다. 잠시 후 다시 시도해 주세요."
}

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

        return try {
            val response = httpClient.request("POST", endpoint, payload)
            VibeLlmResponse(
                taskId = response.optString("task_id", taskId),
                message = response.optString("message"),
                model = response.optString("model"),
                provider = response.optString("provider"),
                usage = response.optJSONObject("usage")?.toMap().orEmpty(),
                dailyUsage = response.optJSONObject("daily_usage")?.toMap().orEmpty(),
            )
        } catch (error: VibeHttpException) {
            throw VibeLlmRequestException(appLlmHttpErrorMessage(error.statusCode), error)
        } catch (error: IOException) {
            throw VibeLlmRequestException(
                "네트워크 연결을 확인한 뒤 AI 요청을 다시 시도해 주세요.",
                error,
            )
        } catch (error: Exception) {
            throw VibeLlmRequestException(
                "AI 응답을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                error,
            )
        }
    }
}
