package models

/**
 * Result of running [interfaces.LyricContentCleaner] on a `.lrc` content string.
 *
 * @property originalContent The input `.lrc` text before cleaning.
 * @property cleanedContent The normalized `.lrc` text after applying the requested cleanup rules.
 * @property modified True if the content changed as a result of cleaning.
 * @property appliedFixes Human-readable tags of the cleanup rules that modified the content.
 */
data class LyricCleanResult(
    val originalContent: String,
    val cleanedContent: String,
    val modified: Boolean,
    val appliedFixes: List<String>,
)
