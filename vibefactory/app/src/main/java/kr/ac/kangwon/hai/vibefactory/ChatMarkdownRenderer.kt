package kr.ac.kangwon.hai.vibefactory

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat

internal object ChatMarkdownRenderer {
    private val unorderedListRegex = Regex("""^\s*[-*•]\s+(.+)$""")
    private val orderedListRegex = Regex("""^\s*(\d+)[.)]\s+(.+)$""")

    fun render(context: Context, rawText: String): CharSequence {
        if (rawText.isBlank()) return rawText

        val builder = SpannableStringBuilder()
        rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .forEach { line ->
                appendLine(context, builder, line)
            }

        if (builder.endsWith("\n")) {
            builder.delete(builder.length - 1, builder.length)
        }
        return builder
    }

    private fun appendLine(context: Context, builder: SpannableStringBuilder, line: String) {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            if (!builder.endsWith("\n\n")) builder.append('\n')
            return
        }

        val unordered = unorderedListRegex.matchEntire(line)
        if (unordered != null) {
            appendUnorderedListItem(context, builder, unordered.groupValues[1])
            return
        }

        val ordered = orderedListRegex.matchEntire(line)
        if (ordered != null) {
            appendOrderedListItem(context, builder, ordered.groupValues[1], ordered.groupValues[2])
            return
        }

        val start = builder.length
        appendInlineFormatted(context, builder, trimmed)
        applyBlockSpacing(builder, start)
    }

    private fun appendUnorderedListItem(context: Context, builder: SpannableStringBuilder, content: String) {
        val start = builder.length
        appendInlineFormatted(context, builder, content.trim())
        applyBlockSpacing(builder, start)
        builder.setSpan(
            BulletSpan(dp(context, 8)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            LeadingMarginSpan.Standard(dp(context, 18), dp(context, 18)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendOrderedListItem(context: Context, builder: SpannableStringBuilder, number: String, content: String) {
        val start = builder.length
        val numberPrefix = "${number.trim()}. "
        builder.append(numberPrefix)
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        appendInlineFormatted(context, builder, content.trim())
        applyBlockSpacing(builder, start)
        builder.setSpan(
            LeadingMarginSpan.Standard(dp(context, 20), dp(context, 20)),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendInlineFormatted(context: Context, builder: SpannableStringBuilder, text: String) {
        var index = 0
        while (index < text.length) {
            if (text.startsWith("**", index)) {
                val close = text.indexOf("**", startIndex = index + 2)
                if (close > index + 2) {
                    val start = builder.length
                    builder.append(text.substring(index + 2, close))
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    index = close + 2
                    continue
                }
            }

            if (text[index] == '`') {
                val close = text.indexOf('`', startIndex = index + 1)
                if (close > index + 1) {
                    val start = builder.length
                    builder.append(text.substring(index + 1, close))
                    builder.setSpan(
                        TypefaceSpan("monospace"),
                        start,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        RelativeSizeSpan(0.94f),
                        start,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent_primary_dark)),
                        start,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(context, R.color.bg_panel_alt)),
                        start,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    index = close + 1
                    continue
                }
            }

            builder.append(text[index])
            index += 1
        }
    }

    private fun applyBlockSpacing(builder: SpannableStringBuilder, start: Int) {
        if (builder.length == start) return
        builder.append('\n')
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
