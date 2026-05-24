package interfaces

import java.io.File

/**
 * Handler responsible for managing and filtering lyric/subtitle files (.lrc).
 */
interface LyricFileHandler {
    /**
     * Obtains a list of all valid lyric files within the specified directory.
     *
     * @param lyricsDirectory The base directory where lyric files (.lrc) will be searched.
     * @return A list of found lyric files.
     */
    fun getLyricFiles(lyricsDirectory: File): List<File>
}
