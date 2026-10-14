package comparison

import comparison.infrastructure.parsers.LyricsReportParserImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LyricsReportParserImplTest {

    @TempDir
    lateinit var tempDir: File

    private val parser = LyricsReportParserImpl()

    @Test
    fun `test parse issues report file correctly`() {
        val reportFile = File(tempDir, "LyricsSyncIssues_test.txt")
        reportFile.writeText(
            """
            === LYRICS (.LRC) INCONSISTENCY AND DESYNCHRONIZATION REPORT ===
            Total files with issues: 2
            Total occurrences found: 2
            ======================================================================

            File: Test - Song.lrc
            Path: ${File(tempDir, "Test - Song.lrc").absolutePath}
            Corresponding audio: Test - Song.flac
            Issues:
              - [ULTRAPASSOU_DURACAO] Timestamps exceed audio duration.
            --------------------------------------------------
            File: Another - Track.lrc
            Path: ${File(tempDir, "Another - Track.lrc").absolutePath}
            Corresponding audio: Another - Track.flac
            Issues:
              - [TAG_LENGTH_DIVERGENTE] Length tag differs from audio.
            --------------------------------------------------
            """.trimIndent()
        )

        val items = parser.parseReport(reportFile)

        assertEquals(2, items.size)
        assertEquals("Test - Song.lrc", items[0].fileName)
        assertEquals(1, items[0].issues.size)
        assertEquals("[ULTRAPASSOU_DURACAO] Timestamps exceed audio duration.", items[0].issues[0])

        assertEquals("Another - Track.lrc", items[1].fileName)
        assertEquals(1, items[1].issues.size)
        assertEquals("[TAG_LENGTH_DIVERGENTE] Length tag differs from audio.", items[1].issues[0])
    }
}
