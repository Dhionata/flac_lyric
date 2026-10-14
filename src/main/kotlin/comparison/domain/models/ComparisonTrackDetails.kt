package comparison.domain.models

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Aggregate model containing comprehensive comparison information for the web player view.
 *
 * @property id Item identifier.
 * @property fileName Name of the .lrc file.
 * @property lyricPath Absolute path to the local .lrc file.
 * @property audioPath Absolute path to the corresponding audio file, if found.
 * @property metadata Audio track metadata.
 * @property issues List of issues flagged for this track.
 * @property localRawLyrics Raw content of the local .lrc file.
 * @property localLines Parsed timestamped lines for the local lyric.
 * @property remoteRawLyrics Raw content of the remote LRCLIB lyrics.
 * @property remoteLines Parsed timestamped lines for the LRCLIB lyric.
 * @property remoteStatus Remote status ("FOUND_SYNCED", "FOUND_PLAIN", "INSTRUMENTAL", "NOT_FOUND", "ERROR").
 * @property status Current user resolution status ("PENDING", "SAVED_LRCLIB", "KEPT_LOCAL").
 * @property candidates Alternative lyrics records returned by LRCLIB search.
 * @property audioUrl Ready-to-use URL endpoint for streaming audio in web players.
 * @property artworkUrl Ready-to-use URL endpoint for track artwork in web players.
 * @property trackName Track title extracted from tags or inferred from filename.
 * @property artist Artist name extracted from tags or inferred from filename.
 * @property album Album name extracted from tags.
 */
data class ComparisonTrackDetails(
    val id: Int,
    val fileName: String,
    val lyricPath: String,
    val audioPath: String?,
    val metadata: TrackAudioMetadata,
    val issues: List<String>,
    val localRawLyrics: String,
    val localLines: List<ParsedLyricLine>,
    val remoteRawLyrics: String?,
    val remoteLines: List<ParsedLyricLine>,
    val remoteStatus: String,
    val status: String,
    val candidates: List<LrcLibLyrics> = emptyList(),
    val audioUrl: String? = audioPath?.let { "/api/audio?path=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()) },
    val artworkUrl: String? = if (metadata.hasArtwork && audioPath != null) "/api/artwork?path=" + URLEncoder.encode(audioPath, StandardCharsets.UTF_8.name()) else null,
    val trackName: String = metadata.title.ifBlank { fileName },
    val artist: String = metadata.artist,
    val album: String = metadata.album
)
