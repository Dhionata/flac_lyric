package interfaces

interface UserInterface {
    fun showError(message: String)
    fun showResult(changedSet: Set<String>, errorList: Set<Exception>)
}
