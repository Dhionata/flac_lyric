package services

import interfaces.LyricContentCleaner
import interfaces.LyricSyncValidator
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import models.LyricValidationIssue
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import services.LyricSyntaxPatterns.trimAndStripBom

/**
 * Implementation of [LyricSyncValidator] that verifies whether a lyric file (.lrc)
 * is properly synchronized, encoded, free of extraneous clutter tags, and corresponds
 * to the matching audio file.
 * All issue descriptions are localized using [Messages].
 */
class LyricSyncValidatorImpl(
    private val lyricContentCleaner: LyricContentCleaner = LyricContentCleanerImpl(),
) : LyricSyncValidator {

    override fun validate(lyricFile: File, audioFile: File?): List<LyricValidationIssue> {
        val issues = mutableListOf<LyricValidationIssue>()

        if (!lyricFile.exists() || !lyricFile.isFile) {
            return issues
        }

        val lines = try {
            lyricFile.readLines(Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                lyricFile.readLines(Charsets.ISO_8859_1)
            } catch (_: Exception) {
                return listOf(
                    LyricValidationIssue(
                        lyricFile,
                        audioFile,
                        Messages.get("lyric.issue.type.read_error"),
                        Messages.get("lyric.issue.read_error")
                    )
                )
            }
        }

        var lrcArtist: String? = null
        var lrcTitle: String? = null
        var lrcLength: Double? = null
        val timestamps = mutableListOf<Double>()
        val singleLineTimestamps = mutableListOf<Pair<Int, Double>>()
        val nonCreditVerseTimestamps = mutableListOf<Long>()
        val unsyncedLines = mutableListOf<Pair<Int, String>>()
        var firstEncodingErrorLine: Int? = null
        var inlineOutOfOrderIssue: Triple<Int, Double, Double>? = null
        var inlineDuplicateTimestamp: Double? = null

        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            if (firstEncodingErrorLine == null && LyricSyntaxPatterns.ENCODING_CORRUPTION_REGEX.containsMatchIn(line)) {
                firstEncodingErrorLine = lineNum
            }

            val trimmed = line.trimAndStripBom()
            if (trimmed.isEmpty()) return@forEachIndexed

            val arMatch = LyricSyntaxPatterns.AR_TAG_REGEX.find(trimmed)
            if (arMatch != null && lrcArtist == null) {
                lrcArtist = arMatch.groupValues[1].trim()
            }

            val tiMatch = LyricSyntaxPatterns.TI_TAG_REGEX.find(trimmed)
            if (tiMatch != null && lrcTitle == null) {
                lrcTitle = tiMatch.groupValues[1].trim()
            }

            val lenMatch = LyricSyntaxPatterns.LENGTH_TAG_REGEX.find(trimmed)
            if (lenMatch != null && lrcLength == null) {
                val min = lenMatch.groupValues[1].toIntOrNull() ?: 0
                val sec = lenMatch.groupValues[2].toIntOrNull() ?: 0
                lrcLength = (min * 60 + sec).toDouble()
            }

            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) {
                return@forEachIndexed
            }

            val lineMatches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (lineMatches.isEmpty()) {
                unsyncedLines.add(lineNum to trimmed)
                return@forEachIndexed
            }

            val verseText = trimmed
                .replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "")
                .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                .trim()
            val isCreditStub = LyricSyntaxPatterns.CREDIT_ONLY_TEXT_REGEX.matches(verseText)

            val currentLineTsValues = lineMatches.map { LyricSyntaxPatterns.parseMatchToSeconds(it) }
            timestamps.addAll(currentLineTsValues)

            if (lineMatches.size == 1) {
                val ts = currentLineTsValues[0]
                singleLineTimestamps.add(lineNum to ts)
                if (verseText.isNotEmpty() && !isCreditStub) {
                    nonCreditVerseTimestamps.add((ts * 1000.0).roundToLong())
                }
            } else {
                // Check if multiple timestamps on the same line are inverted or duplicated
                for (i in 1 until currentLineTsValues.size) {
                    val prevTagTs = currentLineTsValues[i - 1]
                    val currTagTs = currentLineTsValues[i]
                    if (currTagTs < prevTagTs && inlineOutOfOrderIssue == null) {
                        inlineOutOfOrderIssue = Triple(lineNum, currTagTs, prevTagTs)
                    } else if (currTagTs == prevTagTs && inlineDuplicateTimestamp == null) {
                        inlineDuplicateTimestamp = currTagTs
                    }
                }
            }
        }

        // 0. Check encoding corruption (U+FFFD or mojibake)
        if (firstEncodingErrorLine != null) {
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    Messages.get("lyric.issue.type.encoding_error"),
                    Messages.get("lyric.issue.encoding_error", firstEncodingErrorLine)
                )
            )
        }

        // 1. Basic timestamp checks
        if (timestamps.isEmpty()) {
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    Messages.get("lyric.issue.type.no_timestamps"),
                    Messages.get("lyric.issue.no_timestamps")
                )
            )
            return issues
        }

        // 1b. Check partial synchronization (unsynced verse lines mixed inside a synced file)
        if (unsyncedLines.isNotEmpty()) {
            val firstUnsyncedLine = unsyncedLines.first().first
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    Messages.get("lyric.issue.type.partial_sync"),
                    Messages.get("lyric.issue.partial_sync", unsyncedLines.size, firstUnsyncedLine)
                )
            )
        }

        // 1c. Check out-of-order timestamps (across single-timestamp lines or within a multi-timestamp line)
        var outOfOrderReported = false
        if (singleLineTimestamps.size > 1) {
            for (i in 1 until singleLineTimestamps.size) {
                val (_, prevTs) = singleLineTimestamps[i - 1]
                val (currLineNum, currTs) = singleLineTimestamps[i]
                if (currTs < prevTs) {
                    issues.add(
                        LyricValidationIssue(
                            lyricFile,
                            audioFile,
                            Messages.get("lyric.issue.type.out_of_order"),
                            Messages.get("lyric.issue.out_of_order", currLineNum, formatTime(currTs), formatTime(prevTs))
                        )
                    )
                    outOfOrderReported = true
                    break
                }
            }
        }
        if (!outOfOrderReported && inlineOutOfOrderIssue != null) {
            val (lineNum, currTs, prevTs) = inlineOutOfOrderIssue
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    Messages.get("lyric.issue.type.out_of_order"),
                    Messages.get("lyric.issue.out_of_order", lineNum, formatTime(currTs), formatTime(prevTs))
                )
            )
        }

        timestamps.sort()
        val firstTs = timestamps.first()
        val lastTs = timestamps.last()

        if (timestamps.size > 1 && firstTs == lastTs) {
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    Messages.get("lyric.issue.type.static_timestamps"),
                    Messages.get("lyric.issue.static_timestamps", formatTime(firstTs))
                )
            )
        } else {
            // 1d. Check stacked/duplicate timestamps (3+ non-credit verses sharing the exact same timestamp, or repeated tag on same line)
            val groupedCounts = nonCreditVerseTimestamps.groupingBy { it }.eachCount()
            val maxDupEntry = groupedCounts.entries.maxByOrNull { it.value }
            if (maxDupEntry != null && maxDupEntry.value >= 3) {
                val dupSeconds = maxDupEntry.key / 1000.0
                issues.add(
                    LyricValidationIssue(
                        lyricFile,
                        audioFile,
                        Messages.get("lyric.issue.type.duplicate_timestamps"),
                        Messages.get("lyric.issue.duplicate_timestamps", maxDupEntry.value, formatTime(dupSeconds))
                    )
                )
            } else if (inlineDuplicateTimestamp != null) {
                issues.add(
                    LyricValidationIssue(
                        lyricFile,
                        audioFile,
                        Messages.get("lyric.issue.type.duplicate_timestamps"),
                        Messages.get("lyric.issue.duplicate_timestamps", 2, formatTime(inlineDuplicateTimestamp))
                    )
                )
            }
        }

        // 1e. Check cleanable dirty tags (v1:/v2:, [bg:], <mm:ss>, embedded credits, URLs/watermarks)
        val fullText = lines.joinToString("\n")
        val dirtyPatterns = lyricContentCleaner.inspectDirtyPatterns(fullText)
        if (dirtyPatterns.isNotEmpty()) {
            issues.add(
                LyricValidationIssue(
                    lyricFile,
                    audioFile,
                    Messages.get("lyric.issue.type.dirty_tags"),
                    Messages.get("lyric.issue.dirty_tags", dirtyPatterns.joinToString(", "))
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
                        Messages.get("lyric.issue.type.wrong_song"),
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
                            Messages.get("lyric.issue.type.exceeds_duration"),
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
                                Messages.get("lyric.issue.type.length_mismatch"),
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
                            Messages.get("lyric.issue.type.premature_end"),
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
        val title: String?,
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
        fallbackFileName: String,
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
