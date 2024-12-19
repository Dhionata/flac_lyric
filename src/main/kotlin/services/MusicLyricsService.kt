package services

import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.AudioFileHandler
import interfaces.DirectoryService
import interfaces.FileService
import interfaces.LyricFileHandler
import interfaces.MatchService
import interfaces.UserInterface
import ui.UserInterfaceImpl
import java.io.File

class MusicLyricsService(
    private val directoryService: DirectoryService = DirectoryServiceImpl(),
    private val audioFileHandler: AudioFileHandler = AudioFileHandlerImpl(),
    private val lyricFileHandler: LyricFileHandler = LyricFileHandlerImpl(),
    private val userInterface: UserInterface = UserInterfaceImpl(),
    private val matchService: MatchService = MatchServiceImpl(userInterface = userInterface),
    private val fileService: FileService = FileServiceImpl(),
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

        val filePairs = matchService.matchFiles(lyricFiles, audioFiles)

        matchService.handleFilePairs(filePairs)

        fileService.handleUnmatchedFiles(musicDirectory, lyricsDirectory)

        userInterface.showResult(fileService.changedSet, fileService.errorSet)
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

    fun tryDownloadLyrics() {
        TODO("Fazer um método que pega as letras e criar arquivos .lrc para cada linha de um .txt gerado por findMusicWithoutLyricsPair()")
    }
}
