package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.JsonObject
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.domain.ports.LyricLineParser
import comparison.infrastructure.parsers.LyricLineParserImpl
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback client for Musixmatch desktop API (`apic-desktop.musixmatch.com`).
 * Automatically caches a valid anonymous `user_token` and discards anti-bot dummy tokens (`000000...`).
 * Only returns candidates that have > 0 valid verses (synced or plain).
 */
class MusixmatchLyricsClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://apic-desktop.musixmatch.com/ws/1.1",
    private val lyricParser: LyricLineParser = LyricLineParserImpl(),
) : LrcLibClient {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    override fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?,
    ): LrcLibLyrics? {
        if (trackName.isBlank() && artistName.isBlank()) return null
        val token = getValidToken() ?: return null

        val urlBuilder = "$baseUrl/macro.subtitles.get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("app_id", "web-desktop-app-v1.0")
        urlBuilder.addQueryParameter("format", "json")
        urlBuilder.addQueryParameter("namespace", "lyrics_richsynched")
        urlBuilder.addQueryParameter("subtitle_format", "lrc")
        urlBuilder.addQueryParameter("usertoken", token)
        urlBuilder.addQueryParameter("q_track", trackName)
        urlBuilder.addQueryParameter("q_artist", artistName)
        if (!albumName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("q_album", albumName)
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("f_subtitle_length", durationSeconds.toString())
            urlBuilder.addQueryParameter("q_duration", durationSeconds.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", userAgent)
            .header("Cookie", "x-mxm-token-guid=$token")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java) ?: return null
                val macroCalls = json.getAsJsonObject("message")
                    ?.getAsJsonObject("body")
                    ?.getAsJsonObject("macro_calls") ?: return null

                val trackObj = macroCalls.getAsJsonObject("matcher.track.get")
                    ?.getAsJsonObject("message")
                    ?.getAsJsonObject("body")
                    ?.getAsJsonObject("track")

                val foundTrackName = trackObj?.get("track_name")?.takeIf { !it.isJsonNull }?.asString ?: trackName
                val foundArtistName = trackObj?.get("artist_name")?.takeIf { !it.isJsonNull }?.asString ?: artistName
                val foundAlbumName = trackObj?.get("album_name")?.takeIf { !it.isJsonNull }?.asString ?: albumName
                val trackId = trackObj?.get("track_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val trackLength = trackObj?.get("track_length")?.takeIf { !it.isJsonNull }?.asDouble

                // Guard against Musixmatch anti-bot dummy track ("NOKIA" by "Drake")
                if (foundTrackName.equals("NOKIA", ignoreCase = true) && !trackName.contains("NOKIA", ignoreCase = true)) {
                    return null
                }

                val subtitleList = macroCalls.getAsJsonObject("track.subtitles.get")
                    ?.getAsJsonObject("message")
                    ?.getAsJsonObject("body")
                    ?.getAsJsonArray("subtitle_list")

                val syncedBody = if (subtitleList != null && subtitleList.size() > 0) {
                    subtitleList[0].asJsonObject
                        .getAsJsonObject("subtitle")
                        ?.get("subtitle_body")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.trim()
                } else null

                val plainBody = macroCalls.getAsJsonObject("track.lyrics.get")
                    ?.getAsJsonObject("message")
                    ?.getAsJsonObject("body")
                    ?.getAsJsonObject("lyrics")
                    ?.get("lyrics_body")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.trim()

                val validSynced = syncedBody?.takeIf { lyricParser.parse(it).isNotEmpty() }
                val validPlain = plainBody?.takeIf { lyricParser.parsePlain(it).isNotEmpty() }

                if (validSynced == null && validPlain == null) {
                    return null
                }

                LrcLibLyrics(
                    id = trackId,
                    trackName = foundTrackName,
                    artistName = foundArtistName,
                    albumName = foundAlbumName,
                    duration = trackLength,
                    instrumental = false,
                    plainLyrics = validPlain,
                    syncedLyrics = validSynced,
                    source = "Musixmatch"
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
                "" to query.trim()
            }
        }
        val single = getLyrics(trackName = track, artistName = artist)
        return if (single != null) listOf(single) else emptyList()
    }

    @Synchronized
    private fun getValidToken(): String? {
        val now = System.currentTimeMillis()
        if (!cachedToken.isNullOrBlank() && now < tokenExpiresAtMs) {
            return cachedToken
        }

        val url = "$baseUrl/token.get".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("app_id", "web-desktop-app-v1.0")
            ?.addQueryParameter("user_language", "en")
            ?.addQueryParameter("t", now.toString())
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = gson.fromJson(response.body.string(), JsonObject::class.java)
                val token = json?.getAsJsonObject("message")
                    ?.getAsJsonObject("body")
                    ?.get("user_token")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString

                // Reject dummy zero-filled tokens returned when Musixmatch blocks datacenter/VPN IPs
                if (token.isNullOrBlank() || token.all { it == '0' }) {
                    cachedToken = null
                    tokenExpiresAtMs = now + 60_000L
                    null
                } else {
                    cachedToken = token
                    tokenExpiresAtMs = now + 600_000L // Cache for 10 minutes
                    token
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
