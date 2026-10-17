package comparison.domain.models

/**
 * Represents a single parsed lyric line with its timestamp in milliseconds,
 * human-readable formatted timestamp, and lyric text.
 */
data class ParsedLyricLine(
    val timestampMs: Long,
    val formattedTime: String,
    val text: String,
    val timeMs: Long = timestampMs,
)
