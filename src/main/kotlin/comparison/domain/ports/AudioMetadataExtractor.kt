package comparison.domain.ports

import comparison.domain.models.TrackAudioMetadata
import java.io.File

/**
 * Port for extracting audio metadata and artwork from audio files.
 */
interface AudioMetadataExtractor {
    /**
     * Extracts track metadata such as title, artist, album, and duration.
     *
     * @param audioFile The audio file to inspect.
     * @return Extracted [TrackAudioMetadata].
     */
    fun extractMetadata(audioFile: File): TrackAudioMetadata

    /**
     * Extracts embedded album artwork as raw bytes, or null if absent.
     *
     * @param audioFile The audio file to inspect.
     * @return Raw image bytes or null if no artwork is embedded.
     */
    fun extractArtworkBytes(audioFile: File): ByteArray?
}
