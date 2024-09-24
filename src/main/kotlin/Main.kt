import interfaces.UserInterface
import services.MusicLyricsService
import ui.UserInterfaceImpl

fun main() {
    try {
        val userInterface: UserInterface = UserInterfaceImpl()
        val option = userInterface.option()
        val musicLyricsService = MusicLyricsService()
        if (option == 0) {
            musicLyricsService.organizeMusicAndLyrics()
        } else if (option == 1) {
            musicLyricsService.findMusicWithoutLyricsPair()
        } else {
            throw RuntimeException("this shouldn't happen")
        }
    } catch (e: Exception) {
        UserInterfaceImpl().showError(e.toString())
    }
}
