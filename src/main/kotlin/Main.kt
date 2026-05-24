import exceptions.OperationCancelledException
import interfaces.UserInterface
import services.MusicLyricsService
import ui.UserInterfaceImpl

/**
 * Entry point of the application.
 * Initializes the graphical user interface and runs the main loop displaying the options menu.
 * Captures cancellation exceptions to silently return to the menu and displays other errors.
 */
fun main() {
    val userInterface: UserInterface = UserInterfaceImpl()
    while (true) {
        try {
            val option = userInterface.option()
            val musicLyricsService = MusicLyricsService(userInterface)

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
        } catch (e: OperationCancelledException) {
            // Silently return to the main menu
        } catch (e: Exception) {
            userInterface.showError(e.toString())
        }
    }
}
