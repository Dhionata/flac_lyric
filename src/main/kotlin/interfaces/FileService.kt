package interfaces

import java.io.File

/**
 * Service responsible for physical file manipulation operations (moving, renaming, verifying permissions, and handling orphan files).
 */
interface FileService {
    /** Set of file names or messages for successfully performed modifications. */
    val changedSet: MutableSet<String>
    /** Set of exceptions/errors occurred during the execution of operations. */
    val errorSet: MutableSet<Exception>

    /**
     * Prints the read, write, and execute permissions of a file or directory to the console.
     *
     * @param file The file or directory to inspect.
     */
    fun printFilePermissions(file: File)

    /**
     * Moves a file from its source to the specified target directory.
     *
     * @param sourceFile The file to be moved.
     * @param targetDir The target directory.
     * @return true if the file was successfully moved, false otherwise.
     */
    fun moveFile(sourceFile: File, targetDir: File): Boolean

    /**
     * Renames a file to a newly provided name.
     *
     * @param file The file to be renamed.
     * @param newName The new name of the file (including extension).
     * @return true if renaming was successful, false otherwise.
     */
    fun renameFile(file: File, newName: String): Boolean

    /**
     * Verifies if a file with the same size/content already exists in the target directory but under a different name.
     *
     * @param actualTargetDir Target directory to be verified.
     * @param sourceFile Source file being compared.
     * @return true if there are matching files with different names, false otherwise.
     */
    fun sameFilesWithDiffNames(actualTargetDir: File, sourceFile: File): Boolean

    /**
     * Processes audio or lyric files that did not find a direct pair, deciding whether they should be renamed or moved.
     *
     * @param musicDirectory Directory containing the music files.
     * @param lyricsDirectory Directory containing the lyric files.
     * @param userInterface The UserInterface reference to query confirmation.
     */
    fun handleUnmatchedFiles(musicDirectory: File, lyricsDirectory: File, userInterface: UserInterface)

    /**
     * Specifically moves a lyric file (.lrc) to a target directory, handling potential conflicts.
     *
     * @param lyricFile The .lrc file to be moved.
     * @param targetDir The target directory.
     * @return The resulting moved file, or null if the operation failed.
     */
    fun moveLyricFile(lyricFile: File, targetDir: File): File?

    /**
     * Renames a lyric file based on the corresponding audio file's name.
     *
     * @param lyricFile The source .lrc file.
     * @param audioFile The corresponding audio file from which to extract the name.
     */
    fun renameLyricFile(lyricFile: File, audioFile: File)
}
