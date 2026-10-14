package comparison.application.usecases

import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient

/**
 * Use case to fetch or search lyrics from LRCLIB.
 */
class FetchLrcLibLyricsUseCase(
    private val lrcLibClient: LrcLibClient
) {
    fun fetchBestMatch(
        trackName: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Int? = null
    ): LrcLibLyrics? {
        return lrcLibClient.getLyrics(trackName, artistName, albumName, durationSeconds)
    }

    fun search(query: String): List<LrcLibLyrics> {
        return lrcLibClient.searchLyrics(query)
    }
}
