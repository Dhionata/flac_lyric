package services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.FileHandler
import java.util.logging.Logger
import models.AuditEvent
import models.AuditEventsQueryResult
import models.AuditRunSummary
import models.LogFileEntry
import models.LogFileReadResult

/**
 * Responsible for configuring file system log output, creating a dedicated `.log` file per execution
 * inside `logs/`, logging HTTP requests/responses with pretty-printed JSON,
 * and persisting queryable, language-independent structured audit events (`flac_lyric_events.jsonl`).
 */
object LoggingInitializer {
    private var initialized = false
    private val fileWriteLock = Any()
    private val eventIdCounter = AtomicLong(0)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val entryTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    @Volatile
    var currentExecutionLogFile: File? = null
        private set

    @Volatile
    var currentEventsLogFile: File? = null
        private set

    private fun defaultLogsDirectory(): File {
        val override = System.getProperty("flac.lyric.log.dir")?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File("logs")
    }

    fun getLogsDirectory(): File {
        val dir = currentExecutionLogFile?.parentFile ?: defaultLogsDirectory()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Configures both a per-execution UTF-8 log file (`logs/flac_lyric_YYYY-MM-DD_HH-mm-ss.log`),
     * the rotating `logs/flac_lyric.log`, and the structured `logs/flac_lyric_events.jsonl` store.
     *
     * @param logDir Directory to store logs. Defaults to `logs/` (or `-Dflac.lyric.log.dir` if set).
     * @param maxSizeBytes Maximum file size before rotation. Defaults to 10MB.
     * @param fileCount Number of rotating log files. Defaults to 5.
     */
    fun init(
        logDir: File = defaultLogsDirectory(),
        maxSizeBytes: Int = 10 * 1024 * 1024,
        fileCount: Int = 5,
    ) {
        synchronized(fileWriteLock) {
            if (initialized && currentExecutionLogFile != null) return
            initialized = true

            try {
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }

                val execTimestamp = LocalDateTime.now().format(timestampFormatter)
                val execFile = File(logDir, "flac_lyric_$execTimestamp.log")
                currentExecutionLogFile = execFile
                currentEventsLogFile = File(logDir, "flac_lyric_events.jsonl")

                val header = buildString {
                    appendLine("================================================================================")
                    appendLine(Messages.get("log.exec.header_started", LocalDateTime.now().format(entryTimestampFormatter)))
                    appendLine(Messages.get("log.exec.header_file", execFile.absolutePath))
                    appendLine("================================================================================")
                }
                execFile.writeText(header, StandardCharsets.UTF_8)

                val rootLogger = Logger.getLogger("")

                val execFileHandler = FileHandler(execFile.absolutePath, true).apply {
                    encoding = StandardCharsets.UTF_8.name()
                    formatter = StandardLogFormatter()
                }
                rootLogger.addHandler(execFileHandler)

                val rotatingLogFile = File(logDir, "flac_lyric.log")
                val rotatingHandler = FileHandler(rotatingLogFile.absolutePath, maxSizeBytes, fileCount, true).apply {
                    encoding = StandardCharsets.UTF_8.name()
                    formatter = StandardLogFormatter()
                }
                rootLogger.addHandler(rotatingHandler)
            } catch (e: Exception) {
                System.err.println(Messages.get("log.init.error", e.message ?: e.toString()))
            }
        }
    }

    /**
     * Generates a unique monotonic event ID and current formatted timestamp pair `(id, epochMs, formattedTs)`.
     */
    fun nextEventStamp(): Triple<String, Long, String> {
        val nowMs = System.currentTimeMillis()
        val seq = eventIdCounter.incrementAndGet()
        val nowStr = LocalDateTime.now().format(entryTimestampFormatter)
        return Triple("evt-$nowMs-$seq", nowMs, nowStr)
    }

