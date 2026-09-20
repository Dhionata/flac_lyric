package services

import interfaces.AudioAnalysisService
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
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

    private class EarlyStopException : RuntimeException()

    /**
     * Analyzes the frequency spectrum of the provided FLAC file to detect signs of prior compression (e.g. MP3 upscale).
     *
     * @param file The .flac file to be analyzed.
     * @param fullAnalysis If true, processes the entire file. If false, analyzes a 30-second window.
     * @return [AudioAnalysisService.AnalysisResult] containing the evaluation of the file.
     */
    override fun analyzeCutoff(file: File, fullAnalysis: Boolean): AudioAnalysisService.AnalysisResult {
        if (!file.exists() || file.extension.lowercase() != "flac") {
            return AudioAnalysisService.AnalysisResult(false, 0, "Invalid or unsupported file.")
        }

        var streamInfo: StreamInfo? = null
        val fft = DoubleFFT_1D(windowSize.toLong())
        val numBins = windowSize / 2
        val totalEnergies = DoubleArray(numBins + 1)
        var windowsCount = 0

        val windowBuffer = DoubleArray(windowSize)
        var windowBufferPos = 0

        // Precompute Hann window to reduce spectral leakage
        val hannWindow = DoubleArray(windowSize) { i ->
            0.5 * (1.0 - cos(2.0 * Math.PI * i / (windowSize - 1)))
        }

        var samplesCaptured = 0L
        var samplesProcessed = 0L
        var effectiveSkipSamples = 0L
        var targetSamplesToProcess = Long.MAX_VALUE
        var normFactor = 32768.0

        try {
            FileInputStream(file).use { inputStream ->
                val decoder = FLACDecoder(inputStream)

                decoder.addFrameListener(object : FrameListener {
                    override fun processMetadata(metadata: Metadata) {
                        if (metadata is StreamInfo) {
                            streamInfo = metadata
                        }
                    }

                    override fun processFrame(frame: Frame) {
                        val blockSize = frame.header.blockSize
                        if (blockSize <= 0) return

                        if (!fullAnalysis) {
                            if (samplesCaptured < effectiveSkipSamples) {
                                samplesCaptured += blockSize
                                return
                            }
                            if (samplesProcessed >= targetSamplesToProcess) {
                                throw EarlyStopException()
                            }
                        }

                        val channelDataArray = decoder.channelData
                        if (channelDataArray == null || channelDataArray.isEmpty()) return
                        val channelData = channelDataArray[0].output

                        for (i in 0..<blockSize) {
                            windowBuffer[windowBufferPos++] = channelData[i] / normFactor
                            if (windowBufferPos == windowSize) {
                                val fftData = DoubleArray(windowSize) { j -> windowBuffer[j] * hannWindow[j] }
                                fft.realForward(fftData)

                                totalEnergies[0] += abs(fftData[0])
                                for (k in 1..<numBins) {
                                    val re = fftData[2 * k]
                                    val im = fftData[2 * k + 1]
                                    totalEnergies[k] += sqrt(re * re + im * im)
                                }
                                totalEnergies[numBins] += abs(fftData[1])

                                windowsCount++
                                windowBufferPos = 0
                            }
                        }

                        samplesCaptured += blockSize
                        samplesProcessed += blockSize

                        if (!fullAnalysis && samplesProcessed >= targetSamplesToProcess) {
                            throw EarlyStopException()
                        }
                    }

                    override fun processError(msg: String) {}
                })

                streamInfo = decoder.readStreamInfo()
                val currentInfo = streamInfo
                    ?: return AudioAnalysisService.AnalysisResult(false, 0, "Could not read FLAC info.")

                val bits = currentInfo.bitsPerSample
                normFactor = if (bits in 1..31) (1L shl (bits - 1)).toDouble() else 32768.0

                val sampleRate = currentInfo.sampleRate
                if (sampleRate <= 0) {
                    return AudioAnalysisService.AnalysisResult(false, 0, "Invalid sample rate in FLAC.")
                }

                if (!fullAnalysis) {
                    val totalSamples = currentInfo.totalSamples
                    val durationSeconds = if (totalSamples > 0) totalSamples.toDouble() / sampleRate else 0.0
                    val reqSkip = skipSeconds.toDouble()
                    val reqAnalyze = sampleAnalyzeSeconds.toDouble()

                    val actualSkip = when {
                        durationSeconds <= 0.0 -> reqSkip
                        durationSeconds <= reqAnalyze -> 0.0
                        durationSeconds <= reqSkip + reqAnalyze -> (durationSeconds - reqAnalyze) / 2.0
                        else -> reqSkip
                    }

                    effectiveSkipSamples = (actualSkip * sampleRate).toLong()
                    targetSamplesToProcess = (reqAnalyze * sampleRate).toLong()
                }

                try {
                    decoder.decode()
                } catch (_: EarlyStopException) {
                    // Target analysis window captured successfully
                }
            }
        } catch (e: Exception) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Error decoding: ${e.message}")
        }

        // Process any remaining partial window if we haven't captured a full window yet
        if (windowsCount == 0 && windowBufferPos > 0) {
            val fftData = DoubleArray(windowSize) { j ->
                if (j < windowBufferPos) windowBuffer[j] * hannWindow[j] else 0.0
            }
            fft.realForward(fftData)
            totalEnergies[0] += abs(fftData[0])
            for (k in 1..<numBins) {
                val re = fftData[2 * k]
                val im = fftData[2 * k + 1]
                totalEnergies[k] += sqrt(re * re + im * im)
            }
            totalEnergies[numBins] += abs(fftData[1])
            windowsCount = 1
        }

        if (windowsCount == 0) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Could not extract samples.")
        }

        val coherentGain = windowSize / 2.0
        val energiesDb = DoubleArray(totalEnergies.size) { i ->
            val normalizedMag = (totalEnergies[i] / windowsCount) / coherentGain
            if (normalizedMag > 1e-6) 20.0 * log10(normalizedMag) else -120.0
        }

        val sampleRate = streamInfo?.sampleRate ?: 44100
        return analyzeSpectrum(energiesDb, sampleRate)
    }

    /**
     * Evaluates the averaged frequency spectrum (in dBFS) to detect characteristic brickwall cutoffs.
     */
    internal fun analyzeSpectrum(energiesDb: DoubleArray, sampleRate: Int): AudioAnalysisService.AnalysisResult {
        val nyquist = sampleRate / 2.0
        val binFreq = sampleRate.toDouble() / windowSize

        fun getBandEnergy(fStart: Double, fEnd: Double): Double {
            val binStart = (fStart / binFreq).toInt().coerceIn(0, energiesDb.size - 1)
            val binEnd = (fEnd / binFreq).toInt().coerceIn(binStart, energiesDb.size - 1)
            if (binStart == binEnd) return energiesDb[binStart]
            var sum = 0.0
            for (b in binStart..binEnd) {
                sum += energiesDb[b]
            }
            return sum / (binEnd - binStart + 1)
        }

        // Check overall signal level in the mid-range (2 kHz - 6 kHz)
        val energyMid = getBandEnergy(2000.0, 6000.0)
        if (energyMid < -75.0) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Audio level is too low for reliable analysis.")
        }

        // 1. Check ~16 kHz cutoff (typical 128 kbps MP3)
        if (nyquist >= 18000.0) {
            val before16k = getBandEnergy(13500.0, 15200.0)
            val after16k = getBandEnergy(16300.0, 17800.0)
            val above16k = getBandEnergy(17800.0, minOf(20500.0, nyquist - 200.0))
            if (before16k - after16k >= 20.0 && after16k <= -75.0 && before16k >= -70.0 && above16k <= after16k + 4.0) {
                return AudioAnalysisService.AnalysisResult(
                    true,
                    16000,
                    "Cutoff detected at ~16kHz (Possible upscale of 128kbps MP3)",
                )
            }
        }

        // 2. Check ~18 kHz cutoff (typical 160-192 kbps MP3 / AAC)
        if (nyquist >= 20000.0) {
            val before18k = getBandEnergy(16000.0, 17500.0)
            val after18k = getBandEnergy(18400.0, minOf(19800.0, nyquist - 200.0))
            val above18k = getBandEnergy(19800.0, minOf(21500.0, nyquist - 200.0))
            if (before18k - after18k >= 20.0 && after18k <= -75.0 && before18k >= -70.0 && above18k <= after18k + 4.0) {
                return AudioAnalysisService.AnalysisResult(
                    true,
                    18000,
                    "Cutoff detected at ~18kHz (Possible upscale of 160-192kbps lossy audio)",
                )
            }
        }

        // 3. Check ~20 kHz cutoff (typical 320 kbps MP3)
        if (nyquist >= 21500.0) {
            val before20k = getBandEnergy(18500.0, 19800.0)
            val after20k = getBandEnergy(20600.0, minOf(21900.0, nyquist - 100.0))
            if (before20k - after20k >= 16.0 && after20k <= -75.0 && before20k >= -70.0) {
                return AudioAnalysisService.AnalysisResult(
                    true,
                    20000,
                    "Cutoff detected at ~20kHz (Possible upscale of 320kbps MP3)",
                )
            }
        }

        // 4. Check ~22 kHz cutoff (Hi-Res upscale from 44.1 kHz CD source)
        if (sampleRate >= 48000 && nyquist >= 24000.0) {
            val before22k = getBandEnergy(19500.0, 21500.0)
            val after22k = getBandEnergy(22300.0, minOf(23800.0, nyquist - 200.0))
            val above22k = getBandEnergy(23800.0, minOf(30000.0, nyquist - 200.0))
            if (before22k - after22k >= 20.0 && after22k <= -75.0 && before22k >= -70.0 &&
                (nyquist < 26000.0 || above22k <= after22k + 4.0)
            ) {
                return AudioAnalysisService.AnalysisResult(
                    true,
                    22050,
                    "Cutoff detected at ~22kHz (Possible upscale of 44.1kHz audio into Hi-Res)",
                )
            }
        }

        // 5. Check ~24 kHz cutoff (Hi-Res upscale from 48 kHz source)
        if (sampleRate >= 88200 && nyquist >= 44100.0) {
            val before24k = getBandEnergy(21500.0, 23500.0)
            val after24k = getBandEnergy(24300.0, minOf(26000.0, nyquist - 200.0))
            val above24k = getBandEnergy(26000.0, minOf(34000.0, nyquist - 200.0))
            if (before24k - after24k >= 20.0 && after24k <= -75.0 && before24k >= -70.0 && above24k <= after24k + 4.0) {
                return AudioAnalysisService.AnalysisResult(
                    true,
                    24000,
                    "Cutoff detected at ~24kHz (Possible upscale of 48kHz audio into Hi-Res)",
                )
            }
        }

        // 6. Dynamic scan for intermediate or higher cutoffs (> 20 kHz)
        var scanFreq = 15000.0
        val maxScanFreq = nyquist - 1500.0
        var bestCutoffFreq = 0.0
        var maxObservedDrop = 0.0

        while (scanFreq <= maxScanFreq) {
            val bandBefore = getBandEnergy(scanFreq - 1000.0, scanFreq - 100.0)
            val bandAfter = getBandEnergy(scanFreq + 100.0, minOf(scanFreq + 1200.0, nyquist - 100.0))
            val bandFarAfter = getBandEnergy(
                minOf(scanFreq + 1200.0, nyquist - 100.0),
                minOf(scanFreq + 3500.0, nyquist - 100.0),
            )

            val currentDrop = bandBefore - bandAfter
            if (currentDrop >= 20.0 && bandAfter <= -75.0 && bandBefore >= -70.0 &&
                bandFarAfter <= bandAfter + 4.0
            ) {
                if (currentDrop > maxObservedDrop) {
                    maxObservedDrop = currentDrop
                    bestCutoffFreq = scanFreq
                }
            }
            scanFreq += 250.0
        }

        if (bestCutoffFreq > 0.0) {
            val detectedHz = ((bestCutoffFreq / 500.0).roundToInt() * 500)
            val message = if (detectedHz >= 21000) {
                "Cutoff detected at ~${detectedHz / 1000}kHz (Possible upscale into Hi-Res)"
            } else {
                "Cutoff detected at ~${detectedHz / 1000}kHz (Possible upscale of lossy audio)"
            }
            return AudioAnalysisService.AnalysisResult(true, detectedHz, message)
        }

        return AudioAnalysisService.AnalysisResult(false, 0, "Spectrum seems healthy.")
    }
}
