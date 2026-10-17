package dev.nglmercer.tiktools.tts.supertonic

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device inference smoke tests against the **real** Supertonic 3 model files.
 *
 * Opt-in, twice over:
 *  - the `supertonicIntegration` instrumentation argument must be `true`, and
 *  - the verified model must already be installed in the app's `files/supertonic3/`.
 *
 * Ordinary JVM unit tests never touch these ~400 MB assets.
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.supertonicIntegration=true
 *
 * These assertions check structural correctness (rate, length, finiteness, non-silence) and exercise
 * reload/shutdown. Perceptual quality is a listening check — see `scripts/supertonic/parity/README.md`.
 */
@RunWith(AndroidJUnit4::class)
class SupertonicInferenceTest {

    private lateinit var context: Context
    private var engine: SupertonicEngine? = null

    @Before
    fun requireVerifiedModel() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Enable with -Pandroid.testInstrumentationRunnerArguments.supertonicIntegration=true",
            InstrumentationRegistry.getArguments().getString("supertonicIntegration") == "true",
        )
        assumeTrue(
            "Supertonic model is not installed and verified; download it from the Speech screen first",
            SupertonicModels.status(context) == SupertonicModels.Status.Ready,
        )
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
    }

    private fun openEngine(): SupertonicEngine = SupertonicEngine(SupertonicModels.dir(context)).also { engine = it }

    private fun loadVoice(name: String): SupertonicStyle =
        openEngine().loadStyle(SupertonicModels.voiceFile(context, name))

    private fun assertPlausibleWaveform(wav: FloatArray, sampleRate: Int, duration: Float) {
        assertEquals(44_100, sampleRate)
        assertTrue("empty waveform", wav.isNotEmpty())
        assertTrue("waveform has non-finite samples", wav.all { it.isFinite() })
        val stats = SupertonicDiagnostics.analyze(wav, sampleRate)
        assertTrue("waveform is silent", !stats.silent)
        assertEquals("no NaN samples expected", 0, stats.nanCount)
        assertEquals("no infinite samples expected", 0, stats.infiniteCount)
        // The engine trims to the predicted duration, so the length must track it closely.
        val expected = (duration * sampleRate).toInt()
        assertTrue(
            "length ${wav.size} is not close to the predicted $expected samples",
            abs(wav.size - expected) <= sampleRate / 20,
        )
        assertTrue("peak ${stats.peak} is implausibly large", stats.peak < 4f)
    }

    @Test
    fun synthesizesSpanishEnglishJapaneseAndTheFallback() = runBlocking {
        val e = openEngine()
        val style = e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        try {
            for ((lang, text) in
                listOf(
                    "es" to "Hola, ¿cómo estás?",
                    "en" to "Hello, welcome to my livestream.",
                    "ja" to "こんにちは、今日はいい天気ですね。",
                    "na" to "Mixed language input 123.",
                )) {
                val result = e.synthesize(text = text, lang = lang, style = style, totalStep = 8)
                assertEquals(lang, result.lang)
                assertEquals(SupertonicModels.DEFAULT_VOICE, result.voice)
                assertPlausibleWaveform(result.wav, result.sampleRate, result.duration.first())
                assertEquals("short phrase must be a single chunk", 1, result.chunkCount)
            }
        } finally {
            style.close()
        }
    }

    @Test
    fun repeatedInferenceIsStableAndDoesNotDrift() = runBlocking {
        val e = openEngine()
        val style = e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        try {
            val lengths = mutableListOf<Int>()
            repeat(3) {
                val result = e.synthesize(text = "Thank you for following!", lang = "en", style = style)
                assertPlausibleWaveform(result.wav, result.sampleRate, result.duration.first())
                lengths += result.wav.size
            }
            // The predicted duration is deterministic, so the trimmed length must be too.
            assertEquals(1, lengths.toSet().size)
        } finally {
            style.close()
        }
    }

    @Test
    fun voicesAreInterchangeableWithoutReloadingTheModel() = runBlocking {
        val e = openEngine()
        val f1 = e.loadStyle(SupertonicModels.voiceFile(context, "F1"))
        try {
            assumeTrue("M1 voice style is not downloaded", SupertonicModels.isVoiceDownloaded(context, "M1"))
            val m1 = e.loadStyle(SupertonicModels.voiceFile(context, "M1"))
            try {
                assertTrue("styles must share the model sessions", e.isCompatible(f1, m1))
                val female = e.synthesize(text = "Hello there.", lang = "en", style = f1)
                val male = e.synthesize(text = "Hello there.", lang = "en", style = m1)
                assertPlausibleWaveform(female.wav, female.sampleRate, female.duration.first())
                assertPlausibleWaveform(male.wav, male.sampleRate, male.duration.first())
                assertEquals("F1", female.voice)
                assertEquals("M1", male.voice)
            } finally {
                m1.close()
            }
        } finally {
            f1.close()
        }
    }

    @Test
    fun longerTextIsChunkedRatherThanTruncated() = runBlocking {
        val e = openEngine()
        val style = e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        try {
            val text =
                "This is the first sentence of a longer announcement. " +
                    "Here is a second sentence that should be spoken too. " +
                    "And a third one to force at least two chunks."
            val result = e.synthesize(text = text, lang = "en", style = style, maxChunkLength = 60)
            assertTrue("expected multiple chunks, got ${result.chunkCount}", result.chunkCount >= 2)
            assertPlausibleWaveform(result.wav, result.sampleRate, result.duration.first())
        } finally {
            style.close()
        }
    }

    @Test
    fun engineReloadAndShutdownReleaseResourcesSafely() = runBlocking {
        var e = openEngine()
        val style = e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        val first = e.synthesize(text = "First pass.", lang = "en", style = style)
        style.close()
        e.close()
        engine = null
        assertTrue(first.wav.isNotEmpty())

        // A closed engine must refuse work rather than touch a released session.
        assertThrows(IllegalStateException::class.java) {
            e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        }

        // Reload from disk and confirm a fresh engine still works.
        e = openEngine()
        val reloaded = e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        try {
            val second = e.synthesize(text = "Second pass.", lang = "en", style = reloaded)
            assertPlausibleWaveform(second.wav, second.sampleRate, second.duration.first())
        } finally {
            reloaded.close()
        }
    }

    @Test
    fun qualityPresetsProducePlausibleAudio() = runBlocking {
        val e = openEngine()
        val style = e.loadStyle(SupertonicModels.voiceFile(context, SupertonicModels.DEFAULT_VOICE))
        try {
            for (preset in QualityPreset.entries) {
                val result =
                    e.synthesize(
                        text = "Comparing inference quality.",
                        lang = "en",
                        style = style,
                        totalStep = preset.steps,
                    )
                assertPlausibleWaveform(result.wav, result.sampleRate, result.duration.first())
            }
        } finally {
            style.close()
        }
    }
}
