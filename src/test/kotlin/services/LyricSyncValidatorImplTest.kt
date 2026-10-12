package services

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LyricSyncValidatorImplTest {

    @TempDir
    lateinit var tempDir: File

    private val validator = LyricSyncValidatorImpl()

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
        assertEquals("SEM_TIMESTAMPS", issues[0].issueType)
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
        assertEquals("TIMESTAMPS_ESTATICOS", issues[0].issueType)
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
        assertTrue(issues.any { it.issueType == "MUSICA_DIFERENTE" })
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
        assertTrue(issues.any { it.issueType == "MUSICA_DIFERENTE" })
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
        assertTrue(issues.any { it.issueType == "MUSICA_DIFERENTE" })
    }
}
