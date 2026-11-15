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

    private fun isEmptyTimestampOnlyLine(trimmedLine: String): Boolean {
        if (trimmedLine.isEmpty()) return false
        if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmedLine)) return false
        val matches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmedLine).toList()
        if (matches.isEmpty()) return false
        return trimmedLine.replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "").trim().isEmpty()
    }

    private fun findVerseBounds(lines: List<String>): Pair<Int, Int> {
        var first = -1
        var last = -1
        lines.forEachIndexed { idx, rawLine ->
            val trimmed = rawLine.trimAndStripBom()
            if (trimmed.isEmpty() || LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) return@forEachIndexed
            val matches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEachIndexed
            val verseText = trimmed
                .replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "")
                .replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "")
                .trim()
            if (verseText.isNotEmpty()) {
                if (first == -1) first = idx
                last = idx
            }
        }
        return first to last
    }

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

        val lines = content.split(Regex("\r?\n"))
        val (firstNonEmptyIdx, lastNonEmptyIdx) = findVerseBounds(lines)

        lines.forEachIndexed { idx, rawLine ->
            val trimmed = rawLine.trimAndStripBom()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (LyricSyntaxPatterns.WATERMARK_HEADER_REGEX.matches(trimmed)) {
                found.add("URL/watermark")
                return@forEachIndexed
            }
            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) return@forEachIndexed

            val matches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEachIndexed

            if (matches.any { it.groupValues.getOrNull(3).orEmpty().isEmpty() }) {
                found.add("[mm:ss] sem .xx")
            }

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
            } else if (
                LyricSyntaxPatterns.isContextualAuthorSignature(
                    verseText = verseText,
                    lineIndex = idx,
                    firstNonEmptyIndex = firstNonEmptyIdx,
                    lastNonEmptyIndex = lastNonEmptyIdx,
                    timestampMs = matches.firstOrNull()?.let { LyricSyntaxPatterns.parseMatchToMs(it) }
                )
            ) {
                found.add("créditos")
            } else if (LyricSyntaxPatterns.WATERMARK_VERSE_REGEX.matches(verseText)) {
                found.add("URL/watermark")
            }
        }

        val lastNonBlankIdx = lines.indexOfLast { it.trimAndStripBom().isNotEmpty() }
        if (lastNonBlankIdx > 0 && isEmptyTimestampOnlyLine(lines[lastNonBlankIdx].trimAndStripBom())) {
            val hasVerseBefore = lines.take(lastNonBlankIdx).any { line ->
                val t = line.trimAndStripBom()
                t.isNotEmpty() && !LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(t) &&
                    t.replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "").trim().isNotEmpty()
            }
            if (hasVerseBefore) {
                found.add("timestamp final vazio")
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
        val (firstNonEmptyIdx, lastNonEmptyIdx) = findVerseBounds(rawLines)

        val hasAnySyncedLine = rawLines.any { line ->
            val trimmed = line.trimAndStripBom()
            !LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed) &&
                LyricSyntaxPatterns.TIMESTAMP_REGEX.containsMatchIn(trimmed)
        }

        val outputLines = mutableListOf<String>()

        rawLines.forEachIndexed { lineIdx, rawLine ->
            val line = if (options.stripBom && rawLine.startsWith(LyricSyntaxPatterns.UTF8_BOM)) {
                fixes.add("UTF-8 BOM")
                rawLine.trimStart(LyricSyntaxPatterns.UTF8_BOM)
            } else {
                rawLine
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                outputLines.add("")
                return@forEachIndexed
            }

            // 1. Check watermark header [re:www.megalobiz.com...]
            if (options.removeCreditsAndWatermarks && LyricSyntaxPatterns.WATERMARK_HEADER_REGEX.matches(trimmed)) {
                fixes.add("URL/watermark")
                return@forEachIndexed
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
                return@forEachIndexed
            }

            // Preserve standard metadata headers like [ar:...], [ti:...], [length:...]
            if (LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(trimmed)) {
                outputLines.add(trimmed)
                return@forEachIndexed
            }

            val tsMatches = LyricSyntaxPatterns.TIMESTAMP_REGEX.findAll(trimmed).toList()

            // Unsynced line inside file
            if (tsMatches.isEmpty()) {
                if (options.removeUnsyncedLinesInSyncedFile && hasAnySyncedLine) {
                    fixes.add("unsynced-lines")
                    return@forEachIndexed
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
                return@forEachIndexed
            }

            // Extract verse text after removing line timestamps
            var verseText = trimmed.replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "").trim()

            // Check credits / watermarks on synced lines
            val plainForCheck = verseText.replace(LyricSyntaxPatterns.INLINE_WORD_TAG_REGEX, "").trim()
            if (options.removeCreditsAndWatermarks) {
                if (LyricSyntaxPatterns.CREDIT_ONLY_TEXT_REGEX.matches(plainForCheck)) {
                    fixes.add("créditos")
                    return@forEachIndexed
                }
                if (
                    tsMatches.size == 1 &&
                    LyricSyntaxPatterns.parseMatchToMs(tsMatches[0]) == 0L &&
                    LyricSyntaxPatterns.ZERO_TIME_META_STUB_REGEX.matches(plainForCheck)
                ) {
                    fixes.add("créditos")
                    return@forEachIndexed
                }
                if (
                    LyricSyntaxPatterns.isContextualAuthorSignature(
                        verseText = plainForCheck,
                        lineIndex = lineIdx,
                        firstNonEmptyIndex = firstNonEmptyIdx,
                        lastNonEmptyIndex = lastNonEmptyIdx,
                        timestampMs = tsMatches.firstOrNull()?.let { LyricSyntaxPatterns.parseMatchToMs(it) }
                    )
                ) {
                    fixes.add("créditos")
                    return@forEachIndexed
                }
                if (LyricSyntaxPatterns.WATERMARK_VERSE_REGEX.matches(plainForCheck)) {
                    fixes.add("URL/watermark")
                    return@forEachIndexed
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

            val formatTag: (MatchResult) -> String = { m ->
                if (options.normalizeTimestampsToCentiseconds) {
                    val normalized = LyricSyntaxPatterns.normalizeTimestampToCentiseconds(m)
                    if (normalized != m.value) {
                        fixes.add("[mm:ss] -> [mm:ss.00]")
                    }
                    normalized
                } else {
                    m.value
                }
            }

            // Sort and deduplicate multi-timestamps on the same line if requested
            val finalTimestampPrefix = if (options.sortAndDeduplicateMultiTimestamps && tsMatches.size > 1) {
                val originalTags = tsMatches.map { it.value }
                val sortedDedupedMatches = tsMatches
                    .distinctBy { LyricSyntaxPatterns.parseMatchToMs(it) }
                    .sortedBy { LyricSyntaxPatterns.parseMatchToMs(it) }
                val sortedDedupedTags = sortedDedupedMatches.map { formatTag(it) }
                if (sortedDedupedMatches.map { it.value } != originalTags) {
                    fixes.add("multi-timestamps")
                }
                sortedDedupedTags.joinToString("")
            } else {
                tsMatches.joinToString("") { formatTag(it) }
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

        if (options.removeTrailingEmptyTimestampLines) {
            while (true) {
                val lastNonBlankIdx = outputLines.indexOfLast { it.trim().isNotEmpty() }
                if (lastNonBlankIdx <= 0) break
                val candidate = outputLines[lastNonBlankIdx].trim()
                if (!isEmptyTimestampOnlyLine(candidate)) break
                val hasVerseBefore = outputLines.take(lastNonBlankIdx).any { prev ->
                    val t = prev.trim()
                    t.isNotEmpty() && !LyricSyntaxPatterns.GENERIC_HEADER_TAG_REGEX.matches(t) &&
                        t.replace(LyricSyntaxPatterns.TIMESTAMP_REGEX, "").trim().isNotEmpty()
                }
                if (!hasVerseBefore) break
                outputLines.removeAt(lastNonBlankIdx)
                fixes.add("timestamp final vazio")
            }
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
