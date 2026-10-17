package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback client for Deezer (`api.deezer.com/search` + `lyrics.paxsenix.org/deezer/lyrics`).
 * Supports both synchronized LRC and unsynchronized plain lyrics (with > 0 verses).
 */
class DeezerLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val deezerSearchBaseUrl: String = "https://api.deezer.com",
    private val paxBaseUrl: String = "https://lyrics.paxsenix.org",
    private val maxCandidates: Int = 2,
    private val lyricParser: LyricLineParser = LyricLineParserImpl(),
) : LrcLibClient {

    private val userAgent = "SongSync/3.5 (Android 14)"

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "deezer-lyrics-worker").apply { isDaemon = true }
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

        val searchUrl = "$deezerSearchBaseUrl/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("limit", maxCandidates.toString())
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
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val dataArray = json?.getAsJsonArray("data") ?: return emptyList()

                dataArray.take(maxCandidates).mapNotNull { elem ->
                    if (!elem.isJsonObject) return@mapNotNull null
                    val obj = elem.asJsonObject
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@mapNotNull null
                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val artist = obj.getAsJsonObject("artist")?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val album = obj.getAsJsonObject("album")?.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val durationSec = obj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

                    DeezerTrackMeta(
                        id = id,
                        trackName = title,
                        artistName = artist,
                        albumName = album,
                        durationSeconds = if (durationSec > 0) durationSec else null
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
                future.get(6, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchLyricForTrack(meta: DeezerTrackMeta): LrcLibLyrics? {
        val url = "$paxBaseUrl/deezer/lyrics".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("id", meta.id.toString())
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
                if (!elem.isJsonObject) return null
                val obj = elem.asJsonObject
                if (obj.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean == true) return null

                val rawSynced = (obj.get("lrc")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("elrc")?.takeIf { !it.isJsonNull }?.asString)?.trim()
                val rawPlain = obj.get("plain")?.takeIf { !it.isJsonNull }?.asString?.trim()

                val nonBlankSynced = rawSynced?.takeIf { it.isNotBlank() }
                val nonBlankPlain = (rawPlain ?: rawSynced)?.takeIf { it.isNotBlank() }

                if (nonBlankSynced == null && nonBlankPlain == null) return null

                LrcLibLyrics(
                    id = meta.id,
                    trackName = meta.trackName,
                    artistName = meta.artistName,
                    albumName = meta.albumName,
                    duration = meta.durationSeconds,
                    instrumental = false,
                    plainLyrics = nonBlankPlain,
                    syncedLyrics = nonBlankSynced,
                    source = "Deezer"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class DeezerTrackMeta(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double?,
    )
}
