package comparison.domain.models

/**
 * Encapsulates the live progress, completion status, and output results of a running action.
 */
data class ActionProgressUpdate(
    var isRunning: Boolean = false,
    var actionId: Int = -1,
    var title: String = "",
    var current: Int = 0,
    var max: Int = 0,
    var currentItem: String = "",
    var isCompleted: Boolean = false,
    var success: Boolean = true,
    var errorMessage: String? = null,
    val changes: MutableList<String> = mutableListOf(),
    val errors: MutableList<String> = mutableListOf(),
    var generatedReportPath: String? = null,
)
