package kr.ac.kangwon.hai.vibefactory

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

class KoreanTimestampFormatter(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul")
) {
    private val serverFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        .withZone(zoneId)
    private val displayFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA)
        .withZone(zoneId)
    private val bubbleFormatter = DateTimeFormatter
        .ofPattern("a h:mm", Locale.KOREA)
        .withZone(zoneId)
    private val dateSeparatorFormatter = DateTimeFormatter
        .ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREA)
    private val revisionFormatter = DateTimeFormatter
        .ofPattern("M월 d일 a h:mm", Locale.KOREA)
        .withZone(zoneId)
    private val localTimestampParsers = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA)
    )

    fun nowServerTimestamp(): String = serverFormatter.format(clock.instant())

    fun nowSummaryTimestamp(): String = displayFormatter.format(clock.instant())

    fun formatDisplay(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return parseDate(raw)?.let { displayFormatter.format(it.toInstant()) } ?: raw
    }

    fun formatBubble(value: String?): String? {
        val parsed = parseDate(value) ?: return null
        return bubbleFormatter.format(parsed.toInstant())
    }

    fun formatRevision(value: String?): String {
        val parsed = parseDate(value) ?: return ""
        return revisionFormatter.format(parsed.toInstant())
    }

    fun formatDateSeparator(date: LocalDate): String = dateSeparatorFormatter.format(date)

    fun localDate(value: String?): LocalDate? {
        return parseDate(value)?.toInstant()?.atZone(zoneId)?.toLocalDate()
    }

    fun parseDate(value: String?): Date? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        parseInstant(raw)?.let { return Date.from(it) }
        for (formatter in localTimestampParsers) {
            try {
                val parsed = LocalDateTime.parse(raw, formatter)
                return Date.from(parsed.atZone(zoneId).toInstant())
            } catch (_: DateTimeParseException) {
                // Try the next supported local format.
            }
        }
        return null
    }

    private fun parseInstant(value: String): Instant? {
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
