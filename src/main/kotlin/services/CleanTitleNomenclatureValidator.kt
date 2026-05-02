package services

import interfaces.AudioNomenclatureValidator
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * Validador específico para o padrão "Artista - Título" limpo com Álbum (Single Responsibility Principle)
 */
class CleanTitleNomenclatureValidator : AudioNomenclatureValidator {

    private val featRegex = Regex("(?i)\\s*\\(?feat\\.[^)]*\\)?")
    private val supportedExtensions = setOf("flac", "mp3", "m4a", "wav", "ogg")

    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun formatMultipleValues(values: List<String>): String {
        if (values.isEmpty()) return ""
        return if (values.size > 1) {
            if (values.size == 2) {
                values.joinToString(" & ")
            } else {
                val allButLast = values.slice(0 until values.size - 1)
                val last = values.last()
                allButLast.joinToString(", ") + " & " + last
            }
        } else {
            values.first()
        }
    }

    override fun isValid(file: File, baseDirectory: File): Boolean {
        if (file.extension.lowercase() !in supportedExtensions) return false

        try {
            val audioFileObj = AudioFileIO.read(file)
            val tag = audioFileObj.tag ?: return false

            val albumArtistList = tag.getAll(FieldKey.ALBUM_ARTIST)
            val artistList = tag.getAll(FieldKey.ARTIST)
            
            val fmtAlbumArtist = formatMultipleValues(albumArtistList)
            val fmtArtist = formatMultipleValues(artistList)

            val album = tag.getFirst(FieldKey.ALBUM) ?: ""
            val title = tag.getFirst(FieldKey.TITLE) ?: ""

            if (fmtArtist.isBlank() || title.isBlank()) return false

            val cleanTitle = title.replace(featRegex, "").trim()
            val fileName = "$fmtArtist - $cleanTitle.${file.extension.lowercase()}"
            val sanitizedFileName = sanitize(fileName)

            val folder1 = fmtAlbumArtist.ifBlank { fmtArtist }

            val expectedRelativePath = if (fmtAlbumArtist.isNotBlank() && album.isNotBlank()) {
                File(File(sanitize(folder1)), sanitize(album)).resolve(sanitizedFileName)
            } else {
                File(sanitize(folder1)).resolve(sanitizedFileName)
            }

            val expectedFile = File(baseDirectory, expectedRelativePath.path)

            if (file.absolutePath == expectedFile.absolutePath) {
                return true
            }

            // Fallback: Tentativa de validação sem sanitização, caso as pastas originais não tenham sido sanitizadas da mesma forma
            val expectedRelativePathUnsanitized = if (fmtAlbumArtist.isNotBlank() && album.isNotBlank()) {
                File(File(folder1), album).resolve(fileName)
            } else {
                File(folder1).resolve(fileName)
            }
            val expectedFileUnsanitized = File(baseDirectory, expectedRelativePathUnsanitized.path)

            if (file.absolutePath == expectedFileUnsanitized.absolutePath) {
                return true
            }

        } catch (_: Exception) {
            // Ignorar e retornar false caso não seja possível ler as tags
        }

        return false
    }
}
