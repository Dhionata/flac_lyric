package interfaces

import java.io.File

/**
 * Handler responsible for managing and filtering audio files in a directory.
 */
interface AudioFileHandler {
    /**
     * Obtains a list of all valid audio files within the specified directory.
     *
     * @param musicDirectory The base directory where audio files will be searched.
     * @return A list of found audio files.
     */
    fun getAudioFiles(musicDirectory: File): List<File>
}
