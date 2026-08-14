package kr.ac.kangwon.hai.generated

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class VibeDataRecord(
    val recordId: String,
    val taskId: String,
    val packageName: String,
    val collection: String,
    val ownerId: String,
    val data: Map<String, Any?>,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
) {
    companion object {
        internal fun fromJson(json: JSONObject): VibeDataRecord = VibeDataRecord(
            recordId = json.optString("record_id"),
            taskId = json.optString("task_id"),
            packageName = json.optString("package_name"),
            collection = json.optString("collection"),
            ownerId = json.optString("owner_id"),
            data = json.optJSONObject("data")?.toMap().orEmpty(),
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at"),
            deletedAt = json.opt("deleted_at")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf(String::isNotBlank),
        )
    }
}

class VibeDataClient(
    private val taskId: String,
    private val packageName: String,
    private val ownerId: String = "",
    serverBaseUrl: String = BuildConfig.VIBE_SERVER_BASE_URL,
    private val httpClient: VibeHttpClient = VibeHttpClient(),
) {
    private val serverBaseUrl = serverBaseUrl.trimEnd('/')

    suspend fun list(
        collection: String,
        ownerId: String = this.ownerId,
        limit: Int = 100,
    ): List<VibeDataRecord> {
        val url = collectionUrl(collection).toHttpUrl().newBuilder()
            .addQueryParameter("package_name", packageName)
            .addQueryParameter("limit", limit.coerceIn(1, 500).toString())
            .apply {
                if (ownerId.isNotBlank()) addQueryParameter("owner_id", ownerId)
            }
            .build()
        val response = httpClient.request("GET", url.toString())
        val records = response.optJSONArray("records") ?: return emptyList()
        return (0 until records.length()).map { index ->
            VibeDataRecord.fromJson(records.getJSONObject(index))
        }
    }

    suspend fun create(
        collection: String,
        data: Map<String, Any?>,
        ownerId: String = this.ownerId,
    ): VibeDataRecord {
        val response = httpClient.request(
            "POST",
            collectionUrl(collection),
            mapOf(
                "package_name" to packageName,
                "owner_id" to ownerId,
                "data" to data,
            ).toJsonObject(),
        )
        return VibeDataRecord.fromJson(response.getJSONObject("record"))
    }

    suspend fun get(collection: String, recordId: String): VibeDataRecord {
        val url = recordUrl(collection, recordId).toHttpUrl().newBuilder()
            .addQueryParameter("package_name", packageName)
            .build()
        val response = httpClient.request("GET", url.toString())
        return VibeDataRecord.fromJson(response.getJSONObject("record"))
    }

    suspend fun update(
        collection: String,
        recordId: String,
        data: Map<String, Any?>,
        ownerId: String = this.ownerId,
        replace: Boolean = false,
    ): VibeDataRecord {
        val response = httpClient.request(
            "PATCH",
            recordUrl(collection, recordId),
            mapOf(
                "package_name" to packageName,
                "owner_id" to ownerId,
                "data" to data,
                "replace" to replace,
            ).toJsonObject(),
        )
        return VibeDataRecord.fromJson(response.getJSONObject("record"))
    }

    suspend fun delete(collection: String, recordId: String) {
        val url = recordUrl(collection, recordId).toHttpUrl().newBuilder()
            .addQueryParameter("package_name", packageName)
            .build()
        httpClient.request("DELETE", url.toString())
    }

    private fun collectionUrl(collection: String): String =
        "$serverBaseUrl/apps/$taskId/data/${encodePathSegment(collection)}"

    private fun recordUrl(collection: String, recordId: String): String =
        "${collectionUrl(collection)}/${encodePathSegment(recordId)}"

    private fun encodePathSegment(value: String): String =
        "http://localhost/".toHttpUrl().newBuilder()
            .addPathSegment(value)
            .build()
            .encodedPathSegments
            .last()
}
