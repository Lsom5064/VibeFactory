package kr.ac.kangwon.hai.vibefactory

object TimelineCursorPolicy {
    fun firstUnprocessedIndex(
        eventIds: List<String>,
        processedEventId: String?,
        maxEvents: Int
    ): Int {
        val boundedStart = if (maxEvents > 0) {
            (eventIds.size - maxEvents).coerceAtLeast(0)
        } else {
            0
        }
        val cursor = processedEventId?.trim().orEmpty()
        if (cursor.isBlank()) return boundedStart
        val cursorIndex = eventIds.indexOfLast { it == cursor }
        if (cursorIndex < 0) return boundedStart
        return (cursorIndex + 1).coerceAtLeast(boundedStart)
    }

    fun nextCursor(serverCursor: String?, eventIds: List<String>): String? {
        return serverCursor
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: eventIds.asReversed().firstOrNull { it.isNotBlank() }
    }
}
