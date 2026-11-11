package interfaces

import java.io.File

/**
 * Abstraction for recording comprehensive, timestamped audit trails of user operations,
 * selected configuration options, file system mutations (moves/renames/deletions),
 * and line-by-line before/after content modifications.
 *
 * Adheres to SOLID principles:
 * - SRP: Dedicated exclusively to audit trail generation and formatting.
 * - OCP / DIP: High-level services depend on this interface rather than file system logger details.
 */
interface AuditLogService {

    /**
     * Records the start of a user-triggered operation along with all selected options/parameters.
     *
     * @param actionName Identifier or title of the operation (e.g., "Option 3 - Clean LRC", "Comparator Save").
     * @param options Map of option names to their active values selected by the user.
     */
    fun logOperationStart(actionName: String, options: Map<String, Any?> = emptyMap())

    /**
     * Records the completion of a user-triggered operation with a summary of results.
     *
     * @param actionName Identifier or title of the operation.
     * @param success True if the operation completed without fatal errors.
     * @param processedCount Number of items/files modified or processed.
     * @param errorCount Number of errors encountered.
     * @param details Optional list of summary lines or generated report paths.
     */
    fun logOperationEnd(
        actionName: String,
        success: Boolean,
        processedCount: Int,
        errorCount: Int = 0,
        details: List<String> = emptyList(),
    )

    /**
     * Records a line-by-line before-and-after diff whenever a `.lrc` file's content is created or modified.
     *
     * @param operation Context of the modification (e.g., "Option 3 Bulk Clean", "Comparator Save [LRCLIB]").
     * @param targetFile The file being created or modified.
     * @param beforeContent The previous content of the file (`null` if the file did not exist).
     * @param afterContent The new content written to the file.
     * @param metadata Optional metadata tags describing applied rules or source info.
     */
    fun logContentModification(
        operation: String,
        targetFile: File,
        beforeContent: String?,
        afterContent: String,
        metadata: Map<String, Any?> = emptyMap(),
    )

    /**
     * Records a file-system structural change (move, rename, or deletion) with before and after paths.
     *
     * @param actionType Type of file action (`MOVE`, `RENAME`, `DELETE`).
     * @param beforeFile Original file location before the action.
     * @param afterFile New file location after the action (`null` when deleted).
     * @param details Optional additional context.
     */
    fun logFileAction(
        actionType: String,
        beforeFile: File,
        afterFile: File? = null,
        details: String? = null,
    )
}
