package comparison.infrastructure.services

import comparison.domain.models.ActionExecutionRequest
import comparison.domain.models.ActionProgressUpdate
import exceptions.OperationCancelledException
import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.AuditLogService
import interfaces.DirectoryService
import interfaces.FileService
import java.io.File
import java.util.concurrent.Executors
import services.AudioAnalysisServiceImpl
import services.AuditLogServiceImpl
import services.CleanTitleNomenclatureValidator
import services.DirectoryServiceImpl
import services.FileServiceImpl
import services.LyricSyncValidatorImpl
import services.MatchServiceImpl
import services.Messages
import services.MusicLyricsService

/**
 * Service that coordinates asynchronous execution of actions 0-7, monitoring progress,
 * recording comprehensive audit logs of selected options and outcomes, and providing native
 * desktop folder browsing when requested from the Web UI.
 */
class WebActionRunnerService(
    private val fileService: FileService = FileServiceImpl(),
    private val desktopDirectoryService: DirectoryService = DirectoryServiceImpl(fileService),
    private val auditLogService: AuditLogService = AuditLogServiceImpl(),
) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "web-action-runner").apply { isDaemon = true }
    }
    private val progressLock = Any()
    private var currentProgress = ActionProgressUpdate()

    /**
     * Returns a thread-safe snapshot of the current action progress.
     */
    fun getProgress(): ActionProgressUpdate {
        synchronized(progressLock) {
            return currentProgress.copy(
                changes = currentProgress.changes.toMutableList(),
                errors = currentProgress.errors.toMutableList()
            )
        }
    }

    /**
     * Opens the native desktop directory selector dialog and returns the selected path, or null if cancelled.
     */
    fun browseDirectory(title: String): String? {
        return try {
            desktopDirectoryService.getDirectory(title).canonicalPath
        } catch (_: OperationCancelledException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Starts execution of an action asynchronously if no action is currently running.
     */
    fun executeAction(request: ActionExecutionRequest): ActionProgressUpdate {
        synchronized(progressLock) {
            if (currentProgress.isRunning) {
                return currentProgress
            }
            currentProgress = ActionProgressUpdate(
                isRunning = true,
                actionId = request.actionId,
                title = Messages.get("action.progress.starting", request.actionId)
            )
        }

        executor.submit {
            val actionTitle = try {
                Messages.get("menu.option.${request.actionId}.title")
            } catch (_: Exception) {
                "Action #${request.actionId}"
            }

            auditLogService.logOperationStart(
                actionName = "Option ${request.actionId} - $actionTitle",
                options = linkedMapOf(
                    "actionId" to request.actionId,
                    "musicDir" to request.musicDir,
                    "lyricsDir" to request.lyricsDir,
                    "outDir" to request.outDir,
                    "isFullAnalysis" to request.isFullAnalysis,
                    "moveFiles" to request.moveFiles,
                    "renameFiles" to request.renameFiles,
                    "includeMissingLyrics" to request.includeMissingLyrics,
                    "removeSpeakerAndBgTags" to request.removeSpeakerAndBgTags,
                    "removeInlineWordTimestamps" to request.removeInlineWordTimestamps,
                    "removeCreditsAndWatermarks" to request.removeCreditsAndWatermarks,
                    "sortAndDeduplicateMultiTimestamps" to request.sortAndDeduplicateMultiTimestamps,
                    "removeUnsyncedLinesInSyncedFile" to request.removeUnsyncedLinesInSyncedFile,
                    "includeTxtFiles" to request.includeTxtFiles
                )
            )

            val progressUi = WebProgressUserInterface(request, currentProgress)
            val fixedDirectoryService = FixedDirectoryService(
                musicDir = request.musicDir?.takeIf { it.isNotBlank() }?.let { File(it) },
                lyricsDir = request.lyricsDir?.takeIf { it.isNotBlank() }?.let { File(it) },
                outDir = request.outDir?.takeIf { it.isNotBlank() }?.let { File(it) },
                fallbackDirectoryService = desktopDirectoryService
            )

            val audioFileHandler = AudioFileHandlerImpl()
            val lyricFileHandler = LyricFileHandlerImpl()
            val matchService = MatchServiceImpl(progressUi, fileService)
            val nomenclatureValidator = CleanTitleNomenclatureValidator()
            val audioAnalysisService = AudioAnalysisServiceImpl()
            val lyricSyncValidator = LyricSyncValidatorImpl()

            val service = MusicLyricsService(
                userInterface = progressUi,
                directoryService = fixedDirectoryService,
                audioFileHandler = audioFileHandler,
                lyricFileHandler = lyricFileHandler,
                matchService = matchService,
                fileService = fileService,
                nomenclatureValidator = nomenclatureValidator,
                audioAnalysisService = audioAnalysisService,
                lyricSyncValidator = lyricSyncValidator,
                auditLogService = auditLogService
            )

            try {
                when (request.actionId) {
                    0 -> service.organizeMusicAndLyrics()
                    1 -> service.findMusicWithoutLyricsPair()
                    2 -> service.findLyricsWithoutSync()
                    3 -> service.findLyricsWithV1Text(
                        models.LyricCleanOptions(
                            removeSpeakerAndBgTags = request.removeSpeakerAndBgTags,
                            removeInlineWordTimestamps = request.removeInlineWordTimestamps,
                            removeCreditsAndWatermarks = request.removeCreditsAndWatermarks,
                            sortAndDeduplicateMultiTimestamps = request.sortAndDeduplicateMultiTimestamps,
                            removeUnsyncedLinesInSyncedFile = request.removeUnsyncedLinesInSyncedFile
                        )
                    )

                    4 -> service.findAndMoveAloneLyrics(request.includeTxtFiles)
                    5 -> service.verifyAdvancedNomenclature()
                    6 -> service.verifyFakeFlacFiles()
                    7 -> service.verifyLyricsSyncAndCompatibility(request.includeMissingLyrics)
                    else -> progressUi.showError(Messages.get("error.unknown_option", request.actionId))
                }
            } catch (_: OperationCancelledException) {
                // Silently handle user cancellation
            } catch (e: Exception) {
                progressUi.showError(e.message ?: e.toString())
            } finally {
                synchronized(progressLock) {
                    currentProgress.isRunning = false
                    currentProgress.isCompleted = true
                    currentProgress.success = currentProgress.errors.isEmpty()
                }
                auditLogService.logOperationEnd(
                    actionName = "Option ${request.actionId} - $actionTitle",
                    success = currentProgress.errors.isEmpty(),
                    processedCount = currentProgress.changes.size,
                    errorCount = currentProgress.errors.size,
                    details = buildList {
                        currentProgress.generatedReportPath?.let { add("Report: $it") }
                        addAll(currentProgress.changes)
                        currentProgress.errors.forEach { err -> add("ERROR: $err") }
                    }
                )
            }
        }

        return getProgress()
    }
}
