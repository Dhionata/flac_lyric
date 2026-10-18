package comparison.application.usecases

import comparison.domain.models.ComparisonTrackDetails
import comparison.domain.models.LrcLibLyrics
import comparison.domain.models.ReportIssueItem
import comparison.domain.models.TrackAudioMetadata
import comparison.domain.ports.AudioMetadataExtractor
import comparison.domain.ports.LyricFileStorage
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
                abs(d - targetDurationSeconds)
            } else {
                Double.MAX_VALUE
            }
        }

        /**
         * Returns true if the candidate has > 0 parsed synchronized verses.
         */
        private fun hasValidSyncedVerses(
            cand: LrcLibLyrics,
            parser: LyricLineParser = defaultParser,
        ): Boolean {
            val synced = cand.syncedLyrics ?: return false
            return synced.isNotBlank() && parser.parse(synced).isNotEmpty()
        }

        /**
         * Returns true if the candidate has > 0 parsed plain verses.
         */
        private fun hasValidPlainVerses(
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
                syncedLyrics = validSynced, plainLyrics = validPlain ?: validSynced
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
            compareBy<LrcLibLyrics> { !hasValidSyncedVerses(it) }.thenBy { !hasValidPlainVerses(it) }.thenBy { cand -> durationDistance(cand, targetDurationSeconds) }
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

        /**
         * Builds the search queries covering all three cases:
         * 1. Artist followed by Track Title ("Artist Title")
         * 2. Track Title followed by Artist ("Title Artist")
         * 3. Track Title only ("Title")
         * Including cleaned title variants (without parenthetical/bracketed suffixes) when applicable.
         */
        fun buildSearchQueries(artist: String, title: String): List<String> {
            val cleanArtist = artist.trim()
            val rawTitle = title.trim()
            val cleanTitle = rawTitle.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s*\\[[^]]*]"), "").trim()

            val effectiveTitle = cleanTitle.ifBlank { rawTitle }
            val rawQueries = mutableListOf<String>()

            // 1. Artist + Title
            if (cleanArtist.isNotBlank() && rawTitle.isNotBlank()) {
                rawQueries.add("$cleanArtist $rawTitle")
            }
            if (cleanArtist.isNotBlank() && effectiveTitle.isNotBlank() && !effectiveTitle.equals(rawTitle, ignoreCase = true)) {
                rawQueries.add("$cleanArtist $effectiveTitle")
            }

            // 2. Title + Artist
            if (rawTitle.isNotBlank() && cleanArtist.isNotBlank()) {
                rawQueries.add("$rawTitle $cleanArtist")
            }
            if (effectiveTitle.isNotBlank() && cleanArtist.isNotBlank() && !effectiveTitle.equals(rawTitle, ignoreCase = true)) {
                rawQueries.add("$effectiveTitle $cleanArtist")
            }

            // 3. Title only
            if (rawTitle.isNotBlank()) {
                rawQueries.add(rawTitle)
            }
            if (effectiveTitle.isNotBlank() && !effectiveTitle.equals(rawTitle, ignoreCase = true)) {
                rawQueries.add(effectiveTitle)
            }

            // Fallback if title was blank but artist was present
            if (rawQueries.isEmpty() && cleanArtist.isNotBlank()) {
                rawQueries.add(cleanArtist)
            }

            val seen = mutableSetOf<String>()
            val deduplicated = mutableListOf<String>()
            for (q in rawQueries) {
                val normalized = q.replace(Regex("\\s+"), " ").trim()
                if (normalized.isNotBlank() && seen.add(normalized.lowercase())) {
                    deduplicated.add(normalized)
                }
            }
            return deduplicated
        }

        /**
         * Resolves the localized instrumental tag text for a given language/locale.
         */
        private fun resolveInstrumentalTag(langOrLocale: String? = null): String {
            val code = (langOrLocale ?: services.Messages.getLocale().toLanguageTag()).lowercase().trim()
            return when {
                code.startsWith("pt") -> "♪ Instrumental ♪"
                code.startsWith("en") -> "♪ Instrumental ♪"
                code.startsWith("es") -> "♪ Instrumental ♪"
                code.startsWith("ja") -> "♪ インストゥルメンタル ♪"
                code.startsWith("zh") -> "♪ 纯音乐 ♪"
                code.startsWith("ko") -> "♪ 연주곡 ♪"
                code.startsWith("ru") -> "♪ Инструментал ♪"
                else -> services.Messages.get("lyric.instrumental.tag")
            }
        }

        /**
         * Generates a synchronized LRC string for instrumental tracks from 00:00.00 up to the track's total duration
         * so that sync validation recognizes the full duration and never triggers a false-positive premature ending.
         */
        fun generateInstrumentalLyrics(
            durationSeconds: Int,
            langOrLocale: String? = null,
            customTag: String? = null,
        ): String {
            val tagText = customTag?.trim()?.takeIf { it.isNotBlank() } ?: resolveInstrumentalTag(langOrLocale)
            val effectiveSeconds = if (durationSeconds > 0) durationSeconds else 180
            val endMin = effectiveSeconds / 60
            val endSec = effectiveSeconds % 60
            val endFormatted = String.format("%02d:%02d.00", endMin, endSec)

            return "[00:00.00] $tagText\n[$endFormatted] $tagText"
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
            TrackAudioMetadata(
                title = title, artist = artist, album = "", durationSeconds = 0, hasArtwork = false
            )
        }

        val localRawLyrics = lyricStorage.readText(item.lyricFile)
        val localLines = parseLinesUseCase.execute(localRawLyrics)

        // Build comprehensive search query variants:
        // 1. Artist + Title, 2. Title + Artist, 3. Title only (plus clean title variants)
        val queryList = buildSearchQueries(metadata.artist, metadata.title)

        val collectedSearchResults = CopyOnWriteArrayList<LrcLibLyrics>()
        val collectedExactResults = CopyOnWriteArrayList<LrcLibLyrics>()

        // Run search queries and exact matches in parallel across all providers,
        // collecting results progressively so no partial results are lost on timeout
        val queryFutures = queryList.map { query ->
            CompletableFuture.runAsync({
                try {
                    val batch = fetchLrcLibUseCase.search(query)
                    collectedSearchResults.addAll(batch)
                } catch (_: Exception) {
                }
            }, parallelPool)
        }

        // Do NOT pass duration to fetchAllMatches so we don't limit exact lookups by duration
        val exactFuture = CompletableFuture.runAsync({
            try {
                val exactBatch = fetchLrcLibUseCase.fetchAllMatches(
                    trackName = metadata.title, artistName = metadata.artist, albumName = metadata.album.takeIf { it.isNotBlank() })
                collectedExactResults.addAll(exactBatch)
            } catch (_: Exception) {
            }
        }, parallelPool)

        val allFutures = queryFutures + exactFuture
        try {
            CompletableFuture.allOf(*allFutures.toTypedArray()).get(25, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Preserve all results collected so far in collectedSearchResults and collectedExactResults
        }

        val rawCombined = collectedExactResults.toList() + collectedSearchResults.toList()

        val allSearchResults = rawCombined.distinctBy { cand ->
            if (cand.id != null && cand.id != 0L) {
                "${cand.source}:${cand.id}"
            } else {
                "${cand.source}:${cand.trackName}:${cand.artistName}:${cand.duration}:${cand.syncedLyrics?.hashCode()}:${cand.plainLyrics?.hashCode()}"
            }
        }

        // Deduplicate and strictly enforce > 0 parsed verses (synced or plain)
        // so all valid results across all 3 search strategies appear in candidates ordered by duration proximity.
        val combinedCandidates = allSearchResults.mapNotNull { normalizeCandidateWithVerses(it) }

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
            item.id,
            item.fileName,
            item.lyricFile.absolutePath,
            item.audioFile?.absolutePath,
            metadata,
            item.issues,
            localRawLyrics,
            localLines,
            remoteRawLyrics,
            remoteLines,
            remoteStatus,
            item.status,
            orderedCandidates,
            allSearchResults
        )
    }
}
