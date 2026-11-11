package comparison.infrastructure.parsers

import comparison.domain.models.ParsedLyricLine
import comparison.domain.ports.LyricLineParser
import java.util.*
import services.LyricSyntaxPatterns
import services.LyricSyntaxPatterns.trimAndStripBom

/**
 * Robust parser for synchronized LRC file lines and unsynchronized plain lyrics.
 * Supports standard [mm:ss.xx], [mm:ss.xxx], [mm:ss:xx], [mm:ss,xx], and [mm:ss.xx-1] timestamps,
 * as well as multiple timestamps per line.
 * Filters out metadata header tags (e.g. [ar:], [ti:]) and standalone credit stub lines (e.g. 作词 / 作曲).
 */
class LyricLineParserImpl : LyricLineParser {

    private val sectionHeaderRegex = Regex("""^\[[^\]]+\]$""")

    private fun formatTimestampMs(totalMs: Long): String {
        val clamped = totalMs.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L
        val remMs = clamped % 1000L
        return if (remMs % 10L != 0L) {
            String.format(Locale.ROOT, "%02d:%02d.%03d", mins, secs, remMs)
        } else {
            String.format(Locale.ROOT, "%02d:%02d.%02d", mins, secs, remMs / 10L)
        }
    }

    override fun parse(content: String): List<ParsedLyricLine> {
        if (content.isBlank()) return emptyList()

        val parsedLines = mutableListOf<ParsedLyricLine>()
        var hasMultiTimestampLine = false

        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trimAndStripBom()
            if (trimmed.isEmpty()) return@forEach

            // Ignore header tags like [ti:Title], [ar:Artist], [al:Album], [length:03:00], etc.
            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) return@forEach

            val matches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach
            if (matches.size > 1) {
                hasMultiTimestampLine = true
            }

            // Extract the text after all timestamp tags and remove inline <mm:ss.xx> tags
            val text = trimmed
                .replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "")
                .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                .trim()

            // Skip credit-only metadata lines (e.g. "[00:00.00-1] 作词 : Ana Muller")
            if (LyricSyntaxPatterns.CREDIT_ONLY_TEXT_REGEX.matches(text)) return@forEach

            for (match in matches) {
                val totalMs = LyricSyntaxPatterns.parseMatchToMs(match)
                val formattedTime = formatTimestampMs(totalMs)

                parsedLines.add(ParsedLyricLine(totalMs, formattedTime, text))
            }
        }

        // Require at least one non-blank verse line so empty-timestamp-only files don't count as valid lyrics
        if (parsedLines.none { it.text.isNotBlank() }) {
            return emptyList()
        }

        return if (hasMultiTimestampLine) parsedLines.sortedBy { it.timestampMs } else parsedLines
    }

    override fun parsePlain(content: String): List<ParsedLyricLine> {
        if (content.isBlank()) return emptyList()

        val plainLines = mutableListOf<ParsedLyricLine>()

        content.lineSequence().forEach { rawLine ->
            val cleaned = rawLine
                .trimAndStripBom()
                .replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "")
                .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                .trim()

            if (cleaned.isEmpty()) return@forEach
            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(cleaned)) return@forEach
            if (sectionHeaderRegex.matches(cleaned)) return@forEach
            if (LyricSyntaxPatterns.CREDIT_ONLY_TEXT_REGEX.matches(cleaned)) return@forEach

            plainLines.add(
                ParsedLyricLine(
                    timestampMs = 0L,
                    formattedTime = "00:00.00",
                    text = cleaned
                )
            )
        }

        return plainLines
    }
}
