package interfaces

import java.io.File


interface LyricFileHandler {
    fun getLyricFiles(lyricsDirectory: File): Set<File>
}
