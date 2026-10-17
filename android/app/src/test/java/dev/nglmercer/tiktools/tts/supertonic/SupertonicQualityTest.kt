package dev.nglmercer.tiktools.tts.supertonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quality presets, speed and chunk-length validation against the reference limits. */
class SupertonicQualityTest {

    @Test
    fun presetsMatchTheReferenceSteps() {
        assertEquals(5, QualityPreset.FAST.steps)
        assertEquals(8, QualityPreset.BALANCED.steps)
        assertEquals(12, QualityPreset.HIGH.steps)
        assertEquals(8, SupertonicQuality.DEFAULT_STEPS)
    }

    @Test
    fun defaultPresetIsBalanced() {
        assertEquals(QualityPreset.BALANCED, QualityPreset.DEFAULT)
    }

    @Test
    fun customStepsOverrideThePreset() {
        assertEquals(9, SupertonicQuality.steps(QualityPreset.BALANCED, 9))
        assertEquals(8, SupertonicQuality.steps(QualityPreset.BALANCED, null))
        assertEquals(12, SupertonicQuality.steps(QualityPreset.FAST, 12))
    }

    @Test
    fun stepsOutsideTheOfficialRangeAreRejected() {
        assertEquals(1..12, SupertonicQuality.STEPS_RANGE)
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSteps(0) }
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSteps(13) }
        assertEquals(5, SupertonicQuality.validateSteps(5))
        assertEquals(12, SupertonicQuality.validateSteps(12))
    }

    @Test
    fun speedIsValidatedWithinTheReferenceRange() {
        assertEquals(0.7f, SupertonicQuality.MIN_SPEED, 0f)
        assertEquals(2.0f, SupertonicQuality.MAX_SPEED, 0f)
        assertEquals(1.05f, SupertonicQuality.DEFAULT_SPEED, 0f)
        assertEquals(1.4f, SupertonicQuality.validateSpeed(1.4f), 0f)
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSpeed(0.5f) }
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSpeed(2.5f) }
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSpeed(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicQuality.validateSpeed(Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun stepsAndSpeedAreIndependent() {
        // Changing quality must not move the reference default speed.
        assertEquals(SupertonicQuality.DEFAULT_SPEED, 1.05f, 0f)
        assertEquals(5, SupertonicQuality.steps(QualityPreset.FAST, null))
        assertEquals(12, SupertonicQuality.steps(QualityPreset.HIGH, null))
    }

    @Test
    fun chunkSilenceIsValidated() {
        assertEquals(300, SupertonicQuality.DEFAULT_SILENCE_MS)
        assertEquals(0, SupertonicQuality.validateSilence(0))
        assertEquals(1500, SupertonicQuality.validateSilence(1500))
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSilence(-1) }
        assertThrows(IllegalArgumentException::class.java) { SupertonicQuality.validateSilence(6000) }
    }

    @Test
    fun chunkLengthFallsBackToTheLanguageDefault() {
        assertEquals(120, SupertonicQuality.resolveChunkLength(null, "ja"))
        assertEquals(300, SupertonicQuality.resolveChunkLength(null, "en"))
        assertEquals(200, SupertonicQuality.resolveChunkLength(200, "en"))
        // Out-of-range explicit values fall back rather than reaching the engine.
        assertEquals(120, SupertonicQuality.resolveChunkLength(5, "ko"))
        assertEquals(300, SupertonicQuality.resolveChunkLength(100_000, "en"))
    }

    @Test
    fun settingsDeriveValidatedValuesFromPreferences() {
        val settings =
            SupertonicSettings(
                qualityPreset = QualityPreset.HIGH,
                customSteps = 7,
                speed = 1.3f,
                chunkSilenceMs = 250,
                maxSpeechChars = 140,
            )
        assertEquals(7, settings.steps)
        assertEquals(1.3f, settings.speed, 0f)
        assertEquals(0.25f, settings.silenceDuration, 1e-6f)
        assertEquals(140, settings.maxSpeechChars)
    }

    @Test
    fun settingsSanitizeGarbageFromPersistence() {
        val fromPrefs =
            SupertonicSettings.from(
                dev.nglmercer.tiktools.data.preferences.SpeechPreferences(
                    language = "not-a-language",
                    voice = "Z9",
                    speed = 99f,
                    customSteps = 99,
                    chunkSilenceMs = -5,
                    maxSpeechChars = -10,
                )
            )
        assertEquals("na", fromPrefs.language)
        assertEquals("F1", fromPrefs.resolvedVoice)
        assertEquals(1.05f, fromPrefs.speed, 0f)
        assertEquals(null, fromPrefs.customSteps)
        assertEquals(0, fromPrefs.maxSpeechChars)
        assertTrue(fromPrefs.chunkSilenceMs > 0)
    }

    @Test
    fun autoModeSendsTheUnknownFallbackTag() {
        val settings = SupertonicSettings(languageMode = LanguageMode.AUTO, language = "es")
        assertEquals(
            SupertonicLanguages.UNKNOWN,
            settings.languageFor(java.util.Locale.forLanguageTag("es-ES")),
        )
        val manual = settings.copy(languageMode = LanguageMode.MANUAL, language = "es")
        assertEquals(LanguageMode.MANUAL, manual.languageMode)
        assertEquals("es", manual.languageFor(java.util.Locale.US))
    }
}
