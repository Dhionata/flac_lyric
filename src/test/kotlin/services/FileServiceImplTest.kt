package services

import fakes.FakeUserInterface
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileServiceImplTest {

    @Test
    fun testHandleUnmatchedFilesMoveAccepted() {
        val tempDir = Files.createTempDirectory("test-unmatched").toFile()
        try {
            val musicDir = File(tempDir, "music").apply { mkdirs() }
            val lyricsDir = File(tempDir, "lyrics").apply { mkdirs() }

            val unmatchedLyric = File(lyricsDir, "unmatched.lrc").apply { writeText("lyrics here") }
            val matchedLyric = File(lyricsDir, "matched.lrc").apply { writeText("lyrics here") }

            val fakeUi = FakeUserInterface().apply {
                askToMoveUnmatchedLyricsResponse = true
            }

            val fileService = FileServiceImpl()
            fileService.handleUnmatchedFiles(musicDir, lyricsDir, fakeUi)

            val unmatchedLrcDir = File(musicDir.parentFile, "unmatched_lrc")
            val movedLyric = File(unmatchedLrcDir, "unmatched.lrc")

            assertTrue(movedLyric.exists(), "Unmatched lyric should have been moved")
            assertFalse(unmatchedLyric.exists(), "Original unmatched lyric should have been deleted")
            assertTrue(matchedLyric.exists(), "Matched lyric should not be moved")
            assertTrue(lyricsDir.exists(), "Lyrics directory should not be deleted since matchedLyric is still there")

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHandleUnmatchedFilesMoveRejected() {
        val tempDir = Files.createTempDirectory("test-unmatched-rejected").toFile()
        try {
            val musicDir = File(tempDir, "music").apply { mkdirs() }
            val lyricsDir = File(tempDir, "lyrics").apply { mkdirs() }

            val unmatchedLyric = File(lyricsDir, "unmatched.lrc").apply { writeText("lyrics here") }

            val fakeUi = FakeUserInterface().apply {
                askToMoveUnmatchedLyricsResponse = false
            }

            val fileService = FileServiceImpl()
            fileService.handleUnmatchedFiles(musicDir, lyricsDir, fakeUi)

            val unmatchedLrcDir = File(musicDir.parentFile, "unmatched_lrc")
            val movedLyric = File(unmatchedLrcDir, "unmatched.lrc")

            assertFalse(movedLyric.exists(), "Unmatched lyric should NOT have been moved")
            assertTrue(unmatchedLyric.exists(), "Original unmatched lyric should still exist")
            assertTrue(lyricsDir.exists(), "Lyrics directory should still exist")

        } finally {
            tempDir.deleteRecursively()
        }
    }
}
