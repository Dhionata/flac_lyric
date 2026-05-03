package interfaces

import models.FilePair

interface UserInterface {
    fun showError(message: String)
    fun showResult(changedSet: Set<String>, errorSet: Set<Exception>)
    fun moveAndRename(filePairs: FilePair): Boolean
    fun onlyRename(filePair: FilePair): Boolean
    fun askToMoveIncorrectFiles(count: Int, txtFileName: String): Boolean

    fun askToMoveFakeLossless(count: Int, txtFileName: String): Boolean

    /**
     * Pergunta ao usuário qual o tipo de análise de áudio deseja realizar.
     * @return true para análise completa, false para análise rápida (30s).
     */
    fun askForAnalysisType(): Boolean

    fun showProgress(title: String, max: Int)
    fun updateProgress(current: Int, text: String)
    fun closeProgress()

    fun option(): Int
}
