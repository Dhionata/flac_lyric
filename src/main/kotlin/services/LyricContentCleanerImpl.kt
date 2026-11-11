package services

import interfaces.LyricContentCleaner
import models.LyricCleanOptions
import models.LyricCleanResult
import services.LyricSyntaxPatterns.trimAndStripBom

/**
 * Default implementation of [LyricContentCleaner] that detects and cleans:
 * - Speaker prefixes (`v1:`, `v2:`, `v3:`) and background vocal tags (`[bg:...]`)
 * - Inline word-by-word karaoke timestamps (`<mm:ss.xx>`)
 * - Embedded composition/production credits (`作词 :`, `作曲 :`, `Lyrics by:`, `Artist:` at `00:00`) and URL/watermark lines
 * - Out-of-order or duplicated multiple timestamps on the same line (e.g. `[00:38.28][00:15.05]` -> `[00:15.05][00:38.28]`)
 * - Optional removal of unsynchronized lines mixed inside an already synchronized `.lrc` file
 * - Leading UTF-8 BOM (`\uFEFF`) characters
 */
class LyricContentCleanerImpl : LyricContentCleaner {

    override fun inspectDirtyPatterns(content: String): List<String> {
        if (content.isBlank()) return emptyList()

        val found = linkedSetOf<String>()

        if (content.startsWith(LyricSyntaxPatterns.UTF8_BOM)) {
            found.add("UTF-8 BOM")
        }
        if (LyricSyntaxPatterns.SPEAKER_PREFIX_REGEX.containsMatchIn(content)) {
            found.add("v1:/v2:")
        }
        if (
            LyricSyntaxPatterns.BG_BLOCK_CLOSED_REGEX.containsMatchIn(content) ||
            content.lineSequence().any { LyricSyntaxPatterns.BG_BLOCK_UNCLOSED_REGEX.matches(it.trimAndStripBom()) }
        ) {
            found.add("[bg:]")
        }
        if (LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX.containsMatchIn(content)) {
            found.add("<mm:ss>")
        }

        for (rawLine in content.lineSequence()) {
            val trimmed = rawLine.trimAndStripBom()
            if (trimmed.isEmpty()) continue
            if (LyricSyntaxPatterns.WATERMARK_HEADER_REGEX.matches(trimmed)) {
                found.add("URL/watermark")
                continue
            }
            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) continue

            val matches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val verseText = trimmed
                .replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "")
                .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                .trim()

