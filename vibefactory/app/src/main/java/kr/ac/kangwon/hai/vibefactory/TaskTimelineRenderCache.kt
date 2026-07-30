package kr.ac.kangwon.hai.vibefactory

class TaskTimelineRenderCache {
    private data class Entry(
        val revision: Long,
        val messages: List<ChatMessage>
    )

    private val revisions = mutableMapOf<String, Long>()
    private val entries = mutableMapOf<String, Entry>()

    fun markChanged(taskId: String) {
        val key = taskId.trim()
        if (key.isBlank()) return
        revisions[key] = (revisions[key] ?: 0L) + 1L
        entries.remove(key)
    }

    fun getOrBuild(taskId: String, builder: () -> List<ChatMessage>): List<ChatMessage> {
        val key = taskId.trim()
        if (key.isBlank()) return emptyList()
        val revision = revisions[key] ?: 0L
        entries[key]?.takeIf { it.revision == revision }?.let { return it.messages }
        return builder().also { messages ->
            entries[key] = Entry(revision = revision, messages = messages)
        }
    }

    fun remove(taskId: String) {
        val key = taskId.trim()
        revisions.remove(key)
        entries.remove(key)
    }

    fun clear() {
        revisions.clear()
        entries.clear()
    }
}
