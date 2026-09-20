package services

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioAnalysisServiceImplTest {

    private val service = AudioAnalysisServiceImpl()
    private val windowSize = 4096
    private val numBins = windowSize / 2 + 1

    private fun createSpectrum(sampleRate: Int, cutoffHz: Int?, naturalFloorDb: Double = -65.0): DoubleArray {
        val binFreq = sampleRate.toDouble() / windowSize
        return DoubleArray(numBins) { i ->
            val freq = i * binFreq
            when {
                // Mid-range presence (2 kHz - 6 kHz)
                freq < 2000 -> -25.0 - (freq / 1000.0)
                freq in 2000.0..6000.0 -> -27.0 - ((freq - 2000.0) / 1000.0)
                // Natural gradual slope above 6 kHz
                cutoffHz == null || freq < cutoffHz -> {
                    val progress = (freq - 6000.0) / (sampleRate / 2.0 - 6000.0)
                    -31.0 - progress * (abs(naturalFloorDb) - 31.0)
                }
                // Sharp lowpass cutoff into digital silence
                else -> -100.0
            }
        }
    }

    @Test
    fun testHealthySpectrum44kHz() {
        val spectrum = createSpectrum(44100, null, -62.0)
        val result = service.analyzeSpectrum(spectrum, 44100)

        assertFalse(result.isFake, "Healthy 44.1 kHz spectrum should not be marked as fake")
        assertEquals(0, result.cutoffFrequencyHz)
        assertEquals("Spectrum seems healthy.", result.message)
    }

    @Test
    fun testNaturalAcousticGentleSlopeNotFake() {
        // Acoustic guitar or piano track where high frequencies naturally roll off down to -78 dB
        // but without any brickwall / cliff drop
        val spectrum = createSpectrum(44100, null, -78.0)
        val result = service.analyzeSpectrum(spectrum, 44100)

        assertFalse(result.isFake, "Gentle acoustic rolloff without brickwall filter should not be marked as fake")
        assertEquals(0, result.cutoffFrequencyHz)
        assertEquals("Spectrum seems healthy.", result.message)
    }

    @Test
    fun test16kCutoffDetected() {
        val spectrum = createSpectrum(44100, 16000)
        val result = service.analyzeSpectrum(spectrum, 44100)

        assertTrue(result.isFake, "16 kHz cutoff should be marked as fake")
        assertEquals(16000, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("16kHz"), "Message should mention 16kHz")
    }

    @Test
    fun test18kCutoffDetected() {
        val spectrum = createSpectrum(44100, 18000)
        val result = service.analyzeSpectrum(spectrum, 44100)

        assertTrue(result.isFake, "18 kHz cutoff should be marked as fake")
        assertEquals(18000, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("18kHz"), "Message should mention 18kHz")
    }

    @Test
    fun test20kCutoffDetected() {
        val spectrum = createSpectrum(44100, 20500)
        val result = service.analyzeSpectrum(spectrum, 44100)

        assertTrue(result.isFake, "20 kHz cutoff should be marked as fake")
        assertEquals(20000, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("20kHz"), "Message should mention 20kHz")
    }

    @Test
    fun test22kCutoffInHiResDetected() {
        // 96 kHz file with audio only up to 22 kHz (upscaled from 44.1 kHz CD)
        val spectrum = createSpectrum(96000, 22050)
        val result = service.analyzeSpectrum(spectrum, 96000)

        assertTrue(result.isFake, "22 kHz cutoff in 96 kHz file should be detected as fake Hi-Res")
        assertEquals(22050, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("22kHz"), "Message should mention 22kHz")
    }

    @Test
    fun test24kCutoffInHiResDetected() {
        // 96 kHz file with audio only up to 24 kHz (upscaled from 48 kHz source)
        val spectrum = createSpectrum(96000, 24000)
        val result = service.analyzeSpectrum(spectrum, 96000)

        assertTrue(result.isFake, "24 kHz cutoff in 96 kHz file should be detected as fake Hi-Res")
        assertEquals(24000, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("24kHz"), "Message should mention 24kHz")
    }

    @Test
    fun testDynamicHigherCutoffDetected() {
        // 96 kHz file cut off at 30 kHz
        val spectrum = createSpectrum(96000, 30000)
        val result = service.analyzeSpectrum(spectrum, 96000)

        assertTrue(result.isFake, "30 kHz cutoff should be detected")
        assertEquals(30000, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("30kHz"), "Message should mention 30kHz")
    }

    @Test
    fun testNearSilenceSpectrum() {
        // Spectrum with virtually inaudible levels
        val silentSpectrum = DoubleArray(numBins) { -90.0 }
        val result = service.analyzeSpectrum(silentSpectrum, 44100)

        assertFalse(result.isFake)
        assertEquals(0, result.cutoffFrequencyHz)
        assertTrue(result.message.contains("too low"), "Should indicate audio level is too low")
    }

    @Test
    fun testInvalidFile() {
        val result = service.analyzeCutoff(File("non_existent_file.flac"), false)
        assertFalse(result.isFake)
        assertEquals(0, result.cutoffFrequencyHz)
    }
}
