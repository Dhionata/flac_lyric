package models

import java.io.File

/**
 * Represents a pair of associated files: a lyric file (.lrc) and a corresponding audio file.
 *
 * @property lyricFile The lyric file (.lrc).
 * @property audioFile The corresponding audio file.
 */
data class FilePair(val lyricFile: File, val audioFile: File)
