package interfaces

import java.io.File

/**
 * Service responsible for interacting with the user to obtain directory or file paths.
 */
interface DirectoryService {
    /**
     * Displays a selector for the user to choose a directory.
     *
     * @param dialogTitle Descriptive title displayed in the dialogue window.
     * @return The [File] object representing the selected directory.
     * @throws exceptions.OperationCancelledException if the user closes or cancels the dialog.
     */
    fun getDirectory(dialogTitle: String): File

    /**
     * Displays a selector for the user to choose a text file (.txt).
     *
     * @param dialogTitle Descriptive title displayed in the dialogue window.
     * @return The [File] object representing the selected file.
     * @throws exceptions.OperationCancelledException if the user closes or cancels the dialog.
     */
    fun getTxtFile(dialogTitle: String): File
}
