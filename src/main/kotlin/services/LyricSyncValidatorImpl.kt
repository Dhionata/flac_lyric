package services

import interfaces.LyricSyncValidator
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import models.LyricValidationIssue
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * Implementation of [LyricSyncValidator] that verifies whether a lyric file (.lrc)
 * is properly synchronized and corresponds to the matching audio file.
 * All issue descriptions are localized using [Messages].
 */
class LyricSyncValidatorImpl : LyricSyncValidator {

    private val timeRegex = Regex("""\[(\d{1,2}):(\d{2}(?:\.\d{1,3})?)\]""")
    private val arRegex = Regex("""^\[ar:(.*?)\]""", RegexOption.IGNORE_CASE)
    private val tiRegex = Regex("""^\[ti:(.*?)\]""", RegexOption.IGNORE_CASE)
    private val lenRegex = Regex("""^\[length:\s*(\d{1,2}):(\d{2})\]""", RegexOption.IGNORE_CASE)

    override fun validate(lyricFile: File, audioFile: File?): List<LyricValidationIssue> {
        val issues = mutableListOf<LyricValidationIssue>()

        if (!lyricFile.exists() || !lyricFile.isFile) {
            return issues
        }

        val lines = try {
            lyricFile.readLines()
        } catch (_: Exception) {
            try {
                lyricFile.readLines(Charsets.ISO_8859_1)
            } catch (_: Exception) {
                return listOf(
                    LyricValidationIssue(
                        lyricFile,
                        audioFile,
                        "ERRO_LEITURA",
                        Messages.get("lyric.issue.read_error")
                    )
                )
            }
        }

        var lrcArtist: String? = null
        var lrcTitle: String? = null
        var lrcLength: Double? = null
        val timestamps = mutableListOf<Double>()

        for (line in lines) {
            val trimmed = line.trim()
            val arMatch = arRegex.find(trimmed)
            if (arMatch != null && lrcArtist == null) {
                lrcArtist = arMatch.groupValues[1].trim()
            }

            val tiMatch = tiRegex.find(trimmed)
            if (tiMatch != null && lrcTitle == null) {
                lrcTitle = tiMatch.groupValues[1].trim()
            }

            val lenMatch = lenRegex.find(trimmed)
            if (lenMatch != null && lrcLength == null) {
                val min = lenMatch.groupValues[1].toIntOrNull() ?: 0
                val sec = lenMatch.groupValues[2].toIntOrNull() ?: 0
                lrcLength = (min * 60 + sec).toDouble()
            }

            timeRegex.findAll(trimmed).forEach { match ->
                val min = match.groupValues[1].toDoubleOrNull() ?: 0.0
                val sec = match.groupValues[2].toDoubleOrNull() ?: 0.0
                timestamps.add(min * 60.0 + sec)
            }
        }

        // 1. Basic timestamp checks
        if (timestamps.isEmpty()) {
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    "SEM_TIMESTAMPS",
                    Messages.get("lyric.issue.no_timestamps")
                )
            )
            return issues
        }

        timestamps.sort()
        val firstTs = timestamps.first()
        val lastTs = timestamps.last()

        if (timestamps.size > 1 && firstTs == lastTs) {
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    "TIMESTAMPS_ESTATICOS",
                    Messages.get("lyric.issue.static_timestamps", formatTime(firstTs))
                )
            )
        }

        // 2. Audio cross-checks
        if (audioFile != null) {
            val audioMeta = if (audioFile.exists()) extractAudioMetadata(audioFile) else AudioMeta(null, null, null)
            val audioDuration = audioMeta.duration
            val audioArtist = audioMeta.artist
            val audioTitle = audioMeta.title

            // A. Check metadata belonging (wrong song detection)
            if (isSongMismatch(lrcArtist, lrcTitle, audioArtist, audioTitle, audioFile.nameWithoutExtension)) {
                val lrcInfo = listOfNotNull(
                    lrcArtist?.let { Messages.get("lyric.issue.meta.artist", it) },
                    lrcTitle?.let { Messages.get("lyric.issue.meta.title", it) }
                ).joinToString(", ")
                issues.add(
                    LyricValidationIssue(
                        lyricFile,
                        audioFile,
                        "MUSICA_DIFERENTE",
                        Messages.get("lyric.issue.wrong_song", lrcInfo)
                    )
                )
            }

            // B. Check duration anomalies if audio duration was obtained
            if (audioDuration != null && audioDuration > 0) {
                // Check if timestamps exceed audio length
                if (lastTs > audioDuration + 3.0) {
                    issues.add(
                        LyricValidationIssue(
                            lyricFile,
                            audioFile,
                            "ULTRAPASSOU_DURACAO",
                            Messages.get("lyric.issue.exceeds_duration", formatTime(lastTs), formatTime(audioDuration))
                        )
                    )
                }

                // Check [length] tag mismatch
                if (lrcLength != null) {
                    val diff = abs(lrcLength - audioDuration)
                    if (diff > 6.0) {
                        issues.add(
                            LyricValidationIssue(
                                lyricFile,
                                audioFile,
                                "TAG_LENGTH_DIVERGENTE",
                                Messages.get("lyric.issue.length_mismatch", formatTime(lrcLength), diff.toInt(), formatTime(audioDuration))
                            )
                        )
                    }
                }

                // Check premature ending (unfinished lyrics)
                if (audioDuration > 100.0 && lastTs < audioDuration * 0.45 && (audioDuration - lastTs) > 75.0) {
                    issues.add(
                        LyricValidationIssue(
                            lyricFile,
                            audioFile,
                            "TERMINO_PREMATURO",
                            Messages.get("lyric.issue.premature_end", formatTime(lastTs), formatTime(audioDuration))
                        )
                    )
                }
            }
        }

        return issues
    }

    private data class AudioMeta(
        val duration: Double?,
        val artist: String?,
        val title: String?
    )

    private fun extractAudioMetadata(audioFile: File): AudioMeta {
        var duration: Double? = null
        var artist: String? = null
        var title: String? = null

        try {
            val audioFileObj = AudioFileIO.read(audioFile)
            val header = audioFileObj.audioHeader
            if (header != null) {
                val precise = header.preciseTrackLength
                if (precise > 0.0) {
                    duration = precise
                } else if (header.trackLength > 0) {
                    duration = header.trackLength.toDouble()
                }
            }

            val tag = audioFileObj.tag
            if (tag != null) {
                artist = tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() }
                title = tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            // AudioFileIO may fail on certain codecs like opus; fallback to ffprobe
        }

        if (duration == null) {
            duration = extractDurationWithFfprobe(audioFile)
        }

        return AudioMeta(duration, artist, title)
    }

    private fun extractDurationWithFfprobe(file: File): Double? {
        return try {
            val process = ProcessBuilder(
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (finished && output.isNotBlank()) {
                output.toDoubleOrNull()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSongMismatch(
        lrcArtist: String?,
        lrcTitle: String?,
        audioArtist: String?,
        audioTitle: String?,
        fallbackFileName: String
    ): Boolean {
        if (lrcArtist.isNullOrBlank() && lrcTitle.isNullOrBlank()) {
            return false
        }

        val normLrcArtist = normalize(lrcArtist ?: "")
        val normLrcTitle = normalize(lrcTitle ?: "")

        val targetArtist = normalize(audioArtist ?: fallbackFileName.substringBefore(" - ", ""))
        val targetTitle = normalize(audioTitle ?: fallbackFileName.substringAfter(" - ", fallbackFileName))

        // Check artist mismatch
        if (normLrcArtist.isNotBlank() && targetArtist.isNotBlank()) {
            val artistWordsLrc = normLrcArtist.split(" ").filter { it.length > 2 }
            val artistWordsTarget = targetArtist.split(" ").filter { it.length > 2 }

            val sharesArtistWord = artistWordsLrc.any { lrcWord -> artistWordsTarget.contains(lrcWord) }
            val artistContains = targetArtist.contains(normLrcArtist) || normLrcArtist.contains(targetArtist)

            if (!sharesArtistWord && !artistContains) {
                return true
            }
        }

        // Check title mismatch if title is present in LRC
        if (normLrcTitle.isNotBlank() && targetTitle.isNotBlank()) {
            val titleWordsLrc = normLrcTitle.split(" ").filter { it.length > 2 }
            val titleWordsTarget = targetTitle.split(" ").filter { it.length > 2 }

            if (titleWordsLrc.isNotEmpty() && titleWordsTarget.isNotEmpty()) {
                val commonWords = titleWordsLrc.intersect(titleWordsTarget.toSet())
                if (commonWords.isEmpty() && !targetTitle.contains(normLrcTitle) && !normLrcTitle.contains(targetTitle)) {
                    return true
                }
            }
        }

        return false
    }

    private fun normalize(str: String): String {
        val nfd = Normalizer.normalize(str.lowercase(), Normalizer.Form.NFD)
        return nfd.replace(Regex("""\p{M}"""), "")
            .replace(Regex("""\(feat.*?\)|\[feat.*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun formatTime(seconds: Double): String {
        val totalSec = seconds.toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }
}
