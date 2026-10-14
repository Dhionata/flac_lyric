package comparison.application.usecases

import comparison.domain.models.ComparisonTrackDetails
import comparison.domain.models.ReportIssueItem
import comparison.domain.ports.AudioMetadataExtractor
import comparison.domain.ports.LyricFileStorage

/**
 * Use case to aggregate audio metadata, local lyrics, remote LRCLIB lyrics, and parsed lines
 * into a single comprehensive model for the web player.
 */
class ExtractTrackDetailsUseCase(
    private val metadataExtractor: AudioMetadataExtractor,
    private val lyricStorage: LyricFileStorage,
    private val parseLinesUseCase: ParseLyricLinesUseCase,
    private val fetchLrcLibUseCase: FetchLrcLibLyricsUseCase
) {
    fun execute(item: ReportIssueItem): ComparisonTrackDetails {
        val audioFile = item.audioFile
        val metadata = if (audioFile != null && audioFile.exists()) {
            metadataExtractor.extractMetadata(audioFile)
        } else {
            // Infer basic title/artist from lyric filename
            val baseName = item.lyricFile.nameWithoutExtension
            val parts = baseName.split(" - ", limit = 2)
            val artist = if (parts.size > 1) parts[0].trim() else ""
            val title = if (parts.size > 1) parts[1].trim() else baseName
            comparison.domain.models.TrackAudioMetadata(
                title = title,
                artist = artist,
                album = "",
                durationSeconds = 0,
                hasArtwork = false
            )
        }

        val localRawLyrics = lyricStorage.readText(item.lyricFile)
        val localLines = parseLinesUseCase.execute(localRawLyrics)

        // Query LRCLIB for the best match
        val remoteRecord = fetchLrcLibUseCase.fetchBestMatch(
            trackName = metadata.title,
            artistName = metadata.artist,
            albumName = metadata.album.takeIf { it.isNotBlank() },
            durationSeconds = metadata.durationSeconds.takeIf { it > 0 }
        )

        // Also fetch candidate alternatives to offer a choice if the first match ends prematurely or diverges
        val searchQuery = "${metadata.artist} ${metadata.title}".trim()
        val candidates = if (searchQuery.isNotBlank()) {
            fetchLrcLibUseCase.search(searchQuery)
        } else {
            emptyList()
        }

        val remoteRawLyrics = remoteRecord?.syncedLyrics ?: remoteRecord?.plainLyrics
        val remoteLines = if (!remoteRecord?.syncedLyrics.isNullOrBlank()) {
            parseLinesUseCase.execute(remoteRecord.syncedLyrics)
        } else {
            emptyList()
        }

        val remoteStatus = when {
            remoteRecord == null -> "NOT_FOUND"
            remoteRecord.instrumental == true -> "INSTRUMENTAL"
            !remoteRecord.syncedLyrics.isNullOrBlank() -> "FOUND_SYNCED"
            !remoteRecord.plainLyrics.isNullOrBlank() -> "FOUND_PLAIN"
            else -> "NOT_FOUND"
        }

        return ComparisonTrackDetails(
            id = item.id,
            fileName = item.fileName,
            lyricPath = item.lyricFile.absolutePath,
            audioPath = item.audioFile?.absolutePath,
            metadata = metadata,
            issues = item.issues,
            localRawLyrics = localRawLyrics,
            localLines = localLines,
            remoteRawLyrics = remoteRawLyrics,
            remoteLines = remoteLines,
            remoteStatus = remoteStatus,
            status = item.status,
            candidates = candidates
        )
    }
}
