package comparison.domain.models

/**
 * Encapsulates the user parameters required to execute an application action (0-7)
 * through the web user interface.
 */
data class ActionExecutionRequest(
    val actionId: Int,
    val musicDir: String? = null,
    val lyricsDir: String? = null,
    val outDir: String? = null,
    val isFullAnalysis: Boolean = false,
    val moveFiles: Boolean = true,
    val renameFiles: Boolean = true
)
