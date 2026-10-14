package comparison.domain.models

/**
 * Metadata extracted from an audio file's tags and header.
 *
 * @property title Track title.
 * @property artist Track artist.
 * @property album Album name.
 * @property durationSeconds Duration of the audio file in seconds.
 * @property hasArtwork Indicates whether an embedded cover art is present.
 */
data class TrackAudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val hasArtwork: Boolean
)
