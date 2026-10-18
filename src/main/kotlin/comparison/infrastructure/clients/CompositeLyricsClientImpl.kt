package comparison.infrastructure.clients

import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Multi-provider lyrics client that queries LRCLIB and all fallback providers
 * (AppleMusic, Spotify, YouTube, NetEase, QQMusic, KuGou, Musixmatch, Deezer, Genius)
 * in parallel and collects each provider's results immediately upon completion
 * so a slow or rate-limited provider never blocks or discards fast providers' results.
 */
class CompositeLyricsClientImpl(
    private val providers: List<LrcLibClient> = listOf(
        LrcLibClientImpl(),
        AppleMusicLyricsClientImpl(),
        SpotifyLyricsClientImpl(),
        YouTubeLyricsClientImpl(),
        NetEaseLyricsClientImpl(),
        QQMusicLyricsClientImpl(),
        KuGouLyricsClientImpl(),
        MusixmatchLyricsClientImpl(),
        DeezerLyricsClientImpl(),
        GeniusLyricsClientImpl()
    ),
    private val timeoutSeconds: Long = 10L,
) : LrcLibClient {

    companion object {
        private val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "composite-lyrics-worker").apply { isDaemon = true }
        }
    }

    override fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?,
    ): LrcLibLyrics? {
        val results = getAllLyrics(trackName, artistName, albumName, durationSeconds)
        return ExtractTrackDetailsUseCase.selectBestCandidate(results, durationSeconds ?: 0)
    }

    override fun getAllLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?,
    ): List<LrcLibLyrics> {
        val collected = CopyOnWriteArrayList<LrcLibLyrics>()

        val futures = providers.map { provider ->
            CompletableFuture.runAsync({
                try {
                    val batch = provider.getAllLyrics(trackName, artistName, albumName, durationSeconds)
                    if (batch.isNotEmpty()) {
                        collected.addAll(batch)
                    }
                } catch (_: Exception) {
                }
            }, executor)
        }

        try {
            CompletableFuture.allOf(*futures.toTypedArray()).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Return all results collected from providers that completed within the timeout window
        }

        return collected.toList()
    }

    override fun searchLyrics(query: String): List<LrcLibLyrics> {
        if (query.isBlank()) return emptyList()

        val collected = CopyOnWriteArrayList<LrcLibLyrics>()

        val futures = providers.map { provider ->
            CompletableFuture.runAsync({
                try {
                    val batch = provider.searchLyrics(query)
                    if (batch.isNotEmpty()) {
                        collected.addAll(batch)
                    }
                } catch (_: Exception) {
                }
            }, executor)
        }

        try {
            CompletableFuture.allOf(*futures.toTypedArray()).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Return all results collected from providers that completed within the timeout window
        }

        return collected.toList()
    }
}
