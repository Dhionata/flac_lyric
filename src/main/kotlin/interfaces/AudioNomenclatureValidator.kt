package interfaces

import java.io.File

/**
 * Validation contract for audio file nomenclature and directory structure (DIP - SOLID).
 * Allows injecting different validators in the future without modifying the service code.
 */
interface AudioNomenclatureValidator {
    /**
     * Verifies if the audio file and its directory structure follow the expected pattern.
     *
     * @param file The audio file to be verified.
     * @param baseDirectory The base directory of the music library to calculate the relative structure.
     * @return true if the file and directory are in the correct pattern, false otherwise.
     */
    fun isValid(file: File, baseDirectory: File): Boolean
}
