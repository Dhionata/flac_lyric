import services.AudioFileHandlerImpl
import services.DirectoryServiceImpl
import services.FileServiceImpl
import services.LyricFileHandlerImpl
import services.MusicLyricsService
import ui.UserInterfaceImpl

fun main() {
    val fileService = FileServiceImpl()
    val directoryService = DirectoryServiceImpl(fileService)
    val audioFileHandler = AudioFileHandlerImpl(fileService)
    val lyricFileHandler = LyricFileHandlerImpl(fileService)
    val userInterface = UserInterfaceImpl()

    val musicLyricsService = MusicLyricsService(
        directoryService, audioFileHandler, lyricFileHandler, userInterface
    )

    musicLyricsService.organizeMusicAndLyrics()
}
