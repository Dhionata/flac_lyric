package comparison.application.usecases

import comparison.domain.models.ComparisonTrackDetails
import comparison.domain.models.LrcLibLyrics
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

        // 1. Fetch candidates from LRCLIB search
        val searchQuery = "${metadata.artist} ${metadata.title}".trim()
        val initialCandidates = if (searchQuery.isNotBlank()) {
            fetchLrcLibUseCase.search(searchQuery)
        } else {
            emptyList()
        }

        // Fallback search with cleaned title if no candidates were found
        val candidatesList = if (initialCandidates.isEmpty()) {
            val cleanTitle = metadata.title.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            val fallbackQuery = "${metadata.artist} $cleanTitle".trim()
            if (fallbackQuery.isNotBlank() && fallbackQuery != searchQuery) {
                fetchLrcLibUseCase.search(fallbackQuery)
            } else {
                initialCandidates
            }
        } else {
            initialCandidates
        }

        // 2. Also try exact match via /api/get if available
        val exactRecord = fetchLrcLibUseCase.fetchBestMatch(
            trackName = metadata.title,
            artistName = metadata.artist,
            albumName = metadata.album.takeIf { it.isNotBlank() },
            durationSeconds = metadata.durationSeconds.takeIf { it > 0 }
        )

        val combinedCandidates = if (exactRecord != null && candidatesList.none { it.id == exactRecord.id }) {
            listOf(exactRecord) + candidatesList
        } else {
            candidatesList
        }

        // 3. Automatically select the best candidate:
        //    - Prioritize synchronized lyrics (syncedLyrics).
        //    - If multiple exist, pick the one with duration closest to the local audio track.
        //    - If none synchronized, pick the one with duration closest to the local audio track (or first).
        val remoteRecord = selectBestCandidate(combinedCandidates, metadata.durationSeconds)
        val orderedCandidates = orderCandidates(combinedCandidates, remoteRecord, metadata.durationSeconds)

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
            candidates = orderedCandidates
        )
    }

    companion object {
        /**
         * Selects the best candidate lyrics record from LRCLIB based on:
         * 1. Priority to synchronized lyrics (syncedLyrics).
         * 2. If multiple synchronized lyrics exist, choose the one whose duration is closest to targetDurationSeconds.
         * 3. If targetDurationSeconds is unavailable (<= 0), choose the first synchronized record found.
         * 4. If no synchronized lyrics exist, choose the record whose duration is closest to targetDurationSeconds (or the first).
         */
        fun selectBestCandidate(
            candidates: List<LrcLibLyrics>,
            targetDurationSeconds: Int
        ): LrcLibLyrics? {
            if (candidates.isEmpty()) return null

            val syncedCandidates = candidates.filter { !it.syncedLyrics.isNullOrBlank() }

            return if (syncedCandidates.isNotEmpty()) {
                if (targetDurationSeconds > 0) {
                    syncedCandidates.minByOrNull { cand ->
                        val candDuration = cand.duration ?: 0.0
                        kotlin.math.abs(candDuration - targetDurationSeconds)
                    } ?: syncedCandidates.first()
                } else {
                    syncedCandidates.first()
                }
            } else {
                if (targetDurationSeconds > 0) {
                    candidates.minByOrNull { cand ->
                        val candDuration = cand.duration ?: 0.0
                        kotlin.math.abs(candDuration - targetDurationSeconds)
                    } ?: candidates.first()
                } else {
                    candidates.first()
                }
            }
        }

        /**
         * Places the selected candidate at index 0 and sorts the remaining candidates
         * prioritizing synchronized lyrics and proximity to target duration.
         */
        fun orderCandidates(
            candidates: List<LrcLibLyrics>,
            selectedRecord: LrcLibLyrics?,
            targetDurationSeconds: Int
        ): List<LrcLibLyrics> {
            if (selectedRecord == null || candidates.isEmpty()) return candidates

            val otherCandidates = candidates.filter { it.id != selectedRecord.id }
            val sortedOthers = otherCandidates.sortedWith(
                compareBy<LrcLibLyrics> { it.syncedLyrics.isNullOrBlank() }
                    .thenBy { cand ->
                        if (targetDurationSeconds > 0) {
                            val candDuration = cand.duration ?: 0.0
                            kotlin.math.abs(candDuration - targetDurationSeconds)
                        } else {
                            0.0
                        }
                    }
            )

            return listOf(selectedRecord) + sortedOthers
        }
    }
}
