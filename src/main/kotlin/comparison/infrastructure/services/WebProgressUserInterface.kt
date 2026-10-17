package comparison.infrastructure.services

import comparison.domain.models.ActionExecutionRequest
import comparison.domain.models.ActionProgressUpdate
import interfaces.UserInterface
import java.io.File
import models.FilePair

/**
 * Implementation of [UserInterface] that captures operations progress, messages, and results
 * into an [ActionProgressUpdate] state object that is streamed or polled by the Web UI.
 */
class WebProgressUserInterface(
    private val request: ActionExecutionRequest,
    val state: ActionProgressUpdate,
) : UserInterface {

    override fun showError(message: String) {
        state.errors.add(message)
        state.errorMessage = message
    }

    override fun showResult(changedSet: Set<String>, errorSet: Set<Exception>) {
        state.changes.addAll(changedSet)
        errorSet.forEach { state.errors.add(it.message ?: it.toString()) }

        changedSet.forEach { change ->
            val match = Regex("""(LyricsSyncIssues_\d+\.txt)""").find(change)
            if (match != null) {
                state.generatedReportPath = File(match.groupValues[1]).canonicalPath
            }
        }
    }

    override fun moveAndRename(filePairs: FilePair): Boolean {
        return request.moveFiles
    }

    override fun onlyRename(filePair: FilePair): Boolean {
        return request.renameFiles
    }

    override fun askToMoveIncorrectFiles(count: Int, txtFileName: String): Boolean {
        state.generatedReportPath = File(txtFileName).canonicalPath
        return request.moveFiles && !request.outDir.isNullOrBlank()
    }

    override fun askToMoveFakeLossless(count: Int, txtFileName: String): Boolean {
        state.generatedReportPath = File(txtFileName).canonicalPath
        return request.moveFiles && !request.outDir.isNullOrBlank()
    }

    override fun askToMoveUnmatchedLyrics(count: Int): Boolean {
        return request.moveFiles && !request.outDir.isNullOrBlank()
    }

    override fun askToMoveProblematicLyrics(count: Int, txtFileName: String): Boolean {
        state.generatedReportPath = File(txtFileName).canonicalPath
        return request.moveFiles && !request.outDir.isNullOrBlank()
    }

    override fun askForAnalysisType(): Boolean {
        return request.isFullAnalysis
    }

    override fun showProgress(title: String, max: Int) {
        state.title = title
        state.max = max
        state.current = 0
    }

    override fun updateProgress(current: Int, text: String) {
        state.current = current
        state.currentItem = text
    }

    override fun closeProgress() {
        state.current = state.max
    }

    override fun option(): Int {
        return request.actionId
    }
}
