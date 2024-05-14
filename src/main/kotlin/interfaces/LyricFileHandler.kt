package interfaces

import models.FilePair
import java.io.File


interface LyricFileHandler {
    fun getLyricFiles(lyricsDirectory: File): Set<File>
    fun matchFiles(lyricFiles: Set<File>, audioFiles: Set<File>): Set<FilePair>
    fun handleFilePairs(filePairs: Set<FilePair>, parentDirectory: File, lyricsDirectory: File)
    fun getChangedSet(): Set<String>
    fun getErrorList(): Set<Exception>
}
