package kr.ac.kangwon.hai.vibefactory

internal object ChatTimelineEventTypeBackfill {
    data class SourceEvent(
        val kind: String,
        val body: String,
        val eventType: String
    )

    fun backfill(
        timeline: MutableList<ChatMessage>,
        sourceEvents: List<SourceEvent>
    ): Boolean {
        val eventTypeByKey = sourceEvents
            .mapNotNull { event ->
                val key = eventMatchKey(
                    kind = event.kind,
                    body = event.body
                ) ?: return@mapNotNull null
                val eventType = event.eventType.trim().lowercase().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                key to eventType
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, eventTypes) -> eventTypes.distinct() }
            .filterValues { it.size == 1 }
            .mapValues { (_, eventTypes) -> eventTypes.first() }

        if (eventTypeByKey.isEmpty()) return false

        var changed = false
        timeline.indices.forEach { index ->
            val message = timeline[index]
            if (!message.eventType.isNullOrBlank()) return@forEach
            val key = messageMatchKey(message) ?: return@forEach
            val eventType = eventTypeByKey[key] ?: return@forEach
            timeline[index] = message.copy(eventType = eventType)
            changed = true
        }
        return changed
    }

    private fun eventMatchKey(kind: String, body: String): String? {
        val normalizedBody = ChatTimelineVisibility.normalizeBody(body).takeIf { it.isNotBlank() } ?: return null
        val normalizedKind = when (kind.trim().lowercase()) {
            "user" -> MessageKind.USER.name
            "assistant" -> MessageKind.ASSISTANT.name
            "status" -> MessageKind.STATUS.name
            else -> MessageKind.LOG.name
        }
        return "$normalizedKind\u0001$normalizedBody"
    }

    private fun messageMatchKey(message: ChatMessage): String? {
        val normalizedBody = ChatTimelineVisibility.normalizeBody(message.body).takeIf { it.isNotBlank() } ?: return null
        return "${message.kind.name}\u0001$normalizedBody"
    }
}
