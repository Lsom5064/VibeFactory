package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanPhoneNumberFormatterTest {
    @Test
    fun `formats mobile numbers and strips separators`() {
        assertEquals("010-1234-5678", KoreanPhoneNumberFormatter.format("010 1234-5678"))
    }

    @Test
    fun `formats Seoul landline numbers`() {
        assertEquals("02-1234-5678", KoreanPhoneNumberFormatter.format("0212345678"))
        assertEquals("02-123-4567", KoreanPhoneNumberFormatter.format("021234567"))
    }

    @Test
    fun `limits input to eleven digits`() {
        assertEquals("010-1234-5678", KoreanPhoneNumberFormatter.format("01012345678999"))
    }
}
