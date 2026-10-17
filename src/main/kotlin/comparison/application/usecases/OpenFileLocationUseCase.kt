package comparison.application.usecases

import comparison.domain.ports.SystemFileOpener
import java.io.File

/**
 * Use case to reveal a music or lyric file in the user's native file explorer.
 */
class OpenFileLocationUseCase(
    private val systemFileOpener: SystemFileOpener,
) {
    fun execute(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        val file = File(filePath)
        return systemFileOpener.openFileLocation(file)
    }
}
