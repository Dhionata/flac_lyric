package comparison.application.usecases

import comparison.domain.models.ParsedLyricLine
import comparison.domain.ports.LyricLineParser

/**
 * Use case to parse raw LRC strings into ordered timestamped lyric lines,
 * with fallback support for unsynchronized plain lyrics.
 */
class ParseLyricLinesUseCase(
    private val lineParser: LyricLineParser
) {
    fun execute(content: String): List<ParsedLyricLine> {
        return lineParser.parse(content)
    }

    fun executePlain(content: String): List<ParsedLyricLine> {
        return lineParser.parsePlain(content)
    }
}
