package kr.ac.kangwon.hai.vibefactory

internal data class DownloadProgressRenderDecision(
    val shouldRender: Boolean,
    val percent: Int?
)

internal class DownloadProgressRenderPolicy(
    private val minimumRenderIntervalMs: Long = 200L,
    private val minimumUnknownSizeBytesDelta: Long = 1024L * 1024L
) {
    private var lastRenderedAtMs: Long? = null
    private var lastRenderedPercent: Int? = null
    private var lastRenderedBytes = 0L

    fun evaluate(
        downloadedBytes: Long,
        totalBytes: Long?,
        nowMs: Long
    ): DownloadProgressRenderDecision {
        val percent = totalBytes
            ?.takeIf { it > 0L }
            ?.let { ((downloadedBytes * 100L) / it).toInt().coerceIn(0, 100) }
        val previousRenderAt = lastRenderedAtMs
        val restarted = downloadedBytes < lastRenderedBytes
        val completed = totalBytes != null && totalBytes > 0L && downloadedBytes >= totalBytes
        val progressChanged = if (percent != null) {
            percent != lastRenderedPercent
        } else {
            downloadedBytes - lastRenderedBytes >= minimumUnknownSizeBytesDelta
        }
        val intervalElapsed = previousRenderAt == null ||
            nowMs - previousRenderAt >= minimumRenderIntervalMs
        val shouldRender = previousRenderAt == null ||
            restarted ||
            completed ||
            (progressChanged && intervalElapsed)

        if (shouldRender) {
            lastRenderedAtMs = nowMs
            lastRenderedPercent = percent
            lastRenderedBytes = downloadedBytes
        }
        return DownloadProgressRenderDecision(
            shouldRender = shouldRender,
            percent = percent
        )
    }

    fun reset() {
        lastRenderedAtMs = null
        lastRenderedPercent = null
        lastRenderedBytes = 0L
    }
}
