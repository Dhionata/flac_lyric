package comparison.domain.models

/**
 * Incoming request payload to save or confirm a lyric choice.
 *
 * @property lyricPath Target .lrc file path on disk.
 * @property content Lyric content to be written (if LRCLIB chosen).
 * @property customLrcContent Edited or adjusted LRC content sent from the web client.
 * @property choice Chosen action ("LRCLIB" or "LOCAL").
 */
data class SaveLyricRequest(
    val lyricPath: String? = null,
    val content: String? = null,
    val customLrcContent: String? = null,
    val choice: String? = "LRCLIB",
) {
    /**
     * Resolves the effective content to write to the file.
     * Prefers customLrcContent if provided, falling back to content, or empty string.
     */
    fun resolvedContent(): String {
        return when {
            !customLrcContent.isNullOrBlank() -> customLrcContent
            !content.isNullOrBlank() -> content
            else -> ""
        }
    }
}
