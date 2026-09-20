package services

import interfaces.AudioAnalysisService
import interfaces.AudioFileHandler
import interfaces.AudioNomenclatureValidator
import interfaces.DirectoryService
import interfaces.FileService
import interfaces.LyricFileHandler
import interfaces.MatchService
import interfaces.UserInterface
import java.io.File
import models.OperationResult

/**
 * Orchestrator service that centralizes all business rules related to music and lyrics.
 * Coordinates pairing actions, nomenclature verification, spectrum analysis, and synchronization.
 */
class MusicLyricsService(
    private val userInterface: UserInterface,
    private val directoryService: DirectoryService,
    private val audioFileHandler: AudioFileHandler,
    private val lyricFileHandler: LyricFileHandler,
    private val matchService: MatchService,
    private val fileService: FileService,
    private val nomenclatureValidator: AudioNomenclatureValidator,
    private val audioAnalysisService: AudioAnalysisService,
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
    ): OperationResult {
        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()
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
                    try {
                        if (fileService.moveFile(file, outDirectory)) {
                            movedNames.add(file.name)
                        } else {
                            errors.add(RuntimeException("Failed to move file: ${file.name}"))
                        }
                    } catch (e: Exception) {
                        errors.add(e)
                    }
                }
                userInterface.closeProgress()

                changes.add(Messages.get(movedMessageKey, txtFileName, movedNames.size, incorrectlyNamedFiles.size, outDirectory.absolutePath))
            } else {
                changes.add(Messages.get("result.found_incorrect", incorrectlyNamedFiles.size, txtFileName))
            }
        } else {
            changes.add(Messages.get(allCorrectMessageKey))
        }

        return OperationResult(changes, errors)
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

        var result = matchService.handleFilePairs(filePairs)

        val unmatchedResult = fileService.handleUnmatchedFiles(musicDirectory, lyricsDirectory, userInterface)
        result += unmatchedResult

        userInterface.showResult(result.changedSet, result.errorSet)
    }

    fun findMusicWithoutLyricsPair(): List<File> {
        val musicDirectory = getValidatedMusicDirectory(Messages.get("prompt.select.music_dir"))
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)

        userInterface.showProgress(Messages.get("progress.find_no_lyrics"), audioFiles.size)
        val musicFilesWithoutLyrics = mutableListOf<File>()

        audioFiles.forEachIndexed { index, audioFile ->
            userInterface.updateProgress(index + 1, audioFile.name)
            val parent = audioFile.parentFile
            val hasLyric = parent != null && parent.listFiles { _, name ->
                name.equals("${audioFile.nameWithoutExtension}.lrc", ignoreCase = true)
            }?.isNotEmpty() == true

            if (!hasLyric) {
                musicFilesWithoutLyrics.add(audioFile)
            }
        }
        userInterface.closeProgress()

        val changes = mutableSetOf<String>()
        if (musicFilesWithoutLyrics.isNotEmpty()) {
            val txtFileName = "MusicsWithoutLyrics_${musicFilesWithoutLyrics.hashCode()}.txt"
            File(txtFileName).writeText(
                musicFilesWithoutLyrics.joinToString("\n") { it.name }
            )
            changes.add("Found ${musicFilesWithoutLyrics.size} audio file(s) without .lrc.\nList saved to: $txtFileName")
        } else {
            changes.add("All ${audioFiles.size} audio file(s) have matching .lrc lyrics.")
        }

        userInterface.showResult(changes, emptySet())

        return musicFilesWithoutLyrics
    }

    fun findLyricsWithoutSync(): List<File> {
        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))
        val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.out_dir"))
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        val timestampRegex = Regex("""\[\d{1,2}:\d{2}""")

        userInterface.showProgress(Messages.get("progress.check_sync"), lyricFiles.size)
        val lyricsFilesWithoutSync = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            try {
                !timestampRegex.containsMatchIn(lyricFile.readText())
            } catch (_: Exception) {
                false
            }
        }
        userInterface.closeProgress()

        if (lyricsFilesWithoutSync.isNotEmpty()) {
            File("LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt").writeText(
                lyricsFilesWithoutSync.joinToString("\n") { it.name }
            )
        }

        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()

        userInterface.showProgress(Messages.get("progress.move_unsynced"), lyricsFilesWithoutSync.size)
        lyricsFilesWithoutSync.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            val (_, moveResult) = fileService.moveLyricFile(lyric, outDirectory)
            changes.addAll(moveResult.changedSet)
            errors.addAll(moveResult.errorSet)
        }
        userInterface.closeProgress()

        userInterface.showResult(changes, errors)

        return lyricsFilesWithoutSync
    }

    fun findLyricsWithV1Text(): List<File> {
        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        userInterface.showProgress(Messages.get("progress.find_v1"), lyricFiles.size)
        val lyricsFilesWithV1 = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            try {
                lyricFile.readText().contains("v1:", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
        userInterface.closeProgress()

        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()

        if (lyricsFilesWithV1.isNotEmpty()) {
            val txtFileName = "LyricsWithV1_${lyricsFilesWithV1.hashCode()}.txt"
            File(txtFileName).writeText(
                lyricsFilesWithV1.joinToString("\n") { it.name }
            )

            userInterface.showProgress(Messages.get("progress.remove_v1"), lyricsFilesWithV1.size)
            lyricsFilesWithV1.forEachIndexed { index, lyricFile ->
                userInterface.updateProgress(index + 1, lyricFile.name)
                try {
                    val originalText = lyricFile.readText()
                    if (originalText.contains("v1:", ignoreCase = true)) {
                        val updatedText = originalText.replace(Regex("(?i)v1:"), "")
                        lyricFile.writeText(updatedText)
                        changes.add(lyricFile.name)
                    }
                } catch (e: Exception) {
                    errors.add(e)
                }
            }
            userInterface.closeProgress()
        }

        userInterface.showResult(changes, errors)

        return lyricsFilesWithV1
    }

    fun findAndMoveAloneLyrics(): List<File> {
        val mainDirectory = directoryService.getDirectory(Messages.get("prompt.select.main_dir"))
        val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_alone_lyrics"))

        val lyricFiles = lyricFileHandler.getLyricFiles(mainDirectory)
        val audioFiles = audioFileHandler.getAudioFiles(mainDirectory)

        val audioNamesWithoutExtension = audioFiles.map { it.nameWithoutExtension.lowercase() }.toSet()

        val aloneLyrics = lyricFiles.filter { lyricFile ->
            !audioNamesWithoutExtension.contains(lyricFile.nameWithoutExtension.lowercase())
        }

        val movedLyricsNames = mutableListOf<String>()
        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()

        userInterface.showProgress(Messages.get("progress.move_alone_lyrics"), aloneLyrics.size)
        aloneLyrics.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            val parentFolder = lyric.parentFile
            val (_, moveResult) = fileService.moveLyricFile(lyric, outDirectory)
            changes.addAll(moveResult.changedSet)
            errors.addAll(moveResult.errorSet)
            movedLyricsNames.add(lyric.name)

            if (parentFolder != null && parentFolder != mainDirectory && parentFolder.isDirectory && parentFolder.listFiles()?.isEmpty() == true) {
                if (parentFolder.delete()) {
                    changes.add("Pasta vazia excluída: ${parentFolder.absolutePath}")
                }
            }
        }
        userInterface.closeProgress()

        if (movedLyricsNames.isNotEmpty()) {
            File("AloneLyricsMoved_${movedLyricsNames.hashCode()}.txt").writeText(
                movedLyricsNames.joinToString("\n")
            )
        }

        userInterface.showResult(changes, errors)

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

        val (changedSet, errorSet) = processIncorrectlyNamedFiles(
            incorrectlyNamedFiles,
            "AdvancedIncorrectNomenclatureMoved",
            "result.moved_incorrect",
            "result.all_correct"
        )
        userInterface.showResult(changedSet, errorSet)

        return incorrectlyNamedFiles
    }

    fun verifyFakeFlacFiles(): List<File> {
        val musicDirectory = getValidatedMusicDirectory(Messages.get("prompt.select.spectrum_dir"))
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory).filter { it.extension.lowercase() == "flac" }

        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()

        if (audioFiles.isEmpty()) {
            changes.add(Messages.get("result.no_flac"))
            userInterface.showResult(changes, errors)
            return emptyList()
        }

        val isFullAnalysis = userInterface.askForAnalysisType()

        val fakeFiles = mutableListOf<File>()
        val analysisResults = mutableListOf<String>()

        userInterface.showProgress(Messages.get("progress.analyze_spectrum"), audioFiles.size)
        audioFiles.forEachIndexed { index, file ->
            userInterface.updateProgress(index + 1, file.name)
            val (isFake, _, message) = audioAnalysisService.analyzeCutoff(file, isFullAnalysis)
            if (isFake) {
                fakeFiles.add(file)
                analysisResults.add("${file.name} -> $message")
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
                    try {
                        if (fileService.moveFile(file, outDirectory)) {
                            movedNames.add(file.name)
                        } else {
                            errors.add(RuntimeException("Failed to move file: ${file.name}"))
                        }
                    } catch (e: Exception) {
                        errors.add(e)
                    }
                }

                changes.add(Messages.get("result.moved_fake", txtFileName, movedNames.size, fakeFiles.size, outDirectory.absolutePath))
            } else {
                changes.add(Messages.get("result.found_fake", fakeFiles.size, txtFileName))
            }
        } else {
            changes.add(Messages.get("result.no_fake", audioFiles.size))
        }

        userInterface.showResult(changes, errors)

        return fakeFiles
    }
}
