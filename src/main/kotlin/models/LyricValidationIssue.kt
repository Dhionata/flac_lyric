package models

import java.io.File

/**
 * Represents an issue found during lyric synchronization and compatibility validation.
 *
 * @property lyricFile The lyric (.lrc) file being inspected.
 * @property audioFile The matching audio file, if found.
 * @property issueType Short category of the issue (e.g. NO_SYNC, WRONG_SONG, EXCEEDS_DURATION, LENGTH_MISMATCH).
 * @property description Detailed description explaining why the lyric is considered desynchronized or incompatible.
 */
data class LyricValidationIssue(
    val lyricFile: File,
    val audioFile: File?,
    val issueType: String,
    val description: String,
)
