package comparison.infrastructure.parsers

import comparison.domain.models.ParsedLyricLine
import comparison.domain.ports.LyricLineParser

/**
 * Robust parser for synchronized LRC file lines.
 * Supports standard [mm:ss.xx] and [mm:ss.xxx] timestamps, as well as multiple timestamps per line.
 * Filters out metadata header tags (e.g. [ar:], [ti:]).
 */
class LyricLineParserImpl : LyricLineParser {

    private val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")

    override fun parse(content: String): List<ParsedLyricLine> {
        if (content.isBlank()) return emptyList()

        val parsedLines = mutableListOf<ParsedLyricLine>()

        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            // Ignore header tags like [ti:Title], [ar:Artist], [al:Album], [length:03:00], etc.
            if (trimmed.matches(Regex("""^\[[a-zA-Z]+:[^\]]*\].*"""))) return@forEach

            val matches = timestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach

            // Extract the text after all timestamp tags
            val text = trimmed.replace(timestampRegex, "").trim()

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

        return parsedLines.sortedBy { it.timestampMs }
    }
}
