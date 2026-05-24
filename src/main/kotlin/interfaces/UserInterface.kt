package interfaces

import models.FilePair

/**
 * Interface responsible for defining visual or console interactions with the end user of the application.
 */
interface UserInterface {
    /**
     * Displays an error message on the screen.
     *
     * @param message The error message to be displayed.
     */
    fun showError(message: String)

    /**
     * Displays a summary of the results of the operations performed, including successes and errors.
     *
     * @param changedSet Set of successfully performed modifications.
     * @param errorSet Set of exceptions generated during failures.
     */
    fun showResult(changedSet: Set<String>, errorSet: Set<Exception>)

    /**
     * Requests confirmation to move and rename a corresponding lyric file to the audio folder.
     *
     * @param filePairs The pair of files [FilePair] to be processed.
     * @return true if the user agrees, false otherwise.
     * @throws exceptions.OperationCancelledException if the confirmation dialog is closed (e.g., via the "X" button).
     */
    fun moveAndRename(filePairs: FilePair): Boolean

    /**
     * Requests confirmation to only rename the lyric file in its original location to match the audio file name.
     *
     * @param filePair The pair of files [FilePair] to be processed.
     * @return true if the user agrees, false otherwise.
     * @throws exceptions.OperationCancelledException if the confirmation dialog is closed (e.g., via the "X" button).
     */
    fun onlyRename(filePair: FilePair): Boolean

    /**
     * Asks if the user wants to move files whose nomenclature was verified as incorrect.
     *
     * @param count The number of incorrect files.
     * @param txtFileName Name of the text file listing the incorrect files.
     * @return true if the user wishes to move the files, false otherwise.
     */
    fun askToMoveIncorrectFiles(count: Int, txtFileName: String): Boolean

    /**
     * Asks if the user wants to move files whose spectrum indicates a suspicion of being "fake lossless".
     *
     * @param count The number of suspicious files.
     * @param txtFileName Name of the text file generated with the list of files.
     * @return true if the user wishes to move them, false otherwise.
     */
    fun askToMoveFakeLossless(count: Int, txtFileName: String): Boolean

    /**
     * Asks the user which type of audio analysis to perform.
     * @return true for full analysis, false for quick analysis (30s).
     */
    fun askForAnalysisType(): Boolean

    /**
     * Creates and displays a progress bar with a maximum value.
     *
     * @param title Title of the progress dialog.
     * @param max Maximum value of the progress (total steps).
     */
    fun showProgress(title: String, max: Int)

    /**
     * Updates the current state of the progress bar and its descriptive text.
     *
     * @param current The current progress (from 0 to `max`).
     * @param text Descriptive text for the current step.
     */
    fun updateProgress(current: Int, text: String)

    /**
     * Closes and releases resources of the active progress dialog.
     */
    fun closeProgress()

    /**
     * Displays the menu with the main actions and returns the option chosen by the user.
     *
     * @return The numerical index of the selected option (from 0 to N).
     */
    fun option(): Int
}
