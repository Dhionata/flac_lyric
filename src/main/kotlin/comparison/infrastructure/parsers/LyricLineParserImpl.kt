package comparison.infrastructure.parsers

import comparison.domain.models.ParsedLyricLine
import comparison.domain.ports.LyricLineParser

/**
 * Robust parser for synchronized LRC file lines and unsynchronized plain lyrics.
 * Supports standard [mm:ss.xx], [mm:ss.xxx], [mm:ss:xx], [mm:ss,xx], and [mm:ss.xx-1] timestamps,
 * as well as multiple timestamps per line.
 * Filters out metadata header tags (e.g. [ar:], [ti:]) and standalone credit stub lines (e.g. 作词 / 作曲).
 */
class LyricLineParserImpl : LyricLineParser {

    private val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.,:](\d{1,3}))?(?:-\d+)?\]""")
    private val inlineWordTagRegex = Regex("""<\d{1,2}:\d{2}(?:[.,:]\d{1,3})?>""")
    private val headerTagRegex = Regex("""^\[[a-zA-Z]+:[^\]]*\].*""")
    private val sectionHeaderRegex = Regex("""^\[[^\]]+\]$""")
    private val creditOnlyLineRegex = Regex(
        """^(?:作词|作曲|编曲|制作人|混音|母带|录音|吉他|贝斯|鼓|和声|词|曲|Lyrics\s*by|Composed\s*by|Arranged\s*by|Produced\s*by)\s*[:：].*$""",
        RegexOption.IGNORE_CASE
    )

    override fun parse(content: String): List<ParsedLyricLine> {
        if (content.isBlank()) return emptyList()

        val parsedLines = mutableListOf<ParsedLyricLine>()
        var hasMultiTimestampLine = false

        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            // Ignore header tags like [ti:Title], [ar:Artist], [al:Album], [length:03:00], etc.
            if (headerTagRegex.matches(trimmed)) return@forEach

            val matches = timestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach
            if (matches.size > 1) {
                hasMultiTimestampLine = true
            }

            // Extract the text after all timestamp tags and remove inline <mm:ss.xx> tags
            val text = trimmed
                .replace(timestampRegex, "")
                .replace(inlineWordTagRegex, "")
                .trim()

            // Skip credit-only metadata lines (e.g. "[00:00.00-1] 作词 : Ana Muller")
            if (creditOnlyLineRegex.matches(text)) return@forEach

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val millisRaw = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (millisRaw.length) {
                    1 -> (millisRaw.toLongOrNull() ?: 0L) * 100
                    2 -> (millisRaw.toLongOrNull() ?: 0L) * 10
                    3 -> millisRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }

                val totalMs = (minutes * 60 + seconds) * 1000 + millis
                val formattedTime = String.format("%02d:%02d", minutes, seconds)

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
                .replace(timestampRegex, "")
                .replace(inlineWordTagRegex, "")
                .trim()

            if (cleaned.isEmpty()) return@forEach
            if (headerTagRegex.matches(cleaned)) return@forEach
            if (sectionHeaderRegex.matches(cleaned)) return@forEach
            if (creditOnlyLineRegex.matches(cleaned)) return@forEach

            plainLines.add(
                ParsedLyricLine(
                    timestampMs = 0L,
                    formattedTime = "00:00",
                    text = cleaned
                )
            )
        }

        return plainLines
    }
}
