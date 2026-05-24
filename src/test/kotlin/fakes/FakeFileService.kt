package fakes

import interfaces.FileService
import interfaces.UserInterface
import java.io.File
import models.OperationResult

class FakeFileService : FileService {
    val permissionsPrinted: MutableList<File> = mutableListOf<File>()

    val moveFileCalls: MutableList<Pair<File, File>> = mutableListOf()
    var moveFileResponse: Boolean = true

    val renameFileCalls: MutableList<Pair<File, String>> = mutableListOf()
    var renameFileResponse: Boolean = true

    val sameFilesWithDiffNamesCalls: MutableList<Pair<File, File>> = mutableListOf()
    var sameFilesWithDiffNamesResponse: Boolean = false

    val handleUnmatchedFilesCalls: MutableList<Pair<File, File>> = mutableListOf()
    var handleUnmatchedFilesResponse: OperationResult = OperationResult()

    val moveLyricFileCalls: MutableList<Pair<File, File>> = mutableListOf()
    var moveLyricFileResponse: File? = null
    var moveLyricOperationResult: OperationResult = OperationResult()

    val renameLyricFileCalls: MutableList<Pair<File, File>> = mutableListOf()
    var renameLyricFileResponse: OperationResult = OperationResult()

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
