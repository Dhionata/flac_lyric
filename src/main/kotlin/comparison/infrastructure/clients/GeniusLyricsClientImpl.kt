package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.JsonElement
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback client for Genius via Lyrically/Paxsenix API (`lyrics.paxsenix.org/genius/lyrics`).
 * Fetches plain (unsynchronized) lyrics with > 0 verses so the user can select and synchronize them manually.
 */
class GeniusLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://lyrics.paxsenix.org",
    private val lyricParser: LyricLineParser = LyricLineParserImpl()
) : LrcLibClient {

    private val userAgent = "SongSync/3.5 (Android 14)"

    override fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?
    ): LrcLibLyrics? {
        if (trackName.isBlank() || artistName.isBlank()) return null
        val geniusSongUrl = buildGeniusUrl(artistName, trackName) ?: return null

        val url = "$baseUrl/genius/lyrics".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("url", geniusSongUrl)
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

                val plain = (obj.get("plain")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("lyrics")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString)?.trim()

                if (plain.isNullOrBlank() || lyricParser.parsePlain(plain).isEmpty()) {
                    return null
                }

                LrcLibLyrics(
                    id = abs(geniusSongUrl.hashCode().toLong()),
                    trackName = trackName,
                    artistName = artistName,
                    albumName = albumName ?: "",
                    duration = durationSeconds?.takeIf { it > 0 }?.toDouble(),
                    instrumental = false,
                    plainLyrics = plain,
                    syncedLyrics = null,
                    source = "Genius"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun searchLyrics(query: String): List<LrcLibLyrics> {
        if (query.isBlank()) return emptyList()
        val parts = query.split(" - ", limit = 2)
        val (artist, track) = if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            val words = query.trim().split(Regex("\\s+"))
            if (words.size >= 2) {
                words.take(words.size / 2).joinToString(" ") to words.drop(words.size / 2).joinToString(" ")
            } else {
                return emptyList()
            }
        }
        val single = getLyrics(trackName = track, artistName = artist, albumName = null, durationSeconds = null)
        return if (single != null) listOf(single) else emptyList()
    }

    internal fun buildGeniusUrl(artistName: String, trackName: String): String? {
        val raw = "$artistName $trackName"
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace("&", " and ")
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .trim()
            .replace(Regex("[\\s-]+"), "-")
            .lowercase()

        if (normalized.isBlank()) return null
        val capitalized = normalized.replaceFirstChar { it.uppercase() }
        return "https://genius.com/$capitalized-lyrics"
    }
}