    /**
     * Pretty-prints a raw string if it is valid JSON (Object or Array), or returns it as-is otherwise.
     */
    fun prettyFormatJson(rawBody: String?): String {
        if (rawBody.isNullOrBlank()) return Messages.get("log.exec.empty_body")
        val trimmed = rawBody.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed
        }
        return try {
            val parsed = JsonParser.parseString(trimmed)
            prettyGson.toJson(parsed)
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * Appends a detailed HTTP request and response entry (with pretty-printed JSON)
     * to the current execution `.log` file inside `logs/`.
     */
    fun logHttpExchange(
        category: String,
        method: String,
        url: String,
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        responseStatus: Int? = null,
        durationMs: Long? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String? = null,
        error: String? = null,
    ) {
        if (!initialized || currentExecutionLogFile == null) {
            init()
        }
        val targetFile = currentExecutionLogFile ?: return

        val nowStr = LocalDateTime.now().format(entryTimestampFormatter)
        val entry = buildString {
            appendLine("--------------------------------------------------------------------------------")
            appendLine("[$nowStr] [$category] $method $url")
            if (requestHeaders.isNotEmpty()) {
                appendLine("  Request Headers: $requestHeaders")
            }
            if (!requestBody.isNullOrBlank()) {
                appendLine("  Request Body:")
                prettyFormatJson(requestBody).lineSequence().forEach { line ->
                    appendLine("    $line")
                }
            }
            if (responseStatus != null) {
                val timePart = if (durationMs != null) " (${durationMs}ms)" else ""
                appendLine("  Response Status: HTTP $responseStatus$timePart")
            }
            if (responseHeaders.isNotEmpty()) {
                appendLine("  Response Headers: $responseHeaders")
            }
            if (error != null) {
                appendLine("  Error: $error")
            }
            if (responseBody != null) {
                appendLine("  ${Messages.get("log.exec.response_body_json")}")
                prettyFormatJson(responseBody).lineSequence().forEach { line ->
                    appendLine("    $line")
                }
            }
            appendLine("--------------------------------------------------------------------------------")
        }

        synchronized(fileWriteLock) {
            try {
                targetFile.appendText(entry, StandardCharsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Appends a structured, timestamped audit trail block to both the current execution `.log` file
     * (`logs/flac_lyric_YYYY-MM-DD_HH-mm-ss.log`) and the persistent audit log (`logs/flac_lyric_audit.log`).
     */
    fun logAuditEntry(
        category: String,
        body: String,
    ) {
        if (!initialized || currentExecutionLogFile == null) {
            init()
        }
        val targetFile = currentExecutionLogFile ?: return
        val auditFile = File(targetFile.parentFile ?: File("logs"), "flac_lyric_audit.log")

        val nowStr = LocalDateTime.now().format(entryTimestampFormatter)
        val entry = buildString {
            appendLine("================================================================================")
            appendLine("[$nowStr] [$category]")
            body.lineSequence().forEach { line ->
                appendLine("  $line")
            }
            appendLine("================================================================================")
        }

        synchronized(fileWriteLock) {
            try {
                targetFile.appendText(entry, StandardCharsets.UTF_8)
                auditFile.appendText(entry, StandardCharsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Appends a structured JSONL [AuditEvent] to `logs/flac_lyric_events.jsonl`.
     */
    fun appendStructuredEvent(
        event: AuditEvent,
        eventsFileOverride: File? = null,
    ) {
        if (eventsFileOverride == null && (!initialized || currentEventsLogFile == null)) {
            init()
        }
        val eventsFile = eventsFileOverride ?: currentEventsLogFile ?: File(getLogsDirectory(), "flac_lyric_events.jsonl")
        val jsonLine = compactGson.toJson(event) + "\n"

        synchronized(fileWriteLock) {
            try {
                eventsFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                eventsFile.appendText(jsonLine, StandardCharsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Queries structured events from `logs/flac_lyric_events.jsonl` in reverse chronological order (newest first),
     * computing category counts and distinct execution run summaries.
     */
    fun queryStructuredEvents(
        category: String? = null,
        actionId: Int? = null,
        runId: String? = null,
        search: String? = null,
        limit: Int = 150,
        offset: Int = 0,
        eventsFileOverride: File? = null,
    ): AuditEventsQueryResult {
        val eventsFile = eventsFileOverride ?: currentEventsLogFile ?: File(getLogsDirectory(), "flac_lyric_events.jsonl")
        if (!eventsFile.exists() || !eventsFile.isFile) {
            return AuditEventsQueryResult(
                events = emptyList(),
                totalMatching = 0,
                totalEvents = 0,
                countsByCategory = emptyMap(),
                runs = emptyList()
            )
        }

        val allEvents = mutableListOf<AuditEvent>()
        synchronized(fileWriteLock) {
            try {
                eventsFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            try {
                                val parsed = compactGson.fromJson(line, AuditEvent::class.java)
                                if (parsed != null) {
                                    allEvents.add(parsed)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Most recent first
        allEvents.reverse()

        // Run summaries (preserving latest-run-first order)
        val runBuckets = linkedMapOf<String, MutableList<AuditEvent>>()
        for (ev in allEvents) {
            runBuckets.getOrPut(ev.runId) { mutableListOf() }.add(ev)
        }
        val runs = runBuckets.map { (id, list) ->
            val oldest = list.last()
            val withAction = list.firstOrNull { it.actionId != null } ?: oldest
            AuditRunSummary(
                runId = id,
                actionId = withAction.actionId,
                actionKey = withAction.actionKey,
                actionFallback = withAction.actionFallback,
                startedAt = oldest.timestamp,
                epochMs = oldest.epochMs,
                eventCount = list.size,
                errorCount = list.count { it.category == "ERROR" }
            )
        }

        val countsByCategory = allEvents.groupingBy { it.category }.eachCount()

        val catFilter = category?.trim()?.takeIf { it.isNotBlank() && !it.equals("ALL", ignoreCase = true) }
        val runFilter = runId?.trim()?.takeIf { it.isNotBlank() && !it.equals("ALL", ignoreCase = true) }
        val q = search?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        val filtered = allEvents.filter { ev ->
            if (catFilter != null && !ev.category.equals(catFilter, ignoreCase = true)) return@filter false
            if (actionId != null && ev.actionId != actionId) return@filter false
            if (runFilter != null && ev.runId != runFilter) return@filter false
            if (q != null) {
                val localizedMsg = renderEventMessage(ev).lowercase()
                val haystack = buildString {
                    append(ev.targetName.orEmpty()).append(' ')
                    append(ev.targetPath.orEmpty()).append(' ')
                    append(ev.beforePath.orEmpty()).append(' ')
                    append(ev.afterPath.orEmpty()).append(' ')
                    append(ev.fallbackMessage).append(' ')
                    append(localizedMsg).append(' ')
                    append(ev.eventType).append(' ')
                    ev.metadata.values.forEach { append(it).append(' ') }
                }.lowercase()
                if (!haystack.contains(q)) return@filter false
            }
            true
        }

        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 1000)
        val page = if (safeOffset >= filtered.size) emptyList() else filtered.subList(safeOffset, minOf(filtered.size, safeOffset + safeLimit))

        return AuditEventsQueryResult(
            events = page,
            totalMatching = filtered.size,
            totalEvents = allEvents.size,
            countsByCategory = countsByCategory,
            runs = runs
        )
    }

    /**
     * Renders an [AuditEvent]'s localized message in the currently active [Messages] locale.
     */
    fun renderEventMessage(event: AuditEvent): String {
        val effectiveArgs = if (event.messageKey == "audit.event.file_skipped" && event.metadata["reasonKey"] != null) {
            val reasonKey = event.metadata["reasonKey"]!!
            val reasonArgs = event.metadata["reasonArgs"]?.takeIf { it.isNotEmpty() }?.split("|")?.toTypedArray<Any>() ?: emptyArray()
            val dynamicReason = Messages.get(reasonKey, *reasonArgs)
            val fileName = event.messageArgs.firstOrNull() ?: event.targetName.orEmpty()
            arrayOf<Any>(fileName, dynamicReason)
        } else {
            event.messageArgs.toTypedArray<Any>()
        }
        val rendered = Messages.get(event.messageKey, *effectiveArgs)
        return if (rendered == event.messageKey || rendered.startsWith("${event.messageKey}:")) {
            event.fallbackMessage.ifBlank { rendered }
        } else {
            rendered
        }
    }

    /**
     * Lists `.log` and `.jsonl` files inside [logDir] sorted newest first (ignoring `.lck` lock files).
     */
    fun listLogFiles(logDir: File = getLogsDirectory()): List<LogFileEntry> {
        if (!logDir.exists() || !logDir.isDirectory) return emptyList()
        val currentExecName = currentExecutionLogFile?.name
        return (logDir.listFiles() ?: emptyArray())
            .filter { f ->
                f.isFile && !f.name.endsWith(".lck", ignoreCase = true) &&
                    (f.name.contains(".log", ignoreCase = true) || f.name.endsWith(".jsonl", ignoreCase = true))
            }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                LogFileEntry(
                    name = f.name,
                    path = f.canonicalPath,
                    sizeBytes = f.length(),
                    lastModifiedMs = f.lastModified(),
                    isCurrentExecution = currentExecName != null && f.name == currentExecName,
                    isAuditLog = f.name == "flac_lyric_audit.log",
                    isEventsLog = f.name == "flac_lyric_events.jsonl",
                )
            }
    }

    /**
     * Safely reads the last [tailLines] lines (or matching lines if [search] is provided) from a file inside [logDir].
     * Uses reverse chunk scanning for large files when [search] is blank so multi-megabyte logs open instantaneously.
     */
    fun readLogFileTail(
        fileName: String,
        tailLines: Int = 400,
        search: String? = null,
        logDir: File = getLogsDirectory(),
    ): LogFileReadResult? {
        val safeName = File(fileName).name
        if (safeName.isBlank()) return null
        val target = File(logDir, safeName)
        val canonicalDir = logDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.parentFile.equals(canonicalDir) || !canonicalTarget.exists() || !canonicalTarget.isFile) {
            return null
        }

        val maxLines = tailLines.coerceIn(10, 3000)
        val q = search?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        return if (q != null) {
            val deque = ArrayDeque<String>(maxLines + 1)
            var matchCount = 0
            canonicalTarget.bufferedReader(StandardCharsets.UTF_8).useLines { seq ->
                seq.forEach { line ->
                    if (line.lowercase().contains(q)) {
                        matchCount++
                        if (deque.size >= maxLines) {
                            deque.removeFirst()
                        }
                        deque.addLast(line)
                    }
                }
            }
            LogFileReadResult(
                name = canonicalTarget.name,
                path = canonicalTarget.canonicalPath,
                sizeBytes = canonicalTarget.length(),
                lines = deque.toList(),
                totalLinesShown = deque.size,
                truncated = matchCount > deque.size
            )
        } else {
            val (lines, hasMore) = readLastLinesUtf8(canonicalTarget, maxLines)
            LogFileReadResult(
                name = canonicalTarget.name,
                path = canonicalTarget.canonicalPath,
                sizeBytes = canonicalTarget.length(),
                lines = lines,
                totalLinesShown = lines.size,
                truncated = hasMore
            )
        }
    }

    private fun readLastLinesUtf8(file: File, maxLines: Int): Pair<List<String>, Boolean> {
        val fileLength = file.length()
        if (fileLength == 0L) return emptyList<String>() to false

        // Fast path for smaller files (< 512 KB)
        if (fileLength <= 512 * 1024) {
            val allLines = file.readLines(StandardCharsets.UTF_8)
            return if (allLines.size > maxLines) {
                allLines.subList(allLines.size - maxLines, allLines.size) to true
            } else {
                allLines to false
            }
        }

        // Reverse scan for huge log files without loading whole file into RAM
        RandomAccessFile(file, "r").use { raf ->
            var pos = fileLength - 1
            var lineBreaks = 0
            var startOffset = 0L

            while (pos >= 0) {
                raf.seek(pos)
                val b = raf.readByte().toInt()
                if (b == '\n'.code && pos < fileLength - 1) {
                    lineBreaks++
                    if (lineBreaks >= maxLines) {
                        startOffset = pos + 1
                        break
                    }
                }
                pos--
            }

            raf.seek(startOffset)
            val remainingBytes = (fileLength - startOffset).toInt().coerceAtLeast(0)
            val buffer = ByteArray(remainingBytes)
            raf.readFully(buffer)
            val text = String(buffer, StandardCharsets.UTF_8)
            val lines = text.split(Regex("\r?\n")).let {
                if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
            }
            val truncated = startOffset > 0L
            return if (lines.size > maxLines) {
                lines.subList(lines.size - maxLines, lines.size) to true
            } else {
                lines to truncated
            }
        }
    }
}


