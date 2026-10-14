package comparison.infrastructure.audio

import comparison.domain.models.TrackAudioMetadata
import comparison.domain.ports.AudioMetadataExtractor
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Extracts metadata and embedded cover art from audio files using Jaudiotagger.
 * Provides fallback parsing based on filename when tags cannot be retrieved.
 */
class AudioMetadataExtractorImpl : AudioMetadataExtractor {

    override fun extractMetadata(audioFile: File): TrackAudioMetadata {
        if (!audioFile.exists() || !audioFile.isFile) {
            return fallbackMetadata(audioFile)
        }

        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag
            val header = audio.audioHeader

            val titleFromTag = tag?.getFirst(FieldKey.TITLE)?.trim().orEmpty()
            val artistFromTag = tag?.getFirst(FieldKey.ARTIST)?.trim().orEmpty()
            val albumFromTag = tag?.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
            val durationSeconds = header?.trackLength ?: 0
            val hasArtwork = tag?.firstArtwork != null

            if (titleFromTag.isNotBlank()) {
                TrackAudioMetadata(
                    title = titleFromTag,
                    artist = artistFromTag,
                    album = albumFromTag,
                    durationSeconds = durationSeconds,
                    hasArtwork = hasArtwork
                )
            } else {
                val fallback = fallbackMetadata(audioFile)
                fallback.copy(
                    album = albumFromTag,
                    durationSeconds = durationSeconds,
                    hasArtwork = hasArtwork
                )
            }
        } catch (_: Exception) {
            fallbackMetadata(audioFile)
        }
    }

    override fun extractArtworkBytes(audioFile: File): ByteArray? {
        if (!audioFile.exists() || !audioFile.isFile) return null

        return try {
            val audio = AudioFileIO.read(audioFile)
            audio.tag?.firstArtwork?.binaryData
        } catch (_: Exception) {
            null
        }
    }

    private fun fallbackMetadata(file: File): TrackAudioMetadata {
        val baseName = file.nameWithoutExtension
        val parts = baseName.split(" - ", limit = 2)
        val artist = if (parts.size > 1) parts[0].trim() else ""
        val title = if (parts.size > 1) parts[1].trim() else baseName

        return TrackAudioMetadata(
            title = title,
            artist = artist,
            album = "",
            durationSeconds = 0,
            hasArtwork = false
        )
    }
}
