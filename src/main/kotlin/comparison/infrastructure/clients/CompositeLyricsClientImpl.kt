package comparison.infrastructure.clients

import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Multi-provider lyrics client that queries LRCLIB and all fallback providers
 * (AppleMusic, Spotify, YouTube, NetEase, QQMusic, KuGou, Musixmatch, Deezer, Genius, Karalyr)
 * in parallel to maximize speed and song coverage.
 */
class CompositeLyricsClientImpl(
    private val providers: List<LrcLibClient> = listOf(
        LrcLibClientImpl(baseUrl = "https://lrclib.net", providerName = "LRCLIB"),
        AppleMusicLyricsClientImpl(),
        SpotifyLyricsClientImpl(),
        YouTubeLyricsClientImpl(),
        NetEaseLyricsClientImpl(),
        QQMusicLyricsClientImpl(),
        KuGouLyricsClientImpl(),
        MusixmatchLyricsClientImpl(),
        DeezerLyricsClientImpl(),
        GeniusLyricsClientImpl(),
        LrcLibClientImpl(baseUrl = "https://karalyr.com", providerName = "Karalyr")
    ),
    private val timeoutSeconds: Long = 8L
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
        durationSeconds: Int?
    ): LrcLibLyrics? {
        val futures = providers.map { provider ->
            CompletableFuture.supplyAsync({
                try {
                    provider.getLyrics(trackName, artistName, albumName, durationSeconds)
                } catch (_: Exception) {
                    null
                }
            }, executor)
        }

        val results = futures.mapNotNull { future ->
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }

        return ExtractTrackDetailsUseCase.selectBestCandidate(results, durationSeconds ?: 0)
    }

    override fun searchLyrics(query: String): List<LrcLibLyrics> {
        if (query.isBlank()) return emptyList()

        val futures = providers.map { provider ->
            CompletableFuture.supplyAsync({
                try {
                    provider.searchLyrics(query)
                } catch (_: Exception) {
                    emptyList()
                }
            }, executor)
        }

        return futures.flatMap { future ->
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
