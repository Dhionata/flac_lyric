import interfaces.UserInterface
import services.MusicLyricsService
import ui.UserInterfaceImpl

fun main() {
    val userInterface: UserInterface = UserInterfaceImpl()
    try {
        val option = userInterface.option()
        val musicLyricsService = MusicLyricsService(userInterface)

        if (option == 0) {
            musicLyricsService.organizeMusicAndLyrics()
        } else if (option == 1) {
            musicLyricsService.findMusicWithoutLyricsPair()
        }
    } catch (e: Exception) {
        userInterface.showError(e.toString())
    }
}
