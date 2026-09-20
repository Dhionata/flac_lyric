package handlers

import interfaces.LyricFileHandler
import java.io.File

/**
 * Implementation of [LyricFileHandler] responsible for locating lyric files (.lrc).
 */
class LyricFileHandlerImpl : LyricFileHandler {
    /**
     * Recursively scans the provided directory and returns all files whose extension is "lrc" (case-insensitive).
     *
     * @param lyricsDirectory The base directory to scan.
     * @return List of valid .lrc files.
     */
    override fun getLyricFiles(lyricsDirectory: File): List<File> {
        return lyricsDirectory.walk().filter { it.isFile && it.extension.equals("lrc", ignoreCase = true) }.toList()
    }
}
