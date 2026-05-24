package models

/**
 * Global configuration containing constant definitions related to supported audio formats.
 * Prevents list duplication and facilitates adding new formats in the future (Open/Closed Principle).
 */
object AudioConfig {
    /**
     * Set of supported audio file extensions.
     */
    val supportedExtensions: Set<String> = setOf("flac", "mp3", "ogg", "wav", "m4a", "opus")
}
