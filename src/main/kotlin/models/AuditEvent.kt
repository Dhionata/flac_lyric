package models

/**
 * Represents a single changed line inside a before/after content modification event.
 */
data class AuditDiffLine(
    val lineNumber: Int,
    val type: String, // "MODIFIED", "ADDED", "REMOVED"
    val before: String? = null,
    val after: String? = null,
)

/**
 * Machine-readable, language-independent structured audit/activity event persisted in `logs/flac_lyric_events.jsonl`.
 * Storing [messageKey] and [messageArgs] allows the UI and APIs to render the complete activity timeline
 * in whatever language the user selects at runtime.
 */
data class AuditEvent(
    val id: String,
    val runId: String,
    val epochMs: Long,
    val timestamp: String,
    val category: String, // OPERATION, ANALYSIS, MODIFIED, FILE_ACTION, SKIPPED, ERROR
    val eventType: String, // OPERATION_START, OPERATION_END, ANALYSIS_SUMMARY, CONTENT_MODIFIED, FILE_MOVE, FILE_RENAME, FILE_DELETE, FILE_DELETE_EMPTY_DIR, FILE_SKIPPED, ERROR, REPORT_GENERATED
    val actionId: Int? = null,
    val actionKey: String? = null,
    val actionFallback: String? = null,
    val messageKey: String,
    val messageArgs: List<String> = emptyList(),
    val fallbackMessage: String = "",
    val targetPath: String? = null,
    val targetName: String? = null,
    val beforePath: String? = null,
    val afterPath: String? = null,
    val summaryStats: Map<String, String> = emptyMap(),
    val diffLines: List<AuditDiffLine> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Summary of a distinct execution run found in the structured events log.
 */
data class AuditRunSummary(
    val runId: String,
    val actionId: Int? = null,
    val actionKey: String? = null,
    val actionFallback: String? = null,
    val startedAt: String,
    val epochMs: Long,
    val eventCount: Int,
    val errorCount: Int,
)

/**
 * Paginated query result for structured audit events.
 */
data class AuditEventsQueryResult(
    val events: List<AuditEvent>,
    val totalMatching: Int,
    val totalEvents: Int,
    val countsByCategory: Map<String, Int>,
    val runs: List<AuditRunSummary>,
)

/**
 * Metadata descriptor for a log file stored in `logs/`.
 */
data class LogFileEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isCurrentExecution: Boolean = false,
    val isAuditLog: Boolean = false,
    val isEventsLog: Boolean = false,
)

/**
 * Result of reading the tail (or filtered lines) of a raw log file in `logs/`.
 */
data class LogFileReadResult(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lines: List<String>,
    val totalLinesShown: Int,
    val truncated: Boolean,
)
