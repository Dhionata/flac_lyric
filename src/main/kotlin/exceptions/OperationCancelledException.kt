package exceptions

/**
 * Exception thrown when the user cancels or closes a dialog box or file/directory chooser
 * in the system, aborting the running operation and returning to the main menu.
 *
 * @param message Descriptive message of the exception.
 */
class OperationCancelledException(message: String = "Operation cancelled by the user") : RuntimeException(message)
