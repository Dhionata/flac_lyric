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

    @Test
    fun `test normalizes timestamps without centiseconds to mm ss 00`() {
        val raw = """
            [ar: Babado Novo]
            [ti: Bola De Sabão]
            [length: 03:59]
            [00:13] Baby baby
            [00:18][01:45] Chorus line
            [01:55]NoSpace
            [02:10.5] Already decimal
        """.trimIndent()

        val dirty = cleaner.inspectDirtyPatterns(raw)
        assertTrue(dirty.contains("[mm:ss] sem .xx"))

        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertTrue(result.appliedFixes.contains("[mm:ss] -> [mm:ss.00]"))
        assertEquals(
            """
            [ar: Babado Novo]
            [ti: Bola De Sabão]
            [length: 03:59]
            [00:13.00] Baby baby
            [00:18.00][01:45.00] Chorus line
            [01:55.00]NoSpace
            [02:10.5] Already decimal
            """.trimIndent(),
            result.cleanedContent
        )

        val disabledResult = cleaner.clean(
            "[00:13] Baby baby",
            LyricCleanOptions(normalizeTimestampsToCentiseconds = false)
        )
        assertFalse(disabledResult.modified)
    }

    @Test
    fun `test removes contextual author signatures without false positives on real verses`() {
        val raw = """
            [00:01.00]by tOnHaUm ® 05/02/2010
            [00:05.00]SINCRONIZADO POR MARCELO JR.
            [00:10.00]By: Walter
            [00:15.00]By the time you get home
            [00:20.00]By my side forever
            [00:25.00]By the rivers of Babylon
            [00:30.00]By the way, I miss you
            [00:35.00]By now I should know
            [03:46.00]By RarF.
        """.trimIndent()

        val dirty = cleaner.inspectDirtyPatterns(raw)
        assertTrue(dirty.contains("créditos"))

        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertTrue(result.appliedFixes.contains("créditos"))
        assertEquals(
            """
            [00:15.00]By the time you get home
            [00:20.00]By my side forever
            [00:25.00]By the rivers of Babylon
            [00:30.00]By the way, I miss you
            [00:35.00]By now I should know
            """.trimIndent(),
            result.cleanedContent
        )
    }

    @Test
    fun `test removes only trailing empty timestamp lines while preserving middle instrumental breaks`() {
        val raw = """
            [00:12.72] Não adianta nem tentar
            [00:17.61] 
            [00:17.61] Me esquecer
            [03:46] By RarF.
            [03:48]
            [03:50.00]   
        """.trimIndent()

        val dirty = cleaner.inspectDirtyPatterns(raw)
        assertTrue(dirty.contains("timestamp final vazio"))

        val result = cleaner.clean(raw)
        assertTrue(result.modified)
        assertTrue(result.appliedFixes.contains("timestamp final vazio"))
        assertEquals(
            """
            [00:12.72] Não adianta nem tentar
            [00:17.61]
            [00:17.61] Me esquecer
            """.trimIndent(),
            result.cleanedContent
        )

        val keepTrailing = cleaner.clean(
            "[00:12.72]Verse\n[03:48.00]",
            LyricCleanOptions(removeTrailingEmptyTimestampLines = false)
        )
        assertFalse(keepTrailing.modified)
    }
}
