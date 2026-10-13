package services

import interfaces.LogService
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Concrete implementation of [LogService] backed by [java.util.logging.Logger] and [Messages].
 *
 * Adheres to:
 * - DRY: Centralizes level checking, key tagging `[$key]`, and resource bundle resolution in [logKey].
 * - SRP: Focuses exclusively on translating and dispatching log events to a JDK Logger.
 * - DIP: Business components depend on [LogService], not directly on this implementation.
 */
class JdkLogService(
    private val logger: Logger,
) : LogService {

    constructor(clazz: Class<*>) : this(Logger.getLogger(clazz.name))

    private fun logKey(level: Level, key: String, vararg args: Any) {
        if (logger.isLoggable(level)) {
            val message = Messages.get(key, *args)
            logger.log(level, "[$key] $message")
        }
    }

    override fun info(key: String, vararg args: Any) = logKey(Level.INFO, key, *args)

    override fun warning(key: String, vararg args: Any) = logKey(Level.WARNING, key, *args)

    override fun severe(key: String, vararg args: Any) = logKey(Level.SEVERE, key, *args)

    override fun fine(key: String, vararg args: Any) = logKey(Level.FINE, key, *args)
}
