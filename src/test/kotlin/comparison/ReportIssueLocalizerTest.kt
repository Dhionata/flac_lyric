package comparison

import comparison.infrastructure.parsers.ReportIssueLocalizer
import java.util.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import services.Messages

class ReportIssueLocalizerTest {

    private val originalLocale = Messages.getLocale()

    @AfterEach
    fun tearDown() {
        Messages.setLocale(originalLocale)
    }

    @Test
    fun `test localizes Portuguese report tags and descriptions into English`() {
        Messages.setLocale(Locale.ENGLISH)

        val ptIssues = listOf(
            "[SEM_LETRA] A música não possui arquivo de letra (.lrc) correspondente.",
            "[SEM_TIMESTAMPS] O arquivo não possui marcações de timestamp [mm:ss] (letra não sincronizada).",
            "[TIMESTAMPS_FORA_DE_ORDEM] Verso na linha 5 possui minutagem (01:12.40) inferior à linha anterior (01:20.00).",
            "[TERMINO_PREMATURO] Letra encerra prematuramente no verso em 02:10 para uma faixa de 04:00.",
            "[MUSICA_DIFERENTE] A letra possui metadados de outra música (artista: 'ABBA', título: 'Dancing Queen')."
        )

        val localized = ReportIssueLocalizer.localizeIssues(ptIssues)
        assertEquals(5, localized.size)

        assertTrue(localized[0].startsWith("[MISSING_LYRICS] "))
        assertTrue(localized[0].contains("does not have a corresponding (.lrc) lyric file"))

        assertTrue(localized[1].startsWith("[NO_TIMESTAMPS] "))
        assertTrue(localized[1].contains("no [mm:ss] timestamps"))

        assertTrue(localized[2].startsWith("[OUT_OF_ORDER] "))
        assertTrue(localized[2].contains("Line 5 has a timestamp (01:12.40) lower than the previous line (01:20.00)"))

        assertTrue(localized[3].startsWith("[PREMATURE_END] "))
        assertTrue(localized[3].contains("at line 02:10 for an audio track of 04:00"))

        assertTrue(localized[4].startsWith("[DIFFERENT_SONG] "))
        assertTrue(localized[4].contains("artist: 'ABBA', title: 'Dancing Queen'"))
    }

    @Test
    fun `test localizes English report tags and descriptions into Portuguese`() {
        Messages.setLocale(Locale.forLanguageTag("pt-BR"))

        val enIssues = listOf(
            "[MISSING_LYRICS] The audio track does not have a corresponding (.lrc) lyric file.",
            "[STATIC_TIMESTAMPS] All timestamps are static/identical (00:00.00) - lyrics have no real synchronization.",
            "[LENGTH_MISMATCH] The LRC [length: 03:45] tag differs by 17s from audio track duration (04:02).",
            "[EXCEEDS_DURATION] Timestamps exceed audio duration (last line at 04:15 for an audio track of 04:00)."
        )

        val localized = ReportIssueLocalizer.localizeIssues(enIssues)
        assertEquals(4, localized.size)

        assertTrue(localized[0].startsWith("[SEM_LETRA] "))
        assertTrue(localized[0].contains("não possui arquivo de letra (.lrc) correspondente"))

        assertTrue(localized[1].startsWith("[TIMESTAMPS_ESTATICOS] "))
        assertTrue(localized[1].contains("Todos os timestamps são estáticos/idênticos (00:00.00)"))

        assertTrue(localized[2].startsWith("[TAG_LENGTH_DIVERGENTE] "))
        assertTrue(localized[2].contains("[length: 03:45]"))
        assertTrue(localized[2].contains("diverge em 17s da duração da faixa (04:02)"))

        assertTrue(localized[3].startsWith("[ULTRAPASSOU_DURACAO] "))
        assertTrue(localized[3].contains("último verso em 04:15 para faixa de 04:00"))
    }
}
