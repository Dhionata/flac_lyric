package handlers

import interfaces.LyricFileHandler
import java.io.File

class LyricFileHandlerImpl : LyricFileHandler {
    override fun getLyricFiles(lyricsDirectory: File): List<File> {
        return lyricsDirectory.walk().filter { it.extension == "lrc" }.toList()
    }
}
