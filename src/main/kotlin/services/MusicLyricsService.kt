package services

import interfaces.AudioFileHandler
import interfaces.DirectoryService
import interfaces.LyricFileHandler
import interfaces.UserInterface

class MusicLyricsService(
    private val directoryService: DirectoryService,
    private val audioFileHandler: AudioFileHandler,
    private val lyricFileHandler: LyricFileHandler,
    private val userInterface: UserInterface
) {

    fun organizeMusicAndLyrics() {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")

        if (!musicDirectory.canWrite()) {
            userInterface.showError("Diretório $musicDirectory somente leitura!")
            return
        }

        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")

        if (!lyricsDirectory.canWrite()) {
            userInterface.showError("Diretório $lyricsDirectory somente leitura!")
            return
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        val filePairs = lyricFileHandler.matchFiles(lyricFiles, audioFiles)

        lyricFileHandler.handleFilePairs(filePairs, musicDirectory.parentFile, lyricsDirectory)

        userInterface.showResult(lyricFileHandler.getChangedSet(), lyricFileHandler.getErrorList())
    }
}
