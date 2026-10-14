package comparison.infrastructure.io

import comparison.domain.ports.SystemFileOpener
import java.awt.Desktop
import java.io.File

/**
 * Operating system file opener implementation.
 * On Windows, leverages 'explorer.exe /select,path' to reveal and highlight the exact file.
 * Falls back to standard Desktop.open on parent directory.
 */
class SystemFileOpenerImpl : SystemFileOpener {

    override fun openFileLocation(file: File): Boolean {
        if (!file.exists()) return false

        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true

        if (isWindows) {
            try {
                ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                return true
            } catch (_: Exception) {
                // Fallback to Desktop API
            }
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                val target = if (file.isDirectory) file else (file.parentFile ?: file)
                Desktop.getDesktop().open(target)
                return true
            } catch (_: Exception) {
                return false
            }
        }

        return false
    }
}
