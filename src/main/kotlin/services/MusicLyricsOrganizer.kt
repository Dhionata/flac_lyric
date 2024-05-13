package services

import ui.UserInterface

class MusicLyricsOrganizer {

    private val fileService = FileService()
    private val lyricFileHandler = LyricFileHandler(fileService)
    private val audioFileHandler = AudioFileHandler(fileService)
    private val directoryService = DirectoryService(fileService)
    private val userInterface = UserInterface()

    fun run() {
        val musicDirectory = directoryService.getDirectory("Selecione o diretório das músicas")
        fileService.printFilePermissions(musicDirectory)

        if (!musicDirectory.canWrite()) {
            userInterface.showError("Diretório $musicDirectory somente leitura!")
            return
        }

        val lyricsDirectory = directoryService.getDirectory("Selecione o diretório dos arquivos .lrc")
        fileService.printFilePermissions(lyricsDirectory)

        if (!lyricsDirectory.canWrite()) {
            userInterface.showError("Diretório $lyricsDirectory somente leitura!")
            return
        }

        val audioFiles = audioFileHandler.getAudioFiles(musicDirectory)
        val lyricFiles = lyricFileHandler.getLyricFiles(lyricsDirectory)

        val filePairs = lyricFileHandler.matchFiles(lyricFiles, audioFiles)

        lyricFileHandler.handleFilePairs(filePairs, musicDirectory.parentFile, lyricsDirectory)

        userInterface.showResult(lyricFileHandler.getChangedSet(), lyricFileHandler.getErrorList())
    }
}
