package dev.nglmercer.tiktools.data.preferences

import dev.nglmercer.tiktools.core.model.EventDisplayConfig
import dev.nglmercer.tiktools.core.model.EventFilter
import dev.nglmercer.tiktools.data.rewards.PointsConfig
import dev.nglmercer.tiktools.tts.SanitizerSettings
import dev.nglmercer.tiktools.tts.TtsEngine
import dev.nglmercer.tiktools.tts.supertonic.LanguageMode
import dev.nglmercer.tiktools.tts.supertonic.QualityPreset
import dev.nglmercer.tiktools.tts.supertonic.SupertonicLanguages
import dev.nglmercer.tiktools.tts.supertonic.SupertonicModels
import dev.nglmercer.tiktools.tts.supertonic.SupertonicQuality

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class SpeechPreferences(
    val chat: Boolean = true,
    val gifts: Boolean = true,
    val follows: Boolean = true,
    val shares: Boolean = true,
    val joins: Boolean = false,
    val chatNameMode: ChatNameMode = ChatNameMode.ALWAYS,
    val chatPrefix: String = "",
    /** `AUTO` sends the model's `na` fallback; `MANUAL` sends [language]. */
    val languageMode: LanguageMode = LanguageMode.AUTO,
    val language: String = SupertonicLanguages.DEFAULT,
    /** Supertonic voice style; F1 is the default so existing users hear no change. */
    val voice: String = SupertonicModels.DEFAULT_VOICE,
    val qualityPreset: QualityPreset = QualityPreset.DEFAULT,
    /** Overrides [qualityPreset] when set; validated against the official step range. */
    val customSteps: Int? = null,
    val speed: Float = SupertonicQuality.DEFAULT_SPEED,
    /** Per-chunk character budget; `null` uses the language default (120 for ko/ja, 300 otherwise). */
    val chunkLength: Int? = null,
    val chunkSilenceMs: Int = SupertonicQuality.DEFAULT_SILENCE_MS,
    val diagnosticLogging: Boolean = false,
    val exportWav: Boolean = false,
    /** LIVE speech-length cap in characters; `0` speaks everything (the default). */
    val maxSpeechChars: Int = SupertonicQuality.DEFAULT_MAX_CHARS,
    /** How a chat comment is cleaned before speech; applies to every engine. */
    val sanitizer: SanitizerSettings = SanitizerSettings(),
)

enum class ChatNameMode {
    ALWAYS,
    WHEN_CHANGED,
    NEVER,
}

data class UserPreferences(
    val engine: TtsEngine = TtsEngine.OFF,
    val speech: SpeechPreferences = SpeechPreferences(),
    val rewards: PointsConfig = PointsConfig(),
    val display: EventDisplayConfig = EventDisplayConfig(),
    val filter: EventFilter = EventFilter(),
    val reconnect: Boolean = true,
    val backgroundConnection: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val recentCreators: List<String> = emptyList(),
)
