package fakes

import interfaces.UserInterface
import models.FilePair
import java.lang.Exception

class FakeUserInterface : UserInterface {
    var lastErrorMessage: String? = null
    val resultsShown = mutableListOf<Pair<Set<String>, Set<Exception>>>()
    
    var moveAndRenameResponse = true
    val moveAndRenameCalls = mutableListOf<FilePair>()
    
    var onlyRenameResponse = true
    val onlyRenameCalls = mutableListOf<FilePair>()
    
    var askToMoveIncorrectFilesResponse = true
    var askToMoveFakeLosslessResponse = true
    var askToMoveUnmatchedLyricsResponse = true
    var askForAnalysisTypeResponse = false
    
    var progressTitle: String? = null
    var progressMax: Int? = null
    val progressUpdates = mutableListOf<Pair<Int, String>>()
    var isProgressClosed = false
    
    var optionResponse = 0

    override fun showError(message: String) {
        lastErrorMessage = message
    }

    override fun showResult(changedSet: Set<String>, errorSet: Set<Exception>) {
        resultsShown.add(changedSet to errorSet)
    }

    override fun moveAndRename(filePairs: FilePair): Boolean {
        moveAndRenameCalls.add(filePairs)
        return moveAndRenameResponse
    }

    override fun onlyRename(filePair: FilePair): Boolean {
        onlyRenameCalls.add(filePair)
        return onlyRenameResponse
    }

    override fun askToMoveIncorrectFiles(count: Int, txtFileName: String): Boolean {
        return askToMoveIncorrectFilesResponse
    }

    override fun askToMoveFakeLossless(count: Int, txtFileName: String): Boolean {
        return askToMoveFakeLosslessResponse
    }

    override fun askToMoveUnmatchedLyrics(count: Int): Boolean {
        return askToMoveUnmatchedLyricsResponse
    }

    override fun askForAnalysisType(): Boolean {
        return askForAnalysisTypeResponse
    }

    override fun showProgress(title: String, max: Int) {
        progressTitle = title
        progressMax = max
        isProgressClosed = false
    }

    override fun updateProgress(current: Int, text: String) {
        progressUpdates.add(current to text)
    }

    override fun closeProgress() {
        isProgressClosed = true
    }

    override fun option(): Int {
        return optionResponse
    }
}
