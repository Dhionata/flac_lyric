import interfaces.UserInterface
import services.MusicLyricsService
import ui.UserInterfaceImpl

fun main() {
    val userInterface: UserInterface = UserInterfaceImpl()
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

            else -> {
                userInterface.showError("Opção inválida!")
            }
        }
    } catch (e: Exception) {
        userInterface.showError(e.toString())
    }
}
