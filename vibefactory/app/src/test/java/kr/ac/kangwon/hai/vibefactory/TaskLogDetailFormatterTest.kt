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

    @Test
    fun agentItemsFromStatus_hidesInternalLinksAndDeveloperValidationDetails() {
        val response = StatusResponse(
            status = "succeeded",
            full_log = """
                {"type":"item.completed","item":{"type":"agent_message","text":"메인 화면 레이아웃을 정돈했습니다. [project/lib/main.dart](/private/tmp/task/project/lib/main.dart)에서 상단 제목과 설명을 중앙 정렬했습니다.\n검증은 `flutter analyze`로 마쳤고 `No issues found!`였습니다.\n결과 계약 파일은 [.codex_result/task_result.json](/private/tmp/task/.codex_result/task_result.json)에 작성했습니다."}}
            """.trimIndent()
        )

        val items = TaskLogDetailFormatter.agentItemsFromStatus(response)

        assertEquals(
            listOf("메인 화면 레이아웃을 정돈했습니다. 상단 제목과 설명을 중앙 정렬했습니다."),
            items.map { it.body }
        )
    }
}
