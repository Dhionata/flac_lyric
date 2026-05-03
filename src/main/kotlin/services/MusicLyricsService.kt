package services

import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.AudioAnalysisService
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
    private val audioAnalysisService: AudioAnalysisService = AudioAnalysisServiceImpl(),
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

                userInterface.showProgress("Movendo arquivos incorretos", incorrectlyNamedFiles.size)
                incorrectlyNamedFiles.forEachIndexed { index, file ->
                    userInterface.updateProgress(index + 1, file.name)
                    if (fileService.moveFile(file, outDirectory)) {
                        movedNames.add(file.name)
                    } else {
                        fileService.errorSet.add(RuntimeException("Falha ao mover o arquivo: ${file.name}"))
                    }
                }
                userInterface.closeProgress()

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

        userInterface.showProgress("Buscando músicas sem letras", audioFiles.size)
        val musicFilesWithoutLyrics = mutableListOf<File>()

        audioFiles.forEachIndexed { index, audioFile ->
            userInterface.updateProgress(index + 1, audioFile.name)
            val hasLyric = audioFile.parentFile.walk().any { audioFileParent ->
                audioFileParent.name.equals(audioFile.nameWithoutExtension + ".lrc")
            }
            if (!hasLyric) {
                musicFilesWithoutLyrics.add(audioFile)
            }
        }
        userInterface.closeProgress()

        File("MusicsWithoutLyrics_${musicFilesWithoutLyrics.hashCode()}.txt").writeText(
            musicFilesWithoutLyrics.joinToString("\n") { it.name }
        )

        return musicFilesWithoutLyrics
    }

    fun findLyricsWithoutSync(): List<File> {
        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        val outDirectory = directoryService.getDirectory("Selecione o diretório de saída")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        userInterface.showProgress("Verificando sincronia das letras", lyricFiles.size)
        val lyricsFilesWithoutSync = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            lyricFile.readLines().none { line -> line.contains(Regex("\\d")) }
        }
        userInterface.closeProgress()

        File("LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt").writeText(
            lyricsFilesWithoutSync.joinToString("\n") { it.name }
        )

        userInterface.showProgress("Movendo letras sem sincronia", lyricsFilesWithoutSync.size)
        lyricsFilesWithoutSync.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            fileService.moveLyricFile(lyric, outDirectory)
        }
        userInterface.closeProgress()

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return lyricsFilesWithoutSync
    }

    fun findLyricsWithV1Text(): List<File> {
        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        userInterface.showProgress("Buscando letras com 'V1:'", lyricFiles.size)
        val lyricsFilesWithV1 = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            lyricFile.readLines().any { line -> line.contains("v1:") }
        }
        userInterface.closeProgress()

        File("LyricsWithV1_${lyricsFilesWithV1.hashCode()}.txt").writeText(
            lyricsFilesWithV1.joinToString("\n") { it.name }
        )

        userInterface.showProgress("Removendo 'V1:'", lyricsFilesWithV1.size)
        lyricsFilesWithV1.forEachIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            lyricFile.readLines().forEach { line ->
                if (line.contains("v1:")) {
                    val newLine = line.replace("v1:", "")
                    lyricFile.writeText(lyricFile.readText().replace(line, newLine))
                    fileService.changedSet.add(lyricFile.name)
                }
            }
        }
        userInterface.closeProgress()

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

        userInterface.showProgress("Movendo letras isoladas", aloneLyrics.size)
        aloneLyrics.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            val parentFolder = lyric.parentFile
            fileService.moveLyricFile(lyric, outDirectory)
            movedLyricsNames.add(lyric.name)

            if (parentFolder.isDirectory && parentFolder.listFiles()?.isEmpty() == true) {
                if (parentFolder.delete()) {
                    fileService.changedSet.add("Pasta vazia excluída: ${parentFolder.absolutePath}")
                }
            }
        }
        userInterface.closeProgress()

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

        userInterface.showProgress("Verificando nomenclatura", audioFiles.size)
        audioFiles.forEachIndexed { index, audioFile ->
            userInterface.updateProgress(index + 1, audioFile.name)
            if (!nomenclatureValidator.isValid(audioFile, musicDirectory)) {
                incorrectlyNamedFiles.add(audioFile)
            }
        }
        userInterface.closeProgress()

        processIncorrectlyNamedFiles(
            incorrectlyNamedFiles,
            "AdvancedIncorrectNomenclatureMoved",
            "arquivos fora do padrão",
            "Todos os arquivos verificados estão com a nomenclatura e estrutura corretas."
        )

        return incorrectlyNamedFiles
    }

    fun verifyFakeFlacFiles(): List<File> {
        val musicDirectory = getValidatedMusicDirectory("Selecione o diretório das músicas para analisar o espectro")
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory).filter { it.extension.lowercase() == "flac" }

        if (audioFiles.isEmpty()) {
            fileService.changedSet.add("Nenhum arquivo FLAC encontrado para análise.")
            userInterface.showResult(fileService.changedSet, fileService.errorSet)
            return emptyList()
        }

        val isFullAnalysis = userInterface.askForAnalysisType()

        val fakeFiles = mutableListOf<File>()
        val analysisResults = mutableListOf<String>()

        userInterface.showProgress("Analisando espectro de áudio", audioFiles.size)
        audioFiles.forEachIndexed { index, file ->
            userInterface.updateProgress(index + 1, file.name)
            val result = audioAnalysisService.analyzeCutoff(file, isFullAnalysis)
            if (result.isFake) {
                fakeFiles.add(file)
                analysisResults.add("${file.name} -> ${result.message}")
            }
        }
        userInterface.closeProgress()

        if (fakeFiles.isNotEmpty()) {
            val txtFileName = "FakeLossless_${fakeFiles.hashCode()}.txt"
            File(txtFileName).writeText(analysisResults.joinToString("\n"))

            val shouldMove = userInterface.askToMoveFakeLossless(fakeFiles.size, txtFileName)

            if (shouldMove) {
                val outDirectory = directoryService.getDirectory("Selecione o diretório para mover os arquivos Fake Lossless")
                val movedNames = mutableListOf<String>()

                fakeFiles.forEach { file ->
                    if (fileService.moveFile(file, outDirectory)) {
                        movedNames.add(file.name)
                    } else {
                        fileService.errorSet.add(RuntimeException("Falha ao mover o arquivo: ${file.name}"))
                    }
                }

                fileService.changedSet.add("Arquivos listados em: $txtFileName\nMovidos ${movedNames.size} de ${fakeFiles.size} arquivos Fake Lossless para ${outDirectory.absolutePath}.")
            } else {
                fileService.changedSet.add("Encontrados ${fakeFiles.size} arquivos Fake Lossless.\nApenas listados no arquivo gerado: $txtFileName.")
            }
        } else {
            fileService.changedSet.add("Nenhum arquivo Fake Lossless detectado nos ${audioFiles.size} arquivos analisados.")
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return fakeFiles
    }
}
