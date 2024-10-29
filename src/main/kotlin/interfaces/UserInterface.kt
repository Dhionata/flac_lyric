package interfaces

import models.FilePair

interface UserInterface {
    fun showError(message: String)
    fun showResult(changedSet: Set<String>, errorSet: Set<Exception>)
    fun moveAndRename(filePairs: FilePair): Boolean
    fun onlyRename(filePair: FilePair): Boolean
    fun option(): Int
}
