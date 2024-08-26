package interfaces

import models.FilePair
import java.io.File


interface LyricFileHandler {
    val changedSet: MutableSet<String>
    val errorSet: MutableSet<Exception>
    fun getLyricFiles(lyricsDirectory: File): Set<File>
    fun matchFiles(lyricFiles: Set<File>, audioFiles: Set<File>): Set<FilePair>
    fun handleFilePairs(filePairs: Set<FilePair>, musicDirectory: File, lyricsDirectory: File)
    fun handleUnmatchedFiles(
        musicDirectoryParent: File, lyricsDirectory: File
    )
}
