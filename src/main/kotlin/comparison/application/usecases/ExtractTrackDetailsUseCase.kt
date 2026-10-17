package comparison.application.usecases

import comparison.domain.models.ComparisonTrackDetails
import comparison.domain.models.LrcLibLyrics
import comparison.domain.models.ReportIssueItem
import comparison.domain.ports.AudioMetadataExtractor
import comparison.domain.ports.LyricFileStorage
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Use case to aggregate audio metadata, local lyrics, remote multi-provider lyrics, and parsed lines
 * into a single comprehensive model for the web player.
 */
class ExtractTrackDetailsUseCase(
    private val metadataExtractor: AudioMetadataExtractor,
    private val lyricStorage: LyricFileStorage,
    private val parseLinesUseCase: ParseLyricLinesUseCase,
    private val fetchLrcLibUseCase: FetchLrcLibLyricsUseCase,
) {
    companion object {
        private val parallelPool = Executors.newCachedThreadPool { r ->
            Thread(r, "extract-track-details-worker").apply { isDaemon = true }
        }
        private val defaultParser: LyricLineParser = LyricLineParserImpl()

        private fun sourcePriority(source: String?): Int = when (source?.uppercase()) {
            "LRCLIB" -> 0
            "APPLEMUSIC" -> 1
            "SPOTIFY" -> 2
            "YOUTUBE" -> 3
            "NETEASE" -> 4
            "QQMUSIC" -> 5
            "KUGOU" -> 6
            "MUSIXMATCH" -> 7
            "DEEZER" -> 8
            "GENIUS" -> 9
            "KARALYR" -> 10
            else -> 11
        }

        private fun durationDistance(cand: LrcLibLyrics, targetDurationSeconds: Int): Double {
            if (targetDurationSeconds <= 0) return 0.0
            val d = cand.duration
            return if (d != null && d > 0.0) {
                kotlin.math.abs(d - targetDurationSeconds)
            } else {
                Double.MAX_VALUE
            }
        }

        /**
         * Returns true if the candidate has > 0 parsed synchronized verses.
         */
        fun hasValidSyncedVerses(
            cand: LrcLibLyrics,
            parser: LyricLineParser = defaultParser,
        ): Boolean {
            val synced = cand.syncedLyrics ?: return false
            return synced.isNotBlank() && parser.parse(synced).isNotEmpty()
        }

        /**
         * Returns true if the candidate has > 0 parsed plain verses.
         */
        fun hasValidPlainVerses(
            cand: LrcLibLyrics,
            parser: LyricLineParser = defaultParser,
        ): Boolean {
            val plain = cand.plainLyrics ?: return false
            return plain.isNotBlank() && parser.parsePlain(plain).isNotEmpty()
        }

        /**
         * Normalizes a candidate so that 0-verse syncedLyrics or 0-verse plainLyrics
         * (e.g., credit-only stubs like "[00:00.00-1] 作词 : ...") are stripped to null.
         * Returns null if neither syncedLyrics nor plainLyrics has > 0 verses.
         */
        fun normalizeCandidateWithVerses(
            cand: LrcLibLyrics,
            parser: LyricLineParser = defaultParser,
        ): LrcLibLyrics? {
            val validSynced = cand.syncedLyrics?.takeIf { it.isNotBlank() && parser.parse(it).isNotEmpty() }
            val validPlain = cand.plainLyrics?.takeIf { it.isNotBlank() && parser.parsePlain(it).isNotEmpty() }
            if (validSynced == null && validPlain == null) return null
            return cand.copy(
                syncedLyrics = validSynced,
                plainLyrics = validPlain ?: validSynced
            )
        }

        /**
         * Filters out any candidate that has 0 parsed verses (both synced and plain)
         * and normalizes candidates whose syncedLyrics has 0 verses down to plainLyrics (or removes them).
         */
        fun filterCandidatesWithVerses(
            candidates: List<LrcLibLyrics>,
            parser: LyricLineParser = defaultParser,
        ): List<LrcLibLyrics> = candidates.mapNotNull { normalizeCandidateWithVerses(it, parser) }

        private fun candidateComparator(targetDurationSeconds: Int): Comparator<LrcLibLyrics> =
            compareBy<LrcLibLyrics> { !hasValidSyncedVerses(it) }
                .thenBy { !hasValidPlainVerses(it) }
                .thenBy { cand -> durationDistance(cand, targetDurationSeconds) }
                .thenBy { cand -> sourcePriority(cand.source) }

        /**
         * Selects the best candidate lyrics record across all providers based on:
         * 1. Priority to synchronized lyrics with > 0 parsed verses, then plain lyrics with > 0 verses.
         * 2. Proximity of candidate duration to targetDurationSeconds (sorting only).
         * 3. Provider priority tie-breaker.
         */
        fun selectBestCandidate(
            candidates: List<LrcLibLyrics>,
            targetDurationSeconds: Int,
        ): LrcLibLyrics? {
            val valid = filterCandidatesWithVerses(candidates)
            if (valid.isEmpty()) return null
            return valid.sortedWith(candidateComparator(targetDurationSeconds)).firstOrNull()
        }

        /**
         * Places the selected candidate at index 0 and sorts the remaining candidates
         * prioritizing synchronized lyrics and proximity to target duration in seconds.
         */
        fun orderCandidates(
            candidates: List<LrcLibLyrics>,
            selectedRecord: LrcLibLyrics?,
            targetDurationSeconds: Int,
        ): List<LrcLibLyrics> {
            if (selectedRecord == null || candidates.isEmpty()) return candidates

            val otherCandidates = candidates.filterNot {
                it === selectedRecord || (it.id != null && it.id == selectedRecord.id && it.source == selectedRecord.source)
            }
            val sortedOthers = otherCandidates.sortedWith(candidateComparator(targetDurationSeconds))

            return listOf(selectedRecord) + sortedOthers
        }
    }

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

        val cleanTitle = metadata.title.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        val primaryQuery = "${metadata.artist} ${metadata.title}".trim()
        val queryWithCleanTitle = "${metadata.artist} $cleanTitle".trim()
        val titleOnlyQuery = cleanTitle.ifBlank { metadata.title }.trim()

        // Build comprehensive search query variants so covers/remixes without duration match
        // still bring back all versions of the song across all providers
        val queryList = linkedSetOf<String>().apply {
            if (primaryQuery.isNotBlank()) add(primaryQuery)
            if (queryWithCleanTitle.isNotBlank()) add(queryWithCleanTitle)
            if (titleOnlyQuery.isNotBlank()) add(titleOnlyQuery)
        }.toList()

        // Run search queries and exact match in parallel across all providers
        val searchFuture = CompletableFuture.supplyAsync({
            val allResults = mutableListOf<LrcLibLyrics>()
            for (query in queryList) {
                try {
                    val batch = fetchLrcLibUseCase.search(query)
                    allResults.addAll(batch)
                } catch (_: Exception) {
                }
            }
            allResults
        }, parallelPool)

        // Do NOT pass duration to fetchAllMatches so we don't limit exact lookups by duration
        val exactFuture = CompletableFuture.supplyAsync({
            fetchLrcLibUseCase.fetchAllMatches(
                trackName = metadata.title,
                artistName = metadata.artist,
                albumName = metadata.album.takeIf { it.isNotBlank() }
            )
        }, parallelPool)

        val candidatesList = try {
            searchFuture.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            emptyList()
        }

        val exactRecords = try {
            exactFuture.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            emptyList()
        }

        val rawCombined = exactRecords + candidatesList

        val allSearchResults = rawCombined.distinctBy { cand ->
            if (cand.id != null) {
                "${cand.source}:${cand.id}"
            } else {
                "${cand.source}:${cand.trackName}:${cand.artistName}:${cand.duration}:${cand.syncedLyrics?.hashCode()}:${cand.plainLyrics?.hashCode()}"
            }
        }

        // Deduplicate by (source, id) when id is present, or by content signature,
        // and strictly enforce > 0 parsed verses (synced or plain) so 0-verse results never appear in filtered candidates.
        val combinedCandidates = allSearchResults
            .mapNotNull { normalizeCandidateWithVerses(it) }

        val remoteRecord = selectBestCandidate(combinedCandidates, metadata.durationSeconds)
        val orderedCandidates = orderCandidates(combinedCandidates, remoteRecord, metadata.durationSeconds)

        val remoteRawLyrics = remoteRecord?.syncedLyrics ?: remoteRecord?.plainLyrics
        val remoteLines = when {
            !remoteRecord?.syncedLyrics.isNullOrBlank() -> parseLinesUseCase.execute(remoteRecord.syncedLyrics)
            !remoteRecord?.plainLyrics.isNullOrBlank() -> parseLinesUseCase.executePlain(remoteRecord.plainLyrics)
            else -> emptyList()
        }

        val remoteStatus = when {
            remoteRecord == null || remoteLines.isEmpty() -> "NOT_FOUND"
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
            candidates = orderedCandidates,
            allSearchResults = allSearchResults
        )
    }
}
