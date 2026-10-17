package services

import interfaces.FileService
import interfaces.LogService
import interfaces.UserInterface
import java.io.File
import java.nio.file.Paths
import models.OperationResult

/**
 * Practical implementation of [FileService] to perform copies, deletions,
 * moves, and renaming of physical files, managing and reporting successes and errors.
 */
class FileServiceImpl(
    private val logService: LogService = JdkLogService(FileServiceImpl::class.java),
) : FileService {

    override fun printFilePermissions(file: File) {
        logService.info("log.file.permissions", file, file.canRead(), file.canWrite(), file.canExecute())
    }

    override fun moveFile(sourceFile: File, targetDir: File): Boolean {
        if (!sourceFile.exists()) {
            throw Exception(Messages.get("error.file_not_found", sourceFile.name))
        }

        val actualTargetDir = if (targetDir.isDirectory) targetDir else (targetDir.parentFile ?: targetDir)

        if (!actualTargetDir.exists()) {
            actualTargetDir.mkdirs()
        }

        val targetFile = File(actualTargetDir, sourceFile.name)

        return if (targetFile.exists()) {
            val sameFileWithDifferentName = sameFilesWithDiffNames(actualTargetDir, sourceFile)
            if (sameFileWithDifferentName && sourceFile.parentFile != targetFile.parentFile) {
                logService.warning("log.file.will_delete", sourceFile)
                sourceFile.delete().also {
                    logService.info("log.file.deleted_duplicate", sourceFile)
                }
            } else {
                throw Exception(
                    Messages.get("error.file_conflict", targetFile.name, sourceFile.parentFile, targetFile.parent)
                )
            }
        } else if (actualTargetDir.usableSpace in 1..<sourceFile.length()) {
            throw Exception(Messages.get("error.not_enough_space", sourceFile.name))
        } else {
            try {
                val moved = sourceFile.renameTo(targetFile)
                if (!moved) {
                    sourceFile.copyTo(targetFile, overwrite = false)
                    sourceFile.delete()
                }
                logService.info("log.file.moved", sourceFile.name, sourceFile.parent, targetFile.parent)
                true
            } catch (e: Exception) {
                throw Exception(
                    Messages.get("error.move_failed", sourceFile.name, sourceFile.parent, targetFile.parent, e.javaClass.simpleName)
                )
            }
        }
    }

    override fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean = actualTargetDir.walk().any {
        it.isFile && it.extension.equals(sourceFile.extension, true) && filesAreEqual(it, sourceFile)
    }

    override fun renameFile(file: File, newName: String): Boolean {
        val targetFile = File(file.parent, newName)
        return if (targetFile.exists()) {
            if (filesAreEqual(targetFile, file)) {
                file.delete()
                throw Exception(
                    Messages.get("error.rename_duplicate", targetFile.name, targetFile.parent, file.name)
                )
            } else {
                val targetDirectory = File(Paths.get(System.getProperty("user.home"), "Desktop").toString(), "Lyrics With Wrong Name")

                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs()
                }

                moveFile(file, targetDirectory)
                throw Exception(
                    Messages.get("error.rename_conflict", targetFile.name, targetFile.parent, file.name, targetDirectory.absolutePath)
                )
            }
        } else {
            file.renameTo(targetFile)
        }
    }

    override fun moveLyricFile(lyricFile: File, targetDir: File): Pair<File?, OperationResult> {
        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()
        var movedFile: File? = null
        try {
            if (moveFile(lyricFile, targetDir)) {
                changes.add(Messages.get("result.lyric_moved", lyricFile.name, lyricFile.parent, targetDir))
                movedFile = File(targetDir, lyricFile.name)
            } else {
                errors.add(Exception(Messages.get("error.lyric_not_moved", lyricFile.name, targetDir)))
            }
        } catch (e: Exception) {
            errors.add(e)
        }
        return Pair(movedFile, OperationResult(changes, errors))
    }

    override fun renameLyricFile(lyricFile: File, audioFile: File): OperationResult {
        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()
        try {
            if (renameFile(lyricFile, "${audioFile.nameWithoutExtension}.lrc")) {
                changes.add(Messages.get("result.lyric_renamed", lyricFile.name, audioFile.nameWithoutExtension))
            }
        } catch (e: Exception) {
            errors.add(e)
        }
        return OperationResult(changes, errors)
    }

    override fun handleUnmatchedFiles(
        musicDirectory: File, lyricsDirectory: File, userInterface: UserInterface,
    ): OperationResult {
        val changes = mutableSetOf<String>()
        val errors = mutableSetOf<Exception>()
        val musicFilesMap = musicDirectory.walk()
            .filter { it.isFile && it.extension.lowercase() in models.AudioConfig.supportedExtensions }
            .associateBy { it.nameWithoutExtension.lowercase() }

        val unmatchedLyricFiles = lyricsDirectory.walk()
            .filter { it.isFile && it.extension.equals("lrc", ignoreCase = true) }
            .filterNot { lyricFile ->
                musicFilesMap.containsKey(lyricFile.nameWithoutExtension.lowercase())
            }.toList()

        if (unmatchedLyricFiles.isNotEmpty()) {
            if (userInterface.askToMoveUnmatchedLyrics(unmatchedLyricFiles.size)) {
                val newDirectory = File(musicDirectory.parentFile ?: musicDirectory, "unmatched_lrc")
                if (!newDirectory.exists()) {
                    newDirectory.mkdirs()
                }
                unmatchedLyricFiles.forEach { lyricFile ->
                    if (lyricFile.parentFile != newDirectory) {
                        val (_, moveResult) = moveLyricFile(lyricFile, newDirectory)
                        changes.addAll(moveResult.changedSet)
                        errors.addAll(moveResult.errorSet)
                    }
                }
            }
        }

        if (lyricsDirectory.walk().none { it.isFile }) {
            if (lyricsDirectory.deleteRecursively()) {
                changes.add(Messages.get("result.lyrics_dir_deleted", lyricsDirectory.name))
            } else {
                errors.add(Exception(Messages.get("error.lyrics_dir_delete_failed", lyricsDirectory.name)))
            }
        }
        return OperationResult(changes, errors)
    }

    private fun filesAreEqual(file1: File, file2: File): Boolean {
        if (file1.length() != file2.length()) return false

        file1.inputStream().buffered().use { input1 ->
            file2.inputStream().buffered().use { input2 ->
                val buffer1 = ByteArray(8192)
                val buffer2 = ByteArray(8192)
                while (true) {
                    val read1 = input1.read(buffer1)
                    val read2 = input2.read(buffer2)
                    if (read1 != read2) return false
                    if (read1 == -1) return true
                    for (i in 0..<read1) {
                        if (buffer1[i] != buffer2[i]) return false
                    }
                }
            }
        }
    }
}
