package services

import interfaces.AuditLogService
import java.io.File

/**
 * Default implementation of [AuditLogService] that computes human-readable before/after diffs
 * and persists structured audit entries via [LoggingInitializer] into `logs/`.
 *
 * Adheres to DRY and SRP by centralizing diff computation and structured audit block formatting.
 */
class AuditLogServiceImpl : AuditLogService {

    override fun logOperationStart(actionName: String, options: Map<String, Any?>) {
        val body = buildString {
            appendLine("Operation Started: $actionName")
            if (options.isNotEmpty()) {
                appendLine("Selected Options / Parameters:")
                options.forEach { [k, v] ->
                    appendLine("  • $k = $v")
                }
            }
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:OPERATION_START", body)
    }

    override fun logOperationEnd(
        actionName: String,
        success: Boolean,
        processedCount: Int,
        errorCount: Int,
        details: List<String>,
    ) {
        val body = buildString {
            appendLine("Operation Finished: $actionName")
            appendLine("Status: ${if (success) "SUCCESS" else "COMPLETED_WITH_ERRORS"} (Processed: $processedCount, Errors: $errorCount)")
            if (details.isNotEmpty()) {
                appendLine("Summary Details (${details.size}):")
                details.forEach { item ->
                    appendLine("  • $item")
                }
            }
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:OPERATION_END", body)
    }

    override fun logContentModification(
        operation: String,
        targetFile: File,
        beforeContent: String?,
        afterContent: String,
        metadata: Map<String, Any?>,
    ) {
        val body = buildString {
            appendLine("Operation: $operation")
            appendLine("Target File: ${targetFile.absolutePath}")
            if (metadata.isNotEmpty()) {
                appendLine("Metadata: ${metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            append(buildBeforeAfterDiffSummary(beforeContent, afterContent))
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:CONTENT_MODIFIED", body)
    }

    override fun logFileAction(
        actionType: String,
        beforeFile: File,
        afterFile: File?,
        details: String?,
    ) {
        val body = buildString {
            appendLine("File Action: $actionType")
            appendLine("  BEFORE: ${beforeFile.absolutePath}")
            appendLine("  AFTER : ${afterFile?.absolutePath ?: "(DELETED)"}")
            if (!details.isNullOrBlank()) {
                appendLine("  INFO  : $details")
            }
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:FILE_$actionType", body)
    }

    companion object {
        /**
         * Builds a clear, human-readable BEFORE vs AFTER comparison block between [beforeContent] and [afterContent],
         * highlighting line-level changes as well as invisible UTF-8 BOM or whitespace differences.
         */
        fun buildBeforeAfterDiffSummary(beforeContent: String?, afterContent: String): String {
            if (beforeContent == null) {
                val afterLines = afterContent.split(Regex("\r?\n"))
                return buildString {
                    appendLine("Change Type: NEW FILE CREATED (${afterLines.size} lines, ${afterContent.length} chars)")
                    appendLine("--- AFTER (New Content) ---")
                    afterLines.forEachIndexed { idx, line ->
                        appendLine("  + [L${idx + 1}] $line")
                    }
                }
            }

            if (beforeContent == afterContent) {
                return "Change Type: NO CONTENT CHANGE (Identical)\n"
            }

            val hasBomBefore = beforeContent.startsWith(LyricSyntaxPatterns.UTF8_BOM)
            val hasBomAfter = afterContent.startsWith(LyricSyntaxPatterns.UTF8_BOM)
            val beforeLines = beforeContent.split(Regex("\r?\n"))
            val afterLines = afterContent.split(Regex("\r?\n"))

            return buildString {
                appendLine(
                    "Change Type: MODIFIED (Lines: ${beforeLines.size} -> ${afterLines.size}, " +
                            "Chars: ${beforeContent.length} -> ${afterContent.length}, " +
                            "UTF-8 BOM: $hasBomBefore -> $hasBomAfter)"
                )

                val maxLines = maxOf(beforeLines.size, afterLines.size)
                var changedLineCount = 0
                val diffBuilder = StringBuilder()

                for (i in 0 until maxLines) {
                    val bLine = beforeLines.getOrNull(i)
                    val aLine = afterLines.getOrNull(i)
                    if (bLine != aLine) {
                        changedLineCount++
                        val lineNum = i + 1
                        if (bLine != null && aLine != null) {
                            diffBuilder.appendLine("  Line $lineNum:")
                            diffBuilder.appendLine("    - BEFORE: ${formatVisibleSpecials(bLine)}")
                            diffBuilder.appendLine("    + AFTER : ${formatVisibleSpecials(aLine)}")
                        } else if (bLine != null) {
                            diffBuilder.appendLine("  Line $lineNum (REMOVED):")
                            diffBuilder.appendLine("    - BEFORE: ${formatVisibleSpecials(bLine)}")
                        } else if (aLine != null) {
                            diffBuilder.appendLine("  Line $lineNum (ADDED):")
                            diffBuilder.appendLine("    + AFTER : ${formatVisibleSpecials(aLine)}")
                        }
                    }
                }

                if (changedLineCount > 0) {
                    appendLine("Line-by-Line Changes ($changedLineCount line position(s) affected):")
                    append(diffBuilder)
                } else {
                    appendLine("Line-by-Line Changes: Line endings Only (CRLF <-> LF)")
                }
            }
        }

        private fun formatVisibleSpecials(line: String): String {
            val withBomVisible = if (line.startsWith(LyricSyntaxPatterns.UTF8_BOM)) {
                "<UTF8-BOM>" + line.removePrefix(LyricSyntaxPatterns.UTF8_BOM.toString())
            } else {
                line
            }
            return if (withBomVisible.endsWith(" ") || withBomVisible.endsWith("\t")) {
                val trailingCount = withBomVisible.takeLastWhile { it == ' ' || it == '\t' }.length
                "$withBomVisible <+$trailingCount trailing-space(s)>"
            } else {
                withBomVisible
            }
        }
    }
}
