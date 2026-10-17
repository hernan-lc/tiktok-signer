package dev.nglmercer.tiktools.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.nglmercer.tiktools.core.model.*
import dev.nglmercer.tiktools.data.rewards.PointsConfig
import dev.nglmercer.tiktools.tts.SanitizerSettings
import dev.nglmercer.tiktools.tts.TtsEngine
import dev.nglmercer.tiktools.tts.supertonic.LanguageMode
import dev.nglmercer.tiktools.tts.supertonic.QualityPreset
import dev.nglmercer.tiktools.tts.supertonic.SupertonicLanguages
import dev.nglmercer.tiktools.tts.supertonic.SupertonicModels
import dev.nglmercer.tiktools.tts.supertonic.SupertonicQuality
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

private val Context.studioPreferences by
    preferencesDataStore(
        name = "studio",
        produceMigrations = { context ->
            listOf("tiktools_tts", "tiktools_points", "tiktools_events_display").map {
                SharedPreferencesMigration(context, it)
            }
        },
    )

class PreferencesRepository(
    context: Context,
    scope: CoroutineScope,
    private val store: DataStore<Preferences> = context.applicationContext.studioPreferences,
) {
    val values =
        store.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map(::decode)
    val state = values.stateIn(scope, SharingStarted.Eagerly, UserPreferences())

    suspend fun setEngine(engine: TtsEngine) =
        store.edit { it[stringPreferencesKey("engine")] = engine.name }

    suspend fun setSpeech(p: SpeechPreferences) =
        store.edit {
            it[booleanPreferencesKey("speech_chat")] = p.chat
            it[booleanPreferencesKey("speech_gifts")] = p.gifts
            it[booleanPreferencesKey("speech_follows")] = p.follows
            it[booleanPreferencesKey("speech_shares")] = p.shares
            it[booleanPreferencesKey("joins")] = p.joins
            it[stringPreferencesKey("speech_chat_name_mode")] = p.chatNameMode.name
            it[stringPreferencesKey("speech_chat_prefix")] = p.chatPrefix
            it[stringPreferencesKey("speech_language_mode")] = p.languageMode.name
            it[stringPreferencesKey("speech_language")] = p.language
            it[stringPreferencesKey("speech_voice")] = p.voice
            it[stringPreferencesKey("speech_quality")] = p.qualityPreset.name
            it[intPreferencesKey("speech_steps")] = p.customSteps ?: UNLIMITED
            it[floatPreferencesKey("speech_speed")] = p.speed
            it[intPreferencesKey("speech_chunk_length")] = p.chunkLength ?: UNLIMITED
            it[intPreferencesKey("speech_chunk_silence_ms")] = p.chunkSilenceMs
            it[booleanPreferencesKey("speech_diagnostic_logging")] = p.diagnosticLogging
            it[booleanPreferencesKey("speech_export_wav")] = p.exportWav
            it[intPreferencesKey("speech_max_chars")] = p.maxSpeechChars
            it[booleanPreferencesKey("speech_emote_remove_known")] = p.sanitizer.removeKnownEmotes
            it[booleanPreferencesKey("speech_emote_remove_unicode")] = p.sanitizer.removeUnicodeEmoji
            it[booleanPreferencesKey("speech_emote_collapse")] = p.sanitizer.collapseWhitespace
            it[booleanPreferencesKey("speech_emote_skip_empty")] = p.sanitizer.skipEmptyMessages
        }

    suspend fun setRewards(p: PointsConfig) =
        store.edit { it[stringPreferencesKey("config")] = PointsConfig.encode(p.normalize()) }

    suspend fun setDisplay(p: EventDisplayConfig) =
        store.edit {
            it[booleanPreferencesKey("compact")] = p.compact
            it[booleanPreferencesKey("badge")] = p.showBadge
            it[booleanPreferencesKey("time")] = p.showTime
            it[booleanPreferencesKey("single_line")] = p.singleLine
        }

    suspend fun setFilter(filter: EventFilter) =
        store.edit {
            it[stringSetPreferencesKey("filter_categories")] =
                filter.enabled.map { c -> c.name }.toSet()
            it[stringPreferencesKey("filter_query")] = filter.query
        }

    suspend fun setReconnect(enabled: Boolean) =
        store.edit { it[booleanPreferencesKey("reconnect")] = enabled }

    suspend fun setBackground(enabled: Boolean) =
        store.edit { it[booleanPreferencesKey("background")] = enabled }

    suspend fun setTheme(mode: ThemeMode) =
        store.edit { it[stringPreferencesKey("theme")] = mode.name }

    suspend fun rememberCreator(id: String) =
        store.edit {
            val previous =
                it[stringPreferencesKey("recent_creators")]
                    .orEmpty()
                    .split(",")
                    .filter(String::isNotBlank)
            it[stringPreferencesKey("recent_creators")] =
                (listOf(id) + previous.filter { name -> name != id }).take(8).joinToString(",")
        }

    companion object {
        /** Sentinel for "no explicit value" in the integer-backed optional speech settings. */
        internal const val UNLIMITED = -1

        internal fun decode(p: Preferences): UserPreferences {
            fun bool(name: String, fallback: Boolean) = p[booleanPreferencesKey(name)] ?: fallback
            fun string(name: String) = p[stringPreferencesKey(name)]
            val engine =
                string("engine")?.let { runCatching { TtsEngine.valueOf(it) }.getOrNull() }
                    ?: if (bool("enabled", false)) TtsEngine.DEVICE else TtsEngine.OFF
            return UserPreferences(
                engine = engine,
                speech =
                    SpeechPreferences(
                        chat = bool("speech_chat", true),
                        gifts = bool("speech_gifts", true),
                        follows = bool("speech_follows", true),
                        shares = bool("speech_shares", true),
                        joins = bool("joins", false),
                        chatNameMode =
                            string("speech_chat_name_mode")?.let {
                                runCatching { ChatNameMode.valueOf(it) }.getOrNull()
                            } ?: ChatNameMode.ALWAYS,
                        chatPrefix = string("speech_chat_prefix").orEmpty(),
                        // Upgrades default to Auto/na: unknown-language input keeps working, and the
                        // previous build never sent a language tag at all.
                        languageMode =
                            string("speech_language_mode")?.let {
                                runCatching { LanguageMode.valueOf(it) }.getOrNull()
                            } ?: LanguageMode.AUTO,
                        language =
                            string("speech_language")
                                ?.takeIf { SupertonicLanguages.isValid(it) }
                                ?: SupertonicLanguages.DEFAULT,
                        voice =
                            string("speech_voice")
                                ?.takeIf { it in SupertonicModels.VOICES }
                                ?: SupertonicModels.DEFAULT_VOICE,
                        qualityPreset =
                            string("speech_quality")?.let {
                                runCatching { QualityPreset.valueOf(it) }.getOrNull()
                            } ?: QualityPreset.DEFAULT,
                        customSteps =
                            p[intPreferencesKey("speech_steps")]
                                ?.takeIf { it in SupertonicQuality.STEPS_RANGE },
                        speed =
                            p[floatPreferencesKey("speech_speed")]
                                ?.takeIf { it in SupertonicQuality.MIN_SPEED..SupertonicQuality.MAX_SPEED }
                                ?: SupertonicQuality.DEFAULT_SPEED,
                        chunkLength =
                            p[intPreferencesKey("speech_chunk_length")]
                                ?.takeIf { it in SupertonicQuality.CHUNK_LENGTH_RANGE },
                        chunkSilenceMs =
                            p[intPreferencesKey("speech_chunk_silence_ms")]
                                ?.takeIf { it >= 0 }
                                ?: SupertonicQuality.DEFAULT_SILENCE_MS,
                        diagnosticLogging = bool("speech_diagnostic_logging", false),
                        exportWav = bool("speech_export_wav", false),
                        maxSpeechChars =
                            p[intPreferencesKey("speech_max_chars")]?.coerceAtLeast(0)
                                ?: SupertonicQuality.DEFAULT_MAX_CHARS,
                        // Emote cleanup is on by default for existing installs: a shortcode token
                        // spoken aloud is noise, and unknown bracketed text is preserved anyway.
                        sanitizer =
                            SanitizerSettings(
                                removeKnownEmotes = bool("speech_emote_remove_known", true),
                                removeUnicodeEmoji = bool("speech_emote_remove_unicode", false),
                                collapseWhitespace = bool("speech_emote_collapse", true),
                                skipEmptyMessages = bool("speech_emote_skip_empty", true),
                            ),
                    ),
                rewards =
                    string("config")?.let {
                        runCatching { PointsConfig.decode(it).normalize() }.getOrNull()
                    } ?: PointsConfig(),
                display =
                    EventDisplayConfig(
                        bool("compact", true),
                        bool("badge", true),
                        bool("time", true),
                        bool("single_line", true),
                    ),
                filter =
                    EventFilter(
                        p[stringSetPreferencesKey("filter_categories")]
                            ?.mapNotNull {
                                runCatching { LiveEvent.Category.valueOf(it) }.getOrNull()
                            }
                            ?.toSet() ?: LiveEvent.Category.entries.toSet(),
                        string("filter_query").orEmpty(),
                    ),
                reconnect = bool("reconnect", true),
                backgroundConnection = bool("background", true),
                theme =
                    string("theme")?.let {
                        runCatching { ThemeMode.valueOf(it) }.getOrNull()
                    } ?: ThemeMode.SYSTEM,
                recentCreators =
                    string("recent_creators").orEmpty().split(",").filter(String::isNotBlank),
            )
        }
    }
}
