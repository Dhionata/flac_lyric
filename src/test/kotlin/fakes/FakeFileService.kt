package fakes

import interfaces.FileService
import interfaces.UserInterface
import java.io.File
import java.lang.Exception
import models.OperationResult

class FakeFileService : FileService {
    val permissionsPrinted = mutableListOf<File>()
    
    val moveFileCalls = mutableListOf<Pair<File, File>>()
    var moveFileResponse = true
    
    val renameFileCalls = mutableListOf<Pair<File, String>>()
    var renameFileResponse = true
    
    val sameFilesWithDiffNamesCalls = mutableListOf<Pair<File, File>>()
    var sameFilesWithDiffNamesResponse = false
    
    val handleUnmatchedFilesCalls = mutableListOf<Pair<File, File>>()
    var handleUnmatchedFilesResponse = OperationResult()
    
    val moveLyricFileCalls = mutableListOf<Pair<File, File>>()
    var moveLyricFileResponse: File? = null
    var moveLyricOperationResult = OperationResult()
    
    val renameLyricFileCalls = mutableListOf<Pair<File, File>>()
    var renameLyricFileResponse = OperationResult()

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

    override fun handleUnmatchedFiles(musicDirectory: File, lyricsDirectory: File, userInterface: UserInterface): OperationResult {
        handleUnmatchedFilesCalls.add(musicDirectory to lyricsDirectory)
        return handleUnmatchedFilesResponse
    }

    override fun moveLyricFile(lyricFile: File, targetDir: File): Pair<File?, OperationResult> {
        moveLyricFileCalls.add(lyricFile to targetDir)
        val fileRes = moveLyricFileResponse ?: File(targetDir, lyricFile.name)
        return Pair(fileRes, moveLyricOperationResult)
    }

    override fun renameLyricFile(lyricFile: File, audioFile: File): OperationResult {
        renameLyricFileCalls.add(lyricFile to audioFile)
        return renameLyricFileResponse
    }
}
