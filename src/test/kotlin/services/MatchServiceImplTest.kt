package services

import fakes.FakeFileService
import fakes.FakeUserInterface
import java.io.File
import models.FilePair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchServiceImplTest {

    @Test
    fun testMatchFilesExactMatch() {
        val fakeUi = FakeUserInterface()
        val fakeFileService = FakeFileService()
        val matchService = MatchServiceImpl(fakeUi, fakeFileService)

        val musicDir = File("music")
        val lyricsDir = File("lyrics")

        val lyricFile = File(lyricsDir, "Song1.lrc")
        val audioFile = File(musicDir, "Song1.flac")

        val matched = matchService.matchFiles(listOf(lyricFile), listOf(audioFile))

        assertEquals(1, matched.size)
        assertEquals(lyricFile, matched[0].lyricFile)
        assertEquals(audioFile, matched[0].audioFile)
    }

    @Test
    fun testMatchFilesExcludeAlreadyMatched() {
        val fakeUi = FakeUserInterface()
        val fakeFileService = FakeFileService()
        val matchService = MatchServiceImpl(fakeUi, fakeFileService)

        val musicDir = File("music")

        val lyricFile = File(musicDir, "Song1.lrc")
        val audioFile = File(musicDir, "Song1.flac")

        val matched = matchService.matchFiles(listOf(lyricFile), listOf(audioFile))

        assertTrue(matched.isEmpty())
    }

    @Test
    fun testMatchFilesApproximateMatch() {
        val fakeUi = FakeUserInterface()
        val fakeFileService = FakeFileService()
        val matchService = MatchServiceImpl(fakeUi, fakeFileService)

        val musicDir = File("music")
        val lyricsDir = File("lyrics")

        val lyricFile = File(lyricsDir, "Coldplay - Fix You (feat. Someone).lrc")
        val audioFile = File(musicDir, "Coldplay - Fix You.flac")

        val matched = matchService.matchFiles(listOf(lyricFile), listOf(audioFile))

        assertEquals(1, matched.size)
        assertEquals(lyricFile, matched[0].lyricFile)
        assertEquals(audioFile, matched[0].audioFile)
    }

    @Test
    fun testHandleFilePairsMoveOnly() {
        val fakeUi = FakeUserInterface()
        val fakeFileService = FakeFileService()
        val matchService = MatchServiceImpl(fakeUi, fakeFileService)

        val lyricsDir = File("lyrics")
        val musicDir = File("music")

        val lyricFile = File(lyricsDir, "Song1.lrc")
        val audioFile = File(musicDir, "Song1.flac")

        val pairs = listOf(FilePair(lyricFile, audioFile))
        matchService.handleFilePairs(pairs)

        assertEquals(1, fakeFileService.moveLyricFileCalls.size)
        assertEquals(lyricFile, fakeFileService.moveLyricFileCalls[0].first)
        assertEquals(musicDir, fakeFileService.moveLyricFileCalls[0].second)
        assertTrue(fakeFileService.renameLyricFileCalls.isEmpty())
    }

    @Test
    fun testHandleFilePairsMoveAndRename() {
        val fakeUi = FakeUserInterface()
        val fakeFileService = FakeFileService()
        val matchService = MatchServiceImpl(fakeUi, fakeFileService)

        val lyricsDir = File("lyrics")
        val musicDir = File("music")

        val lyricFile = File(lyricsDir, "Song1Different.lrc")
        val audioFile = File(musicDir, "Song1.flac")

        fakeUi.moveAndRenameResponse = true

        val pairs = listOf(FilePair(lyricFile, audioFile))
        matchService.handleFilePairs(pairs)

        assertEquals(1, fakeUi.moveAndRenameCalls.size)
        assertEquals(lyricFile, fakeUi.moveAndRenameCalls[0].lyricFile)
        assertEquals(audioFile, fakeUi.moveAndRenameCalls[0].audioFile)

        assertEquals(1, fakeFileService.moveLyricFileCalls.size)
        assertEquals(1, fakeFileService.renameLyricFileCalls.size)
        assertEquals(audioFile, fakeFileService.renameLyricFileCalls[0].second)
    }

    @Test
    fun testHandleFilePairsOnlyRename() {
        val fakeUi = FakeUserInterface()
        val fakeFileService = FakeFileService()
        val matchService = MatchServiceImpl(fakeUi, fakeFileService)

        val musicDir = File("music")

        val lyricFile = File(musicDir, "Song1Different.lrc")
        val audioFile = File(musicDir, "Song1.flac")

        fakeUi.onlyRenameResponse = true

        val pairs = listOf(FilePair(lyricFile, audioFile))
        matchService.handleFilePairs(pairs)

        assertEquals(1, fakeUi.onlyRenameCalls.size)
        assertEquals(1, fakeFileService.renameLyricFileCalls.size)
        assertEquals(lyricFile, fakeFileService.renameLyricFileCalls[0].first)
        assertEquals(audioFile, fakeFileService.renameLyricFileCalls[0].second)
        assertTrue(fakeFileService.moveLyricFileCalls.isEmpty())
    }
}
