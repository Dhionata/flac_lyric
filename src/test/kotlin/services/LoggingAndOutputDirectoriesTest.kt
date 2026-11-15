package services

import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.infrastructure.clients.CompositeLyricsClientImpl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoggingAndOutputDirectoriesTest {

    @Test
    fun `prettyFormatJson should format JSON objects and arrays with indentation`() {
        val rawObject = """{"id":1,"trackName":"Song","artistName":"Artist","nested":{"ok":true}}"""
        val formattedObject = LoggingInitializer.prettyFormatJson(rawObject)
        assertTrue(formattedObject.contains("\n"), "Expected multiline pretty JSON")
        assertTrue(formattedObject.contains("  \"trackName\": \"Song\""))

        val rawArray = """[{"id":1,"source":"LRCLIB"},{"id":2,"source":"NETEASE"}]"""
        val formattedArray = LoggingInitializer.prettyFormatJson(rawArray)
        assertTrue(formattedArray.contains("\n"))
        assertTrue(formattedArray.contains("\"source\": \"LRCLIB\""))
    }

    @Test
    fun `LoggingInitializer should create per-execution log file in logs directory and log HTTP exchange`() {
        LoggingInitializer.init()
        val logFile = LoggingInitializer.currentExecutionLogFile
        assertTrue(logFile != null && logFile.exists(), "Per-execution .log file must exist in logs/")
        assertEquals("logs", (logFile ?: return).parentFile.name)
        assertTrue(logFile.name.endsWith(".log"))

        LoggingInitializer.logHttpExchange(
            category = "TEST-API",
            method = "GET",
            url = "https://lrclib.net/api/search?q=test",
            requestHeaders = mapOf("Accept" to "application/json"),
            responseStatus = 200,
            durationMs = 42L,
            responseHeaders = mapOf("Content-Type" to "application/json"),
            responseBody = """[{"id":123,"trackName":"Test Track"}]"""
        )

        val content = logFile.readText(Charsets.UTF_8)
        assertTrue(content.contains("https://lrclib.net/api/search?q=test"))
        assertTrue(content.contains("\"trackName\": \"Test Track\""))
    }

    @Test
    fun `AppOutputDirectories should create organized subfolders inside flac_lyric main folder`(@TempDir tempDir: File) {
        val missingFile = AppOutputDirectories.createReportFile(
            AppOutputDirectories.SUBDIR_NO_LYRICS,
            "missing_lyrics_test.txt",
            tempDir
        )
        assertTrue(missingFile.parentFile.exists())
        assertEquals(AppOutputDirectories.SUBDIR_NO_LYRICS, missingFile.parentFile.name)
        assertEquals(AppOutputDirectories.MAIN_DIR_NAME, missingFile.parentFile.parentFile.name)

        val syncReportFile = AppOutputDirectories.createReportFile(
            AppOutputDirectories.SUBDIR_SYNC_REPORTS,
            "LyricsSyncIssues_Test.txt",
            tempDir
        )
        syncReportFile.writeText("Test report", Charsets.UTF_8)

        val discovered = AppOutputDirectories.findAllSyncIssueReports(tempDir)
        assertEquals(1, discovered.size)
        assertEquals(syncReportFile.canonicalPath, discovered.first().canonicalPath)
    }

    @Test
    fun `CompositeLyricsClientImpl should collect fast provider results even when a slow provider times out`() {
        val fastProvider = object : LrcLibClient {
            override fun getLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): LrcLibLyrics? = null
            override fun getAllLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): List<LrcLibLyrics> = emptyList()
            override fun searchLyrics(query: String): List<LrcLibLyrics> {
                return listOf(
                    LrcLibLyrics(
                        id = 10,
                        trackName = "Fast Song",
                        artistName = "Fast Artist",
                        albumName = "Album",
                        duration = 200.0,
                        instrumental = false,
                        plainLyrics = "Hello world",
                        syncedLyrics = "[00:01.00] Hello world",
                        source = "FAST_API"
                    )
                )
            }
        }

        val slowProvider = object : LrcLibClient {
            override fun getLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): LrcLibLyrics? = null
            override fun getAllLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): List<LrcLibLyrics> = emptyList()
            override fun searchLyrics(query: String): List<LrcLibLyrics> {
                Thread.sleep(2500)
                return emptyList()
            }
        }

        val composite = CompositeLyricsClientImpl(
            providers = listOf(fastProvider, slowProvider),
            timeoutSeconds = 1L
        )

        val results = composite.searchLyrics("Fast Artist Fast Song")
        assertEquals(1, results.size)
        assertEquals("Fast Song", results.first().trackName)
        assertEquals("FAST_API", results.first().source)
    }

    @Test
    fun `LoggingInitializer should list log files, support reverse tailing on large logs, and block path traversal`(@TempDir tempDir: File) {
        val smallLog = File(tempDir, "flac_lyric_audit.log")
        smallLog.writeText((1..50).joinToString("\n") { "Audit line $it" }, Charsets.UTF_8)

        val ignoredLock = File(tempDir, "flac_lyric.log.lck")
        ignoredLock.writeText("lock", Charsets.UTF_8)

        // Create > 600KB file to trigger the reverse RandomAccessFile tailing code path
        val hugeLog = File(tempDir, "huge_execution.log")
        val lineTemplate = "2026-11-15 10:00:00 [INFO] Padding payload for reverse chunk reader verification number="
        hugeLog.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (i in 1..7000) {
                writer.write("$lineTemplate$i\n")
            }
        }
        assertTrue(hugeLog.length() > 512 * 1024, "Expected hugeLog > 512KB, was ${hugeLog.length()}")

        val listed = LoggingInitializer.listLogFiles(tempDir)
        assertEquals(2, listed.size)
        assertTrue(listed.none { it.name.endsWith(".lck") })
        assertTrue(listed.any { it.isAuditLog && it.name == "flac_lyric_audit.log" })

        val tailedHuge = LoggingInitializer.readLogFileTail(
            fileName = "huge_execution.log",
            tailLines = 15,
            logDir = tempDir
        )
        assertTrue(tailedHuge != null)
        assertEquals(15, tailedHuge!!.totalLinesShown)
        assertTrue(tailedHuge.truncated)
        assertTrue(tailedHuge.lines.last().endsWith("number=7000"))
        assertTrue(tailedHuge.lines.first().endsWith("number=6986"))

        val searched = LoggingInitializer.readLogFileTail(
            fileName = "huge_execution.log",
            tailLines = 20,
            search = "number=6999",
            logDir = tempDir
        )
        assertEquals(listOf("${lineTemplate}6999"), searched?.lines)

        // Attempt path traversal
        val outside = File(tempDir.parentFile, "outside_secret.log").apply {
            writeText("secret", Charsets.UTF_8)
        }
        try {
            val traversal = LoggingInitializer.readLogFileTail(
                fileName = "../${outside.name}",
                logDir = tempDir
            )
            // Note: File("../outside_secret.log").name strips directory and looks in tempDir where it doesn't exist
            assertEquals(null, traversal)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `Messages getAvailableLocales discovers built-in locales and external i18n bundles`(@TempDir tempDir: File) {
        val frBundle = File(tempDir, "messages_fr.properties")
        frBundle.writeText("web.menu.hero_title=Tableau des opérations\n", Charsets.UTF_8)

        val options = Messages.getAvailableLocales(externalDir = tempDir)
        val tags = options.map { it.tag }

        assertTrue(tags.contains("en"))
        assertTrue(tags.contains("pt-BR"))
        assertTrue(tags.contains("es"))
        assertTrue(tags.contains("fr"), "Expected dynamically discovered 'fr' bundle in $tags")
    }
}
