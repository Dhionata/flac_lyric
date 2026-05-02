package services

import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.AudioFileHandler
import interfaces.AudioNomenclatureValidator
import interfaces.DirectoryService
import interfaces.FileService
import interfaces.LyricFileHandler
import interfaces.MatchService
import interfaces.UserInterface
import java.io.File
import ui.UserInterfaceImpl

class MusicLyricsService(
    private val userInterface: UserInterface = UserInterfaceImpl(),
    private val directoryService: DirectoryService = DirectoryServiceImpl(),
    private val audioFileHandler: AudioFileHandler = AudioFileHandlerImpl(),
    private val lyricFileHandler: LyricFileHandler = LyricFileHandlerImpl(),
    private val matchService: MatchService = MatchServiceImpl(userInterface),
    private val fileService: FileService = FileServiceImpl(),
    private val nomenclatureValidator: AudioNomenclatureValidator = CleanTitleNomenclatureValidator(),
) {

    private fun getValidatedMusicDirectory(message: String): File {
        val musicDirectory = directoryService.getDirectory(message)
        if (!musicDirectory.canRead()) {
            userInterface.showError("Diretório $musicDirectory sem permissão de leitura!")
            throw RuntimeException("Diretório $musicDirectory sem permissão de leitura!")
        }
        return musicDirectory
    }

    private fun processIncorrectlyNamedFiles(
        incorrectlyNamedFiles: List<File>,
        txtPrefix: String,
        movedMessagePrefix: String,
        allCorrectMessage: String,
    ) {
        if (incorrectlyNamedFiles.isNotEmpty()) {
            val txtFileName = "${txtPrefix}_${incorrectlyNamedFiles.hashCode()}.txt"
            File(txtFileName).writeText(
                incorrectlyNamedFiles.joinToString("\n") { it.name }
            )

            val shouldMove = userInterface.askToMoveIncorrectFiles(incorrectlyNamedFiles.size, txtFileName)

            if (shouldMove) {
                val outDirectory = directoryService.getDirectory("Selecione o diretório para mover os arquivos com nome incorreto")
                val movedNames = mutableListOf<String>()

                incorrectlyNamedFiles.forEach { file ->
                    if (fileService.moveFile(file, outDirectory)) {
                        movedNames.add(file.name)
                    } else {
                        fileService.errorSet.add(RuntimeException("Falha ao mover o arquivo: ${file.name}"))
                    }
                }

                fileService.changedSet.add("Arquivos listados em: $txtFileName\nMovidos ${movedNames.size} de ${incorrectlyNamedFiles.size} $movedMessagePrefix para ${outDirectory.absolutePath}.")
            } else {
                fileService.changedSet.add("Encontrados ${incorrectlyNamedFiles.size} $movedMessagePrefix.\nApenas listados no arquivo gerado: $txtFileName.")
            }
        } else {
            fileService.changedSet.add(allCorrectMessage)
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)
    }

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
        val musicDirectory = getValidatedMusicDirectory("Selecione o diretório das músicas")
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
        val outDirectory = directoryService.getDirectory("Selecione o diretório de saída")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)
        val lyricsFilesWithoutSync = lyricFiles.filter { lyricFile ->
            lyricFile.readLines().none { line -> line.contains(Regex("\\d")) }
        }

        File("LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt").writeText(
            lyricsFilesWithoutSync.joinToString("\n") { it.name }
        )

        lyricsFilesWithoutSync.forEach { lyric ->
            fileService.moveLyricFile(lyric, outDirectory)
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return lyricsFilesWithoutSync
    }

    fun findLyricsWithV1Text(): List<File> {
        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)
        val lyricsFilesWithV1 = lyricFiles.filter { lyricFiles ->
            lyricFiles.readLines().any { line -> line.contains("v1:") }
        }

        File("LyricsWithV1_${lyricsFilesWithV1.hashCode()}.txt").writeText(
            lyricsFilesWithV1.joinToString("\n") { it.name }
        )

        lyricsFilesWithV1.forEach { lyricFile ->
            lyricFile.readLines().forEach { line ->
                if (line.contains("v1:")) {
                    val newLine = line.replace("v1:", "")
                    lyricFile.writeText(lyricFile.readText().replace(line, newLine))
                    fileService.changedSet.add(lyricFile.name)
                }
            }
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return lyricsFilesWithV1
    }

    fun findAndMoveAloneLyrics(): List<File> {
        val mainDirectory = directoryService.getDirectory("Selecione o diretório contendo as músicas e os arquivos .lrc")
        val outDirectory = directoryService.getDirectory("Selecione o diretório para onde mover os arquivos .lrc sozinhos")

        val lyricFiles = lyricFileHandler.getLyricFiles(mainDirectory)
        val audioFiles = audioFileHandler.getAudioFiles(mainDirectory)

        val audioNamesWithoutExtension = audioFiles.map { it.nameWithoutExtension }.toSet()

        val aloneLyrics = lyricFiles.filter { lyricFile ->
            !audioNamesWithoutExtension.contains(lyricFile.nameWithoutExtension)
        }

        val movedLyricsNames = mutableListOf<String>()

        aloneLyrics.forEach { lyric ->
            val parentFolder = lyric.parentFile
            fileService.moveLyricFile(lyric, outDirectory)
            movedLyricsNames.add(lyric.name)

            if (parentFolder.isDirectory && parentFolder.listFiles()?.isEmpty() == true) {
                if (parentFolder.delete()) {
                    fileService.changedSet.add("Pasta vazia excluída: ${parentFolder.absolutePath}")
                }
            }
        }

        if (movedLyricsNames.isNotEmpty()) {
            File("AloneLyricsMoved_${movedLyricsNames.hashCode()}.txt").writeText(
                movedLyricsNames.joinToString("\n")
            )
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return aloneLyrics
    }

    fun verifyAdvancedNomenclature(): List<File> {
        val musicDirectory = getValidatedMusicDirectory("Selecione o diretório das músicas (Base)")
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val incorrectlyNamedFiles = mutableListOf<File>()

        for (audioFile in audioFiles) {
            if (!nomenclatureValidator.isValid(audioFile, musicDirectory)) {
                incorrectlyNamedFiles.add(audioFile)
            }
        }

        processIncorrectlyNamedFiles(
            incorrectlyNamedFiles,
            "AdvancedIncorrectNomenclatureMoved",
            "arquivos fora do padrão",
            "Todos os arquivos verificados estão com a nomenclatura e estrutura corretas."
        )

        return incorrectlyNamedFiles
    }
}
