package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.JsonObject
import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback client for KuGou Music (`kugou.com`) public lyrics API.
 * Searches songs, resolves LRC candidates, and decodes Base64 synchronized lyrics in parallel.
 * Only returns candidates that have > 0 valid verses (synced or plain).
 */
class KuGouLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val songSearchBaseUrl: String = "http://mobilecdn.kugou.com",
    private val lyricSearchBaseUrl: String = "http://krcs.kugou.com",
    private val lyricDownloadBaseUrl: String = "http://lyrics.kugou.com",
    private val maxCandidates: Int = 3,
    private val lyricParser: LyricLineParser = LyricLineParserImpl(),
) : LrcLibClient {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "kugou-lyrics-worker").apply { isDaemon = true }
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

        val searchUrl = "$songSearchBaseUrl/api/v3/search/song".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("keyword", query)
            ?.addQueryParameter("page", "1")
            ?.addQueryParameter("pagesize", maxCandidates.toString())
            ?.addQueryParameter("showtype", "1")
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", userAgent)
            .get()
            .build()

        val songs = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val infoArray = json?.getAsJsonObject("data")?.getAsJsonArray("info") ?: return emptyList()

                infoArray.take(maxCandidates).mapNotNull { elem ->
                    val obj = elem.asJsonObject
                    val hash = obj.get("hash")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    val songName = obj.get("songname")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val singerName = obj.get("singername")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val albumName = obj.get("album_name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val durationSec = obj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

                    KuGouSong(
                        hash = hash,
                        trackName = songName,
                        artistName = singerName,
                        albumName = albumName,
                        durationSeconds = if (durationSec > 0) durationSec else null
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (songs.isEmpty()) return emptyList()

        val futures = songs.map { song ->
            CompletableFuture.supplyAsync({ fetchLyricForSong(song) }, pool)
        }

        return futures.mapNotNull { future ->
            try {
                future.get(6, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchLyricForSong(song: KuGouSong): LrcLibLyrics? {
        val durationMs = ((song.durationSeconds ?: 0.0) * 1000).toLong()
        val krcSearchUrl = "$lyricSearchBaseUrl/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ver", "1")
            ?.addQueryParameter("man", "yes")
            ?.addQueryParameter("client", "mobi")
            ?.addQueryParameter("keyword", "")
            ?.addQueryParameter("duration", if (durationMs > 0) durationMs.toString() else "")
            ?.addQueryParameter("hash", song.hash)
            ?.addQueryParameter("album_audio_id", "")
            ?.build() ?: return null

        val searchReq = Request.Builder()
            .url(krcSearchUrl)
            .header("User-Agent", userAgent)
            .get()
            .build()

        val candidateInfo = try {
            httpClient.newCall(searchReq).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val candidates = json?.getAsJsonArray("candidates") ?: return null
                if (candidates.size() == 0) return null
                val first = candidates[0].asJsonObject
                val id = first.get("id")?.asString ?: return null
                val accessKey = first.get("accesskey")?.asString ?: return null
                id to accessKey
            }
        } catch (_: Exception) {
            return null
        }

        val downloadUrl = "$lyricDownloadBaseUrl/download".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ver", "1")
            ?.addQueryParameter("client", "pc")
            ?.addQueryParameter("id", candidateInfo.first)
            ?.addQueryParameter("accesskey", candidateInfo.second)
            ?.addQueryParameter("fmt", "lrc")
            ?.addQueryParameter("charset", "utf8")
            ?.build() ?: return null

        val downloadReq = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", userAgent)
            .get()
            .build()

        return try {
            httpClient.newCall(downloadReq).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java) ?: return null
                val base64Content = json.get("content")?.takeIf { !it.isJsonNull }?.asString ?: return null
                if (base64Content.isBlank()) return null

                val decodedLrc = String(Base64.getDecoder().decode(base64Content), StandardCharsets.UTF_8).trim()
                if (decodedLrc.isBlank()) return null

                val hasSyncedVerses = lyricParser.parse(decodedLrc).isNotEmpty()
                val hasTimestampPattern = decodedLrc.contains(Regex("""\[\d{2}:\d{2}"""))

                LrcLibLyrics(
                    id = candidateInfo.first.toLongOrNull() ?: song.hash.hashCode().toLong(),
                    trackName = song.trackName,
                    artistName = song.artistName,
                    albumName = song.albumName,
                    duration = song.durationSeconds,
                    instrumental = false,
                    plainLyrics = if (!hasSyncedVerses) decodedLrc else null,
                    syncedLyrics = if (hasSyncedVerses || hasTimestampPattern) decodedLrc else null,
                    source = "KuGou"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class KuGouSong(
        val hash: String,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double?,
    )
}
