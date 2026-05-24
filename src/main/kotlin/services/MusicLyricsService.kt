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

/**
 * Orchestrator service that centralizes all business rules related to music and lyrics.
 * Coordinates pairing actions, nomenclature verification, spectrum analysis, and synchronization.
 */
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
            userInterface.showError(Messages.get("error.read_permission", musicDirectory))
            throw RuntimeException(Messages.get("error.read_permission", musicDirectory))
        }
        return musicDirectory
    }

    private fun processIncorrectlyNamedFiles(
        incorrectlyNamedFiles: List<File>,
        txtPrefix: String,
        movedMessageKey: String,
        allCorrectMessageKey: String,
    ) {
        if (incorrectlyNamedFiles.isNotEmpty()) {
            val txtFileName = "${txtPrefix}_${incorrectlyNamedFiles.hashCode()}.txt"
            File(txtFileName).writeText(
                incorrectlyNamedFiles.joinToString("\n") { it.name }
            )

            val shouldMove = userInterface.askToMoveIncorrectFiles(incorrectlyNamedFiles.size, txtFileName)

            if (shouldMove) {
                val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_incorrect"))
                val movedNames = mutableListOf<String>()

                userInterface.showProgress(Messages.get("progress.move_incorrect"), incorrectlyNamedFiles.size)
                incorrectlyNamedFiles.forEachIndexed { index, file ->
                    userInterface.updateProgress(index + 1, file.name)
                    if (fileService.moveFile(file, outDirectory)) {
                        movedNames.add(file.name)
                    } else {
                        fileService.errorSet.add(RuntimeException("Failed to move file: ${file.name}"))
                    }
                }
                userInterface.closeProgress()

                fileService.changedSet.add(Messages.get(movedMessageKey, txtFileName, movedNames.size, incorrectlyNamedFiles.size, outDirectory.absolutePath))
            } else {
                fileService.changedSet.add(Messages.get("result.found_incorrect", incorrectlyNamedFiles.size, txtFileName))
            }
        } else {
            fileService.changedSet.add(Messages.get(allCorrectMessageKey))
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)
    }

    fun organizeMusicAndLyrics() {
        val musicDirectory = directoryService.getDirectory(Messages.get("prompt.select.music_dir"))

        if (!musicDirectory.canWrite()) {
            userInterface.showError(Messages.get("error.write_permission", musicDirectory))
            return
        }

        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))

        if (!lyricsDirectory.canWrite()) {
            userInterface.showError(Messages.get("error.write_permission", lyricsDirectory))
            return
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        val filePairs = matchService.matchFiles(lyricFiles, audioFiles)

        matchService.handleFilePairs(filePairs)

        fileService.handleUnmatchedFiles(musicDirectory, lyricsDirectory, userInterface)

        userInterface.showResult(fileService.changedSet, fileService.errorSet)
    }

    fun findMusicWithoutLyricsPair(): List<File> {
        val musicDirectory = getValidatedMusicDirectory(Messages.get("prompt.select.music_dir"))
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)

        userInterface.showProgress(Messages.get("progress.find_no_lyrics"), audioFiles.size)
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
        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))
        val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.out_dir"))
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        userInterface.showProgress(Messages.get("progress.check_sync"), lyricFiles.size)
        val lyricsFilesWithoutSync = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            lyricFile.readLines().none { line -> line.contains(Regex("\\d")) }
        }
        userInterface.closeProgress()

        File("LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt").writeText(
            lyricsFilesWithoutSync.joinToString("\n") { it.name }
        )

        userInterface.showProgress(Messages.get("progress.move_unsynced"), lyricsFilesWithoutSync.size)
        lyricsFilesWithoutSync.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            fileService.moveLyricFile(lyric, outDirectory)
        }
        userInterface.closeProgress()

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return lyricsFilesWithoutSync
    }

    fun findLyricsWithV1Text(): List<File> {
        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        userInterface.showProgress(Messages.get("progress.find_v1"), lyricFiles.size)
        val lyricsFilesWithV1 = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            lyricFile.readLines().any { line -> line.contains("v1:") }
        }
        userInterface.closeProgress()

        File("LyricsWithV1_${lyricsFilesWithV1.hashCode()}.txt").writeText(
            lyricsFilesWithV1.joinToString("\n") { it.name }
        )

        userInterface.showProgress(Messages.get("progress.remove_v1"), lyricsFilesWithV1.size)
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
        val mainDirectory = directoryService.getDirectory(Messages.get("prompt.select.main_dir"))
        val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_alone_lyrics"))

        val lyricFiles = lyricFileHandler.getLyricFiles(mainDirectory)
        val audioFiles = audioFileHandler.getAudioFiles(mainDirectory)

        val audioNamesWithoutExtension = audioFiles.map { it.nameWithoutExtension }.toSet()

        val aloneLyrics = lyricFiles.filter { lyricFile ->
            !audioNamesWithoutExtension.contains(lyricFile.nameWithoutExtension)
        }

        val movedLyricsNames = mutableListOf<String>()

        userInterface.showProgress(Messages.get("progress.move_alone_lyrics"), aloneLyrics.size)
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
        val musicDirectory = getValidatedMusicDirectory(Messages.get("prompt.select.music_base_dir"))
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val incorrectlyNamedFiles = mutableListOf<File>()

        userInterface.showProgress(Messages.get("progress.check_nomenclature"), audioFiles.size)
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
            "result.moved_incorrect",
            "result.all_correct"
        )

        return incorrectlyNamedFiles
    }

    fun verifyFakeFlacFiles(): List<File> {
        val musicDirectory = getValidatedMusicDirectory(Messages.get("prompt.select.spectrum_dir"))
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory).filter { it.extension.lowercase() == "flac" }

        if (audioFiles.isEmpty()) {
            fileService.changedSet.add(Messages.get("result.no_flac"))
            userInterface.showResult(fileService.changedSet, fileService.errorSet)
            return emptyList()
        }

        val isFullAnalysis = userInterface.askForAnalysisType()

        val fakeFiles = mutableListOf<File>()
        val analysisResults = mutableListOf<String>()

        userInterface.showProgress(Messages.get("progress.analyze_spectrum"), audioFiles.size)
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
                val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_fake"))
                val movedNames = mutableListOf<String>()

                fakeFiles.forEach { file ->
                    if (fileService.moveFile(file, outDirectory)) {
                        movedNames.add(file.name)
                    } else {
                        fileService.errorSet.add(RuntimeException("Failed to move file: ${file.name}"))
                    }
                }

                fileService.changedSet.add(Messages.get("result.moved_fake", txtFileName, movedNames.size, fakeFiles.size, outDirectory.absolutePath))
            } else {
                fileService.changedSet.add(Messages.get("result.found_fake", fakeFiles.size, txtFileName))
            }
        } else {
            fileService.changedSet.add(Messages.get("result.no_fake", audioFiles.size))
        }

        userInterface.showResult(fileService.changedSet, fileService.errorSet)

        return fakeFiles
    }
}
