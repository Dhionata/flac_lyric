package services

import interfaces.AuditLogService
import java.io.File
import models.AuditDiffLine
import models.AuditEvent

/**
 * Default implementation of [AuditLogService] that computes human-readable before/after diffs
 * and persists both human-readable audit blocks (`flac_lyric_audit.log`) and
 * language-independent structured events (`flac_lyric_events.jsonl`) via [LoggingInitializer].
 */
class AuditLogServiceImpl : AuditLogService {

    private fun currentScope(): AuditRunContext.Scope {
        return AuditRunContext.current() ?: AuditRunContext.Scope(
            runId = AuditRunContext.newRunId("adhoc")
        )
    }

    override fun logOperationStart(actionName: String, options: Map<String, Any?>) {
        val body = buildString {
            appendLine("Operation Started: $actionName")
            if (options.isNotEmpty()) {
                appendLine("Selected Options / Parameters:")
                options.forEach { (k, v) ->
                    appendLine("  • $k = $v")
                }
            }
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:OPERATION_START", body)

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        val cleanMeta = options.entries
            .filter { (_, v) -> v != null && v.toString().isNotBlank() }
            .associate { (k, v) -> k to v.toString() }
        val targetPath = (options["lyricsDir"] ?: options["musicDir"] ?: options["outDir"])?.toString()?.takeIf { it.isNotBlank() }
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "OPERATION",
                eventType = "OPERATION_START",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = actionName,
                messageKey = "audit.event.op_start",
                messageArgs = listOf(actionName),
                fallbackMessage = "Operation started: $actionName",
                targetPath = targetPath,
                targetName = targetPath?.let { File(it).name },
                metadata = cleanMeta
            )
        )
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

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        val msgKey = if (success && errorCount == 0) "audit.event.op_end_success" else "audit.event.op_end_errors"
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "OPERATION",
                eventType = "OPERATION_END",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = actionName,
                messageKey = msgKey,
                messageArgs = listOf(actionName, processedCount.toString(), errorCount.toString()),
                fallbackMessage = "Operation finished: $actionName (processed=$processedCount, errors=$errorCount)",
                summaryStats = mapOf(
                    "success" to success.toString(),
                    "processedCount" to processedCount.toString(),
                    "errorCount" to errorCount.toString()
                ),
                metadata = buildMap {
                    put("status", if (success && errorCount == 0) "SUCCESS" else "COMPLETED_WITH_ERRORS")
                    if (details.isNotEmpty()) {
                        put("detailsPreview", details.take(25).joinToString("\n"))
                        put("totalDetailsCount", details.size.toString())
                    }
                }
            )
        )
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

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        val diffLines = computeStructuredDiffLines(beforeContent, afterContent)
        val beforeLinesCount = beforeContent?.split(Regex("\r?\n"))?.size ?: 0
        val afterLinesCount = afterContent.split(Regex("\r?\n")).size
        val hasBomBefore = beforeContent?.startsWith(LyricSyntaxPatterns.UTF8_BOM) ?: false
        val hasBomAfter = afterContent.startsWith(LyricSyntaxPatterns.UTF8_BOM)
        val cleanMeta = metadata.entries.associate { (k, v) -> k to (v?.toString() ?: "") }
        val detailNote = cleanMeta["appliedFixes"]?.takeIf { it.isNotBlank() }
            ?: cleanMeta["removedTags"]?.takeIf { it.isNotBlank() }
            ?: cleanMeta["choice"]?.takeIf { it.isNotBlank() }
            ?: operation

        val msgKey = when {
            beforeContent == null -> "audit.event.content_created"
            cleanMeta.containsKey("removedTags") -> "audit.event.embedded_removed"
            else -> "audit.event.content_modified"
        }

        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "MODIFIED",
                eventType = "CONTENT_MODIFIED",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = operation,
                messageKey = msgKey,
                messageArgs = listOf(targetFile.name, detailNote),
                fallbackMessage = "$operation: ${targetFile.name} ($detailNote)",
                targetPath = targetFile.absolutePath,
                targetName = targetFile.name,
                summaryStats = mapOf(
                    "changeType" to when {
                        beforeContent == null -> "CREATED"
                        beforeContent == afterContent -> "UNCHANGED"
                        else -> "MODIFIED"
                    },
                    "beforeLines" to beforeLinesCount.toString(),
                    "afterLines" to afterLinesCount.toString(),
                    "beforeChars" to (beforeContent?.length ?: 0).toString(),
                    "afterChars" to afterContent.length.toString(),
                    "bomBefore" to hasBomBefore.toString(),
                    "bomAfter" to hasBomAfter.toString(),
                    "changedPositions" to diffLines.size.toString()
                ),
                diffLines = diffLines,
                metadata = cleanMeta + ("operation" to operation)
            )
        )
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

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        val upperType = actionType.uppercase()
        val (msgKey, args) = when {
            upperType == "MOVE" && afterFile != null ->
                "audit.event.file_moved" to listOf(beforeFile.name, afterFile.parentFile?.name ?: afterFile.absolutePath)
            upperType == "RENAME" && afterFile != null ->
                "audit.event.file_renamed" to listOf(beforeFile.name, afterFile.name)
            upperType == "DELETE_EMPTY_DIR" ->
                "audit.event.dir_deleted" to listOf(beforeFile.name)
            upperType.startsWith("DELETE") ->
                "audit.event.file_deleted" to listOf(beforeFile.name)
            else ->
                "audit.event.file_action" to listOf(actionType, beforeFile.name)
        }
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "FILE_ACTION",
                eventType = "FILE_$upperType",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = scope.actionFallback,
                messageKey = msgKey,
                messageArgs = args,
                fallbackMessage = "$actionType: ${beforeFile.name}${afterFile?.let { " -> ${it.absolutePath}" } ?: ""}",
                targetPath = afterFile?.absolutePath ?: beforeFile.absolutePath,
                targetName = afterFile?.name ?: beforeFile.name,
                beforePath = beforeFile.absolutePath,
                afterPath = afterFile?.absolutePath,
                metadata = buildMap {
                    put("actionType", actionType)
                    if (!details.isNullOrBlank()) put("details", details)
                }
            )
        )
    }

    override fun logAnalysis(
        operation: String,
        targetDir: File?,
        messageKey: String,
        messageArgs: List<Any>,
        metadata: Map<String, Any?>,
    ) {
        val strArgs = messageArgs.map { it.toString() }
        val rendered = Messages.get(messageKey, *strArgs.toTypedArray<Any>())
        val body = buildString {
            appendLine("Analysis: $operation")
            if (targetDir != null) appendLine("Directory: ${targetDir.absolutePath}")
            appendLine("Summary: $rendered")
            if (metadata.isNotEmpty()) {
                appendLine("Metrics: ${metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:ANALYSIS", body)

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "ANALYSIS",
                eventType = "ANALYSIS_SUMMARY",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = operation,
                messageKey = messageKey,
                messageArgs = strArgs,
                fallbackMessage = rendered,
                targetPath = targetDir?.absolutePath,
                targetName = targetDir?.name,
                metadata = metadata.entries.associate { (k, v) -> k to (v?.toString() ?: "") } + ("operation" to operation)
            )
        )
    }

    override fun logFileAnalyzed(
        operation: String,
        targetFile: File,
        messageKey: String,
        messageArgs: List<Any>,
        metadata: Map<String, Any?>,
    ) {
        val strArgs = messageArgs.map { it.toString() }
        val rendered = Messages.get(messageKey, *strArgs.toTypedArray<Any>())
        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "ANALYSIS",
                eventType = "FILE_ANALYZED",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = operation,
                messageKey = messageKey,
                messageArgs = strArgs,
                fallbackMessage = rendered,
                targetPath = targetFile.absolutePath,
                targetName = targetFile.name,
                metadata = metadata.entries.associate { (k, v) -> k to (v?.toString() ?: "") } + ("operation" to operation)
            )
        )
    }

    override fun logSkipped(
        operation: String,
        targetFile: File,
        reasonKey: String,
        reasonArgs: List<Any>,
        metadata: Map<String, Any?>,
    ) {
        val strArgs = reasonArgs.map { it.toString() }
        val reasonText = Messages.get(reasonKey, *strArgs.toTypedArray<Any>())
        val body = buildString {
            appendLine("Skipped: $operation")
            appendLine("Target File: ${targetFile.absolutePath}")
            appendLine("Reason: $reasonText")
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:SKIPPED", body)

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "SKIPPED",
                eventType = "FILE_SKIPPED",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = operation,
                messageKey = "audit.event.file_skipped",
                messageArgs = listOf(targetFile.name, reasonText),
                fallbackMessage = "${targetFile.name}: $reasonText",
                targetPath = targetFile.absolutePath,
                targetName = targetFile.name,
                metadata = metadata.entries.associate { (k, v) -> k to (v?.toString() ?: "") } + mapOf(
                    "operation" to operation,
                    "reasonKey" to reasonKey,
                    "reasonArgs" to strArgs.joinToString("|")
                )
            )
        )
    }

    override fun logError(
        operation: String,
        targetFile: File?,
        error: Throwable?,
        messageKey: String?,
        messageArgs: List<Any>,
        metadata: Map<String, Any?>,
    ) {
        val errorClass = error?.javaClass?.simpleName ?: "Error"
        val rawErrMsg = error?.message?.takeIf { it.isNotBlank() } ?: error?.toString() ?: ""
        val strArgs = messageArgs.map { it.toString() }

        val cleanFileErrMsg = if (targetFile != null) {
            var cleaned = rawErrMsg.ifBlank { errorClass }
            val namePrefix = "${targetFile.name}: "
            while (cleaned.startsWith(namePrefix)) {
                cleaned = cleaned.removePrefix(namePrefix)
            }
            val pathSuffix = " [${targetFile.absolutePath}]"
            if (cleaned.endsWith(pathSuffix)) {
                cleaned = cleaned.removeSuffix(pathSuffix)
            }
            cleaned.ifBlank { errorClass }
        } else {
            rawErrMsg.ifBlank { errorClass }
        }

        val effectiveKey: String
        val effectiveArgs: List<String>
        val fallbackMsg: String
        if (!messageKey.isNullOrBlank()) {
            effectiveKey = messageKey
            effectiveArgs = strArgs
            fallbackMsg = Messages.get(messageKey, *strArgs.toTypedArray<Any>())
        } else if (targetFile != null) {
            effectiveKey = "audit.event.file_error"
            effectiveArgs = listOf(targetFile.name, cleanFileErrMsg)
            fallbackMsg = "${targetFile.name}: $cleanFileErrMsg"
        } else {
            effectiveKey = "audit.event.generic_error"
            effectiveArgs = listOf(operation, cleanFileErrMsg)
            fallbackMsg = "$operation: $cleanFileErrMsg"
        }

        val body = buildString {
            appendLine("Error in Operation: $operation")
            if (targetFile != null) {
                appendLine("Target File: ${targetFile.absolutePath}")
            }
            appendLine("Error Type: $errorClass")
            if (rawErrMsg.isNotBlank()) {
                appendLine("Message: $rawErrMsg")
            }
            if (metadata.isNotEmpty()) {
                appendLine("Metadata: ${metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:ERROR", body)

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "ERROR",
                eventType = "ERROR",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = operation,
                messageKey = effectiveKey,
                messageArgs = effectiveArgs,
                fallbackMessage = fallbackMsg,
                targetPath = targetFile?.absolutePath,
                targetName = targetFile?.name,
                metadata = metadata.entries.associate { (k, v) -> k to (v?.toString() ?: "") } + buildMap {
                    put("operation", operation)
                    put("errorClass", errorClass)
                    if (rawErrMsg.isNotBlank()) put("errorMessage", rawErrMsg)
                }
            )
        )
    }

    override fun logReportGenerated(
        operation: String,
        reportFile: File,
        itemCount: Int,
        metadata: Map<String, Any?>,
    ) {
        val body = buildString {
            appendLine("Report Generated: ${reportFile.absolutePath}")
            appendLine("Operation: $operation")
            appendLine("Items Listed: $itemCount")
        }.trimEnd()
        LoggingInitializer.logAuditEntry("AUDIT:REPORT_GENERATED", body)

        val scope = currentScope()
        val (id, epochMs, ts) = LoggingInitializer.nextEventStamp()
        LoggingInitializer.appendStructuredEvent(
            AuditEvent(
                id = id,
                runId = scope.runId,
                epochMs = epochMs,
                timestamp = ts,
                category = "ANALYSIS",
                eventType = "REPORT_GENERATED",
                actionId = scope.actionId,
                actionKey = scope.actionKey,
                actionFallback = operation,
                messageKey = "audit.event.report_generated",
                messageArgs = listOf(reportFile.name, itemCount.toString()),
                fallbackMessage = "Report generated: ${reportFile.name} ($itemCount item(s))",
                targetPath = reportFile.absolutePath,
                targetName = reportFile.name,
                metadata = metadata.entries.associate { (k, v) -> k to (v?.toString() ?: "") } + mapOf(
                    "operation" to operation,
                    "itemCount" to itemCount.toString()
                )
            )
        )
    }

    companion object {
        private const val MAX_STRUCTURED_DIFF_LINES = 200

        /**
         * Computes line-by-line changed entries for structured diff rendering in the Web UI.
         */
        fun computeStructuredDiffLines(beforeContent: String?, afterContent: String): List<AuditDiffLine> {
            if (beforeContent == null) {
                return afterContent.split(Regex("\r?\n"))
                    .take(MAX_STRUCTURED_DIFF_LINES)
                    .mapIndexed { idx, line ->
                        AuditDiffLine(
                            lineNumber = idx + 1,
                            type = "ADDED",
                            before = null,
                            after = formatVisibleSpecials(line)
                        )
                    }
            }
            if (beforeContent == afterContent) return emptyList()

            val beforeLines = beforeContent.split(Regex("\r?\n"))
            val afterLines = afterContent.split(Regex("\r?\n"))
            val maxLines = maxOf(beforeLines.size, afterLines.size)
            val result = mutableListOf<AuditDiffLine>()

            for (i in 0 until maxLines) {
                val bLine = beforeLines.getOrNull(i)
                val aLine = afterLines.getOrNull(i)
                if (bLine != aLine) {
                    val lineNum = i + 1
                    val diffEntry = when {
                        bLine != null && aLine != null -> AuditDiffLine(
                            lineNumber = lineNum,
                            type = "MODIFIED",
                            before = formatVisibleSpecials(bLine),
                            after = formatVisibleSpecials(aLine)
                        )
                        bLine != null -> AuditDiffLine(
                            lineNumber = lineNum,
                            type = "REMOVED",
                            before = formatVisibleSpecials(bLine),
                            after = null
                        )
                        else -> AuditDiffLine(
                            lineNumber = lineNum,
                            type = "ADDED",
                            before = null,
                            after = formatVisibleSpecials(aLine.orEmpty())
                        )
                    }
                    result.add(diffEntry)
                    if (result.size >= MAX_STRUCTURED_DIFF_LINES) break
                }
            }
            return result
        }

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

