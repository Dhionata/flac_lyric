package services

import interfaces.AudioAnalysisService
import interfaces.AudioFileHandler
import interfaces.AudioNomenclatureValidator
import interfaces.AuditLogService
import interfaces.DirectoryService
import interfaces.FileService
import interfaces.LogService
import interfaces.LyricContentCleaner
import interfaces.LyricFileHandler
import interfaces.LyricSyncValidator
import interfaces.MatchService
import interfaces.UserInterface
import java.io.File
import models.LyricCleanOptions
import models.LyricValidationIssue
import models.OperationResult

/**
 * Orchestrator service that centralizes all business rules related to music and lyrics.
 * Coordinates pairing actions, nomenclature verification, spectrum analysis, content cleaning, and synchronization.
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
    private val lyricSyncValidator: LyricSyncValidator = LyricSyncValidatorImpl(),
    private val lyricContentCleaner: LyricContentCleaner = LyricContentCleanerImpl(),
    private val logService: LogService = JdkLogService(MusicLyricsService::class.java),
    private val auditLogService: AuditLogService = AuditLogServiceImpl(),
) {

    private fun wrapFileException(file: File, e: Exception): Exception {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val formatted = if (raw.contains(file.name) && raw.contains(file.absolutePath)) {
            raw
        } else if (raw.contains(file.name)) {
            "$raw [${file.absolutePath}]"
        } else {
            "${file.name}: $raw [${file.absolutePath}]"
        }
        return RuntimeException(formatted, e)
    }

    private fun getValidatedMusicDirectory(message: String): File {
        val musicDirectory = directoryService.getDirectory(message)
        if (!musicDirectory.canRead()) {
            val errMsg = Messages.get("error.read_permission", musicDirectory)
            auditLogService.logError("Directory Validation", musicDirectory, RuntimeException(errMsg))
            userInterface.showError(errMsg)
            throw RuntimeException(errMsg)
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
            val reportFile = AppOutputDirectories.createReportFile(
                AppOutputDirectories.SUBDIR_INCORRECT_NOMENCLATURE, "${txtPrefix}_${incorrectlyNamedFiles.hashCode()}.txt"
            )
            val txtFileName = reportFile.path
            reportFile.writeText(
                incorrectlyNamedFiles.joinToString("\n") { it.name })
            auditLogService.logReportGenerated("Option 5 - Nomenclature Report", reportFile, incorrectlyNamedFiles.size)

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
                            val ex = RuntimeException(Messages.get("error.move_simple_failed", file.name))
                            auditLogService.logError("Option 5 - Move Incorrect File", file, ex)
                            errors.add(wrapFileException(file, ex))
                        }
                    } catch (e: Exception) {
                        val wrapped = wrapFileException(file, e)
                        auditLogService.logError("Option 5 - Move Incorrect File", file, wrapped)
                        errors.add(wrapped)
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
            val errMsg = Messages.get("error.write_permission", musicDirectory)
            auditLogService.logError("Option 0 - Organize Music and Lyrics", musicDirectory, RuntimeException(errMsg))
            userInterface.showError(errMsg)
            return
        }

        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))

        if (!lyricsDirectory.canWrite()) {
            val errMsg = Messages.get("error.write_permission", lyricsDirectory)
            auditLogService.logError("Option 0 - Organize Music and Lyrics", lyricsDirectory, RuntimeException(errMsg))
            userInterface.showError(errMsg)
            return
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        logService.info("log.organize.started", audioFiles.size, lyricFiles.size)

        val filePairs = matchService.matchFiles(lyricFiles, audioFiles)
        auditLogService.logAnalysis(
            operation = "Option 0 - Match Audio & Lyrics",
            targetDir = musicDirectory,
            messageKey = "audit.analysis.organize_matched",
            messageArgs = listOf(audioFiles.size, lyricFiles.size, filePairs.size),
            metadata = mapOf(
                "audioCount" to audioFiles.size,
                "lyricCount" to lyricFiles.size,
                "matchedPairs" to filePairs.size
            )
        )

        var result = matchService.handleFilePairs(filePairs)

        val unmatchedResult = fileService.handleUnmatchedFiles(musicDirectory, lyricsDirectory, userInterface)
        result += unmatchedResult
        result.errorSet.forEach { err ->
            auditLogService.logError("Option 0 - Organize Music and Lyrics", null, err)
        }

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
                auditLogService.logFileAnalyzed(
                    operation = "Option 1 - Missing Lyrics Check",
                    targetFile = audioFile,
                    messageKey = "audit.file.missing_lyric",
                    messageArgs = listOf(audioFile.name)
                )
            }
        }
        userInterface.closeProgress()

        auditLogService.logAnalysis(
            operation = "Option 1 - Missing Lyrics Check",
            targetDir = musicDirectory,
            messageKey = "audit.analysis.missing_lyrics",
            messageArgs = listOf(audioFiles.size, musicFilesWithoutLyrics.size),
            metadata = mapOf("totalAudio" to audioFiles.size, "missingCount" to musicFilesWithoutLyrics.size)
        )

        val changes = mutableSetOf<String>()
        if (musicFilesWithoutLyrics.isNotEmpty()) {
            val reportFile = AppOutputDirectories.createReportFile(
                AppOutputDirectories.SUBDIR_NO_LYRICS, "MusicsWithoutLyrics_${musicFilesWithoutLyrics.hashCode()}.txt"
            )
            val txtFileName = reportFile.path
            reportFile.writeText(
                musicFilesWithoutLyrics.joinToString("\n") { it.name })
            auditLogService.logReportGenerated("Option 1 - Missing Lyrics Report", reportFile, musicFilesWithoutLyrics.size)
            changes.add(Messages.get("result.found_no_lyrics", musicFilesWithoutLyrics.size, txtFileName))
        } else {
            changes.add(Messages.get("result.no_lyrics_unpaired", audioFiles.size))
        }

        userInterface.showResult(changes, emptySet())

        return musicFilesWithoutLyrics
    }

    fun findLyricsWithoutSync(): List<File> {
        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))
        val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.out_dir"))
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        val scanErrors = mutableSetOf<Exception>()
        userInterface.showProgress(Messages.get("progress.check_sync"), lyricFiles.size)
        val lyricsFilesWithoutSync = lyricFiles.filterIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            try {
                val unsynced = !LyricSyntaxPatterns.TIMESTAMP_REGEX.containsMatchIn(lyricFile.readText())
                if (unsynced) {
                    auditLogService.logFileAnalyzed(
                        operation = "Option 2 - Unsynced Check",
                        targetFile = lyricFile,
                        messageKey = "audit.file.unsynced_lyric",
                        messageArgs = listOf(lyricFile.name)
                    )
                }
                unsynced
            } catch (e: Exception) {
                val wrapped = wrapFileException(lyricFile, e)
                scanErrors.add(wrapped)
                auditLogService.logError("Option 2 - Read Lyric File", lyricFile, wrapped)
                false
            }
        }
        userInterface.closeProgress()

        auditLogService.logAnalysis(
            operation = "Option 2 - Unsynced Check",
            targetDir = lyricsDirectory,
            messageKey = "audit.analysis.unsynced_lyrics",
            messageArgs = listOf(lyricFiles.size, lyricsFilesWithoutSync.size, scanErrors.size),
            metadata = mapOf(
                "scannedLyrics" to lyricFiles.size,
                "unsyncedCount" to lyricsFilesWithoutSync.size,
                "readErrors" to scanErrors.size
            )
        )

        if (lyricsFilesWithoutSync.isNotEmpty()) {
            val reportFile = AppOutputDirectories.createReportFile(
                AppOutputDirectories.SUBDIR_UNSYNCED_LYRICS, "LyricsWithoutSync_${lyricsFilesWithoutSync.hashCode()}.txt"
            )
            reportFile.writeText(
                lyricsFilesWithoutSync.joinToString("\n") { it.name })
            auditLogService.logReportGenerated("Option 2 - Unsynced Lyrics Report", reportFile, lyricsFilesWithoutSync.size)
        }

        val changes = mutableSetOf<String>()
        val errors = scanErrors.toMutableSet()

        userInterface.showProgress(Messages.get("progress.move_unsynced"), lyricsFilesWithoutSync.size)
        lyricsFilesWithoutSync.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            val [_, moveResult] = fileService.moveLyricFile(lyric, outDirectory)
            changes.addAll(moveResult.changedSet)
            moveResult.errorSet.forEach { err ->
                val wrapped = wrapFileException(lyric, err)
                auditLogService.logError("Option 2 - Move Unsynced Lyric", lyric, wrapped)
                errors.add(wrapped)
            }
        }
        userInterface.closeProgress()

        userInterface.showResult(changes, errors)

        return lyricsFilesWithoutSync
    }

    /**
     * Scans and cleans `.lrc` files using [LyricContentCleaner] according to the provided [options]
     * (speaker markers `v1:/v2:`, background tags `[bg:]`, inline `<mm:ss>` word tags, credits/watermarks,
     * out-of-order/duplicated multi-timestamps, UTF-8 BOM, and optional unsynced lines in synced files),
     * recording a complete before/after audit log for every modified file.
     */
    fun findLyricsWithV1Text(
        options: LyricCleanOptions = LyricCleanOptions(),
        removeEmbeddedSyncedLyricsFromAudio: Boolean = true,
    ): List<File> {
        val lyricsDirectory = directoryService.getDirectory(Messages.get("prompt.select.lyrics_dir"))
        val ownAuditRun = AuditRunContext.current() == null
        val runBlock: () -> List<File> = {
            if (ownAuditRun) {
                auditLogService.logOperationStart(
                    actionName = "Option 3 - Clean and Normalize .lrc Files",
                    options = mapOf(
                        "lyricsDirectory" to lyricsDirectory.absolutePath,
                        "removeSpeakerAndBgTags" to options.removeSpeakerAndBgTags,
                        "removeInlineWordTimestamps" to options.removeInlineWordTimestamps,
                        "removeCreditsAndWatermarks" to options.removeCreditsAndWatermarks,
                        "sortAndDeduplicateMultiTimestamps" to options.sortAndDeduplicateMultiTimestamps,
                        "removeUnsyncedLinesInSyncedFile" to options.removeUnsyncedLinesInSyncedFile,
                        "stripBom" to options.stripBom,
                        "normalizeTimestampsToCentiseconds" to options.normalizeTimestampsToCentiseconds,
                        "removeTrailingEmptyTimestampLines" to options.removeTrailingEmptyTimestampLines,
                        "removeEmbeddedSyncedLyricsFromAudio" to removeEmbeddedSyncedLyricsFromAudio
                    )
                )
            }

            val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

            val cleanedMatches = mutableListOf<Pair<File, models.LyricCleanResult>>()
            val errors = mutableSetOf<Exception>()

            userInterface.showProgress(Messages.get("progress.find_v1"), lyricFiles.size)
            lyricFiles.forEachIndexed { index, lyricFile ->
                userInterface.updateProgress(index + 1, lyricFile.name)
                try {
                    val originalText = lyricFile.readText()
                    val cleanResult = lyricContentCleaner.clean(originalText, options)
                    if (cleanResult.modified) {
                        cleanedMatches.add(lyricFile to cleanResult)
                    }
                } catch (e: Exception) {
                    val wrapped = wrapFileException(lyricFile, e)
                    errors.add(wrapped)
                    auditLogService.logError("Option 3 - Read .lrc File", lyricFile, wrapped)
                }
            }
            userInterface.closeProgress()

            val changes = mutableSetOf<String>()
            val modifiedFiles = cleanedMatches.map { it.first }.toMutableList()
            val reportLines = mutableListOf<String>()

            if (cleanedMatches.isNotEmpty()) {
                userInterface.showProgress(Messages.get("progress.remove_v1"), cleanedMatches.size)
                cleanedMatches.forEachIndexed { index, [lyricFile, cleanResult] ->
                    userInterface.updateProgress(index + 1, lyricFile.name)
                    try {
                        lyricFile.writeText(cleanResult.cleanedContent)
                        auditLogService.logContentModification(
                            operation = "Option 3 - Clean and Normalize .lrc",
                            targetFile = lyricFile,
                            beforeContent = cleanResult.originalContent,
                            afterContent = cleanResult.cleanedContent,
                            metadata = mapOf("appliedFixes" to cleanResult.appliedFixes.joinToString(", "))
                        )
                        val summary = "${lyricFile.name} [${cleanResult.appliedFixes.joinToString(", ")}]"
                        reportLines.add(summary)
                        changes.add("${lyricFile.name} (${cleanResult.appliedFixes.joinToString(", ")})")
                    } catch (e: Exception) {
                        val wrapped = wrapFileException(lyricFile, e)
                        errors.add(wrapped)
                        auditLogService.logError("Option 3 - Write Cleaned .lrc", lyricFile, wrapped)
                    }
                }
                userInterface.closeProgress()
            }

            var audioCheckedCount = 0
            var audioRemovedCount = 0
            var audioSkippedUnsupportedCount = 0

            if (removeEmbeddedSyncedLyricsFromAudio) {
                val lrcKeys = lyricFiles.map {
                    (it.parentFile?.canonicalPath ?: "") to it.nameWithoutExtension.lowercase()
                }.toSet()
                val audioWithLrc = audioFileHandler.getAudioFiles(lyricsDirectory).filter { audio ->
                    val key = (audio.parentFile?.canonicalPath ?: "") to audio.nameWithoutExtension.lowercase()
                    key in lrcKeys
                }
                audioCheckedCount = audioWithLrc.size

                if (audioWithLrc.isNotEmpty()) {
                    userInterface.showProgress(Messages.get("progress.clean_embedded_lyrics"), audioWithLrc.size)
                    audioWithLrc.forEachIndexed { index, audioFile ->
                        userInterface.updateProgress(index + 1, audioFile.name)
                        try {
                            when (val outcome = EmbeddedLyricsHelper.removeEmbeddedSyncedLyricsWithOutcome(audioFile)) {
                                is EmbeddedCleanupOutcome.Removed -> {
                                    audioRemovedCount++
                                    val tagsDesc = outcome.info.tagKeys.joinToString(", ")
                                    auditLogService.logContentModification(
                                        operation = "Option 3 - Remove Embedded Synced Lyrics",
                                        targetFile = audioFile,
                                        beforeContent = outcome.info.content,
                                        afterContent = "(removed embedded tags: $tagsDesc)",
                                        metadata = mapOf("removedTags" to tagsDesc)
                                    )
                                    modifiedFiles.add(audioFile)
                                    reportLines.add("${audioFile.name} [embedded: $tagsDesc]")
                                    changes.add("${audioFile.name} (embedded $tagsDesc removed)")
                                }
                                is EmbeddedCleanupOutcome.SkippedUnsupported -> {
                                    audioSkippedUnsupportedCount++
                                    auditLogService.logSkipped(
                                        operation = "Option 3 - Embedded Lyrics Cleanup",
                                        targetFile = audioFile,
                                        reasonKey = outcome.reasonKey,
                                        reasonArgs = outcome.reasonArgs,
                                        metadata = mapOf("extension" to outcome.extension)
                                    )
                                }
                                EmbeddedCleanupOutcome.Clean -> Unit
                            }
                        } catch (e: Exception) {
                            val wrapped = wrapFileException(audioFile, e)
                            errors.add(wrapped)
                            auditLogService.logError("Option 3 - Clean Embedded Audio Lyrics", audioFile, wrapped)
                        }
                    }
                    userInterface.closeProgress()
                }
            }

            auditLogService.logAnalysis(
                operation = "Option 3 - Clean & Normalize Summary",
                targetDir = lyricsDirectory,
                messageKey = "audit.analysis.option3_summary",
                messageArgs = listOf(
                    lyricFiles.size,
                    cleanedMatches.size,
                    audioCheckedCount,
                    audioRemovedCount,
                    audioSkippedUnsupportedCount,
                    errors.size
                ),
                metadata = mapOf(
                    "lrcScanned" to lyricFiles.size,
                    "lrcCleaned" to cleanedMatches.size,
                    "audioChecked" to audioCheckedCount,
                    "embeddedRemoved" to audioRemovedCount,
                    "skippedUnsupported" to audioSkippedUnsupportedCount,
                    "errors" to errors.size
                )
            )

            if (reportLines.isNotEmpty()) {
                val reportFile = AppOutputDirectories.createReportFile(
                    AppOutputDirectories.SUBDIR_V1_LYRICS, "LyricsWithV1_${modifiedFiles.hashCode()}.txt"
                )
                reportFile.writeText(reportLines.joinToString("\n"))
                auditLogService.logReportGenerated("Option 3 - Cleaned Files Report", reportFile, reportLines.size)
            }

            if (ownAuditRun) {
                auditLogService.logOperationEnd(
                    actionName = "Option 3 - Clean and Normalize .lrc Files",
                    success = errors.isEmpty(),
                    processedCount = changes.size,
                    errorCount = errors.size,
                    details = changes.toList()
                )
            }

            userInterface.showResult(changes, errors)

            modifiedFiles
        }

        return if (ownAuditRun) {
            AuditRunContext.withRun(
                actionId = 3,
                actionKey = "menu.option.3.title",
                actionFallback = "Option 3 - Clean and Normalize .lrc Files"
            ) {
                runBlock()
            }
        } else {
            runBlock()
        }
    }

    /**
     * Finds and moves `.lrc` files that do not have a matching audio file **in the same folder**
     * (catching both completely unpaired lyrics and orphan `.lrc` files left in empty/different folders).
     * When [includeTxtFiles] is `true`, also moves stray `.txt` files found inside the music directory.
     */
    fun findAndMoveAloneLyrics(includeTxtFiles: Boolean = false): List<File> {
        val mainDirectory = directoryService.getDirectory(Messages.get("prompt.select.main_dir"))
        val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_alone_lyrics"))

        val lyricFiles = lyricFileHandler.getLyricFiles(mainDirectory)
        val audioFiles = audioFileHandler.getAudioFiles(mainDirectory)

        val audioParentAndNameSet = audioFiles.map { audio ->
            (audio.parentFile?.canonicalPath ?: "") to audio.nameWithoutExtension.lowercase()
        }.toSet()

        val aloneLyrics = lyricFiles.filter { lyricFile ->
            val key = (lyricFile.parentFile?.canonicalPath ?: "") to lyricFile.nameWithoutExtension.lowercase()
            !audioParentAndNameSet.contains(key)
        }.toMutableList()

        if (includeTxtFiles) {
            val outCanonical = outDirectory.canonicalPath
            val strayTxtFiles = mainDirectory.walkTopDown()
                .filter { file ->
                    file.isFile &&
                            file.extension.equals("txt", ignoreCase = true) &&
                            !file.canonicalPath.startsWith(outCanonical)
                }
                .toList()
            aloneLyrics.addAll(strayTxtFiles)
        }

        auditLogService.logAnalysis(
            operation = "Option 4 - Find Alone Lyrics",
            targetDir = mainDirectory,
            messageKey = "audit.analysis.alone_lyrics",
            messageArgs = listOf(lyricFiles.size, aloneLyrics.size),
            metadata = mapOf(
                "scannedLyrics" to lyricFiles.size,
                "aloneFound" to aloneLyrics.size,
                "includeTxtFiles" to includeTxtFiles
            )
        )

        val movedLyricsNames = mutableListOf<String>()
        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()

        userInterface.showProgress(Messages.get("progress.move_alone_lyrics"), aloneLyrics.size)
        aloneLyrics.forEachIndexed { index, lyric ->
            userInterface.updateProgress(index + 1, lyric.name)
            val parentFolder = lyric.parentFile
            if (lyric.extension.equals("lrc", ignoreCase = true)) {
                val [_, moveResult] = fileService.moveLyricFile(lyric, outDirectory)
                changes.addAll(moveResult.changedSet)
                moveResult.errorSet.forEach { err ->
                    val wrapped = wrapFileException(lyric, err)
                    auditLogService.logError("Option 4 - Move Alone Lyric", lyric, wrapped)
                    errors.add(wrapped)
                }
                if (moveResult.errorSet.isEmpty()) {
                    movedLyricsNames.add(lyric.name)
                }
            } else {
                try {
                    if (fileService.moveFile(lyric, outDirectory)) {
                        movedLyricsNames.add(lyric.name)
                        changes.add(Messages.get("result.lyric_moved", lyric.name, parentFolder?.absolutePath ?: "", outDirectory.absolutePath))
                    } else {
                        val ex = RuntimeException(Messages.get("error.move_simple_failed", lyric.name))
                        auditLogService.logError("Option 4 - Move File", lyric, ex)
                        errors.add(wrapFileException(lyric, ex))
                    }
                } catch (e: Exception) {
                    val wrapped = wrapFileException(lyric, e)
                    auditLogService.logError("Option 4 - Move File", lyric, wrapped)
                    errors.add(wrapped)
                }
            }

            if (parentFolder != null && parentFolder != mainDirectory && parentFolder.isDirectory && parentFolder.listFiles()?.isEmpty() == true) {
                if (parentFolder.delete()) {
                    auditLogService.logFileAction("DELETE_EMPTY_DIR", parentFolder)
                    changes.add(Messages.get("result.deleted_empty_dir", parentFolder.absolutePath))
                }
            }
        }
        userInterface.closeProgress()

        if (movedLyricsNames.isNotEmpty()) {
            val reportFile = AppOutputDirectories.createReportFile(
                AppOutputDirectories.SUBDIR_ALONE_LYRICS, "AloneLyricsMoved_${movedLyricsNames.hashCode()}.txt"
            )
            reportFile.writeText(
                movedLyricsNames.joinToString("\n")
            )
            auditLogService.logReportGenerated("Option 4 - Alone Lyrics Moved Report", reportFile, movedLyricsNames.size)
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
                auditLogService.logFileAnalyzed(
                    operation = "Option 5 - Nomenclature Check",
                    targetFile = audioFile,
                    messageKey = "audit.file.invalid_nomenclature",
                    messageArgs = listOf(audioFile.name)
                )
            }
        }
        userInterface.closeProgress()

        logService.info("log.nomenclature.checked", audioFiles.size, incorrectlyNamedFiles.size)
        auditLogService.logAnalysis(
            operation = "Option 5 - Nomenclature Check",
            targetDir = musicDirectory,
            messageKey = "audit.analysis.nomenclature",
            messageArgs = listOf(audioFiles.size, incorrectlyNamedFiles.size),
            metadata = mapOf("totalAudio" to audioFiles.size, "invalidCount" to incorrectlyNamedFiles.size)
        )

        val (changedSet, errorSet) = processIncorrectlyNamedFiles(
            incorrectlyNamedFiles, "AdvancedIncorrectNomenclatureMoved", "result.moved_incorrect", "result.all_correct"
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
            try {
                val (isFake, _, message) = audioAnalysisService.analyzeCutoff(file, isFullAnalysis)
                if (isFake) {
                    fakeFiles.add(file)
                    analysisResults.add("${file.name} -> $message")
                    auditLogService.logFileAnalyzed(
                        operation = "Option 6 - Fake FLAC Spectrum Check",
                        targetFile = file,
                        messageKey = "audit.file.fake_flac",
                        messageArgs = listOf(file.name, message),
                        metadata = mapOf("fullAnalysis" to isFullAnalysis, "detail" to message)
                    )
                }
            } catch (e: Exception) {
                val wrapped = wrapFileException(file, e)
                errors.add(wrapped)
                auditLogService.logError("Option 6 - Analyze FLAC Spectrum", file, wrapped)
            }
        }
        userInterface.closeProgress()

        logService.info("log.spectrum.checked", audioFiles.size, fakeFiles.size)
        auditLogService.logAnalysis(
            operation = "Option 6 - Fake FLAC Spectrum Check",
            targetDir = musicDirectory,
            messageKey = "audit.analysis.spectrum",
            messageArgs = listOf(audioFiles.size, fakeFiles.size),
            metadata = mapOf("flacCount" to audioFiles.size, "fakeDetected" to fakeFiles.size, "fullAnalysis" to isFullAnalysis)
        )

        if (fakeFiles.isNotEmpty()) {
            val reportFile = AppOutputDirectories.createReportFile(
                AppOutputDirectories.SUBDIR_FAKE_FLAC, "FakeLossless_${fakeFiles.hashCode()}.txt"
            )
            val txtFileName = reportFile.path
            reportFile.writeText(analysisResults.joinToString("\n"))
            auditLogService.logReportGenerated("Option 6 - Fake FLAC Report", reportFile, fakeFiles.size)

            val shouldMove = userInterface.askToMoveFakeLossless(fakeFiles.size, txtFileName)

            if (shouldMove) {
                val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_fake"))
                val movedNames = mutableListOf<String>()

                fakeFiles.forEach { file ->
                    try {
                        if (fileService.moveFile(file, outDirectory)) {
                            movedNames.add(file.name)
                        } else {
                            val ex = RuntimeException(Messages.get("error.move_simple_failed", file.name))
                            auditLogService.logError("Option 6 - Move Fake FLAC", file, ex)
                            errors.add(wrapFileException(file, ex))
                        }
                    } catch (e: Exception) {
                        val wrapped = wrapFileException(file, e)
                        auditLogService.logError("Option 6 - Move Fake FLAC", file, wrapped)
                        errors.add(wrapped)
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

    fun verifyLyricsSyncAndCompatibility(includeMissingLyrics: Boolean = true): List<LyricValidationIssue> {
        val musicDirectory = getValidatedMusicDirectory(Messages.get("prompt.select.music_lyrics_dir"))
        val lyricFiles = lyricFileHandler.getLyricFiles(musicDirectory)
        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)

        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()

        if (lyricFiles.isEmpty() && (!includeMissingLyrics || audioFiles.isEmpty())) {
            changes.add(Messages.get("result.no_lyrics_found"))
            userInterface.showResult(changes, errors)
            return emptyList()
        }

        val audioMap = audioFiles.groupBy { it.nameWithoutExtension.lowercase() }
        val lyricMap = lyricFiles.groupBy { it.nameWithoutExtension.lowercase() }
        val allIssues = mutableListOf<LyricValidationIssue>()

        val totalSteps = lyricFiles.size + if (includeMissingLyrics) audioFiles.size else 0
        userInterface.showProgress(Messages.get("progress.check_sync_compatibility"), totalSteps.coerceAtLeast(1))
        lyricFiles.forEachIndexed { index, lyricFile ->
            userInterface.updateProgress(index + 1, lyricFile.name)
            try {
                val candidates = audioMap[lyricFile.nameWithoutExtension.lowercase()]
                val sameFolderAudio = candidates?.find { it.parentFile == lyricFile.parentFile }

                if (sameFolderAudio == null) {
                    allIssues.add(
                        LyricValidationIssue(
                            lyricFile = lyricFile,
                            audioFile = null,
                            issueType = Messages.get("lyric.issue.type.orphan_lyric"),
                            description = Messages.get("lyric.issue.orphan_lyric")
                        )
                    )
                }

                val issues = lyricSyncValidator.validate(lyricFile, sameFolderAudio)
                allIssues.addAll(issues)
            } catch (e: Exception) {
                val wrapped = wrapFileException(lyricFile, e)
                errors.add(wrapped)
                auditLogService.logError("Option 7 - Validate Lyric Sync", lyricFile, wrapped)
            }
        }

        if (includeMissingLyrics) {
            audioFiles.forEachIndexed { index, audioFile ->
                userInterface.updateProgress(lyricFiles.size + index + 1, audioFile.name)
                try {
                    val siblingLrc = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                    val matchingLyrics = lyricMap[audioFile.nameWithoutExtension.lowercase()]
                    val hasMatchingLrc = siblingLrc.exists() ||
                            (matchingLyrics?.any { it.parentFile == audioFile.parentFile } == true)
                    if (!hasMatchingLrc) {
                        allIssues.add(
                            LyricValidationIssue(
                                lyricFile = siblingLrc,
                                audioFile = audioFile,
                                issueType = Messages.get("lyric.issue.type.missing_lyric"),
                                description = Messages.get("lyric.issue.missing_lyric")
                            )
                        )
                    }
                } catch (e: Exception) {
                    val wrapped = wrapFileException(audioFile, e)
                    errors.add(wrapped)
                    auditLogService.logError("Option 7 - Check Missing Lyric", audioFile, wrapped)
                }
            }
        }
        userInterface.closeProgress()

        val distinctProblematicFiles = allIssues.map { it.lyricFile }.distinct()
        auditLogService.logAnalysis(
            operation = "Option 7 - Verify Lyrics Integrity & Sync",
            targetDir = musicDirectory,
            messageKey = "audit.analysis.sync_check",
            messageArgs = listOf(lyricFiles.size, audioFiles.size, distinctProblematicFiles.size, allIssues.size),
            metadata = mapOf(
                "lyricsScanned" to lyricFiles.size,
                "audioScanned" to audioFiles.size,
                "problematicFiles" to distinctProblematicFiles.size,
                "totalIssues" to allIssues.size
            )
        )

        if (allIssues.isNotEmpty()) {
            val existingProblematicFiles = distinctProblematicFiles.filter { it.exists() }
            val reportFile = AppOutputDirectories.createReportFile(
                AppOutputDirectories.SUBDIR_SYNC_REPORTS, "LyricsSyncIssues_${allIssues.hashCode()}.txt"
            )
            val txtFileName = reportFile.path

            logService.warning("log.sync.checked", lyricFiles.size, allIssues.size)

            val reportContent = buildString {
                appendLine(Messages.get("lyric.report.header"))
                appendLine(Messages.get("lyric.report.problematic_files", distinctProblematicFiles.size))
                appendLine(Messages.get("lyric.report.total_issues", allIssues.size))
                appendLine("=".repeat(70))
                appendLine()

                allIssues.groupBy { it.lyricFile }.forEach { [file, fileIssues] ->
                    appendLine(Messages.get("lyric.report.file_label", file.name))
                    appendLine(Messages.get("lyric.report.path_label", file.absolutePath))
                    val audio = fileIssues.firstOrNull()?.audioFile
                    if (audio != null) {
                        appendLine(Messages.get("lyric.report.audio_label", audio.name))
                    }
                    appendLine(Messages.get("lyric.report.issues_label"))
                    fileIssues.forEach { issue ->
                        appendLine("  - [${issue.issueType}] ${issue.description}")
                    }
                    appendLine("-".repeat(50))
                }
            }

            reportFile.writeText(reportContent)
            auditLogService.logReportGenerated("Option 7 - Sync Issues Report", reportFile, allIssues.size)

            val shouldMove = existingProblematicFiles.isNotEmpty() && userInterface.askToMoveProblematicLyrics(existingProblematicFiles.size, txtFileName)

            if (shouldMove) {
                val outDirectory = directoryService.getDirectory(Messages.get("prompt.select.move_problematic_lyrics"))
                val movedFiles = mutableListOf<String>()

                userInterface.showProgress(Messages.get("progress.move_problematic_lyrics"), existingProblematicFiles.size)
                existingProblematicFiles.forEachIndexed { index, file ->
                    userInterface.updateProgress(index + 1, file.name)
                    try {
                        val [_, moveResult] = fileService.moveLyricFile(file, outDirectory)
                        changes.addAll(moveResult.changedSet)
                        moveResult.errorSet.forEach { err ->
                            val wrapped = wrapFileException(file, err)
                            auditLogService.logError("Option 7 - Move Problematic Lyric", file, wrapped)
                            errors.add(wrapped)
                        }
                        if (moveResult.errorSet.isEmpty()) {
                            movedFiles.add(file.name)
                        }
                    } catch (e: Exception) {
                        val wrapped = wrapFileException(file, e)
                        auditLogService.logError("Option 7 - Move Problematic Lyric", file, wrapped)
                        errors.add(wrapped)
                    }
                }
                userInterface.closeProgress()

                changes.add(Messages.get("result.moved_problematic_lyrics", txtFileName, movedFiles.size, existingProblematicFiles.size, outDirectory.absolutePath))
            } else {
                changes.add(Messages.get("result.found_problematic_lyrics", distinctProblematicFiles.size, txtFileName))
            }
        } else {
            logService.info("log.sync.all_ok", lyricFiles.size)
            changes.add(Messages.get("result.all_lyrics_synced", lyricFiles.size))
        }

        userInterface.showResult(changes, errors)

        return allIssues
    }

}
