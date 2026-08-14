package kr.ac.kangwon.hai.generated

import org.junit.Assert.assertEquals
import org.junit.Test

class VibeLlmClientTest {
    @Test
    fun `runtime configuration error has a user-facing message`() {
        assertEquals(
            "AI 서버를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            appLlmHttpErrorMessage(503),
        )
    }

    @Test
    fun `daily quota error explains when to retry`() {
        assertEquals(
            "오늘 사용할 수 있는 AI 한도를 초과했습니다. 다음 날 다시 시도해 주세요.",
            appLlmHttpErrorMessage(429),
        )
    }

    @Test
    fun `invalid image or prompt error asks the user to inspect input`() {
        assertEquals(
            "AI 요청을 처리하지 못했습니다. 입력 내용과 첨부 이미지를 확인해 주세요.",
            appLlmHttpErrorMessage(400),
        )
    }
}
