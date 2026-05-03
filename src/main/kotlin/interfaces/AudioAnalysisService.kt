package interfaces

import java.io.File

interface AudioAnalysisService {
    /**
     * Analisa um arquivo de áudio para determinar se ele parece ser um "fake lossless"
     * (ex: um arquivo cortado em 16kHz ou 20kHz).
     * @param fullAnalysis Se true, analisa o arquivo inteiro. Se false, analisa apenas uma amostra.
     * @return O resultado da análise contendo a frequência de corte detectada, se houver.
     */
    fun analyzeCutoff(file: File, fullAnalysis: Boolean): AnalysisResult

    data class AnalysisResult(
        val isFake: Boolean,
        val cutoffFrequencyHz: Int,
        val message: String,
    )
}
