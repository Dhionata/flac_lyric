package comparison.domain.ports

import comparison.domain.models.LrcLibLyrics

/**
 * Port for interacting with the LRCLIB API to fetch and search synchronized lyrics.
 */
interface LrcLibClient {
    /**
     * Attempts to find the best matching lyric record for given track details.
     *
     * @param trackName Track title.
     * @param artistName Artist name.
     * @param albumName Optional album name.
     * @param durationSeconds Optional duration in seconds.
     * @return [LrcLibLyrics] if found, null otherwise.
     */
    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Int? = null,
    ): LrcLibLyrics?

    /**
     * Fetches matching lyric records across all underlying providers for the given track details.
     */
    fun getAllLyrics(
        trackName: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Int? = null,
    ): List<LrcLibLyrics> = listOfNotNull(getLyrics(trackName, artistName, albumName, durationSeconds))

    /**
     * Searches for lyrics using a query string.
     *
     * @param query Search query text (e.g. artist and song name).
     * @return List of matching [LrcLibLyrics].
     */
    fun searchLyrics(query: String): List<LrcLibLyrics>
}
