package services

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag

/**
 * Metadata summary about embedded synchronized/timestamped lyrics found inside an audio file's tag.
 */
data class EmbeddedSyncedLyricsInfo(
    val tagKeys: List<String>,
    val content: String,
)

/**
 * Detailed outcome when attempting to inspect and clean embedded synchronized lyrics from an audio file.
 */
sealed class EmbeddedCleanupOutcome {
    data class Removed(val info: EmbeddedSyncedLyricsInfo) : EmbeddedCleanupOutcome()
    object Clean : EmbeddedCleanupOutcome()
    data class SkippedUnsupported(val extension: String) : EmbeddedCleanupOutcome() {
        val reasonKey: String
            get() = if (extension == "ogg-opus") "audit.reason.ogg_opus_container" else "audit.reason.unsupported_tag_format"
        val reasonArgs: List<Any>
            get() = if (extension == "ogg-opus") emptyList() else listOf(extension)
    }
}

/**
 * Inspects and cleans embedded lyric tags (such as Vorbis `LYRICS` in `.flac` or ID3 `USLT`/`SYLT` in `.mp3`)
 * that contain LRC timestamps (`[mm:ss]` / `<mm:ss>`).
 *
 * Players like Samsung Music do not parse timestamps inside embedded tags and instead display raw
 * `[00:12.72]` text on screen (or fail to scroll), even when a valid external `.lrc` sidecar exists.
 */
object EmbeddedLyricsHelper {

    private val vorbisExtraLyricKeys = listOf("UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRIC")
    private val unsupportedTagExtensions = setOf("opus", "aac", "mid", "midi", "mod")

