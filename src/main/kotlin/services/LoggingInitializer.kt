package services

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.logging.FileHandler
import java.util.logging.Logger

/**
 * Responsible solely for configuring file system log output and rotating file handlers (SRP).
 */
object LoggingInitializer {
    private var initialized = false

    /**
     * Configures a rotating UTF-8 file logger in `logs/flac_lyric.log`.
     *
     * @param logDir Directory to store logs. Defaults to `logs/`.
     * @param maxSizeBytes Maximum file size before rotation. Defaults to 5MB.
     * @param fileCount Number of rotating log files. Defaults to 3.
     */
    fun init(
        logDir: File = File("logs"),
        maxSizeBytes: Int = 5 * 1024 * 1024,
        fileCount: Int = 3
    ) {
        if (initialized) return
        initialized = true

        try {
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val logFile = File(logDir, "flac_lyric.log")
            val fileHandler = FileHandler(logFile.absolutePath, maxSizeBytes, fileCount, true).apply {
                encoding = StandardCharsets.UTF_8.name()
                formatter = StandardLogFormatter()
            }
            val rootLogger = Logger.getLogger("")
            rootLogger.addHandler(fileHandler)
        } catch (e: Exception) {
            System.err.println("Could not initialize file logger: ${e.message}")
        }
    }
}
