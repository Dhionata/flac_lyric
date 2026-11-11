package services

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.FileHandler
import java.util.logging.Logger

/**
 * Responsible for configuring file system log output, creating a dedicated `.log` file per execution
 * inside `logs/`, and logging HTTP requests/responses with pretty-printed JSON.
 */
object LoggingInitializer {
    private var initialized = false
    private val fileWriteLock = Any()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val entryTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    @Volatile
    var currentExecutionLogFile: File? = null
        private set

    /**
     * Configures both a per-execution UTF-8 log file (`logs/flac_lyric_YYYY-MM-DD_HH-mm-ss.log`)
     * and the rotating `logs/flac_lyric.log`.
     *
     * @param logDir Directory to store logs. Defaults to `logs/`.
     * @param maxSizeBytes Maximum file size before rotation. Defaults to 10MB.
     * @param fileCount Number of rotating log files. Defaults to 5.
     */
    fun init(
        logDir: File = File("logs"),
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
}

