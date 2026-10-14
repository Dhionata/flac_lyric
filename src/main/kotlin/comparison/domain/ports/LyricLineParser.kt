package comparison.domain.ports

import comparison.domain.models.ParsedLyricLine

/**
 * Port for parsing raw LRC content strings into structured [ParsedLyricLine] objects.
 */
interface LyricLineParser {
    /**
     * Parses the given LRC string content into chronological [ParsedLyricLine] entries.
     *
     * @param content Raw text of the .lrc file.
     * @return Ordered list of parsed lines.
     */
    fun parse(content: String): List<ParsedLyricLine>
}
