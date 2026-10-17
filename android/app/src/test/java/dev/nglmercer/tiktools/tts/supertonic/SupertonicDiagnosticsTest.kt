package dev.nglmercer.tiktools.tts.supertonic

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Waveform validation, Float32 → PCM16 conversion and the diagnostic WAV writer. */
class SupertonicDiagnosticsTest {

    @get:Rule val folder = TemporaryFolder()

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        signedShortLe(bytes, offset)

    /** Signed 16-bit read, matching what a decoder actually hears. */
    private fun signedShortLe(bytes: ByteArray, offset: Int): Int =
        (((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort())
            .toInt()

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    // ------------------------------------------------------------------ PCM conversion

    @Test
    fun convertsFloatSamplesToLittleEndianPcm16() {
        val pcm = SupertonicDiagnostics.toPcm16(floatArrayOf(0f, 1f, -1f, 0.5f))
        assertEquals(8, pcm.size)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, // 0.0
                0xFF.toByte(), 0x7F, // +1.0 -> 32767
                0x01, 0x80.toByte(), // -1.0 -> -32767
                0x00, 0x40, // +0.5 -> 16384
            ),
            pcm,
        )
    }

    @Test
    fun clampsOnlyAtTheIntegerBoundary() {
        val pcm = SupertonicDiagnostics.toPcm16(floatArrayOf(2f, -2f))
        assertEquals(0xFF.toByte(), pcm[0])
        assertEquals(0x7F.toByte(), pcm[1])
        assertEquals(0x00.toByte(), pcm[2])
        assertEquals(0x80.toByte(), pcm[3])
    }

    @Test
    fun nonFiniteSamplesBecomeSilenceInsteadOfFullScaleNoise() {
        val pcm =
            SupertonicDiagnostics.toPcm16(
                floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            )
        for (b in pcm) assertEquals(0.toByte(), b)
    }

    @Test
    fun preservesSampleOrderingAndMonoFrameCount() {
        val wav = floatArrayOf(0.25f, -0.25f, 0f)
        val pcm = SupertonicDiagnostics.toPcm16(wav)
        assertEquals(wav.size * 2, pcm.size)
        assertTrue(shortLe(pcm, 0) > 0)
        assertTrue(shortLe(pcm, 2) < 0)
        assertEquals(0, shortLe(pcm, 4))
    }

    // ------------------------------------------------------------------ metrics

    @Test
    fun analyzesPeakRmsClippingAndNonFiniteSamples() {
        val stats =
            SupertonicDiagnostics.analyze(
                floatArrayOf(0f, 1f, -1f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY, 1.5f),
                44_100,
            )
        assertEquals(7, stats.samples)
        assertEquals(1, stats.nanCount)
        assertEquals(1, stats.infiniteCount)
        assertEquals(1, stats.clippedCount)
        assertEquals(1.5f, stats.peak, 1e-6f)
        assertEquals(0.801784f, stats.rms, 1e-4f)
        assertEquals(7.0 / 44_100.0, stats.durationSeconds, 1e-9)
        assertFalse(stats.finite)
        assertFalse(stats.silent)
    }

    @Test
    fun detectsSilentWaveforms() {
        assertTrue(SupertonicDiagnostics.analyze(floatArrayOf(0f, 0f), 44_100).silent)
        assertFalse(SupertonicDiagnostics.analyze(floatArrayOf(0f, 0.5f), 44_100).silent)
        // An all-NaN waveform is not valid audio either.
        assertTrue(SupertonicDiagnostics.analyze(floatArrayOf(Float.NaN), 44_100).silent)
    }

    @Test
    fun emptyWaveformIsReportedAsEmpty() {
        val stats = SupertonicDiagnostics.analyze(FloatArray(0), 44_100)
        assertEquals(0, stats.samples)
        assertEquals(0f, stats.peak, 0f)
        assertTrue(stats.silent)
    }

    // ------------------------------------------------------------------ WAV export

    @Test
    fun pcm16HeaderDescribesA16BitMonoFile() {
        val header = SupertonicDiagnostics.wavHeader(8, 44_100)
        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(16, intLe(header, 16))
        assertEquals(1, shortLe(header, 20)) // PCM
        assertEquals(1, shortLe(header, 22)) // mono
        assertEquals(44_100, intLe(header, 24))
        assertEquals(16, shortLe(header, 34))
        assertEquals(8, intLe(header, 40))
        assertEquals(44, intLe(header, 4))
    }

    @Test
    fun floatHeaderMarksIeeeFloat() {
        val header = SupertonicDiagnostics.floatWavHeader(16, 44_100)
        assertEquals(3, shortLe(header, 20))
        assertEquals(32, shortLe(header, 34))
        assertEquals(16, intLe(header, 40))
        assertEquals(176_400, intLe(header, 28)) // 44100 * 4 bytes/frame
    }

    @Test
    fun writePcm16WavRoundTripsThroughTheHeader() {
        val wav = floatArrayOf(0.1f, -0.1f, 0.9f)
        val target = File(folder.root, "out.wav")
        SupertonicDiagnostics.writePcm16Wav(target, SupertonicDiagnostics.toPcm16(wav), 44_100)
        assertEquals(44 + wav.size * 2, target.readBytes().size.toInt())

        val bytes = target.readBytes()
        assertEquals(44_100, intLe(bytes, 24))
        assertEquals(wav.size * 2, intLe(bytes, 40))
        // The first sample must survive the write unchanged.
        assertEquals(shortLe(SupertonicDiagnostics.toPcm16(wav), 0), shortLe(bytes, 44))
    }

    @Test
    fun writeFloat32WavKeepsTheRawModelSamples() {
        val wav = floatArrayOf(0f, 0.25f, -0.75f, 1f)
        val target = File(folder.root, "raw.wav")
        SupertonicDiagnostics.writeFloat32Wav(target, wav, 44_100)
        val bytes = target.readBytes()
        assertEquals(44 + wav.size * 4, bytes.size)
        assertEquals(3, shortLe(bytes, 20))
        for (i in wav.indices) {
            val bits = intLe(bytes, 44 + i * 4)
            assertEquals(wav[i], java.lang.Float.intBitsToFloat(bits), 0f)
        }
    }

    @Test
    fun exportWritesBothDiagnosticFilesAtTheModelSampleRate() {
        val wav = FloatArray(1000) { 0.5f }
        val files = SupertonicDiagnostics.export(folder.root, wav, 44_100, "case")
        assertEquals(2, files.size)
        assertEquals(setOf("case-float32.wav", "case-pcm16.wav"), files.map { it.name }.toSet())
        assertEquals(44 + 1000 * 4, files.first { it.name == "case-float32.wav" }.length().toInt())
        assertEquals(44 + 1000 * 2, files.first { it.name == "case-pcm16.wav" }.length().toInt())
    }

    @Test
    fun exportOfAnEmptyWaveformWritesNothing() {
        assertTrue(SupertonicDiagnostics.export(folder.root, FloatArray(0), 44_100, "empty").isEmpty())
    }
}
