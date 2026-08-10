package kr.ac.kangwon.hai.vibefactory

object TimelineCursorPolicy {
    private val persistedEventIdPattern = Regex("^[0-9a-fA-F]{32}$")

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

    fun restoredCursor(taskId: String, messageIds: List<String>): String? {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isBlank()) return null
        val prefixes = listOf(
            "timeline-$normalizedTaskId-",
            "artifact-$normalizedTaskId-"
        )
        return messageIds.asReversed().firstNotNullOfOrNull { messageId ->
            prefixes.firstNotNullOfOrNull { prefix ->
                messageId.removePrefix(prefix)
                    .takeIf { messageId.startsWith(prefix) && it.matches(persistedEventIdPattern) }
            }
        }
    }
}
