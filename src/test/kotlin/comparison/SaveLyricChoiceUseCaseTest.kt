package comparison

import comparison.application.usecases.SaveLyricChoiceUseCase
import comparison.domain.models.SaveLyricRequest
import comparison.infrastructure.io.LyricFileStorageImpl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SaveLyricChoiceUseCaseTest {

    @TempDir
    lateinit var tempDir: File

    private val storage = LyricFileStorageImpl()
    private val useCase = SaveLyricChoiceUseCase(storage)

    @Test
    fun `test save LRCLIB choice writes new content`() {
        val lrcFile = File(tempDir, "Song.lrc")
        lrcFile.writeText("[00:01.00]Old local")

        val newContent = "[00:05.00]New from LRCLIB"
        val request = SaveLyricRequest(
            lyricPath = lrcFile.absolutePath,
            content = newContent
        )

        val result = useCase.execute(request)

        assertTrue(result.success)
        assertEquals(newContent, lrcFile.readText())
    }

    @Test
    fun `test keep LOCAL choice does not overwrite file when no custom content`() {
        val lrcFile = File(tempDir, "Song.lrc")
        val initialContent = "[00:01.00]Keep me"
        lrcFile.writeText(initialContent)

        val request = SaveLyricRequest(
            lyricPath = lrcFile.absolutePath,
            content = "[00:99.99]Ignored remote",
            choice = "LOCAL"
        )

        val result = useCase.execute(request)

        assertTrue(result.success)
        assertEquals(initialContent, lrcFile.readText())
    }

    @Test
    fun `test keep LOCAL choice with custom content updates file`() {
        val lrcFile = File(tempDir, "Song.lrc")
        lrcFile.writeText("[00:01.00]Initial")

        val adjustedContent = "[00:02.50]Adjusted local line"
        val request = SaveLyricRequest(
            lyricPath = lrcFile.absolutePath,
            customLrcContent = adjustedContent,
            choice = "LOCAL"
        )

        val result = useCase.execute(request)

        assertTrue(result.success)
        assertEquals(adjustedContent, lrcFile.readText())
    }

    @Test
    fun `test save with null content does not throw and handles gracefully`() {
        val originalLocale = services.Messages.getLocale()
        try {
            val lrcFile = File(tempDir, "Song.lrc")
            val request = SaveLyricRequest(
                lyricPath = lrcFile.absolutePath
            )

            services.Messages.setLocale(java.util.Locale.forLanguageTag("pt-BR"))
            val resultPt = useCase.execute(request)
            org.junit.jupiter.api.Assertions.assertFalse(resultPt.success)
            assertEquals("O conteúdo da letra do LRCLIB está vazio.", resultPt.message)

            services.Messages.setLocale(java.util.Locale.ENGLISH)
            val resultEn = useCase.execute(request)
            org.junit.jupiter.api.Assertions.assertFalse(resultEn.success)
            assertEquals("LRCLIB lyric content is empty.", resultEn.message)
        } finally {
            services.Messages.setLocale(originalLocale)
        }
    }
}
