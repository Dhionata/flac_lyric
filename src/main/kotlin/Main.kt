import services.MusicLyricsService
import ui.UserInterfaceImpl

fun main() {
    try {
        MusicLyricsService().organizeMusicAndLyrics()
    } catch (e: Exception) {
        UserInterfaceImpl().showError(e.toString())
    }
}
