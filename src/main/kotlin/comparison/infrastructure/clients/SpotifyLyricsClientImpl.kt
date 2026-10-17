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
 * Fallback client for Spotify via Lyrically/Paxsenix API (`lyrics.paxsenix.org/spotify/search` & `/spotify/lyrics`).
 * Supports both synchronized LRC and unsynchronized plain lyrics (with > 0 verses).
 */
class SpotifyLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://lyrics.paxsenix.org",
    private val maxCandidates: Int = 3,
    private val lyricParser: LyricLineParser = LyricLineParserImpl(),
) : LrcLibClient {

    private val userAgent = "SongSync/3.5 (Android 14)"

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "spotify-lyrics-worker").apply { isDaemon = true }
        }

        internal fun parseDurationStringToSeconds(durationStr: String?): Double? {
            if (durationStr.isNullOrBlank()) return null
            val parts = durationStr.trim().split(":").mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]).toDouble()
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toDouble()
                else -> durationStr.toDoubleOrNull()
            }?.takeIf { it > 0.0 }
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

        val searchUrl = "$baseUrl/spotify/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()

        val tracks = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = gson.fromJson(response.body.string(), JsonElement::class.java) ?: return emptyList()
                if (!root.isJsonArray) return emptyList()

                root.asJsonArray.take(maxCandidates).mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val obj = elem.asJsonObject
                    val trackId = (obj.get("trackId") ?: obj.get("id"))?.takeIf { !it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                    val name = (obj.get("name") ?: obj.get("songName"))?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val artist = obj.get("artistName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val album = obj.get("albumName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val durationStr = obj.get("duration")?.takeIf { !it.isJsonNull }?.asString

                    SpotifyTrackMeta(
                        trackId = trackId,
                        trackName = name,
                        artistName = artist,
                        albumName = album,
                        durationSeconds = parseDurationStringToSeconds(durationStr)
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (tracks.isEmpty()) return emptyList()

        val futures = tracks.map { meta ->
            CompletableFuture.supplyAsync({ fetchLyricForTrack(meta) }, pool)
        }

        return futures.mapNotNull { future ->
            try {
                future.get(7, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchLyricForTrack(meta: SpotifyTrackMeta): LrcLibLyrics? {
        val url = "$baseUrl/spotify/lyrics".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("id", meta.trackId)
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
                    id = abs(meta.trackId.hashCode().toLong()),
                    trackName = meta.trackName,
                    artistName = meta.artistName,
                    albumName = meta.albumName,
                    duration = meta.durationSeconds,
                    instrumental = false,
                    plainLyrics = nonBlankPlain,
                    syncedLyrics = nonBlankSynced,
                    source = "Spotify"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class SpotifyTrackMeta(
        val trackId: String,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double?,
    )
}
