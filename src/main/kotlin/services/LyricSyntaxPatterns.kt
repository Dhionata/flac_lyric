package services

/**
 * Centralized regular expressions and syntax helpers for LRC files.
 * Shared across [LyricSyncValidatorImpl], [LyricContentCleanerImpl], and [comparison.infrastructure.parsers.LyricLineParserImpl]
 * to adhere to the DRY (Don't Repeat Yourself) principle.
 */
object LyricSyntaxPatterns {

    /** UTF-8 Byte Order Mark character. */
    const val UTF8_BOM: Char = '\uFEFF'

    /** Standard and extended LRC line timestamp pattern: `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss,xxx]`, `[mm:ss:xx]`, `[mm:ss.xx-1]`. */
    val TIMESTAMP_REGEX: Regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.,:](\d{1,3}))?(?:-\d+)?\]""")

    /** Inline word-by-word (Enhanced LRC / karaoke) timestamp pattern: `<mm:ss.xx>` or `<mm:ss.xxx>`. */
    val INLINE_WORD_TAG_REGEX: Regex = Regex("""<\d{1,2}:\d{2}(?:[.,:]\d{1,3})?>""")

    /** Standard LRC metadata header line pattern: `[ar:...]`, `[ti:...]`, `[al:...]`, `[by:...]`, `[id:...]`, `[offset:...]`, `[length:...]`, `[re:...]`, `[ve:...]`. */
    val HEADER_TAG_LINE_REGEX: Regex = Regex("""^\[(?:ar|ti|al|by|id|offset|length|re|ve|tool|au|la):[^\]]*\].*$""", RegexOption.IGNORE_CASE)

    /** Generic header tag line pattern used when stripping non-verse metadata headers (excluding `[bg:...]`). */
    val GENERIC_HEADER_TAG_REGEX: Regex = Regex("""^\[(?!bg:)[a-zA-Z]+:[^\]]*\].*$""")

    /** Artist metadata header pattern `[ar:...]`. */
    val AR_TAG_REGEX: Regex = Regex("""^\[ar:(.*?)\]""", RegexOption.IGNORE_CASE)

    /** Title metadata header pattern `[ti:...]`. */
    val TI_TAG_REGEX: Regex = Regex("""^\[ti:(.*?)\]""", RegexOption.IGNORE_CASE)

    /** Length metadata header pattern `[length: mm:ss]`. */
    val LENGTH_TAG_REGEX: Regex = Regex("""^\[length:\s*(\d{1,2}):(\d{2})(?:\.\d+)?\]""", RegexOption.IGNORE_CASE)

    /** Speaker prefix pattern (`v1:`, `v2:`, `v3:`, `v4:`) at the start of verse text. */
    val SPEAKER_PREFIX_REGEX: Regex = Regex("""(?i)\bv[1-4]\s*:\s*""")

    /** Background vocal block pattern `[bg: ...]` (closed or unclosed at end of line). */
    val BG_BLOCK_CLOSED_REGEX: Regex = Regex("""(?i)\[bg:\s*([^\]]*)\]""")
    val BG_BLOCK_UNCLOSED_REGEX: Regex = Regex("""(?i)^\[bg:\s*(.+)$""")

    /** Standalone section header in brackets or parentheses, e.g. `[Chorus]`, `[Hook]`, `(Verse)`, `(Chorus)`. */
    val SECTION_HEADER_ONLY_REGEX: Regex = Regex(
        """^(?:\[(?:verse|chorus|bridge|intro|outro|refrão|refrao|verso|ponte|solo|hook|pre-chorus|instrumental)[^\]]*\]|\((?:verse|chorus|bridge|intro|outro|refrão|refrao|verso|ponte|solo|hook|pre-chorus)\))$""",
        RegexOption.IGNORE_CASE
    )

    /** Credit-only verse text pattern (Chinese/English composition/production credits or redundant Artist/Title/Album header lines at 00:00). */
    val CREDIT_ONLY_TEXT_REGEX: Regex = Regex(
        """^(?:作词|作曲|编曲|制作人|混音|母带|录音|吉他|贝斯|鼓|和声|词|曲|Lyrics\s*by|Composed\s*by|Arranged\s*by|Produced\s*by|Synced\s*by|Edited\s*by|Transcribed\s*by)\s*[:：].*$""",
        RegexOption.IGNORE_CASE
    )

    /** Metadata stub repeated as a synced line at 00:00 (e.g. `Artist: Carl Douglas`, `Title: ...`, `Album: ...`). */
    val ZERO_TIME_META_STUB_REGEX: Regex = Regex(
        """^(?:Artist|Title|Album|Artista|Título|Titulo|Álbum|Album)\s*:\s*.+$""",
        RegexOption.IGNORE_CASE
    )

    /** Watermark, URL, or editor signature inside a synced verse or header (e.g. `Daddyyankee.com`, `.com`, `by WhiteNights (@gmx.net)`, `[re:www.megalobiz.com...]`). */
    val WATERMARK_HEADER_REGEX: Regex = Regex("""^\[re:\s*(?:www\.|http).*?\]$""", RegexOption.IGNORE_CASE)
    val WATERMARK_VERSE_REGEX: Regex = Regex(
        """^(?:(?:https?://|www\.)\S+|\S+\.(?:com|net|org)\b.*|\.(?:com|net|org)|by\s+[^\n]*\(@[^\n]+\).*)$""",
        RegexOption.IGNORE_CASE
    )

    /** Encoding corruption pattern: Unicode replacement character U+FFFD or classic UTF-8-as-Latin1 mojibake sequences. */
    val ENCODING_CORRUPTION_REGEX: Regex = Regex("""\uFFFD|Ã[£©§³ªáéíóúâêô¡º­¢´µ]|â€™|â€œ|â€""")

    /**
     * Strips leading UTF-8 BOM (`\uFEFF`) and surrounding whitespace from a line.
     */
    fun String.trimAndStripBom(): String = this.trim().trimStart(UTF8_BOM).trim()

    /**
     * Parses a regex match from [TIMESTAMP_REGEX] into total milliseconds.
     */
    fun parseMatchToMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val millisRaw = match.groupValues.getOrNull(3).orEmpty()
        val millis = when (millisRaw.length) {
            1 -> (millisRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (millisRaw.toLongOrNull() ?: 0L) * 10L
            3 -> millisRaw.toLongOrNull() ?: 0L
            else -> 0L
        }
        return (minutes * 60L + seconds) * 1000L + millis
    }

    /**
     * Parses a regex match from [TIMESTAMP_REGEX] into total seconds as Double.
     */
    fun parseMatchToSeconds(match: MatchResult): Double = parseMatchToMs(match) / 1000.0
}
