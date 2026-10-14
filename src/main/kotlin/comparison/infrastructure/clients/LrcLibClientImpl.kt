package comparison.infrastructure.clients

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Client implementation for lrclib.net API using OkHttp and Gson.
 */
class LrcLibClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://lrclib.net",
) : LrcLibClient {

    private val userAgent = "flac_lyric/1.7.0 (https://github.com/dhionata/flac_lyric)"

    override fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?,
    ): LrcLibLyrics? {
        val urlBuilder = "$baseUrl/api/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("track_name", trackName)
        urlBuilder.addQueryParameter("artist_name", artistName)

        if (!albumName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album_name", albumName)
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", userAgent)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    gson.fromJson(bodyString, LrcLibLyrics::class.java)
                } else if (response.code == 404) {
                    // Fallback: search by query
                    val searchResults = searchLyrics("$artistName $trackName")
                    searchResults.firstOrNull { it.syncedLyrics != null }
                        ?: searchResults.firstOrNull()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun searchLyrics(query: String): List<LrcLibLyrics> {
        val urlBuilder = "$baseUrl/api/search".toHttpUrlOrNull()?.newBuilder() ?: return emptyList()
        urlBuilder.addQueryParameter("q", query)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", userAgent)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    val type = object : TypeToken<List<LrcLibLyrics>>() {}.type
                    gson.fromJson<List<LrcLibLyrics>>(bodyString, type) ?: emptyList()
                } else {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
