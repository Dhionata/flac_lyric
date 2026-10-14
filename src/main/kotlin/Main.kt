import comparison.application.services.LyricReviewCoordinatorService
import comparison.infrastructure.services.WebActionRunnerService
import exceptions.OperationCancelledException
import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.UserInterface
import kotlin.system.exitProcess
import services.AudioAnalysisServiceImpl
import services.CleanTitleNomenclatureValidator
import services.DirectoryServiceImpl
import services.FileServiceImpl
import services.LoggingInitializer
import services.LyricSyncValidatorImpl
import services.MatchServiceImpl
import services.MusicLyricsService
import ui.UserInterfaceImpl

/**
 * Entry point of the application.
 * Initializes logging, starts the modern web interface by default, opens the browser,
 * and maintains the UserInterfaceImpl (Swing GUI) available for fallback or command execution.
 */
fun main() {
    LoggingInitializer.init()

    val userInterface: UserInterface = UserInterfaceImpl()
    val fileService = FileServiceImpl()
    val directoryService = DirectoryServiceImpl(fileService)
    val audioFileHandler = AudioFileHandlerImpl()
    val lyricFileHandler = LyricFileHandlerImpl()
    val matchService = MatchServiceImpl(userInterface, fileService)
    val nomenclatureValidator = CleanTitleNomenclatureValidator()
    val audioAnalysisService = AudioAnalysisServiceImpl()
    val lyricSyncValidator = LyricSyncValidatorImpl()

    val musicLyricsService = MusicLyricsService(
        userInterface,
        directoryService,
        audioFileHandler,
        lyricFileHandler,
        matchService,
        fileService,
        nomenclatureValidator,
        audioAnalysisService,
        lyricSyncValidator
    )

    val webActionRunner = WebActionRunnerService(fileService, directoryService)
    val coordinatorService = LyricReviewCoordinatorService(actionRunner = webActionRunner)

    // Default startup: Launch modern Web Interface directly and open user's default browser
    val webUrl = coordinatorService.startReview(preferredPort = 8080, openBrowser = true)
    println("=================================================================")
    println("FLAC Lyric - Interface Web iniciada por padrão em: $webUrl")
    println("Pressione Ctrl+C para encerrar o servidor web.")
    println("=================================================================")

    // Keep server running
    while (true) {
        try {
            Thread.sleep(1000)
        } catch (_: InterruptedException) {
            coordinatorService.stopReview()
            break
        }
    }

    exitProcess(0)
}
