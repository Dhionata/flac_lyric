package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.JsonArray
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
 * Fallback client for Apple Music + Paxsenix API (`amp-api.music.apple.com` + `lyrics.paxsenix.org/apple-music/lyrics`),
 * using the same architecture as `Lambada10/SongSync`.
 */
class AppleMusicLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val appleCatalogBaseUrl: String = "https://amp-api.music.apple.com/v1/catalog/us",
    private val paxLyricsBaseUrl: String = "https://lyrics.paxsenix.org",
    private val maxCandidates: Int = 3,
    private val lineParser: LyricLineParser = LyricLineParserImpl()
) : LrcLibClient {

    private val appleWebUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0"
    private val paxUserAgent = "SongSync/3.5 (Android 14)"
    private val jsAssetRegex = Regex("""/assets/index[^/"]+\.js""")
    private val jwtRegex = Regex("""eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+""")

    @Volatile
    private var cachedBearerToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "applemusic-lyrics-worker").apply { isDaemon = true }
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
        val token = getOrFetchAppleToken() ?: return emptyList()

        val searchUrl = "$appleCatalogBaseUrl/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("term", query)
            ?.addQueryParameter("types", "songs")
            ?.addQueryParameter("limit", maxCandidates.toString())
            ?.addQueryParameter("l", "en-US")
            ?.addQueryParameter("platform", "web")
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer $token")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", appleWebUserAgent)
            .header("Accept", "application/json")
            .get()
            .build()

        val songs = try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    cachedBearerToken = null
                    return emptyList()
                }
                if (!response.isSuccessful) return emptyList()
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val dataArray = json?.getAsJsonObject("results")
                    ?.getAsJsonObject("songs")
                    ?.getAsJsonArray("data") ?: return emptyList()

                dataArray.take(maxCandidates).mapNotNull { elem ->
                    val obj = elem.asJsonObject
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@mapNotNull null
                    val attrs = obj.getAsJsonObject("attributes") ?: return@mapNotNull null
                    val name = attrs.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val artist = attrs.get("artistName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val album = attrs.get("albumName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val durationMs = attrs.get("durationInMillis")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

                    AppleSongMeta(
                        id = id,
                        trackName = name,
                        artistName = artist,
                        albumName = album,
                        durationSeconds = if (durationMs > 0) durationMs / 1000.0 else null
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (songs.isEmpty()) return emptyList()

        val futures = songs.map { song ->
            CompletableFuture.supplyAsync({ fetchPaxLyricsForSong(song) }, pool)
        }

        return futures.mapNotNull { future ->
            try {
                future.get(7, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fetchPaxLyricsForSong(song: AppleSongMeta): LrcLibLyrics? {
        val url = "$paxLyricsBaseUrl/apple-music/lyrics".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("id", song.id.toString())
            ?.build() ?: return null

        for (attempt in 1..2) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", paxUserAgent)
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (attempt == 1 && response.code in listOf(429, 502, 503)) {
                            Thread.sleep(350)
                            return@use
                        }
                        return null
                    }

                    val bodyStr = response.body.string()
                    if (bodyStr.isBlank()) return null

                    val (syncedLrc, plainText) = convertPaxJsonToLyrics(bodyStr)
                    val validSynced = syncedLrc?.takeIf { lineParser.parse(it).isNotEmpty() }
                    val validPlain = plainText?.takeIf { lineParser.parsePlain(it).isNotEmpty() }
                    if (validSynced == null && validPlain == null) return null

                    return LrcLibLyrics(
                        id = song.id,
                        trackName = song.trackName,
                        artistName = song.artistName,
                        albumName = song.albumName,
                        duration = song.durationSeconds,
                        instrumental = false,
                        plainLyrics = if (validSynced == null) validPlain else null,
                        syncedLyrics = validSynced,
                        source = "AppleMusic"
                    )
                }
            } catch (_: Exception) {
                if (attempt == 2) return null
            }
        }
        return null
    }

    private fun convertPaxJsonToLyrics(jsonStr: String): Pair<String?, String?> {
        return try {
            val rootElement = gson.fromJson(jsonStr, com.google.gson.JsonElement::class.java) ?: return null to null
            var rawPlain: String? = null
            var rawLrcField: String? = null

            val contentArray: JsonArray? = when {
                rootElement.isJsonObject -> {
                    val obj = rootElement.asJsonObject
                    if (obj.get("error")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean == true) {
                        return null to null
                    }
                    rawLrcField = obj.get("lrc")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    rawPlain = obj.get("plain")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    obj.getAsJsonArray("content")
                }
                rootElement.isJsonArray -> rootElement.asJsonArray
                else -> null
            }

            if (!rawLrcField.isNullOrBlank()) {
                return rawLrcField to rawPlain
            }

            if (contentArray == null || contentArray.size() == 0) {
                return null to rawPlain
            }

            val sb = StringBuilder()
            for (lineElem in contentArray) {
                if (!lineElem.isJsonObject) continue
                val lineObj = lineElem.asJsonObject
                val timestampMs = lineObj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val textArray = lineObj.getAsJsonArray("text") ?: continue

                val lineText = buildString {
                    for (sylElem in textArray) {
                        if (!sylElem.isJsonObject) continue
                        val syl = sylElem.asJsonObject
                        val word = syl.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val isPart = syl.get("part")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        append(word)
                        if (!isPart) append(" ")
                    }
                }.trim()

                if (lineText.isNotEmpty()) {
                    sb.append("[${formatMsToLrc(timestampMs)}] $lineText\n")
                }
            }

            val builtLrc = sb.toString().trim().takeIf { it.isNotEmpty() }
            builtLrc to rawPlain
        } catch (_: Exception) {
            null to null
        }
    }

    private fun formatMsToLrc(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = ((ms % 1000) / 10).coerceIn(0L, 99L)
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    @Synchronized
    private fun getOrFetchAppleToken(): String? {
        val now = System.currentTimeMillis()
        if (!cachedBearerToken.isNullOrBlank() && now < tokenExpiresAtMs) {
            return cachedBearerToken
        }

        return try {
            val mainReq = Request.Builder()
                .url("https://beta.music.apple.com")
                .header("User-Agent", appleWebUserAgent)
                .get()
                .build()

            val jsPath = httpClient.newCall(mainReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                jsAssetRegex.find(resp.body.string())?.value
            } ?: return null

            val jsReq = Request.Builder()
                .url("https://beta.music.apple.com$jsPath")
                .header("User-Agent", appleWebUserAgent)
                .get()
                .build()

            val token = httpClient.newCall(jsReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                jwtRegex.find(resp.body.string())?.value
            } ?: return null

            cachedBearerToken = token
            tokenExpiresAtMs = now + 3_600_000L // Cache for 1 hour
            token
        } catch (_: Exception) {
            null
        }
    }

    private data class AppleSongMeta(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double?
    )
}
