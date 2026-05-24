package interfaces

import java.io.File

/**
 * Service responsible for analyzing audio files and detecting frequency inconsistencies.
 */
interface AudioAnalysisService {
    /**
     * Analyzes an audio file to determine if it seems to be "fake lossless"
     * (e.g., a file cutoff at 16kHz or 20kHz).
     * @param file The audio file to be analyzed.
     * @param fullAnalysis If true, analyzes the entire file. If false, analyzes only a sample.
     * @return The analysis result containing the detected cutoff frequency, if any.
     */
    fun analyzeCutoff(file: File, fullAnalysis: Boolean): AnalysisResult

    /**
     * Result containing the data obtained from the spectrum analysis.
     *
     * @property isFake Indicates if the audio file is suspected of being a fake Lossless.
     * @property cutoffFrequencyHz Approximate cutoff frequency detected in Hertz.
     * @property message Detailed message with the analysis result.
     */
    data class AnalysisResult(
        val isFake: Boolean,
        val cutoffFrequencyHz: Int,
        val message: String,
    )
}
