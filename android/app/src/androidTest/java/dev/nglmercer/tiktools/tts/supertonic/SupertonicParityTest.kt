package dev.nglmercer.tiktools.tts.supertonic

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reference-parity comparison against the official Python implementation.
 *
 * Fixtures come from `scripts/supertonic/parity/reference.py`, which injects a fixed noise tensor so
 * that the whole chain becomes deterministic on both sides (Kotlin `Random` and NumPy's RNG never
 * produce the same stream). Opt-in and skipped when the fixtures or model are missing.
 *
 *     python3 scripts/supertonic/parity/reference.py ... --out /tmp/parity/es
 *     adb push /tmp/parity \
 *         /sdcard/Android/data/com.example.ttlsigner/files/supertonic-parity
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.supertonicParity=true
 *
 * Tolerances are documented in `scripts/supertonic/parity/README.md`.
 */
@RunWith(AndroidJUnit4::class)
class SupertonicParityTest {

    private companion object {
        const val DURATION_TOLERANCE = 1e-3f
        const val WAVEFORM_TOLERANCE = 2e-3f
    }

    private lateinit var context: Context
    private lateinit var fixtures: File
    private var engine: SupertonicEngine? = null

    @Before
    fun requireOptInAndFixtures() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Enable with -Pandroid.testInstrumentationRunnerArguments.supertonicParity=true",
            InstrumentationRegistry.getArguments().getString("supertonicParity") == "true",
        )
        assumeTrue(
            "Supertonic model is not installed and verified",
            SupertonicModels.status(context) == SupertonicModels.Status.Ready,
        )
        fixtures =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "supertonic-parity",
            )
        assumeTrue(
            "No reference fixtures at ${fixtures.absolutePath}; see scripts/supertonic/parity/README.md",
            fixtures.isDirectory && fixtures.listFiles().orEmpty().any { it.isDirectory },
        )
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
    }

    @Test
    fun kotlinPreprocessingAndInferenceMatchThePythonReference() = runBlocking {
        val cases = fixtures.listFiles().orEmpty().filter { it.isDirectory && File(it, "meta.json").isFile }
        assertTrue("no fixture cases found", cases.isNotEmpty())

        val compared = mutableListOf<String>()
        val modelDir = SupertonicModels.dir(context)
        val processor = UnicodeProcessor(File(modelDir, "unicode_indexer.json"))
        val e = SupertonicEngine(modelDir).also { engine = it }

        for (case in cases) {
            val meta = JSONObject(File(case, "meta.json").readText())
            val label = "${case.name} (${meta.getString("lang")}, ${meta.getInt("steps")} steps)"

            // 1. Text normalization and token ids must match exactly.
            val text = meta.getString("input")
            val lang = meta.getString("lang")
            val actual = processor.process(listOf(text), listOf(lang))
            assertEquals("processed text for $label", meta.getString("processed"), actual.processed[0])
            assertArrayEquals(
                "text ids for $label",
                meta.getJSONArray("textIds").toLongArray(),
                actual.textIds[0],
            )
            assertArrayEquals(
                "text mask for $label",
                meta.getJSONArray("textMask").toFloatArray(),
                actual.textMask[0][0],
                0f,
            )

            // 2. Same injected noise on both sides makes duration and waveform comparable.
            val latentDim = meta.getInt("latentDim")
            val latentLen = meta.getInt("latentLen")
            val noise = readFloats(File(case, "noise.f32"), latentDim * latentLen)
                .let { flat -> Array(1) { Array(latentDim) { d -> flat.copyOfRange(d * latentLen, (d + 1) * latentLen) } } }
            val style = e.loadStyle(SupertonicModels.voiceFile(context, meta.getString("voice")))
            try {
                val expectedDuration = readFloats(File(case, "duration.f32"), 1)
                val expectedWav = readFloats(File(case, "wav.f32"), meta.getInt("durationSamples"))

                val result =
                    e.infer(
                        texts = listOf(text),
                        langs = listOf(lang),
                        style = style,
                        totalStep = meta.getInt("steps"),
                        speed = meta.getDouble("speed").toFloat(),
                        rng = kotlin.random.Random(meta.getInt("seed")),
                        env = OrtEnvironment.getEnvironment(),
                        injectedNoise = noise,
                    )

                assertEquals("sample rate for $label", meta.getInt("sampleRate"), e.sampleRate)
                assertEquals(
                    "predicted duration for $label",
                    expectedDuration[0],
                    result.duration[0],
                    DURATION_TOLERANCE,
                )

                val samples = minOf(expectedWav.size, result.wav.size)
                assertTrue("waveform length for $label", abs(expectedWav.size - result.wav.size) <= 1)
                var maxDiff = 0f
                for (i in 0 until samples) maxDiff = maxOf(maxDiff, abs(expectedWav[i] - result.wav[i]))
                assertTrue(
                    "waveform diverged for $label: max |Δ| = $maxDiff (tolerance $WAVEFORM_TOLERANCE)",
                    maxDiff <= WAVEFORM_TOLERANCE,
                )
                compared += label
            } finally {
                style.close()
            }
        }
        assertTrue("no cases were compared", compared.isNotEmpty())
    }

    private fun readFloats(file: File, count: Int): FloatArray {
        val bytes = file.readBytes()
        assertEquals("${file.name} byte length", count * 4, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.float }
    }

    private fun org.json.JSONArray.toLongArray(): LongArray =
        LongArray(length()) { getLong(it) }

    private fun org.json.JSONArray.toFloatArray(): FloatArray =
        FloatArray(length()) { getDouble(it).toFloat() }
}
