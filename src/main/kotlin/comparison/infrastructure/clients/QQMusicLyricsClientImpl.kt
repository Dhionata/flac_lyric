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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback client for QQ Music (`y.qq.com`) synchronized lyrics API (used in SongSync).
 * Searches candidate tracks and fetches `.lrc` lyrics in parallel.
 * Only returns candidates that have > 0 valid verses (synced or plain).
 */
class QQMusicLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val searchBaseUrl: String = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp",
    private val lyricBaseUrl: String = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
    private val maxCandidates: Int = 3,
    private val lineParser: LyricLineParser = LyricLineParserImpl(),
) : LrcLibClient {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val referer = "https://y.qq.com/"

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "qqmusic-lyrics-worker").apply { isDaemon = true }
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

        val url = searchBaseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("inCharset", "utf8")
            ?.addQueryParameter("outCharset", "utf8")
            ?.addQueryParameter("platform", "yqq.json")
            ?.addQueryParameter("new_json", "1")
            ?.addQueryParameter("w", query)
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .get()
            .build()

        val songs = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val list = json?.getAsJsonObject("data")
                    ?.getAsJsonObject("song")
                    ?.getAsJsonArray("list") ?: return emptyList()

                list.take(maxCandidates).mapNotNull { elem ->
                    val obj = elem.asJsonObject
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@mapNotNull null
                    val mid = obj.get("mid")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val artists = obj.getAsJsonArray("singer")
                        ?.mapNotNull { it.asJsonObject.get("name")?.takeIf { n -> !n.isJsonNull }?.asString }
                        ?.joinToString(", ") ?: ""
                    val album = obj.getAsJsonObject("album")?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val intervalSec = obj.get("interval")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

                    QQSongMeta(
                        id = id,
                        songMid = mid,
                        trackName = title,
                        artistName = artists,
                        albumName = album,
                        durationSeconds = if (intervalSec > 0) intervalSec else null
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

    private fun fetchLyricForSong(song: QQSongMeta): LrcLibLyrics? {
        val url = lyricBaseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("songmid", song.songMid)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("nobase64", "1")
            ?.addQueryParameter("g_tk", "5381")
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java) ?: return null
                val rawLyric = json.get("lyric")?.takeIf { !it.isJsonNull }?.asString
                    ?.replace("&#58;", ":")
                    ?.replace("&#46;", ".")
                    ?.replace("&#10;", "\n")
                    ?.replace("&#13;", "")
                    ?.replace("&#32;", " ")
                    ?.replace("&#45;", "-")
                    ?.replace("&#40;", "(")
                    ?.replace("&#41;", ")")
                    ?.trim()

                if (rawLyric.isNullOrBlank()) return null

                val hasSyncedVerses = lineParser.parse(rawLyric).isNotEmpty()
                val hasTimestampPattern = rawLyric.contains(Regex("""\[\d{2}:\d{2}"""))

                LrcLibLyrics(
                    id = song.id,
                    trackName = song.trackName,
                    artistName = song.artistName,
                    albumName = song.albumName,
                    duration = song.durationSeconds,
                    instrumental = false,
                    plainLyrics = if (!hasSyncedVerses) rawLyric else null,
                    syncedLyrics = if (hasSyncedVerses || hasTimestampPattern) rawLyric else null,
                    source = "QQMusic"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class QQSongMeta(
        val id: Long,
        val songMid: String,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double?,
    )
}
