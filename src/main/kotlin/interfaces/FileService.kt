package interfaces

import java.io.File

interface FileService {
    val changedSet: MutableSet<String>
    val errorSet: MutableSet<Exception>

    fun printFilePermissions(file: File)
    fun moveFile(sourceFile: File, targetDir: File): Boolean
    fun renameFile(file: File, newName: String): Boolean
    fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean
    fun handleUnmatchedFiles(musicDirectory: File, lyricsDirectory: File)
    fun moveLyricFile(lyricFile: File, targetDir: File): File?
    fun renameLyricFile(lyricFile: File, audioFile: File)
}
