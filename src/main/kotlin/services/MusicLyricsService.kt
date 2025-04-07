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
    private val userInterface: UserInterface = UserInterfaceImpl(),
    private val directoryService: DirectoryService = DirectoryServiceImpl(),
    private val audioFileHandler: AudioFileHandler = AudioFileHandlerImpl(),
    private val lyricFileHandler: LyricFileHandler = LyricFileHandlerImpl(),
    private val matchService: MatchService = MatchServiceImpl(userInterface),
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

    fun findMusicWithoutLyricsPair(): List<File> {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")

        if (!musicDirectory.canRead()) {
            userInterface.showError("Diretório $musicDirectory sem premissão de leitura!")
            throw RuntimeException("Diretório $musicDirectory sem premissão de leitura!")
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)

        val musicFilesWithoutLyrics = audioFiles.filter { audioFile ->
            audioFile.parentFile.walk().none { audioFileParent ->
                audioFileParent.name.equals(audioFile.nameWithoutExtension + ".lrc")
            }
        }

        File("MusicsWithoutLyrics_${musicFilesWithoutLyrics.hashCode()}.txt").writeText(
            musicFilesWithoutLyrics.joinToString("\n") { it.name }
        )

        return musicFilesWithoutLyrics
    }

    fun findLyricsWithoutSync(): List<File> {
        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)
        val lyricsFilesWithoutSync = lyricFiles.filter { lyricFile ->
            lyricFile.readLines().none { line -> line.contains(Regex("\\d")) }
        }

        File("LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt").writeText(
            lyricsFilesWithoutSync.joinToString("\n") { it.name }
        )

        return lyricsFilesWithoutSync
    }
}
