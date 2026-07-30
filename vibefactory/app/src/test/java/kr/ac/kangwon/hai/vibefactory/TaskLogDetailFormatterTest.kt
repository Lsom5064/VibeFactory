package kr.ac.kangwon.hai.vibefactory

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLogDetailFormatterTest {
    @Test
    fun agentItemsFromStatus_readsStructuredLogsNewestFirst() {
        val response = StatusResponse(
            status = "succeeded",
            raw_log_sections = JsonParser().parse(
                """
                [
                  {
                    "title": "Codex",
                    "content": "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"첫 번째 메모\"}}\n{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"두 번째 메모\"}}"
                  }
                ]
                """.trimIndent()
            )
        )

        val items = TaskLogDetailFormatter.agentItemsFromStatus(response)

        assertEquals(listOf("두 번째 메모", "첫 번째 메모"), items.map { it.body })
    }

    @Test
    fun agentItemsFromStatus_fallsBackToFullLog() {
        val response = StatusResponse(
            status = "succeeded",
            full_log = """
                {"type":"item.completed","item":{"type":"agent_message","text":"완료 메모"}}
            """.trimIndent()
        )

        val items = TaskLogDetailFormatter.agentItemsFromStatus(response)

        assertEquals(listOf("완료 메모"), items.map { it.body })
    }
}
