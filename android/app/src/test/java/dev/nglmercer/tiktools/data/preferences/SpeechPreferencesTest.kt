package dev.nglmercer.tiktools.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nglmercer.tiktools.tts.TtsEngine
import dev.nglmercer.tiktools.tts.supertonic.LanguageMode
import dev.nglmercer.tiktools.tts.supertonic.QualityPreset
import dev.nglmercer.tiktools.tts.supertonic.SupertonicLanguages
import dev.nglmercer.tiktools.tts.supertonic.SupertonicModels
import dev.nglmercer.tiktools.tts.supertonic.SupertonicQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence contract for the Supertonic speech settings, including the migration defaults an
 * existing installation sees after upgrading.
 */
class SpeechPreferencesTest {

    private fun speech(prefs: Preferences): SpeechPreferences =
        PreferencesRepository.decode(prefs).speech

    @Test
    fun anExistingInstallKeepsWorkingWithDocumentedDefaults() {
        val speech = speech(mutablePreferencesOf())
        assertEquals(LanguageMode.AUTO, speech.languageMode)
        assertEquals(SupertonicLanguages.DEFAULT, speech.language)
        assertEquals("na", speech.language)
        assertEquals(SupertonicModels.DEFAULT_VOICE, speech.voice)
        assertEquals("F1", speech.voice)
        assertEquals(QualityPreset.BALANCED, speech.qualityPreset)
        assertNull(speech.customSteps)
        assertEquals(SupertonicQuality.DEFAULT_SPEED, speech.speed, 0f)
        assertNull(speech.chunkLength)
        assertEquals(SupertonicQuality.DEFAULT_SILENCE_MS, speech.chunkSilenceMs)
        assertFalse(speech.diagnosticLogging)
        assertFalse(speech.exportWav)
        assertEquals(SupertonicQuality.DEFAULT_MAX_CHARS, speech.maxSpeechChars)
        assertEquals(0, speech.maxSpeechChars)
        assertTrue(speech.sanitizer.removeKnownEmotes)
        assertFalse(speech.sanitizer.removeUnicodeEmoji)
        assertTrue(speech.sanitizer.collapseWhitespace)
        assertTrue(speech.sanitizer.skipEmptyMessages)
    }

    @Test
    fun legacyBooleanUpgradesToTheDeviceEngine() {
        assertEquals(
            TtsEngine.DEVICE,
            PreferencesRepository.decode(
                    mutablePreferencesOf(booleanPreferencesKey("enabled") to true)
                )
                .engine,
        )
        assertEquals(TtsEngine.OFF, PreferencesRepository.decode(mutablePreferencesOf()).engine)
    }

    @Test
    fun everyAdvancedSettingRoundTrips() {
        val speech =
            speech(
                mutablePreferencesOf(
                    stringPreferencesKey("speech_language_mode") to "MANUAL",
                    stringPreferencesKey("speech_language") to "es",
                    stringPreferencesKey("speech_voice") to "M3",
                    stringPreferencesKey("speech_quality") to "HIGH",
                    intPreferencesKey("speech_steps") to 10,
                    floatPreferencesKey("speech_speed") to 1.4f,
                    intPreferencesKey("speech_chunk_length") to 200,
                    intPreferencesKey("speech_chunk_silence_ms") to 120,
                    booleanPreferencesKey("speech_diagnostic_logging") to true,
                    booleanPreferencesKey("speech_export_wav") to true,
                    intPreferencesKey("speech_max_chars") to 140,
                    booleanPreferencesKey("speech_emote_remove_known") to false,
                    booleanPreferencesKey("speech_emote_remove_unicode") to true,
                    booleanPreferencesKey("speech_emote_collapse") to false,
                    booleanPreferencesKey("speech_emote_skip_empty") to false,
                )
            )
        assertEquals(LanguageMode.MANUAL, speech.languageMode)
        assertEquals("es", speech.language)
        assertEquals("M3", speech.voice)
        assertEquals(QualityPreset.HIGH, speech.qualityPreset)
        assertEquals(10, speech.customSteps)
        assertEquals(1.4f, speech.speed, 0f)
        assertEquals(200, speech.chunkLength)
        assertEquals(120, speech.chunkSilenceMs)
        assertTrue(speech.diagnosticLogging)
        assertTrue(speech.exportWav)
        assertEquals(140, speech.maxSpeechChars)
        assertFalse(speech.sanitizer.removeKnownEmotes)
        assertTrue(speech.sanitizer.removeUnicodeEmoji)
        assertFalse(speech.sanitizer.collapseWhitespace)
        assertFalse(speech.sanitizer.skipEmptyMessages)
    }

    @Test
    fun corruptValuesNeverReachInference() {
        val speech =
            speech(
                mutablePreferencesOf(
                    stringPreferencesKey("speech_language") to "xx",
                    stringPreferencesKey("speech_voice") to "Z9",
                    stringPreferencesKey("speech_quality") to "NOPE",
                    intPreferencesKey("speech_steps") to 99,
                    floatPreferencesKey("speech_speed") to 9f,
                    intPreferencesKey("speech_chunk_length") to 5,
                    intPreferencesKey("speech_chunk_silence_ms") to -20,
                    intPreferencesKey("speech_max_chars") to -5,
                )
            )
        assertEquals(SupertonicLanguages.DEFAULT, speech.language)
        assertEquals(SupertonicModels.DEFAULT_VOICE, speech.voice)
        assertEquals(QualityPreset.DEFAULT, speech.qualityPreset)
        assertNull(speech.customSteps)
        assertEquals(SupertonicQuality.DEFAULT_SPEED, speech.speed, 0f)
        assertNull(speech.chunkLength)
        assertEquals(SupertonicQuality.DEFAULT_SILENCE_MS, speech.chunkSilenceMs)
        assertEquals(0, speech.maxSpeechChars)
    }

    @Test
    fun theUnlimitedSentinelDecodesBackToNull() {
        // `-1` is what setSpeech writes for "no explicit value".
        assertEquals(-1, PreferencesRepository.UNLIMITED)
        val speech =
            speech(
                mutablePreferencesOf(
                    intPreferencesKey("speech_steps") to PreferencesRepository.UNLIMITED,
                    intPreferencesKey("speech_chunk_length") to PreferencesRepository.UNLIMITED,
                )
            )
        assertNull(speech.customSteps)
        assertNull(speech.chunkLength)
    }
}
