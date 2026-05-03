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

class AudioAnalysisServiceImpl : AudioAnalysisService {

    private val windowSize = 4096
    private val sampleAnalyzeSeconds = 30
    private val skipSeconds = 30 // Pular o início para evitar silêncio

    override fun analyzeCutoff(file: File, fullAnalysis: Boolean): AudioAnalysisService.AnalysisResult {
        if (!file.exists() || file.extension.lowercase() != "flac") {
            return AudioAnalysisService.AnalysisResult(false, 0, "Arquivo inválido ou não suportado.")
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

                // Pegamos apenas o primeiro canal para análise de espectro simplificada
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
            if (streamInfo == null) return AudioAnalysisService.AnalysisResult(false, 0, "Não foi possível ler info do FLAC.")

            decoder.decode()
        } catch (e: Exception) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Erro ao decodificar: ${e.message}")
        } finally {
            inputStream.close()
        }

        if (samples.isEmpty()) {
            return AudioAnalysisService.AnalysisResult(false, 0, "Não foi possível extrair samples.")
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

            // Aplicar janela de Hamming para reduzir leakage
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

        // Média das magnitudes
        for (i in avgEnergies.indices) {
            avgEnergies[i] /= numWindows.toDouble()
        }

        // Converter para dB (escala logarítmica)
        val energiesDb = DoubleArray(avgEnergies.size) { i ->
            if (avgEnergies[i] > 0) 20 * log10(avgEnergies[i]) else -100.0
        }

        val binFreq = sampleRate.toDouble() / windowSize

        // Frequências de interesse
        val index16k = (16000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)
        val index18k = (18000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)
        val index20k = (20000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)
        val indexBase = (5000 / binFreq).toInt().coerceAtMost(energiesDb.size - 1)

        val energyBase = energiesDb.sliceArray(indexBase - 10..indexBase + 10).average()
        val energy16k = energiesDb.sliceArray(index16k - 5..index16k + 5).average()
        val energy18k = energiesDb.sliceArray(index18k - 5..index18k + 5).average()
        val energy20k = energiesDb.sliceArray(index20k - 5..index20k + 5).average()

        // Critério de corte: Se a energia cai mais de 30dB em relação à base e continua caindo
        // Valores baseados em observações comuns de fakes
        if (energyBase - energy16k > 35 && energy16k - energy18k > 5) {
            return AudioAnalysisService.AnalysisResult(true, 16000, "Corte detectado em ~16kHz (Possível upscale de MP3 128kbps)")
        }

        if (energyBase - energy20k > 35 && energy20k > -80.0) { // -80dB é quase silêncio digital
            // Se houver uma queda brusca antes de 20k
            val energy19k = energiesDb.sliceArray((19000 / binFreq).toInt() - 5..(19000 / binFreq).toInt() + 5).average()
            if (energy19k - energy20k > 20) {
                return AudioAnalysisService.AnalysisResult(true, 20000, "Corte detectado em ~20kHz (Possível upscale de MP3 320kbps)")
            }
        }

        return AudioAnalysisService.AnalysisResult(false, 0, "Espectro parece saudável.")
    }
}
