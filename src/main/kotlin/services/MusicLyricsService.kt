package services

import interfaces.AudioFileHandler
import interfaces.DirectoryService
import interfaces.LyricFileHandler
import interfaces.UserInterface
import ui.UserInterfaceImpl
import java.io.File
import kotlin.collections.mutableSetOf

class MusicLyricsService(
    private val directoryService: DirectoryService = DirectoryServiceImpl(),
    private val audioFileHandler: AudioFileHandler = AudioFileHandlerImpl(),
    private val lyricFileHandler: LyricFileHandler = LyricFileHandlerImpl(),
    private val userInterface: UserInterface = UserInterfaceImpl()
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

        lyricFileHandler.handleUnmatchedFiles(musicDirectory, lyricsDirectory)

        userInterface.showResult(lyricFileHandler.changedSet, lyricFileHandler.errorSet)
    }

    fun findMusicWithoutLyricsPair() {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")

        if (!musicDirectory.canWrite()) {
            userInterface.showError("Diretório $musicDirectory somente leitura!")
            return
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val musicsWithoutLyrics = mutableSetOf<String>()

        audioFiles.forEach { audioFile ->
            val contains = audioFile.parentFile.walk().find { otherFile ->
                otherFile.name == audioFile.nameWithoutExtension + ".lrc"
            }
            if (contains == null) {
                musicsWithoutLyrics.add(audioFile.nameWithoutExtension)
            }
        }

        File("MusicsWithoutLyrics ${musicsWithoutLyrics.hashCode()}.txt").writeText(
            musicsWithoutLyrics.joinToString("\n")
        )
    }
}
