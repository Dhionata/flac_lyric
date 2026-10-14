package comparison.infrastructure.services

import comparison.domain.models.ActionExecutionRequest
import comparison.domain.models.ActionProgressUpdate
import exceptions.OperationCancelledException
import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.DirectoryService
import interfaces.FileService
import services.AudioAnalysisServiceImpl
import services.CleanTitleNomenclatureValidator
import services.DirectoryServiceImpl
import services.FileServiceImpl
import services.LyricSyncValidatorImpl
import services.MatchServiceImpl
import services.MusicLyricsService
import java.io.File
import java.util.concurrent.Executors

/**
 * Service that coordinates asynchronous execution of actions 0-7, monitoring progress
 * and providing native desktop folder browsing when requested from the Web UI.
 */
class WebActionRunnerService(
    private val fileService: FileService = FileServiceImpl(),
    private val desktopDirectoryService: DirectoryService = DirectoryServiceImpl(fileService)
) {

    private val executor = Executors.newSingleThreadExecutor()
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
        } catch (e: Exception) {
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
                title = "Iniciando operação ${request.actionId}..."
            )
        }

        executor.submit {
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
                lyricSyncValidator = lyricSyncValidator
            )

            try {
                when (request.actionId) {
                    0 -> service.organizeMusicAndLyrics()
                    1 -> service.findMusicWithoutLyricsPair()
                    2 -> service.findLyricsWithoutSync()
                    3 -> service.findLyricsWithV1Text()
                    4 -> service.findAndMoveAloneLyrics()
                    5 -> service.verifyAdvancedNomenclature()
                    6 -> service.verifyFakeFlacFiles()
                    7 -> service.verifyLyricsSyncAndCompatibility()
                    else -> progressUi.showError("Opção desconhecida: ${request.actionId}")
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
            }
        }

        return getProgress()
    }
}
