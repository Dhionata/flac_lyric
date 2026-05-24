package services

import interfaces.AudioNomenclatureValidator
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * Specific validator for the clean "Artist - Title" pattern with Album (Single Responsibility Principle).
 * Validates the expected directory structure: Album Artist / Album / Artist - Title.
 */
class CleanTitleNomenclatureValidator : AudioNomenclatureValidator {

    /** Regex to remove feat. or similar indications from the title. */
    private val featRegex = Regex("(?i)\\s*\\(?feat\\.[^)]*\\)?")
    /** Audio extensions supported by the nomenclature validation. */
    private val supportedExtensions = models.AudioConfig.supportedExtensions

    /**
     * Sanitizes the name by replacing characters prohibited in the file system with underscores.
     *
     * @param name Name to be sanitized.
     * @return Sanitized name.
     */
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

            // Fallback: Attempt validation without sanitization, in case the original folders were not sanitized the same way
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
            // Ignore and return false if tags cannot be read
        }

        return false
    }
}
