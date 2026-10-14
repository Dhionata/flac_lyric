package comparison.infrastructure.io

import comparison.domain.ports.LyricFileStorage
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Standard file storage implementation for reading and writing UTF-8 encoded lyric files.
 */
class LyricFileStorageImpl : LyricFileStorage {

    override fun readText(file: File): String {
        return if (file.exists() && file.isFile) {
            file.readText(StandardCharsets.UTF_8)
        } else {
            ""
        }
    }

    override fun writeText(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        file.writeText(content, StandardCharsets.UTF_8)
    }
}
