package services

import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.Formatter
import java.util.logging.LogRecord

/**
 * Formatter responsible solely for formatting [LogRecord] entries into clean text lines (SRP).
 */
class StandardLogFormatter : Formatter() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun format(record: LogRecord): String {
        val timestamp = dateFormat.format(Date(record.millis))
        val level = record.level.name.padEnd(7)
        val loggerName = record.loggerName?.substringAfterLast('.') ?: "App"
        val message = formatMessage(record)
        val throwable = record.thrown?.let { "\n" + it.stackTraceToString() } ?: ""
        return "[$timestamp] [$level] [$loggerName] $message$throwable\n"
    }
}
