package interfaces

/**
 * Abstraction for logging across the application.
 * Decouples business logic from concrete logging frameworks (DIP) and supports
 * internationalized message keys with standardized diagnostic codes.
 */
interface LogService {
    /**
     * Logs an informational message using an internationalized [key] and arguments [args].
     */
    fun info(key: String, vararg args: Any)

    /**
     * Logs a warning message using an internationalized [key] and arguments [args].
     */
    fun warning(key: String, vararg args: Any)

    /**
     * Logs an error/severe message using an internationalized [key] and arguments [args].
     */
    fun severe(key: String, vararg args: Any)

    /**
     * Logs a debug/fine message using an internationalized [key] and arguments [args].
     */
    fun fine(key: String, vararg args: Any)
}
