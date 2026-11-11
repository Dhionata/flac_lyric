package services

import java.io.File
import java.util.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LyricSyncValidatorImplTest {

    @TempDir
    lateinit var tempDir: File

    private val validator = LyricSyncValidatorImpl()
    private val originalLocale = Messages.getLocale()

    @AfterEach
    fun tearDown() {
        Messages.setLocale(originalLocale)
    }

    @Test
    fun testValidSynchronizedLyric() {
        val lrc = File(tempDir, "Artist - Song.lrc").apply {
            writeText(
                """
                [ar: Artist]
                [ti: Song]
                [00:10.00]First line
                [00:20.00]Second line
                [00:30.00]Third line
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun testLyricWithoutTimestamps() {
        val lrc = File(tempDir, "Artist - Song.lrc").apply {
            writeText(
                """
                First line
                Second line
                Third line
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertEquals(1, issues.size)
        assertEquals(Messages.get("lyric.issue.type.no_timestamps"), issues[0].issueType)
    }

    @Test
    fun testLyricWithStaticTimestamps() {
        val lrc = File(tempDir, "Artist - Song.lrc").apply {
            writeText(
                """
                [00:00.00]First line
                [00:00.00]Second line
                [00:00.00]Third line
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertEquals(1, issues.size)
        assertEquals(Messages.get("lyric.issue.type.static_timestamps"), issues[0].issueType)
    }

    @Test
    fun testLyricBelongingToDifferentSong() {
        val lrc = File(tempDir, "Claudia Leitte - Bola De Sabao.lrc").apply {
            writeText(
                """
                [ti:Crocodixongas]
                [ar:Xongas Company]
                [00:11.67]Ikis
                [00:12.99]Outra letra
                """.trimIndent()
            )
        }
        val dummyAudio = File(tempDir, "Claudia Leitte - Bola De Sabao.flac")

        val issues = validator.validate(lrc, dummyAudio)
        assertTrue(issues.any { it.issueType == Messages.get("lyric.issue.type.wrong_song") })
    }

    @Test
    fun testEttaMarcusDefLeppardMismatch() {
        val lrc = File(tempDir, "Etta Marcus - Let It Happen, Let It Go.lrc").apply {
            writeText(
                """
                [ti:Let It Go]
                [ar:Def Leppard]
                [00:36.95]Cool woman, cool eyes
                [00:41.60]You got me hypnotized
                """.trimIndent()
            )
        }
        val dummyAudio = File(tempDir, "Etta Marcus - Let It Happen, Let It Go.opus")

        val issues = validator.validate(lrc, dummyAudio)
        assertTrue(issues.any { it.issueType == Messages.get("lyric.issue.type.wrong_song") })
    }

    @Test
    fun testHollyHumberstoneAbbaMismatch() {
        val lrc = File(tempDir, "Holly Humberstone - Embers In The Sky.lrc").apply {
            writeText(
                """
                [ti:From A Twinkling Star To A Passing Angel - Demo Medley]
                [ar:ABBA]
                [00:27.72]Twinkle twinkle little star
                """.trimIndent()
            )
        }
        val dummyAudio = File(tempDir, "Holly Humberstone - Embers In The Sky.opus")

        val issues = validator.validate(lrc, dummyAudio)
        assertTrue(issues.any { it.issueType == Messages.get("lyric.issue.type.wrong_song") })
    }

    @Test
    fun testIssueDescriptionsLocalizedInPortuguese() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val lrc = File(tempDir, "TestNoSync.lrc").apply {
            writeText("Apenas texto sem timestamps")
        }

        val issues = validator.validate(lrc, null)
        assertEquals(1, issues.size)
        assertEquals("SEM_TIMESTAMPS", issues[0].issueType)
        assertTrue(
            issues[0].description.contains("não possui marcações de timestamp"),
            "Expected Portuguese description, got: ${issues[0].description}"
        )
    }

    @Test
    fun testIssueDescriptionsLocalizedInEnglish() {
        Messages.setLocale(Locale.ENGLISH)
        val lrc = File(tempDir, "TestNoSyncEn.lrc").apply {
            writeText("Plain text without timestamps")
        }

        val issues = validator.validate(lrc, null)
        assertEquals(1, issues.size)
        assertEquals("NO_TIMESTAMPS", issues[0].issueType)
        assertTrue(
            issues[0].description.contains("no [mm:ss] timestamps"),
            "Expected English description, got: ${issues[0].description}"
        )
    }

    @Test
    fun testLyricWithOutOfOrderTimestamps() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val lrc = File(tempDir, "Artist - OutOfOrder.lrc").apply {
            writeText(
                """
                [00:10.00]First line
                [00:25.00]Second line
                [00:18.00]Third line with lower timestamp
                [00:30.00]Fourth line
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertTrue(issues.any { it.issueType == "TIMESTAMPS_FORA_DE_ORDEM" })
    }

    @Test
    fun testLyricWithMultiTimestampSameLineIsNotFalsePositiveOutOfOrder() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val lrc = File(tempDir, "Artist - MultiTs.lrc").apply {
            writeText(
                """
                [00:10.00]First line
                [00:15.00][00:38.00]Chorus line repeated at 15s and 38s
                [00:25.00]Bridge line
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertTrue(issues.none { it.issueType == "TIMESTAMPS_FORA_DE_ORDEM" })
    }

    @Test
    fun testLyricWithPartialSync() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val lrc = File(tempDir, "Artist - PartialSync.lrc").apply {
            writeText(
                """
                [00:10.00]Synced line 1
                Unsynced line in the middle
                [00:20.00]Synced line 2
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertTrue(issues.any { it.issueType == "SINCRONIA_INCOMPLETA" })
    }

    @Test
    fun testLyricWithDuplicateStackedTimestamps() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val lrc = File(tempDir, "Artist - Stacked.lrc").apply {
            writeText(
                """
                [00:10.00]Line 1
                [00:20.00]Line 2
                [00:20.00]Line 3
                [00:20.00]Line 4
                [00:20.00]Line 5
                """.trimIndent()
            )
        }

        val issues = validator.validate(lrc, null)
        assertTrue(issues.any { it.issueType == "TIMESTAMPS_DUPLICADOS" })
    }

    @Test
    fun testLyricWithDirtyTagsAndEncodingError() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))
        val lrc = File(tempDir, "Artist - DirtyAndMojibake.lrc").apply {
            writeText(
                "[00:05.00]作词 : Someone\n" +
                        "[00:10.00]v1: Corrupted char: \uFFFD here <00:10.50>now\n" +
                        "[00:20.00]Second line [bg: background]"
            )
        }

        val issues = validator.validate(lrc, null)
        assertTrue(issues.any { it.issueType == "ERRO_CODIFICACAO" })
        assertTrue(issues.any { it.issueType == "TAGS_SUJAS" })
    }
}
