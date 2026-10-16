package comparison.infrastructure.clients

import com.google.gson.Gson
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Fallback client for NetEase Cloud Music (`music.163.com`) public lyrics API.
 * Fetches candidate songs and retrieves their `.lrc` lyrics in parallel.
 * Only returns candidates that have > 0 valid verses (synced or plain).
 */
class NetEaseLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://music.163.com",
    private val maxCandidates: Int = 4,
    private val lyricParser: LyricLineParser = LyricLineParserImpl()
) : LrcLibClient {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val referer = "https://music.163.com"

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "netease-lyrics-worker").apply { isDaemon = true }
        }
    }

    override fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?
    ): LrcLibLyrics? {
        val query = "$artistName $trackName".trim()
        if (query.isBlank()) return null
        val candidates = searchLyrics(query)
        return ExtractTrackDetailsUseCase.selectBestCandidate(candidates, durationSeconds ?: 0)
    }

    override fun searchLyrics(query: String): List<LrcLibLyrics> {
        if (query.isBlank()) return emptyList()

        val searchUrl = "$baseUrl/api/search/get".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("s", query)
            ?.addQueryParameter("type", "1")
            ?.addQueryParameter("limit", maxCandidates.toString())
            ?.addQueryParameter("offset", "0")
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val songMetas = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val songsArray = json?.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return emptyList()

                songsArray.take(maxCandidates).mapNotNull { elem ->
                    val obj = elem.asJsonObject
                    val id = obj.get("id")?.asLong ?: return@mapNotNull null
                    val name = obj.get("name")?.asString ?: ""
                    val durationMs = obj.get("duration")?.asDouble ?: 0.0
                    val artists = obj.getAsJsonArray("artists")
                        ?.mapNotNull { it.asJsonObject.get("name")?.asString }
                        ?.joinToString(", ") ?: ""
                    val album = obj.getAsJsonObject("album")?.get("name")?.asString ?: ""

                    SongMeta(
                        id = id,
                        trackName = name,
                        artistName = artists,
                        albumName = album,
                        durationSeconds = if (durationMs > 0) durationMs / 1000.0 else null
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (songMetas.isEmpty()) return emptyList()

        val futures = songMetas.map { meta ->
            CompletableFuture.supplyAsync({ fetchLyricForSong(meta) }, pool)
        }

        return futures.mapNotNull { future ->
            try {
                future.get(6, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchLyricForSong(meta: SongMeta): LrcLibLyrics? {
        val lyricUrl = "$baseUrl/api/song/lyric".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("os", "pc")
            ?.addQueryParameter("id", meta.id.toString())
            ?.addQueryParameter("lv", "-1")
            ?.addQueryParameter("kv", "-1")
            ?.addQueryParameter("tv", "-1")
            ?.build() ?: return null

        val request = Request.Builder()
            .url(lyricUrl)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java) ?: return null
                val rawLyric = json.getAsJsonObject("lrc")?.get("lyric")?.takeIf { !it.isJsonNull }?.asString?.trim()
                if (rawLyric.isNullOrBlank()) return null

                // Require > 0 actual parsed verses (filters out credit-only stubs like "[00:00.00-1] 作词 : ...")
                val hasSyncedVerses = lyricParser.parse(rawLyric).isNotEmpty()
                val hasPlainVerses = if (!hasSyncedVerses) lyricParser.parsePlain(rawLyric).isNotEmpty() else false
                if (!hasSyncedVerses && !hasPlainVerses) return null

                LrcLibLyrics(
                    id = meta.id,
                    trackName = meta.trackName,
                    artistName = meta.artistName,
                    albumName = meta.albumName,
                    duration = meta.durationSeconds,
                    instrumental = false,
                    plainLyrics = if (!hasSyncedVerses) rawLyric else null,
                    syncedLyrics = if (hasSyncedVerses) rawLyric else null,
                    source = "NetEase"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class SongMeta(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double?
    )
}
