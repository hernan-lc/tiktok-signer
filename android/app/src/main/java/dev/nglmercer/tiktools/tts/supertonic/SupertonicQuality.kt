package dev.nglmercer.tiktools.tts.supertonic

/**
 * Quality presets. The reference default is 8 steps (`py/example_onnx.py`: `--total-step` default 8,
 * documented range 5 low → 12 high). [HIGH] is an optional experiment: more steps cost more CPU and
 * do **not** necessarily improve perceived quality, so it is never selected silently.
 */
enum class QualityPreset(val steps: Int) {
    FAST(5),
    BALANCED(8),
    HIGH(12),
    ;

    companion object {
        val DEFAULT: QualityPreset = BALANCED

        fun fromSteps(steps: Int): QualityPreset? = entries.firstOrNull { it.steps == steps }
    }
}

/**
 * Validation and defaults shared by the engine, the speaker and the settings screen. Kept free of
 * Android types so it is unit-testable on the JVM.
 */
object SupertonicQuality {

    /** Official supported denoising-step range. */
    val STEPS_RANGE: IntRange = 1..12

    const val DEFAULT_STEPS: Int = 8

    /** Reference default speed (`py/example_onnx.py`). */
    const val DEFAULT_SPEED: Float = 1.05f

    /** Reference-supported speed range. */
    const val MIN_SPEED: Float = 0.7f
    const val MAX_SPEED: Float = 2.0f

    /** Reference silence between chunks, in milliseconds. */
    const val DEFAULT_SILENCE_MS: Int = 300

    const val DEFAULT_MAX_CHARS: Int = 0

    private const val MIN_SILENCE_MS = 0
    private const val MAX_SILENCE_MS = 5_000

    private const val MIN_CHUNK_LENGTH = 20
    private const val MAX_CHUNK_LENGTH = 1_000

    /** Resolve the effective step count: an explicit custom value wins, otherwise the preset. */
    fun steps(preset: QualityPreset, customSteps: Int?): Int =
        validateSteps(customSteps ?: preset.steps)

    fun validateSteps(steps: Int): Int {
        require(steps in STEPS_RANGE) {
            "Inference steps must be in $STEPS_RANGE, was $steps"
        }
        return steps
    }

    fun validateSpeed(speed: Float): Float {
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED) {
            "Speech speed must be in $MIN_SPEED..$MAX_SPEED, was $speed"
        }
        return speed
    }

    fun validateSilence(ms: Int): Int {
        require(ms in MIN_SILENCE_MS..MAX_SILENCE_MS) {
            "Chunk silence must be in $MIN_SILENCE_MS..$MAX_SILENCE_MS ms, was $ms"
        }
        return ms
    }

    /** `null` (or out-of-range) chunk length falls back to the language default. */
    fun resolveChunkLength(requested: Int?, lang: String): Int {
        val value = requested ?: return SupertonicText.chunkLengthFor(lang)
        return if (value in MIN_CHUNK_LENGTH..MAX_CHUNK_LENGTH) value
        else SupertonicText.chunkLengthFor(lang)
    }

    val CHUNK_LENGTH_RANGE: IntRange = MIN_CHUNK_LENGTH..MAX_CHUNK_LENGTH
}
