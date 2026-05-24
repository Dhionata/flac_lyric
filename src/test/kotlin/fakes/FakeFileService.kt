package fakes

import interfaces.FileService
import interfaces.UserInterface
import java.io.File
import java.lang.Exception

class FakeFileService : FileService {
    override val changedSet = mutableSetOf<String>()
    override val errorSet = mutableSetOf<Exception>()

    val permissionsPrinted = mutableListOf<File>()
    
    val moveFileCalls = mutableListOf<Pair<File, File>>()
    var moveFileResponse = true
    
    val renameFileCalls = mutableListOf<Pair<File, String>>()
    var renameFileResponse = true
    
    val sameFilesWithDiffNamesCalls = mutableListOf<Pair<File, File>>()
    var sameFilesWithDiffNamesResponse = false
    
    val handleUnmatchedFilesCalls = mutableListOf<Pair<File, File>>()
    
    val moveLyricFileCalls = mutableListOf<Pair<File, File>>()
    var moveLyricFileResponse: File? = null
    
    val renameLyricFileCalls = mutableListOf<Pair<File, File>>()

    override fun printFilePermissions(file: File) {
        permissionsPrinted.add(file)
    }

    override fun moveFile(sourceFile: File, targetDir: File): Boolean {
        moveFileCalls.add(sourceFile to targetDir)
        return moveFileResponse
    }

    override fun renameFile(file: File, newName: String): Boolean {
        renameFileCalls.add(file to newName)
        return renameFileResponse
    }

    override fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean {
        sameFilesWithDiffNamesCalls.add(actualTargetDir to sourceFile)
        return sameFilesWithDiffNamesResponse
    }

    override fun handleUnmatchedFiles(musicDirectory: File, lyricsDirectory: File, userInterface: UserInterface) {
        handleUnmatchedFilesCalls.add(musicDirectory to lyricsDirectory)
    }

    override fun moveLyricFile(lyricFile: File, targetDir: File): File? {
        moveLyricFileCalls.add(lyricFile to targetDir)
        return moveLyricFileResponse ?: File(targetDir, lyricFile.name)
    }

    override fun renameLyricFile(lyricFile: File, audioFile: File) {
        renameLyricFileCalls.add(lyricFile to audioFile)
    }
}
