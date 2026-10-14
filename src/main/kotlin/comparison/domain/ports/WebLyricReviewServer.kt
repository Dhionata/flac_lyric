package comparison.domain.ports

/**
 * Port for managing the lifecycle of the web review HTTP server.
 */
interface WebLyricReviewServer {
    /**
     * Starts the embedded review server.
     *
     * @param preferredPort Port number to attempt binding to.
     * @return The actual port bound.
     */
    fun start(preferredPort: Int = 8080): Int

    /**
     * Stops the running server.
     */
    fun stop()

    /**
     * Indicates whether the server is currently active.
     */
    val isRunning: Boolean

    /**
     * Returns the full URL to access the server if running, or null.
     */
    val serverUrl: String?
}
