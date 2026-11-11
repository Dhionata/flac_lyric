package services

import models.LyricCleanOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LyricContentCleanerImplTest {

    private val cleaner = LyricContentCleanerImpl()

    @Test
    fun `test cleans speaker markers and background vocal tags`() {
        val raw = """
            [00:10.00]v1: First verse
            [00:15.00]v2: Second verse [bg: background]
            [00:20.00][bg: v1: Solo backing]
        """.trimIndent()

        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertTrue(result.appliedFixes.contains("v1:/v2:"))
        assertTrue(result.appliedFixes.contains("[bg:]"))

        val lines = result.cleanedContent.lines()
        assertEquals("[00:10.00]First verse", lines[0])
        assertEquals("[00:15.00]Second verse (background)", lines[1])
        assertEquals("[00:20.00](Solo backing)", lines[2])
    }

    @Test
    fun `test removes inline word timestamps`() {
        val raw = "[00:12.34]Hello <00:12.80>world <00:13.20>today"
        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertTrue(result.appliedFixes.contains("<mm:ss>"))
        assertEquals("[00:12.34]Hello world today", result.cleanedContent)
    }

    @Test
    fun `test removes composition credits and watermarks`() {
        val raw = """
            [00:00.00]作词 : 张三
            [00:01.00]作曲 : 李四
            [00:02.00]Lyrics by: Someone
            [00:03.00]www.lrcgenerator.com
            [00:10.00]Actual song verse
        """.trimIndent()

        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertEquals("[00:10.00]Actual song verse", result.cleanedContent)
    }

    @Test
    fun `test sorts and deduplicates multi timestamps on the same line`() {
        val raw = "[00:38.49][00:15.20][00:38.49]Chorus line"
        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertEquals("[00:15.20][00:38.49]Chorus line", result.cleanedContent)
    }

    @Test
    fun `test removes unsynced lines in synced file when option is enabled`() {
        val raw = """
            [ar: Artist]
            [00:10.00]Synced line 1
            Loose unsynced comment line
            [00:20.00]Synced line 2
        """.trimIndent()

        val keepResult = cleaner.clean(raw, LyricCleanOptions(removeUnsyncedLinesInSyncedFile = false))
        assertFalse(keepResult.modified)

        val removeResult = cleaner.clean(raw, LyricCleanOptions(removeUnsyncedLinesInSyncedFile = true))
        assertTrue(removeResult.modified)
        assertFalse(removeResult.cleanedContent.contains("Loose unsynced"))
    }

    @Test
    fun `test inspectDirtyPatterns detects dirty tags`() {
        val raw = "\uFEFF[00:01.00]作词: A\n[00:05.00]v1: <00:05.50>Hi [bg: oh]"
        val detected = cleaner.inspectDirtyPatterns(raw)
        assertTrue(detected.contains("UTF-8 BOM"))
        assertTrue(detected.contains("v1:/v2:"))
        assertTrue(detected.contains("[bg:]"))
        assertTrue(detected.contains("<mm:ss>"))
        assertTrue(detected.contains("créditos"))
    }
}
