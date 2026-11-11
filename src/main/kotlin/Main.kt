import comparison.application.services.LyricReviewCoordinatorService
import comparison.infrastructure.services.WebActionRunnerService
import kotlin.system.exitProcess
import services.DirectoryServiceImpl
import services.FileServiceImpl
import services.JdkLogService
import services.LoggingInitializer
import services.Messages

/**
 * Entry point of the application.
 * Initializes logging, starts the modern web interface by default, opens the browser,
 * and terminates the process automatically when the interface window is closed.
 */
fun main() {
    LoggingInitializer.init()

    val fileService = FileServiceImpl()
    val directoryService = DirectoryServiceImpl(fileService)
    val logService = JdkLogService(object {}.javaClass)
    val webActionRunner = WebActionRunnerService(fileService, directoryService)
    val coordinatorService = LyricReviewCoordinatorService(
        actionRunner = webActionRunner,
        logService = logService,
        onShutdown = { exitProcess(0) }
    )

    // Default startup: Launch modern Web Interface directly and open user's default browser
    val webUrl = coordinatorService.startReview()
    logService.info("log.web.started", webUrl)
    logService.info("log.web.press_ctrl_c")
    println("=================================================================")
    println(Messages.get("log.web.started", webUrl))
    println(Messages.get("log.web.press_ctrl_c"))
    println("=================================================================")

    // Keep server running while the web interface is open
    while (coordinatorService.isRunning) {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            coordinatorService.stopReview()
            break
        }
    }

    exitProcess(0)
}
