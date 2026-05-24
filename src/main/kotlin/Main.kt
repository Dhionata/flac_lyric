import exceptions.OperationCancelledException
import handlers.AudioFileHandlerImpl
import handlers.LyricFileHandlerImpl
import interfaces.UserInterface
import services.AudioAnalysisServiceImpl
import services.CleanTitleNomenclatureValidator
import services.DirectoryServiceImpl
import services.FileServiceImpl
import services.MatchServiceImpl
import services.MusicLyricsService
import ui.UserInterfaceImpl

/**
 * Entry point of the application.
 * Initializes the graphical user interface and runs the main loop displaying the options menu.
 * Captures cancellation exceptions to silently return to the menu and displays other errors.
 */
fun main() {
    val userInterface: UserInterface = UserInterfaceImpl()
    val fileService = FileServiceImpl()
    val directoryService = DirectoryServiceImpl(fileService)
    val audioFileHandler = AudioFileHandlerImpl()
    val lyricFileHandler = LyricFileHandlerImpl()
    val matchService = MatchServiceImpl(userInterface, fileService)
    val nomenclatureValidator = CleanTitleNomenclatureValidator()
    val audioAnalysisService = AudioAnalysisServiceImpl()

    val musicLyricsService = MusicLyricsService(
        userInterface,
        directoryService,
        audioFileHandler,
        lyricFileHandler,
        matchService,
        fileService,
        nomenclatureValidator,
        audioAnalysisService
    )

    while (true) {
        try {
            val option = userInterface.option() ?: break

            when (option) {
                0 -> {
                    musicLyricsService.organizeMusicAndLyrics()
                }

                1 -> {
                    musicLyricsService.findMusicWithoutLyricsPair()
                }

                2 -> {
                    musicLyricsService.findLyricsWithoutSync()
                }

                3 -> {
                    musicLyricsService.findLyricsWithV1Text()
                }

                4 -> {
                    musicLyricsService.findAndMoveAloneLyrics()
                }

                5 -> {
                    musicLyricsService.verifyAdvancedNomenclature()
                }

                6 -> {
                    musicLyricsService.verifyFakeFlacFiles()
                }

                else -> {
                    userInterface.showError("Opção inválida!")
                }
            }
        } catch (_: OperationCancelledException) {
            // Silently return to the main menu
        } catch (e: Exception) {
            userInterface.showError(e.toString())
        }
    }
}
