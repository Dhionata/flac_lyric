package services

import interfaces.FileService
import java.io.File
import java.nio.file.Paths
import java.util.logging.Logger

/**
 * Practical implementation of [FileService] to perform copies, deletions,
 * moves, and renaming of physical files, managing and reporting successes and errors.
 */
class FileServiceImpl : FileService {

    /** Internal class logger for registering operations and warnings. */
    private val logger = Logger.getLogger(this.javaClass.name)
    /** Set of logs of changes performed. */
    override val changedSet: MutableSet<String> = mutableSetOf<String>()
    /** Set of captured errors. */
    override val errorSet: MutableSet<Exception> = mutableSetOf<Exception>()

    override fun printFilePermissions(file: File) {
        logger.info(
            "Folder permissions for $file\nRead: ${file.canRead()}\nWrite: ${file.canWrite()}\nExecute: ${file.canExecute()}"
        )
    }

    override fun moveFile(sourceFile: File, targetDir: File): Boolean {
        if (!sourceFile.exists()) {
            throw Exception("\n-- The file ${sourceFile.name} does not exist.\n")
        }

        val actualTargetDir = if (targetDir.isDirectory) targetDir else targetDir.parentFile

        if (!actualTargetDir.exists()) {
            actualTargetDir.mkdirs()
        }

        val targetFile = File(actualTargetDir, sourceFile.name)

        return if (targetFile.exists()) {
            val sameFileWithDifferentName = sameFilesWithDiffNames(targetFile.parentFile, sourceFile)
            if (sameFileWithDifferentName && sourceFile.parentFile != targetFile.parentFile) {
                logger.warning(
                    "The file will be deleted\n$sourceFile"
                )
                sourceFile.delete().also {
                    logger.info(
                        "File\n$sourceFile\ndeleted, a file with the same content and size already exists in the target directory"
                    )
                }

            } else {
                throw Exception(
                    "A file\n${targetFile.name}\nfrom directory\n${sourceFile.parentFile}\nalready exists in the destination directory\n${
                        targetFile.parent
                    }\nBut has different content or size\n"
                )
            }
        } else if (actualTargetDir.parentFile.freeSpace < sourceFile.length()) {
            throw Exception("— There is not enough space in the destination directory for file ${sourceFile.name}.\n")
        } else {
            try {
                sourceFile.copyTo(targetFile, overwrite = false)
                sourceFile.delete()
                logger.info(
                    "\nFile \n${sourceFile.name}\nmoved from\n${sourceFile.parent}\nto\n${targetFile.parent}\n"
                )
                true
            } catch (e: Exception) {
                throw Exception(
                    "Failed to move file\n${sourceFile.name}\nfrom\n${
                        sourceFile.parent
                    }\nto\n${
                        targetFile.parent
                    }\n${e.javaClass}\n"
                )
            }
        }
    }

    override fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean = actualTargetDir.walk().filter {
        it.isFile && it.extension == "lrc" && filesAreEqual(it, sourceFile)
    }.any()

    override fun renameFile(file: File, newName: String): Boolean {
        val targetFile = File(file.parent, newName)
        return if (targetFile.exists()) {
            if (filesAreEqual(targetFile, file)) {
                file.delete()
                throw Exception(
                    "Could not rename!\nThere already exists a file\n${targetFile.name}\nin target directory\n${targetFile.parent}\nwith the same content.\nFile ${file.name} deleted\n"
                )
            } else {
                val targetDirectory = File(Paths.get(System.getProperty("user.home"), "Desktop").toString(), "Lyrics With Wrong Name")

                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs()
                }

                moveFile(file, targetDirectory)
                throw Exception(
                    "Could not rename!\nThere already exists a file\n${targetFile.name}\nin target directory\n${targetFile.parent} with different content.\nFile\n${file.name}\nmoved to\n${targetDirectory.absolutePath}\n"
                )
            }
        } else {
            file.renameTo(targetFile)
        }
    }

    override fun moveLyricFile(lyricFile: File, targetDir: File): File? {
        try {
            if (moveFile(lyricFile, targetDir)) {
                changedSet.add("File \n${lyricFile.name}\nmoved from\n${lyricFile.parent}\nto\n${targetDir}\n")
                return File(targetDir, lyricFile.name)
            } else {
                errorSet.add(Exception("File ${lyricFile.name} not moved to $targetDir"))
            }
        } catch (e: Exception) {
            errorSet.add(e)
        }
        return null
    }

    override fun renameLyricFile(lyricFile: File, audioFile: File) {
        try {
            if (renameFile(lyricFile, "${audioFile.nameWithoutExtension}.lrc")) {
                changedSet.add("File ${lyricFile.name} renamed to ${audioFile.nameWithoutExtension}.lrc")
            }
        } catch (e: Exception) {
            errorSet.add(e)
        }
    }

    override fun handleUnmatchedFiles(
        musicDirectory: File, lyricsDirectory: File,
    ) {
        val musicFilesMap = musicDirectory.walk().filter { it.isFile && it.extension != "lrc" }.associateBy { it.nameWithoutExtension }

        val unmatchedLyricFiles = lyricsDirectory.walk().filter { it.isFile && it.extension == "lrc" }.filterNot { lyricFile ->
            musicFilesMap.containsKey(lyricFile.nameWithoutExtension)
        }.toList()

        if (unmatchedLyricFiles.isNotEmpty()) {
            val newDirectory = File(musicDirectory.parentFile, "unmatched_lrc")
            if (!newDirectory.exists()) {
                newDirectory.mkdirs()
            }
            unmatchedLyricFiles.forEach { lyricFile ->
                if (lyricFile.parentFile != newDirectory) {
                    moveLyricFile(lyricFile, newDirectory)
                }
            }
        }

        if (lyricsDirectory.walk().filter { it.isFile }.none()) {
            if (lyricsDirectory.delete()) {
                changedSet.add("Directory ${lyricsDirectory.name} deleted because there are no more .lrc files.")
            } else {
                errorSet.add(Exception("Directory ${lyricsDirectory.name} could not be deleted."))
            }
        }
    }

    private fun filesAreEqual(file1: File, file2: File): Boolean {
        if (file1.length() != file2.length()) return false

        file1.inputStream().use { input1 ->
            file2.inputStream().use { input2 ->
                return input1.buffered().readBytes() contentEquals input2.buffered().readBytes()
            }
        }
    }
}
