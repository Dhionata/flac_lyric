package comparison

import comparison.domain.models.ActionExecutionRequest
import comparison.infrastructure.services.FixedDirectoryService
import comparison.infrastructure.services.WebActionRunnerService
import comparison.infrastructure.services.WebProgressUserInterface
import exceptions.OperationCancelledException
import interfaces.DirectoryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WebActionRunnerServiceTest {

    @Test
    fun `FixedDirectoryService should return provided directories correctly`(@TempDir tempDir: File) {
        val musicDir = File(tempDir, "music").apply { mkdirs() }
        val lyricsDir = File(tempDir, "lyrics").apply { mkdirs() }
        val outDir = File(tempDir, "out").apply { mkdirs() }

        val fixedDirService = FixedDirectoryService(
            musicDir = musicDir,
            lyricsDir = lyricsDir,
            outDir = outDir
        )

        val resolvedMusic = fixedDirService.getDirectory("Selecione a pasta de música")
        assertEquals(musicDir.canonicalPath, resolvedMusic.canonicalPath)

        val resolvedLyrics = fixedDirService.getDirectory("Selecione a pasta de letras")
        assertEquals(lyricsDir.canonicalPath, resolvedLyrics.canonicalPath)

        val resolvedOut = fixedDirService.getDirectory("Selecione a pasta de saída para mover")
        assertEquals(outDir.canonicalPath, resolvedOut.canonicalPath)
    }

    @Test
    fun `WebProgressUserInterface should track progress and results`() {
        val req = ActionExecutionRequest(actionId = 0, moveFiles = true, renameFiles = true)
        val state = comparison.domain.models.ActionProgressUpdate(isRunning = true)
        val ui = WebProgressUserInterface(req, state)

        ui.showProgress("Processando", 10)
        assertEquals("Processando", state.title)
        assertEquals(10, state.max)
        assertEquals(0, state.current)

        ui.updateProgress(5, "arquivo.flac")
        assertEquals(5, state.current)
        assertEquals("arquivo.flac", state.currentItem)

        ui.showResult(setOf("Moved 1 file", "LyricsSyncIssues_12345.txt"), emptySet())
        assertTrue(state.changes.contains("Moved 1 file"))
        assertNotNull(state.generatedReportPath)
        assertTrue(state.generatedReportPath!!.contains("LyricsSyncIssues_12345.txt"))
    }

    @Test
    fun `WebActionRunnerService should execute and return valid progress state`(@TempDir tempDir: File) {
        val musicDir = File(tempDir, "music").apply { mkdirs() }
        val lyricsDir = File(tempDir, "lyrics").apply { mkdirs() }

        val runner = WebActionRunnerService()
        val request = ActionExecutionRequest(
            actionId = 0,
            musicDir = musicDir.canonicalPath,
            lyricsDir = lyricsDir.canonicalPath,
            moveFiles = false
        )

        val status = runner.executeAction(request)
        assertNotNull(status)
        assertEquals(0, status.actionId)

        // Wait up to 3 seconds for async execution to complete
        var done = false
        for (i in 1..30) {
            val current = runner.getProgress()
            if (current.isCompleted) {
                done = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(done, "Action runner execution should complete asynchronously")
    }
}
