package comparison.domain.ports

import java.io.File

/**
 * Port for reading and writing lyric files to persistent storage.
 */
interface LyricFileStorage {
    /**
     * Reads the entire text content of a file using UTF-8 encoding.
     *
     * @param file Target file.
     * @return Content of the file.
     */
    fun readText(file: File): String

    /**
     * Writes text content to the specified file using UTF-8 encoding.
     *
     * @param file Target file.
     * @param content Text to write.
     */
    fun writeText(file: File, content: String)
}
