package comparison.domain.models

/**
 * Result indicating the outcome of a lyric save/update operation.
 *
 * @property success Indicates whether the operation succeeded.
 * @property message Informational message or error description.
 * @property targetPath Path to the target file.
 */
data class SaveLyricResult(
    val success: Boolean,
    val message: String,
    val targetPath: String,
)
