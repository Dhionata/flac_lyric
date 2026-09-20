package handlers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileHandlersTest {

    @Test
    fun testAudioFileHandlerCaseInsensitive() {
        val tempDir = Files.createTempDirectory("test-audio-handler").toFile()
        try {
            val flacUpper = File(tempDir, "song1.FLAC").apply { writeText("dummy") }
            val mp3Mixed = File(tempDir, "song2.Mp3").apply { writeText("dummy") }
            val nonAudio = File(tempDir, "notes.txt").apply { writeText("dummy") }
            val subDirWithAudio = File(tempDir, "sub").apply { mkdirs() }
            val oggSub = File(subDirWithAudio, "song3.OGG").apply { writeText("dummy") }

            val handler = AudioFileHandlerImpl()
            val files = handler.getAudioFiles(tempDir)

            assertEquals(3, files.size)
            assertTrue(files.contains(flacUpper), "Upper-case .FLAC should be included")
            assertTrue(files.contains(mp3Mixed), "Mixed-case .Mp3 should be included")
            assertTrue(files.contains(oggSub), "Audio in subdirectories should be included")
            assertFalse(files.contains(nonAudio), "Non-audio files (.txt) must not be included")
            assertFalse(files.contains(subDirWithAudio), "Directories must not be included")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLyricFileHandlerCaseInsensitiveAndIgnoresDirectories() {
        val tempDir = Files.createTempDirectory("test-lyric-handler").toFile()
        try {
            val lrcLower = File(tempDir, "song1.lrc").apply { writeText("[00:01.00]test") }
            val lrcUpper = File(tempDir, "song2.LRC").apply { writeText("[00:01.00]test") }
            val dirEndingInLrc = File(tempDir, "album.lrc").apply { mkdirs() }
            val nonLrc = File(tempDir, "song1.txt").apply { writeText("test") }

            val handler = LyricFileHandlerImpl()
            val files = handler.getLyricFiles(tempDir)

            assertEquals(2, files.size)
            assertTrue(files.contains(lrcLower), "Lower-case .lrc should be included")
            assertTrue(files.contains(lrcUpper), "Upper-case .LRC should be included")
            assertFalse(files.contains(dirEndingInLrc), "Directories ending in .lrc must not be included")
            assertFalse(files.contains(nonLrc), "Non-lrc files (.txt) must not be included")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
