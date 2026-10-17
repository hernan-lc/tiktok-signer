package dev.nglmercer.tiktools.tts.supertonic

import dev.nglmercer.tiktools.data.preferences.SpeechPreferences
import java.util.Locale

/**
 * How the Supertonic language tag is chosen.
 *
 * [AUTO] sends the model's `na` **fallback** tag: Supertonic 3 conditions language-agnostically on
 * text whose language is unknown. It is *not* a language detector, and the UI must not present it as
 * one. [MANUAL] sends the user's explicit code and rejects anything unsupported.
 */
enum class LanguageMode {
    AUTO,
    MANUAL,
}

/**
 * Everything the speaker needs to synthesize, resolved from [SpeechPreferences]. Immutable and
 * thread-safe: [SupertonicSpeaker] swaps the whole value instead of mutating parts.
 */
data class SupertonicSettings(
    val languageMode: LanguageMode = LanguageMode.AUTO,
    val language: String = SupertonicLanguages.DEFAULT,
    val voice: String = SupertonicModels.DEFAULT_VOICE,
    val qualityPreset: QualityPreset = QualityPreset.DEFAULT,
    val customSteps: Int? = null,
    val speed: Float = SupertonicQuality.DEFAULT_SPEED,
    val chunkLength: Int? = null,
    val chunkSilenceMs: Int = SupertonicQuality.DEFAULT_SILENCE_MS,
    val diagnosticLogging: Boolean = false,
    val exportWav: Boolean = false,
    val maxSpeechChars: Int = SupertonicQuality.DEFAULT_MAX_CHARS,
) {
    /** Effective denoising steps, validated against the official range. */
    val steps: Int
        get() = SupertonicQuality.steps(qualityPreset, customSteps)

    /** Effective inter-chunk silence in seconds. */
    val silenceDuration: Float
        get() = SupertonicQuality.validateSilence(chunkSilenceMs) / 1000f

    /** [voice] if the file is published for this model revision, otherwise the safe default. */
    val resolvedVoice: String
        get() = if (voice in SupertonicModels.VOICES) voice else SupertonicModels.DEFAULT_VOICE

    /** The language tag to send to the model for this device locale. */
    fun languageFor(locale: Locale): String =
        when (languageMode) {
            LanguageMode.AUTO -> SupertonicLanguages.DEFAULT
            LanguageMode.MANUAL -> SupertonicLanguages.resolve(language)
        }

    companion object {
        fun from(prefs: SpeechPreferences): SupertonicSettings =
            SupertonicSettings(
                languageMode = prefs.languageMode,
                // Persisted garbage can never reach inference: invalid codes become the na fallback.
                language =
                    if (SupertonicLanguages.isValid(prefs.language)) prefs.language
                    else SupertonicLanguages.DEFAULT,
                voice = prefs.voice,
                qualityPreset = prefs.qualityPreset,
                customSteps =
                    prefs.customSteps?.let {
                        runCatching { SupertonicQuality.validateSteps(it) }.getOrNull()
                    },
                speed =
                    runCatching { SupertonicQuality.validateSpeed(prefs.speed) }
                        .getOrDefault(SupertonicQuality.DEFAULT_SPEED),
                chunkLength = prefs.chunkLength,
                chunkSilenceMs =
                    runCatching { SupertonicQuality.validateSilence(prefs.chunkSilenceMs) }
                        .getOrDefault(SupertonicQuality.DEFAULT_SILENCE_MS),
                diagnosticLogging = prefs.diagnosticLogging,
                exportWav = prefs.exportWav,
                maxSpeechChars = prefs.maxSpeechChars.coerceAtLeast(0),
            )
    }
}