            if (LyricSyntaxPatterns.CREDIT_ONLY_TEXT_REGEX.matches(verseText)) {
                found.add("créditos")
            } else if (
                matches.size == 1 &&
                LyricSyntaxPatterns.parseMatchToMs(matches[0]) == 0L &&
                LyricSyntaxPatterns.ZERO_TIME_META_STUB_REGEX.matches(verseText)
            ) {
                found.add("créditos")
            } else if (LyricSyntaxPatterns.WATERMARK_VERSE_REGEX.matches(verseText)) {
                found.add("URL/watermark")
            }
        }

        return found.toList()
    }

    override fun clean(content: String, options: LyricCleanOptions): LyricCleanResult {
        if (content.isEmpty()) {
            return LyricCleanResult(content, content, modified = false, appliedFixes = emptyList())
        }

        val fixes = linkedSetOf<String>()
        var workingContent = content

        if (options.stripBom && workingContent.startsWith(LyricSyntaxPatterns.UTF8_BOM)) {
            workingContent = workingContent.trimStart(LyricSyntaxPatterns.UTF8_BOM)
            fixes.add("UTF-8 BOM")
        }

        val lineBreak = if (workingContent.contains("\r\n")) "\r\n" else "\n"
        val rawLines = workingContent.split(Regex("\r?\n"))

        val hasAnySyncedLine = rawLines.any { line ->
            val trimmed = line.trimAndStripBom()
            !LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed) &&
                    LyricSyntaxPatterns.TIMESTAMP_REGEX.containsMatchIn(trimmed)
        }

        val outputLines = mutableListOf<String>()

        for (rawLine in rawLines) {
            val line = if (options.stripBom && rawLine.startsWith(LyricSyntaxPatterns.UTF8_BOM)) {
                fixes.add("UTF-8 BOM")
                rawLine.trimStart(LyricSyntaxPatterns.UTF8_BOM)
            } else {
                rawLine
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                outputLines.add("")
                continue
            }

            // 1. Check watermark header [re:www.megalobiz.com...]
            if (options.removeCreditsAndWatermarks && LyricSyntaxPatterns.WATERMARK_HEADER_REGEX.matches(trimmed)) {
                fixes.add("URL/watermark")
                continue
            }

            // 2. Check standalone unclosed [bg:<02:49.166>Wanna ...] line
            if (options.removeSpeakerAndBgTags && LyricSyntaxPatterns.BG_BLOCK_UNCLOSED_REGEX.matches(trimmed) && !trimmed.endsWith("]")) {
                val bgBody = LyricSyntaxPatterns.BG_BLOCK_UNCLOSED_REGEX.find(trimmed)?.groupValues?.get(1).orEmpty()
                val firstInlineTime = Regex("""<(\d{1,2}:\d{2}(?:[.,:]\d{1,3})?)>""").find(bgBody)
                val cleanBgText = bgBody
                    .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                    .replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .removeSuffix("]")
                    .trim()
                fixes.add("[bg:]")
                if (firstInlineTime != null && cleanBgText.isNotEmpty()) {
                    outputLines.add("[${firstInlineTime.groupValues[1]}] ($cleanBgText)")
                }
                continue
            }

            // Preserve standard metadata headers like [ar:...], [ti:...], [length:...]
            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) {
                outputLines.add(trimmed)
                continue
            }

            val tsMatches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()

            // Unsynced line inside file
            if (tsMatches.isEmpty()) {
                if (options.removeUnsyncedLinesInSyncedFile && hasAnySyncedLine) {
                    fixes.add("unsynced-lines")
                    continue
                }
                var cleanedUnsynced = trimmed
                if (options.removeSpeakerAndBgTags && LyricSyntaxPatterns.SPEAKER_PREFIX_REGEX.containsMatchIn(cleanedUnsynced)) {
                    cleanedUnsynced = cleanedUnsynced.replace(LyricSyntaxPatterns.SPEAKER_PREFIX_REGEX, "").trim()
                    fixes.add("v1:/v2:")
                }
                if (options.removeInlineWordTimestamps && LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX.containsMatchIn(cleanedUnsynced)) {
                    cleanedUnsynced = cleanedUnsynced.replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "").replace(Regex("""\s{2,}"""), " ").trim()
                    fixes.add("<mm:ss>")
                }
                outputLines.add(cleanedUnsynced)
                continue
            }

            // Extract verse text after removing line timestamps
            var verseText = trimmed.replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "").trim()

            // Check credits / watermarks on synced lines
            val plainForCheck = verseText.replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "").trim()
            if (options.removeCreditsAndWatermarks) {
                if (LyricSyntaxPatterns.CREDIT_ONLY_TEXT_REGEX.matches(plainForCheck)) {
                    fixes.add("créditos")
                    continue
                }
                if (
                    tsMatches.size == 1 &&
                    LyricSyntaxPatterns.parseMatchToMs(tsMatches[0]) == 0L &&
                    LyricSyntaxPatterns.ZERO_TIME_META_STUB_REGEX.matches(plainForCheck)
                ) {
                    fixes.add("créditos")
                    continue
                }
                if (LyricSyntaxPatterns.WATERMARK_VERSE_REGEX.matches(plainForCheck)) {
                    fixes.add("URL/watermark")
                    continue
                }
            }

            // Clean [bg: ...] blocks and v1:/v2: speaker prefixes
            if (options.removeSpeakerAndBgTags) {
                if (LyricSyntaxPatterns.BG_BLOCK_CLOSED_REGEX.containsMatchIn(verseText)) {
                    verseText = verseText.replace(LyricSyntaxPatterns.BG_BLOCK_CLOSED_REGEX) { m ->
                        val inner = m.groupValues[1]
                            .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                            .replace(LyricSyntaxPatterns.SPEAKER_PREFIX_REGEX, "")
                            .replace(Regex("""\s{2,}"""), " ")
                            .trim()
                        if (inner.isNotEmpty()) "($inner)" else ""
                    }.trim()
                    fixes.add("[bg:]")
                }
                if (LyricSyntaxPatterns.SPEAKER_PREFIX_REGEX.containsMatchIn(verseText)) {
                    verseText = verseText.replace(LyricSyntaxPatterns.SPEAKER_PREFIX_REGEX, "").trim()
                    fixes.add("v1:/v2:")
                }
            }

            // Clean inline <mm:ss.xx> word timestamps
            if (options.removeInlineWordTimestamps && LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX.containsMatchIn(verseText)) {
                verseText = verseText
                    .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim()
                fixes.add("<mm:ss>")
            }

            // Sort and deduplicate multi-timestamps on the same line if requested
            val finalTimestampPrefix = if (options.sortAndDeduplicateMultiTimestamps && tsMatches.size > 1) {
                val originalTags = tsMatches.map { it.value }
                val sortedDedupedTags = tsMatches
                    .distinctBy { LyricSyntaxPatterns.parseMatchToMs(it) }
                    .sortedBy { LyricSyntaxPatterns.parseMatchToMs(it) }
                    .map { it.value }
                if (sortedDedupedTags != originalTags) {
                    fixes.add("multi-timestamps")
                }
                sortedDedupedTags.joinToString("")
            } else {
                tsMatches.joinToString("") { it.value }
            }

            val reconstructed = if (verseText.isNotEmpty()) {
                val hadLeadingSpace = trimmed.substringAfterLast("]").startsWith(" ")
                val separator = if (hadLeadingSpace) " " else ""
                "$finalTimestampPrefix$separator$verseText"
            } else {
                finalTimestampPrefix
            }

            if (reconstructed != line && fixes.isEmpty()) {
                fixes.add("espaços/formatação")
            }

            outputLines.add(reconstructed)
        }

        val cleanedContent = outputLines.joinToString(lineBreak)
        val isModified = cleanedContent != content
        if (isModified && fixes.isEmpty()) {
            fixes.add("espaços/formatação")
        }

        return LyricCleanResult(
            originalContent = content,
            cleanedContent = cleanedContent,
            modified = isModified,
            appliedFixes = fixes.toList()
        )
    }
}
