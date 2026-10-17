package comparison

import comparison.infrastructure.parsers.LyricLineParserImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LyricLineParserImplTest {

    private val parser = LyricLineParserImpl()

    @Test
    fun `test parse standard timestamped lrc lines`() {
        val lrc = """
            [ti:Dreamscape]
            [ar:009 Sound System]
            [al:009 Sound System]
            [01:06.57] You, you can do anything you want to
            [01:14.33] You, you can do anything
            [01:22.06] You, you can do anything you want to
        """.trimIndent()

        val lines = parser.parse(lrc)
        assertEquals(3, lines.size)

        assertEquals(66570L, lines[0].timestampMs)
        assertEquals("01:06", lines[0].formattedTime)
        assertEquals("You, you can do anything you want to", lines[0].text)

        assertEquals(74330L, lines[1].timestampMs)
        assertEquals("01:14", lines[1].formattedTime)
        assertEquals("You, you can do anything", lines[1].text)
    }

    @Test
    fun `test ignores empty and metadata only content`() {
        val lrc = """
            [ti:Test]
            [ar:Artist]
            
        """.trimIndent()

        val lines = parser.parse(lrc)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `test ignores NetEase credit-only stub lyrics so they return 0 verses`() {
        val creditStub = """
            [00:00.00-1] 作词 : Ana Muller
            [00:00.00-1] 作曲 : Ana Muller
        """.trimIndent()

        val lines = parser.parse(creditStub)
        assertTrue(lines.isEmpty())
        assertTrue(parser.parsePlain(creditStub).isEmpty())
    }

    @Test
    fun `test parsePlain converts unsynchronized lyrics into manual-sync lines at 00_00`() {
        val plain = """
            [Verse 1]
            Eu queria dizer que não ligo
            Mas eu ligo sim
            
            [Chorus]
            Marceline, volta pra mim
        """.trimIndent()

        val lines = parser.parsePlain(plain)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].timestampMs)
        assertEquals("00:00", lines[0].formattedTime)
        assertEquals("Eu queria dizer que não ligo", lines[0].text)
        assertEquals("Marceline, volta pra mim", lines[2].text)
    }

    @Test
    fun `test parses extended timestamp formats and strips inline word tags`() {
        val lrc = """
            [00:00.00-1] 作词 : Ana Muller
            [00:12.40-1] <00:12.40>Eu <00:12.80>queria <00:13.20>dizer
            [00:15:50] Segunda linha
        """.trimIndent()

        val lines = parser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(12400L, lines[0].timestampMs)
        assertEquals("Eu queria dizer", lines[0].text)
        assertEquals(15500L, lines[1].timestampMs)
        assertEquals("Segunda linha", lines[1].text)
    }

    @Test
    fun `test handles multiple timestamps on single line`() {
        val lrc = "[00:10.00][00:20.50] Repeated Chorus"
        val lines = parser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].timestampMs)
        assertEquals("Repeated Chorus", lines[0].text)
        assertEquals(20500L, lines[1].timestampMs)
        assertEquals("Repeated Chorus", lines[1].text)
    }

    @Test
    fun `test preserves original order of out-of-order single-timestamp lines for sync warning detection`() {
        val lrc = """
            [00:10.00] First line
            [00:25.00] Second line
            [00:18.50] Out of order line
            [00:30.00] Fourth line
        """.trimIndent()

        val lines = parser.parse(lrc)
        assertEquals(4, lines.size)
        assertEquals(10000L, lines[0].timestampMs)
        assertEquals(25000L, lines[1].timestampMs)
        assertEquals(18500L, lines[2].timestampMs)
        assertEquals("Out of order line", lines[2].text)
        assertEquals(30000L, lines[3].timestampMs)
    }
}
