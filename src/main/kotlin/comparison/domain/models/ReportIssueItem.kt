package comparison.domain.models

import java.io.File

/**
 * Represents a single track entry parsed from an issues report.
 *
 * @property id Sequential identifier for UI referencing.
 * @property fileName Name of the .lrc file.
 * @property lyricFile The local .lrc file on disk.
 * @property audioFile The corresponding audio file (.flac) on disk, if located.
 * @property issues List of issues flagged for this track.
 * @property status Current resolution status ("PENDING", "SAVED_LRCLIB", "KEPT_LOCAL").
 */
data class ReportIssueItem(
    val id: Int,
    val fileName: String,
    val lyricFile: File,
    val audioFile: File?,
    val issues: List<String>,
    var status: String = "PENDING",
)
