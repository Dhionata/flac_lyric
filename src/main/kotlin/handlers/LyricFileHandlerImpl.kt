package handlers

import interfaces.LyricFileHandler
import java.io.File

class LyricFileHandlerImpl : LyricFileHandler {
    override fun getLyricFiles(lyricsDirectory: File): Set<File> {
        return lyricsDirectory.walk().filter { it.extension == "lrc" }.toSet()
    }
}
