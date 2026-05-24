package handlers

import interfaces.AudioFileHandler
import java.io.File

/**
 * Implementation of [AudioFileHandler] responsible for locating supported audio files.
 */
class AudioFileHandlerImpl : AudioFileHandler {

    /** List of audio file extensions supported by the application. */
    private val supportedExtensions = listOf("flac", "mp3", "ogg", "wav", "m4a")

    /**
     * Recursively scans the provided directory and returns all files whose extension
     * is present in the list of supported extensions.
     *
     * @param musicDirectory The base directory to scan.
     * @return List of valid audio files.
     */
    override fun getAudioFiles(musicDirectory: File): List<File> {
        return musicDirectory.walk().filter { it.isFile && it.extension in supportedExtensions }.toList()
    }
}
