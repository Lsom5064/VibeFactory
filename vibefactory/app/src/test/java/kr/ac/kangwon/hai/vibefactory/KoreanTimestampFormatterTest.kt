package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KoreanTimestampFormatterTest {
    private val formatter = KoreanTimestampFormatter(
        clock = Clock.fixed(Instant.parse("2026-08-24T07:30:45Z"), ZoneOffset.UTC)
    )

    @Test
    fun utcAndOffsetTimestampsAreDisplayedInKoreanTime() {
        assertEquals("2026-08-24 16:30", formatter.formatDisplay("2026-08-24T07:30:45Z"))
        assertEquals("2026-08-24 16:30", formatter.formatDisplay("2026-08-24T09:30:45+02:00"))
        assertEquals("오후 4:30", formatter.formatBubble("2026-08-24T07:30:45Z"))
        assertEquals("8월 24일 오후 4:30", formatter.formatRevision("2026-08-24T07:30:45Z"))
    }

    @Test
    fun localServerTimestampIsInterpretedAsKoreanTime() {
        assertEquals("2026-08-24 16:30", formatter.formatDisplay("2026-08-24 16:30:45"))
        assertEquals("2026년 8월 24일 월요일", formatter.formatDateSeparator(formatter.localDate("2026-08-24 16:30")!!))
    }

    @Test
    fun currentValuesUseTheInjectedClock() {
        assertEquals("2026-08-24 16:30:45", formatter.nowServerTimestamp())
        assertEquals("2026-08-24 16:30", formatter.nowSummaryTimestamp())
    }

    @Test
    fun invalidValuesRemainVisibleInListsButNotInBubbleTime() {
        assertEquals("not-a-time", formatter.formatDisplay("not-a-time"))
        assertNull(formatter.formatBubble("not-a-time"))
        assertNull(formatter.parseDate("not-a-time"))
    }
}
