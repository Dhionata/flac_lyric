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
