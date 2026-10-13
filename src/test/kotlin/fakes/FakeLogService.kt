package fakes

import interfaces.LogService

/**
 * Fake implementation of [LogService] for unit testing without console or disk I/O.
 */
class FakeLogService : LogService {
    val entries: MutableList<LogEntry> = mutableListOf()

    data class LogEntry(
        val level: String,
        val key: String,
        val args: List<Any>
    )

    override fun info(key: String, vararg args: Any) {
        entries.add(LogEntry("INFO", key, args.toList()))
    }

    override fun warning(key: String, vararg args: Any) {
        entries.add(LogEntry("WARNING", key, args.toList()))
    }

    override fun severe(key: String, vararg args: Any) {
        entries.add(LogEntry("SEVERE", key, args.toList()))
    }

    override fun fine(key: String, vararg args: Any) {
        entries.add(LogEntry("FINE", key, args.toList()))
    }
}
