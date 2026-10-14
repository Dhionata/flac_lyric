package comparison.domain.ports

import java.io.File

/**
 * Port for interacting with the operating system to open files or folders in the system file explorer.
 */
interface SystemFileOpener {
    /**
     * Opens the file's parent directory in the OS file explorer and selects the specified file if supported.
     *
     * @param file The file or directory to reveal in the file manager.
     * @return True if the operation was dispatched successfully, false otherwise.
     */
    fun openFileLocation(file: File): Boolean
}
