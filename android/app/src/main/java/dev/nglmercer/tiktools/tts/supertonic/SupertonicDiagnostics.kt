package dev.nglmercer.tiktools.tts.supertonic

import dev.nglmercer.tiktools.core.diagnostics.Logger
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Waveform validation, Float32 → PCM16 conversion and WAV export.
 *
 * The conversion is deliberately minimal: it only clamps at the final integer boundary and never
 * normalises or applies gain, so the model's natural output reaches the speaker unchanged. Exported
 * WAVs use the model's own sample rate so a developer can tell whether distortion originates in
 * inference (the exported waveform is already distorted) or in playback (the waveform is clean).
 */
object SupertonicDiagnostics {

    const val DIAG_DIR = "supertonic-diag"

    data class WaveformStats(
        val samples: Int,
        val sampleRate: Int,
        val durationSeconds: Double,
        val peak: Float,
        val rms: Float,
        val nanCount: Int,
        val infiniteCount: Int,
        /** Float samples outside [-1, 1] that will clip when converted. */
        val clippedCount: Int,
        val silent: Boolean,
    ) {
        val finite: Boolean
            get() = nanCount == 0 && infiniteCount == 0
    }

    fun analyze(wav: FloatArray, sampleRate: Int): WaveformStats {
        var peak = 0f
        var sumSquares = 0.0
        var nan = 0
        var infinite = 0
        var clipped = 0
        for (v in wav) {
            when {
                v.isNaN() -> {
                    nan++
                    continue
                }
                v.isInfinite() -> {
                    infinite++
                    continue
                }
            }
            val magnitude = if (v < 0f) -v else v
            if (magnitude > peak) peak = magnitude
            if (magnitude > 1f) clipped++
            sumSquares += v.toDouble() * v.toDouble()
        }
        val rms = if (wav.isEmpty()) 0f else kotlin.math.sqrt(sumSquares / wav.size).toFloat()
        return WaveformStats(
            samples = wav.size,
            sampleRate = sampleRate,
            durationSeconds = if (sampleRate > 0) wav.size.toDouble() / sampleRate else 0.0,
            peak = peak,
            rms = rms,
            nanCount = nan,
            infiniteCount = infinite,
            clippedCount = clipped,
            silent = peak == 0f || !peak.isFinite(),
        )
    }

    /**
     * Little-endian mono PCM16. Only the final integer step clamps; NaN/Inf become 0 so a single bad
     * sample can never turn into full-scale noise.
     */
    fun toPcm16(wav: FloatArray): ByteArray {
        val out = ByteArray(wav.size * 2)
        for (i in wav.indices) {
            val sample = wav[i]
            val value =
                if (sample.isFinite()) Math.round(sample * 32_767f).coerceIn(-32_768, 32_767) else 0
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Standard 44-byte RIFF/WAVE header for 16-bit mono PCM at [sampleRate]. */
    fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44)
        out.writeAscii("RIFF")
        out.writeIntLe(36 + dataBytes)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(bitsPerSample)
        out.writeAscii("data")
        out.writeIntLe(dataBytes)
        return out.toByteArray()
    }

    /** 32-bit IEEE-float WAV header, used for the raw model waveform export. */
    fun floatWavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 32
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44)
        out.writeAscii("RIFF")
        out.writeIntLe(36 + dataBytes)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeIntLe(16)
        out.writeShortLe(3)
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(bitsPerSample)
        out.writeAscii("data")
        out.writeIntLe(dataBytes)
        return out.toByteArray()
    }

    fun writePcm16Wav(file: File, pcm16: ByteArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(wavHeader(pcm16.size, sampleRate))
            out.write(pcm16)
        }
    }

    fun writeFloat32Wav(file: File, wav: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val data = ByteArray(wav.size * 4)
        var index = 0
        for (v in wav) {
            val bits = java.lang.Float.floatToIntBits(if (v.isFinite()) v else 0f)
            data[index++] = (bits and 0xFF).toByte()
            data[index++] = ((bits shr 8) and 0xFF).toByte()
            data[index++] = ((bits shr 16) and 0xFF).toByte()
            data[index++] = ((bits shr 24) and 0xFF).toByte()
        }
        file.outputStream().use { out ->
            out.write(floatWavHeader(data.size, sampleRate))
            out.write(data)
        }
    }

    /**
     * Write both diagnostic files for one utterance: the raw Float32 waveform and the PCM16 audio that
     * was actually played. Returns the created files (empty when disabled).
     */
    fun export(
        directory: File,
        wav: FloatArray,
        sampleRate: Int,
        tag: String,
    ): List<File> {
        if (wav.isEmpty()) return emptyList()
        return runCatching {
                val raw = File(directory, "${tag}-float32.wav")
                val pcm = File(directory, "${tag}-pcm16.wav")
                writeFloat32Wav(raw, wav, sampleRate)
                writePcm16Wav(pcm, toPcm16(wav), sampleRate)
                listOf(raw, pcm)
            }
            .onFailure { Logger.append("TTS", "WAV export failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
