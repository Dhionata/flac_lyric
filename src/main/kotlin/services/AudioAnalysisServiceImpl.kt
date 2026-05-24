package services

import interfaces.AudioAnalysisService
import java.io.File
import java.io.FileInputStream
import kotlin.math.log10
import kotlin.math.sqrt
import org.jflac.FLACDecoder
import org.jflac.FrameListener
import org.jflac.frame.Frame
import org.jflac.metadata.Metadata
import org.jflac.metadata.StreamInfo
import org.jtransforms.fft.DoubleFFT_1D

/**
 * Implementation of the audio analysis service using FLAC decoding
 * and Fast Fourier Transform (FFT) to detect frequency cutoffs (upscale / fake lossless).
 */
class AudioAnalysisServiceImpl : AudioAnalysisService {

    /** Window size for FFT calculation. */
    private val windowSize = 4096
    /** Maximum time in seconds for sample extraction in quick analysis. */
    private val sampleAnalyzeSeconds = 30
    /** Seconds to be ignored at the beginning of the audio to avoid silence. */
    private val skipSeconds = 30

    /**
     * Analyzes the frequency spectrum of the provided FLAC file to detect signs of prior compression (e.g. MP3 upscale).
     *
     * @param file The .flac file to be analyzed.
     * @param fullAnalysis If true, processes the entire file. If false, analyzes only a 30-second window.
     * @return [AudioAnalysisService.AnalysisResult] containing the evaluation of the file.
     */
    override fun analyzeCutoff(file: File, fullAnalysis: Boolean): AudioAnalysisService.AnalysisResult {
        if (!file.exists() || file.extension.lowercase() != "flac") {
            return AudioAnalysisService.AnalysisResult(false, 0, "Invalid or unsupported file.")
        }

        val inputStream = FileInputStream(file)
        val decoder = FLACDecoder(inputStream)

        var streamInfo: StreamInfo? = null
        val samples = mutableListOf<Double>()
        var samplesCaptured = 0

        decoder.addFrameListener(object : FrameListener {
            override fun processMetadata(metadata: Metadata) {
                if (metadata is StreamInfo) {
                    streamInfo = metadata
                }
            }

            override fun processFrame(frame: Frame) {
                val currentStreamInfo = streamInfo ?: return

                val currentSecond = samplesCaptured / currentStreamInfo.sampleRate

                if (!fullAnalysis) {
                    if (currentSecond < skipSeconds) {
                        samplesCaptured += frame.header.blockSize
                        return
                    }

                    if (currentSecond > skipSeconds + sampleAnalyzeSeconds) return
                }

                // We take only the first channel for simplified spectrum analysis
                val channelData = decoder.channelData[0].output
                for (i in 0..<frame.header.blockSize) {
                    samples.add(channelData[i].toDouble())
                }
                samplesCaptured += frame.header.blockSize
            }

            override fun processError(msg: String) {}
        })

        try {
            streamInfo = decoder.readStreamInfo()
            if (streamInfo == null) return AudioAnalysisService.AnalysisResult(false, 0, "Could not read FLAC info.")

            decoder.decode()
        } catch (e: Exception) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Error decoding: ${e.message}")
        } finally {
            inputStream.close()
        }

        if (samples.isEmpty()) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Could not extract samples.")
        }

        val sampleRate = streamInfo.sampleRate
        return processFFT(samples.toDoubleArray(), sampleRate)
    }

    private fun processFFT(data: DoubleArray, sampleRate: Int): AudioAnalysisService.AnalysisResult {
        val fft = DoubleFFT_1D(windowSize.toLong())
        val numWindows = data.size / windowSize
        val avgEnergies = DoubleArray(windowSize / 2) { 0.0 }

        for (w in 0..<numWindows) {
            val windowData = DoubleArray(windowSize)
            System.arraycopy(data, w * windowSize, windowData, 0, windowSize)

            // Apply Hamming window to reduce leakage
            for (i in 0..<windowSize) {
                windowData[i] *= 0.54 - 0.46 * kotlin.math.cos(2.0 * Math.PI * i / (windowSize - 1))
            }

            fft.realForward(windowData)

            for (i in 0..<windowSize / 2) {
                val re = windowData[2 * i]
                val im = if (2 * i + 1 < windowSize) windowData[2 * i + 1] else 0.0
                val mag = sqrt(re * re + im * im)
                avgEnergies[i] += mag
            }
        }

        // Average of magnitudes
        for (i in avgEnergies.indices) {
            avgEnergies[i] /= numWindows.toDouble()
        }

        // Convert to dB (logarithmic scale)
        val energiesDb = DoubleArray(avgEnergies.size) { i ->
            if (avgEnergies[i] > 0) 20 * log10(avgEnergies[i]) else -100.0
        }

        val binFreq = sampleRate.toDouble() / windowSize

        // Frequencies of interest
        val index16k = (16000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)
        val index18k = (18000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)
        val index20k = (20000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)
        val indexBase = (5000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)

        val energyBase = energiesDb.sliceArray(indexBase - 10..indexBase + 10).average()
        val energy16k = energiesDb.sliceArray(index16k - 5..index16k + 5).average()
        val energy18k = energiesDb.sliceArray(index18k - 5..index18k + 5).average()
        val energy20k = energiesDb.sliceArray(index20k - 5..index20k + 5).average()

        // Cutoff criterion: If the energy drops more than 30dB relative to the base and keeps falling
        // Values based on common observations of fakes
        if (energyBase - energy16k > 35 && energy16k - energy18k > 5) {
            return AudioAnalysisService.AnalysisResult(true, 16000, "Cutoff detected at ~16kHz (Possible upscale of 128kbps MP3)")
        }

        if (energyBase - energy20k > 35 && energy20k > -80.0) { // -80dB is close to digital silence
            // If there is a sharp drop before 20k
            val energy19k = energiesDb.sliceArray((19000 / binFreq).toInt() - 5..(19000 / binFreq).toInt() + 5).average()
            if (energy19k - energy20k > 20) {
                return AudioAnalysisService.AnalysisResult(true, 20000, "Cutoff detected at ~20kHz (Possible upscale of 320kbps MP3)")
            }
        }

        return AudioAnalysisService.AnalysisResult(false, 0, "Spectrum seems healthy.")
    }
}
