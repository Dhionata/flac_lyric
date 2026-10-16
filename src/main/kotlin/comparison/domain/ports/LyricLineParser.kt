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

    /**
     * Parses unsynchronized (plain) lyrics into [ParsedLyricLine] entries at 00:00
     * so they can be manually synchronized in the web comparator.
     *
     * @param content Raw text of plain lyrics.
     * @return List of parsed plain verse lines.
     */
    fun parsePlain(content: String): List<ParsedLyricLine> = emptyList()
}
