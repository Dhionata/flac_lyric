package interfaces

import models.FilePair

interface UserInterface {
    fun showError(message: String)
    fun showResult(changedSet: Set<String>, errorList: Set<Exception>)
    fun move(filePairs: FilePair): Boolean
}
