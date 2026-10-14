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
    fun `test handles multiple timestamps on single line`() {
        val lrc = "[00:10.00][00:20.50] Repeated Chorus"
        val lines = parser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].timestampMs)
        assertEquals("Repeated Chorus", lines[0].text)
        assertEquals(20500L, lines[1].timestampMs)
        assertEquals("Repeated Chorus", lines[1].text)
    }
}
