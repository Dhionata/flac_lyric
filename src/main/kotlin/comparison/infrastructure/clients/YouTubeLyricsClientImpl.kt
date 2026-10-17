package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.JsonElement
import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback client for YouTube Music via Lyrically/Paxsenix API (`lyrics.paxsenix.org/youtube/search` & `/youtube/lyrics`).
 * Supports both synchronized LRC and unsynchronized plain lyrics (with > 0 verses).
 */
class YouTubeLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://lyrics.paxsenix.org",
    private val maxCandidates: Int = 2,
    private val lyricParser: LyricLineParser = LyricLineParserImpl(),
) : LrcLibClient {

    private val userAgent = "SongSync/3.5 (Android 14)"

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "youtube-lyrics-worker").apply { isDaemon = true }
        }
    }

    override fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?,
    ): LrcLibLyrics? {
        val query = "$artistName $trackName".trim()
        if (query.isBlank()) return null
        val candidates = searchLyrics(query)
        return ExtractTrackDetailsUseCase.selectBestCandidate(candidates, durationSeconds ?: 0)
    }

    override fun searchLyrics(query: String): List<LrcLibLyrics> {
        if (query.isBlank()) return emptyList()

        val searchUrl = "$baseUrl/youtube/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()

        val videos = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = gson.fromJson(response.body.string(), JsonElement::class.java) ?: return emptyList()
                if (!root.isJsonArray) return emptyList()

                root.asJsonArray.take(maxCandidates).mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val obj = elem.asJsonObject
                    val videoId = (obj.get("videoId") ?: obj.get("id"))?.takeIf { !it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                    val title = (obj.get("title") ?: obj.get("songName"))?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val author = (obj.get("author") ?: obj.get("artistName"))?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val durationStr = obj.get("duration")?.takeIf { !it.isJsonNull }?.asString

                    YouTubeTrackMeta(
                        videoId = videoId,
                        trackName = title,
                        artistName = author,
                        durationSeconds = SpotifyLyricsClientImpl.parseDurationStringToSeconds(durationStr)
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (videos.isEmpty()) return emptyList()

        val futures = videos.map { meta ->
            CompletableFuture.supplyAsync({ fetchLyricForVideo(meta) }, pool)
        }

        return futures.mapNotNull { future ->
            try {
                future.get(7, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchLyricForVideo(meta: YouTubeTrackMeta): LrcLibLyrics? {
        val url = "$baseUrl/youtube/lyrics".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("id", meta.videoId)
            ?.addQueryParameter("v", "2")
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                if (body.isBlank()) return null

                val elem = gson.fromJson(body, JsonElement::class.java) ?: return null
                var rawSynced: String? = null
                var rawPlain: String? = null

                if (elem.isJsonObject) {
                    val obj = elem.asJsonObject
                    if (obj.get("error")?.takeIf { !it.isJsonNull } != null && obj.get("lrc") == null && obj.get("plain") == null) {
                        return null
                    }
                    rawSynced = (obj.get("lrc")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("elrc")?.takeIf { !it.isJsonNull }?.asString)?.trim()
                    rawPlain = obj.get("plain")?.takeIf { !it.isJsonNull }?.asString?.trim()
                } else if (elem.isJsonPrimitive) {
                    val text = elem.asString.trim()
                    rawSynced = text
                    rawPlain = text
                }

                val nonBlankSynced = rawSynced?.takeIf { it.isNotBlank() }
                val nonBlankPlain = (rawPlain ?: rawSynced)?.takeIf { it.isNotBlank() }

                if (nonBlankSynced == null && nonBlankPlain == null) return null

                LrcLibLyrics(
                    id = abs(meta.videoId.hashCode().toLong()),
                    trackName = meta.trackName,
                    artistName = meta.artistName,
                    albumName = "",
                    duration = meta.durationSeconds,
                    instrumental = false,
                    plainLyrics = nonBlankPlain,
                    syncedLyrics = nonBlankSynced,
                    source = "YouTube"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class YouTubeTrackMeta(
        val videoId: String,
        val trackName: String,
        val artistName: String,
        val durationSeconds: Double?,
    )
}
