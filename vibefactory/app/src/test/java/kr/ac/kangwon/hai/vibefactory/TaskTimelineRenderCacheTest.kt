package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskTimelineRenderCacheTest {
    @Test
    fun rebuildsOnlyAfterTaskChanges() {
        val cache = TaskTimelineRenderCache()
        var buildCount = 0
        val builder = {
            buildCount += 1
            listOf(
                ChatMessage(
                    id = "message-$buildCount",
                    kind = MessageKind.USER,
                    title = null,
                    body = "hello"
                )
            )
        }

        cache.getOrBuild("task-1", builder)
        cache.getOrBuild("task-1", builder)
        assertEquals(1, buildCount)

        cache.markChanged("task-1")
        cache.getOrBuild("task-1", builder)
        assertEquals(2, buildCount)
    }
}
