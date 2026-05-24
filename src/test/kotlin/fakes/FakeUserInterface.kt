package fakes

import interfaces.UserInterface
import models.FilePair

class FakeUserInterface : UserInterface {
    var lastErrorMessage: String? = null
    val resultsShown: MutableList<Pair<Set<String>, Set<Exception>>> = mutableListOf()

    var moveAndRenameResponse: Boolean = true
    val moveAndRenameCalls: MutableList<FilePair> = mutableListOf()

    var onlyRenameResponse: Boolean = true
    val onlyRenameCalls: MutableList<FilePair> = mutableListOf()

    var askToMoveIncorrectFilesResponse: Boolean = true
    var askToMoveFakeLosslessResponse: Boolean = true
    var askToMoveUnmatchedLyricsResponse: Boolean = true
    var askForAnalysisTypeResponse: Boolean = false

    var progressTitle: String? = null
    var progressMax: Int? = null
    val progressUpdates: MutableList<Pair<Int, String>> = mutableListOf()
    var isProgressClosed: Boolean = false

    var optionResponse: Int = 0

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
