package comparison.domain.models

/**
 * Representation of a lyrics record retrieved from the LRCLIB API.
 *
 * @property id Identifier in the LRCLIB database.
 * @property trackName Track name returned by the service.
 * @property artistName Artist name returned by the service.
 * @property albumName Album name returned by the service.
 * @property duration Duration in seconds registered in LRCLIB.
 * @property instrumental Flag indicating whether the track is instrumental.
 * @property plainLyrics Plain unsynchronized lyrics text.
 * @property syncedLyrics Synced lyrics text with LRC timestamps.
 */
data class LrcLibLyrics(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)