    /**
     * Returns true when [audioFile] is an Ogg container carrying an Opus stream (`OpusHead`),
     * even if it was saved with a `.ogg` or `.oga` extension (jaudiotagger's Ogg reader only supports Vorbis).
     */
    fun isOggOpusContainer(audioFile: File): Boolean {
        if (!audioFile.exists() || !audioFile.isFile) return false
        return try {
            val header = ByteArray(64)
            val bytesRead = audioFile.inputStream().use { it.read(header) }
            if (bytesRead < 36) return false
            val ascii = String(header, 0, bytesRead, Charsets.ISO_8859_1)
            ascii.startsWith("OggS") && ascii.contains("OpusHead")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true when jaudiotagger supports reading/writing tags for the given audio file extension/header.
     */
    fun isTagEditingSupported(audioFile: File): Boolean {
        val ext = audioFile.extension.lowercase()
        if (ext.isBlank() || ext in unsupportedTagExtensions) return false
        if ((ext == "ogg" || ext == "oga") && isOggOpusContainer(audioFile)) return false
        return true
    }

    private fun hasTimestampTags(text: String): Boolean {
        return text.isNotBlank() && (
            LyricSyntaxPatterns.TIMESTAMP_REGEX.containsMatchIn(text) ||
                LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX.containsMatchIn(text)
            )
    }

    /**
     * Inspects an already-loaded Jaudiotagger [Tag] for embedded lyrics containing timestamps.
     * Returns null when no timestamped embedded lyrics are present.
     */
    fun inspectEmbeddedSyncedLyrics(tag: Tag?): EmbeddedSyncedLyricsInfo? {
        if (tag == null) return null

        val foundKeys = linkedSetOf<String>()
        val contentParts = mutableListOf<String>()

        val primaryValues = try {
            tag.getAll(FieldKey.LYRICS).filter { hasTimestampTags(it) }
        } catch (_: Exception) {
            emptyList()
        }
        if (primaryValues.isNotEmpty()) {
            foundKeys.add("LYRICS")
            contentParts.addAll(primaryValues)
        }

        if (tag is VorbisCommentTag) {
            for (key in vorbisExtraLyricKeys) {
                val values = try {
                    tag.getAll(key).filter { hasTimestampTags(it) }
                } catch (_: Exception) {
                    emptyList()
                }
                if (values.isNotEmpty()) {
                    foundKeys.add(key)
                    contentParts.addAll(values)
                }
            }
        }

        if (tag is AbstractID3v2Tag) {
            val syltFields = try {
                tag.getFields("SYLT")
            } catch (_: Exception) {
                emptyList()
            }
            if (syltFields.isNotEmpty()) {
                foundKeys.add("SYLT")
                syltFields.forEach { field ->
                    val text = (field as? TagTextField)?.content ?: field.toString()
                    if (text.isNotBlank()) contentParts.add(text)
                }
            }
        }

        if (foundKeys.isEmpty()) return null
        return EmbeddedSyncedLyricsInfo(
            tagKeys = foundKeys.toList(),
            content = contentParts.distinct().joinToString("\n---\n")
        )
    }

    /**
     * Reads [audioFile] and returns [EmbeddedSyncedLyricsInfo] if embedded timestamped lyrics exist.
     */
    fun inspectEmbeddedSyncedLyrics(audioFile: File): EmbeddedSyncedLyricsInfo? {
        if (!audioFile.exists() || !audioFile.isFile || !isTagEditingSupported(audioFile)) return null
        return try {
            val audioFileObj = AudioFileIO.read(audioFile)
            inspectEmbeddedSyncedLyrics(audioFileObj.tag)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Removes embedded timestamped lyric fields from [audioFile] (preserving all other tags and artwork),
     * returning a structured [EmbeddedCleanupOutcome].
     * Unsupported extensions (such as `.opus`) are reported cleanly as [EmbeddedCleanupOutcome.SkippedUnsupported],
     * while malformed/corrupt audio headers throw an exception clearly attributed to [audioFile].
     */
    fun removeEmbeddedSyncedLyricsWithOutcome(audioFile: File): EmbeddedCleanupOutcome {
        if (!audioFile.exists() || !audioFile.isFile) return EmbeddedCleanupOutcome.Clean
        val ext = audioFile.extension.lowercase()
        if (!isTagEditingSupported(audioFile)) {
            val tag = if ((ext == "ogg" || ext == "oga") && isOggOpusContainer(audioFile)) "ogg-opus" else ext
            return EmbeddedCleanupOutcome.SkippedUnsupported(tag)
        }

        return try {
            val audioFileObj = AudioFileIO.read(audioFile)
            val tag = audioFileObj.tag ?: return EmbeddedCleanupOutcome.Clean
            val info = inspectEmbeddedSyncedLyrics(tag) ?: return EmbeddedCleanupOutcome.Clean

            var modified = false

            val primaryValues = try {
                tag.getAll(FieldKey.LYRICS)
            } catch (_: Exception) {
                emptyList()
            }
            if (primaryValues.any { hasTimestampTags(it) }) {
                tag.deleteField(FieldKey.LYRICS)
                modified = true
            }

            if (tag is VorbisCommentTag) {
                for (key in vorbisExtraLyricKeys) {
                    val values = try {
                        tag.getAll(key)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (values.any { hasTimestampTags(it) }) {
                        tag.deleteField(key)
                        modified = true
                    }
                }
            }

            if (tag is AbstractID3v2Tag && tag.getFields("SYLT").isNotEmpty()) {
                tag.removeFrame("SYLT")
                modified = true
            }

            if (!modified) return EmbeddedCleanupOutcome.Clean
            audioFileObj.commit()
            EmbeddedCleanupOutcome.Removed(info)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (isOggOpusContainer(audioFile)) {
                EmbeddedCleanupOutcome.SkippedUnsupported("ogg-opus")
            } else if (msg.contains("No Reader associated with this extension", ignoreCase = true)) {
                EmbeddedCleanupOutcome.SkippedUnsupported(ext.ifBlank { "unknown" })
            } else {
                throw RuntimeException(
                    "${audioFile.name}: ${msg.ifBlank { e.javaClass.simpleName }} [${audioFile.absolutePath}]",
                    e
                )
            }
        }
    }

    /**
     * Removes embedded timestamped lyric fields from [audioFile] (preserving all other tags and artwork).
     * Returns the removed [EmbeddedSyncedLyricsInfo] if the file was modified, or null otherwise.
     */
    fun removeEmbeddedSyncedLyrics(audioFile: File): EmbeddedSyncedLyricsInfo? {
        return (removeEmbeddedSyncedLyricsWithOutcome(audioFile) as? EmbeddedCleanupOutcome.Removed)?.info
    }
}
