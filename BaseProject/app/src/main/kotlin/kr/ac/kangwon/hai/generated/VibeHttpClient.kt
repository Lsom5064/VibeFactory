package kr.ac.kangwon.hai.generated

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VibeHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Vibe server request failed: $statusCode $responseBody")

class VibeHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun request(
        method: String,
        url: String,
        payload: JSONObject? = null,
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        val requestBody = payload
            ?.toString()
            ?.toRequestBody(JSON_MEDIA_TYPE)
        requestBuilder.method(method, requestBody)

        val call = client.newCall(requestBuilder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseText = response.body?.string().orEmpty()
                    if (!continuation.isActive) return
                    if (!response.isSuccessful) {
                        continuation.resumeWithException(
                            VibeHttpException(response.code, responseText),
                        )
                        return
                    }
                    runCatching {
                        if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
                    }.onSuccess(continuation::resume)
                        .onFailure(continuation::resumeWithException)
                }
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun Map<String, Any?>.toJsonObject(): JSONObject = JSONObject().also { target ->
    forEach { (key, value) -> target.put(key, value.toJsonValue()) }
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { target ->
        forEach { (key, value) ->
            if (key != null) target.put(key.toString(), value.toJsonValue())
        }
    }
    is Iterable<*> -> JSONArray().also { target -> forEach { target.put(it.toJsonValue()) } }
    is Array<*> -> JSONArray().also { target -> forEach { target.put(it.toJsonValue()) } }
    else -> this
}

internal fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    get(key).toKotlinValue()
}

private fun Any.toKotlinValue(): Any? = when (this) {
    JSONObject.NULL -> null
    is JSONObject -> toMap()
    is JSONArray -> (0 until length()).map { get(it).toKotlinValue() }
    else -> this
}
