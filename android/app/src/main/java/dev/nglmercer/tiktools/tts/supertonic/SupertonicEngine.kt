package dev.nglmercer.tiktools.tts.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SuperTonic 3 on-device inference: four ONNX Runtime CPU sessions over a [modelDir] holding
 * `duration_predictor.onnx`, `text_encoder.onnx`, `vector_estimator.onnx`, `vocoder.onnx`,
 * `tts.json`, and `unicode_indexer.json` from `Supertone/supertonic-3`.
 *
 * The inference path mirrors the official reference (`py/helper.py`, `TextToSpeech`):
 * language-tagged text ids → duration predictor (scaled by `speed`) → text encoder → masked
 * Gaussian latent → `totalStep` vector-estimator iterations → vocoder. The vocoder emits whole
 * latent frames, so every chunk is trimmed to its predicted duration exactly like
 * `py/example_onnx.py`; long input is split into sentence chunks joined by `silenceDuration`.
 *
 * Sessions are heavy (~1 GB RAM while loaded); callers keep one engine and share it across voices.
 * A closed engine throws instead of reaching into a released session.
 */
class SupertonicEngine(
    private val modelDir: File,
    env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS,
) : AutoCloseable {
    private val config: SupertonicConfig = loadConfig(modelDir)
    val sampleRate: Int
        get() = config.sampleRate

    /** `tts_version` from the installed `tts.json`, used for compatibility checks. */
    val version: String
        get() = config.version

    val name: String = "Supertonic-3"
    val provider: String = "CPU"

    private val textProcessor = UnicodeProcessor(File(modelDir, "unicode_indexer.json"))
    private val sessionOptions: OrtSession.SessionOptions
    private val dpSession: OrtSession
    private val textEncSession: OrtSession
    private val vectorEstSession: OrtSession
    private val vocoderSession: OrtSession
    private val closeLock = Any()

    @Volatile private var closed = false

    init {
        val opts =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (intraOpThreads > 0) setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(1)
            }
        sessionOptions = opts
        dpSession = env.createSession(File(modelDir, "duration_predictor.onnx").absolutePath, opts)
        textEncSession = env.createSession(File(modelDir, "text_encoder.onnx").absolutePath, opts)
        vectorEstSession =
            env.createSession(File(modelDir, "vector_estimator.onnx").absolutePath, opts)
        vocoderSession = env.createSession(File(modelDir, "vocoder.onnx").absolutePath, opts)
    }

    /** Load `voice_styles/<name>.json`, validating that its shapes match this model package. */
    fun loadStyle(file: File, env: OrtEnvironment = OrtEnvironment.getEnvironment()): SupertonicStyle {
        ensureOpen()
        val style = loadSupertonicStyle(file, env)
        val expectedTtlDim = config.latentDimTotal.toLong()
        if (style.ttlShape[1] != expectedTtlDim) {
            style.close()
            throw IllegalStateException(
                "Voice style ${style.name} is incompatible: style_ttl[1]=${style.ttlShape[1]} " +
                    "but this model expects $expectedTtlDim"
            )
        }
        return style
    }

    /** True when two styles can be swapped without reloading the model sessions. */
    fun isCompatible(base: SupertonicStyle, other: SupertonicStyle): Boolean =
        base.ttlShape == other.ttlShape && base.dpShape == other.dpShape

    /**
     * Synthesize [text] in [lang], chunking sentence-aware and joining chunks with [silenceDuration]
     * seconds of silence.
     *
     * @param onChunk invoked before each chunk; returning `false` aborts (the result is discarded).
     */
    suspend fun synthesize(
        text: String,
        lang: String,
        style: SupertonicStyle,
        totalStep: Int = SupertonicQuality.DEFAULT_STEPS,
        speed: Float = SupertonicQuality.DEFAULT_SPEED,
        maxChunkLength: Int = SupertonicQuality.resolveChunkLength(null, lang),
        silenceDuration: Float = SupertonicQuality.DEFAULT_SILENCE_MS / 1000f,
        maxChars: Int = SupertonicQuality.DEFAULT_MAX_CHARS,
        rng: Random = Random.Default,
        env: OrtEnvironment = OrtEnvironment.getEnvironment(),
        onChunk: (index: Int, total: Int) -> Boolean = { _, _ -> true },
    ): SupertonicResult =
        withContext(Dispatchers.Default) {
            ensureOpen()
            val steps = SupertonicQuality.validateSteps(totalStep)
            val effectiveSpeed = SupertonicQuality.validateSpeed(speed)
            val resolvedLang = SupertonicLanguages.resolve(lang)
            val chunks = SupertonicText.plan(text, maxChunkLength, maxChars)
            require(chunks.isNotEmpty()) { "No speakable text" }

            val silenceSamples =
                (silenceDuration.coerceAtLeast(0f) * config.sampleRate).toInt().coerceAtLeast(0)
            val startedAt = System.nanoTime()

            var wavCat: FloatArray? = null
            var totalDuration = 0f
            var timings = SupertonicTimings()

            chunks.forEachIndexed { index, chunk ->
                if (!onChunk(index, chunks.size)) throw SpeechSupersededException()
                val inferred =
                    infer(listOf(chunk), listOf(resolvedLang), style, steps, effectiveSpeed, rng, env, null)
                val trimmed = trimToDuration(inferred.wav, inferred.duration[0])
                wavCat = if (wavCat == null) trimmed else concatenate(wavCat, silenceSamples, trimmed)
                totalDuration += inferred.duration[0] + if (index == 0) 0f else silenceDuration
                timings += inferred.timings
            }

            SupertonicResult(
                wav = wavCat ?: FloatArray(0),
                duration = floatArrayOf(totalDuration),
                sampleRate = config.sampleRate,
                timings = timings.copy(totalMs = elapsedMs(startedAt)),
                chunkCount = chunks.size,
                lang = resolvedLang,
                voice = style.name,
            )
        }

    internal class InferResult(
        val wav: FloatArray,
        val duration: FloatArray,
        val timings: SupertonicTimings,
    )

    /**
     * One batch through the four sessions — the reference `TextToSpeech._infer`.
     *
     * @param injectedNoise optional pre-generated `randn` tensor `[bsz][latentDim][latentLen]` used by
     *   the reference-parity harness. It is masked exactly like freshly sampled noise.
     */
    internal fun infer(
        texts: List<String>,
        langs: List<String>,
        style: SupertonicStyle,
        totalStep: Int,
        speed: Float,
        rng: Random,
        env: OrtEnvironment,
        injectedNoise: Array<Array<FloatArray>>?,
    ): InferResult {
        ensureOpen()
        val bsz = texts.size
        require(bsz == 1) { "Supertonic voice styles are single-speaker; got $bsz texts" }

        val totalStart = System.nanoTime()

        val preprocessStart = System.nanoTime()
        val textResult = textProcessor.process(texts, langs)
        val preprocessMs = elapsedMs(preprocessStart)

        val textIdsTensor = createLongTensor(textResult.textIds, env)
        val textMaskTensor = createFloatTensor(textResult.textMask, env)
        try {
            val durationStart = System.nanoTime()
            val duration =
                dpSession
                    .run(
                        mapOf(
                            "text_ids" to textIdsTensor,
                            "style_dp" to style.dpTensor,
                            "text_mask" to textMaskTensor,
                        )
                    )
                    .use { res ->
                        flattenDuration(res[0].value, bsz) { raw ->
                            FloatArray(raw.size) { raw[it] / speed }
                        }
                    }
            val durationMs = elapsedMs(durationStart)

            val latentStart = System.nanoTime()
            val latent = sampleNoisyLatent(duration, rng, injectedNoise)
            val latentMs = elapsedMs(latentStart)

            val latentMaskTensor = createFloatTensor(latent.mask, env)
            val totalStepTensor = OnnxTensor.createTensor(env, FloatArray(bsz) { totalStep.toFloat() })
            try {
                val textEncodeStart = System.nanoTime()
                val textEncResult =
                    textEncSession.run(
                        mapOf(
                            "text_ids" to textIdsTensor,
                            "style_ttl" to style.ttlTensor,
                            "text_mask" to textMaskTensor,
                        )
                    )
                val textEncodeMs = elapsedMs(textEncodeStart)
                try {
                    val textEmbTensor = textEncResult[0] as OnnxTensor
                    var xt = latent.noisy
                    val estimatorStart = System.nanoTime()
                    repeat(totalStep) { step ->
                        val currentStepTensor =
                            OnnxTensor.createTensor(env, FloatArray(bsz) { step.toFloat() })
                        val noisyLatentTensor = createFloatTensor(xt, env)
                        try {
                            xt =
                                vectorEstSession
                                    .run(
                                        mapOf(
                                            "noisy_latent" to noisyLatentTensor,
                                            "text_emb" to textEmbTensor,
                                            "style_ttl" to style.ttlTensor,
                                            "latent_mask" to latentMaskTensor,
                                            "text_mask" to textMaskTensor,
                                            "current_step" to currentStepTensor,
                                            "total_step" to totalStepTensor,
                                        )
                                    )
                                    .use { res -> res[0].value as Array<Array<FloatArray>> }
                        } finally {
                            currentStepTensor.close()
                            noisyLatentTensor.close()
                        }
                    }
                    val estimatorMs = elapsedMs(estimatorStart)

                    val finalLatentTensor = createFloatTensor(xt, env)
                    val vocoderStart = System.nanoTime()
                    val wav =
                        vocoderSession
                            .run(mapOf("latent" to finalLatentTensor))
                            .use { res -> (res[0].value as Array<FloatArray>)[0] }
                    val vocoderMs = elapsedMs(vocoderStart)
                    finalLatentTensor.close()

                    return InferResult(
                        wav = wav,
                        duration = duration,
                        timings =
                            SupertonicTimings(
                                preprocessMs = preprocessMs,
                                durationMs = durationMs,
                                textEncodeMs = textEncodeMs,
                                latentMs = latentMs,
                                estimatorMs = estimatorMs,
                                vocoderMs = vocoderMs,
                                totalMs = elapsedMs(totalStart),
                            ),
                    )
                } finally {
                    textEncResult.close()
                }
            } finally {
                latentMaskTensor.close()
                totalStepTensor.close()
            }
        } finally {
            textIdsTensor.close()
            textMaskTensor.close()
        }
    }

    /**
     * Reference `sample_noisy_latent`: standard-normal latent of shape
     * `[bsz, latentDim * chunkCompress, ceil(maxDuration * sampleRate / chunk)]`, multiplied by the
     * latent mask. [injected] must already have that shape.
     */
    internal fun sampleNoisyLatent(
        duration: FloatArray,
        rng: Random,
        injected: Array<Array<FloatArray>>? = null,
    ): LatentBundle {
        val bsz = duration.size
        val wavLenMax = (duration.maxOrNull() ?: 0f) * config.sampleRate
        val wavLengths = LongArray(bsz) { (duration[it] * config.sampleRate).toLong() }

        val chunkSize = config.latentChunkSize
        val latentLen = floor((wavLenMax + chunkSize - 1.0) / chunkSize).toInt().coerceAtLeast(1)
        val latentDim = config.latentDimTotal

        val noisy =
            if (injected != null) {
                require(
                    injected.size == bsz &&
                        injected.all { it.size == latentDim && it.all { row -> row.size == latentLen } }
                ) {
                    "Injected latent noise must be [$bsz][$latentDim][$latentLen]"
                }
                injected
            } else {
                Array(bsz) { Array(latentDim) { FloatArray(latentLen) { boxMuller(rng) } } }
            }

        val mask = latentMask(wavLengths)
        for (b in 0 until bsz) {
            for (d in 0 until latentDim) {
                for (t in noisy[b][d].indices) noisy[b][d][t] *= mask[b][0][t]
            }
        }
        return LatentBundle(noisy, mask)
    }

    private fun latentMask(wavLengths: LongArray): Array<Array<FloatArray>> {
        val latentSize = config.latentChunkSize.toLong()
        val latentLengths =
            LongArray(wavLengths.size) { (wavLengths[it] + latentSize - 1) / latentSize }
        val maxLen = latentLengths.maxOrNull() ?: 0L
        return Array(wavLengths.size) { b ->
            Array(1) { FloatArray(maxLen.toInt()) { idx -> if (idx < latentLengths[b]) 1f else 0f } }
        }
    }

    /** Box–Muller, matching the reference's JS/numpy equivalents. */
    private fun boxMuller(rng: Random): Float {
        val u1 = max(1e-10f, rng.nextFloat())
        val u2 = rng.nextFloat()
        return kotlin.math.sqrt(-2.0f * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0f * Math.PI.toFloat() * u2)
    }

    /** The vocoder pads to whole latent frames; the reference trims to the predicted duration. */
    private fun trimToDuration(wav: FloatArray, duration: Float): FloatArray {
        val samples = (duration * config.sampleRate).toInt()
        if (samples <= 0 || samples >= wav.size) return wav
        return wav.copyOf(samples)
    }

    private fun concatenate(head: FloatArray, silenceSamples: Int, tail: FloatArray): FloatArray {
        val silence = silenceSamples.coerceAtLeast(0)
        val out = FloatArray(head.size + silence + tail.size)
        head.copyInto(out, 0)
        tail.copyInto(out, head.size + silence)
        return out
    }

    /** The duration output is `[bsz]`; tolerate the `[bsz][k]` / `[1][bsz]` shapes ORT may return. */
    private fun flattenDuration(
        value: Any?,
        batch: Int,
        transform: (FloatArray) -> FloatArray,
    ): FloatArray {
        val raw =
            when (value) {
                is FloatArray -> value
                is Array<*> -> {
                    val first = value.firstOrNull()
                    when {
                        first is FloatArray && value.size == batch ->
                            FloatArray(batch) { (value[it] as FloatArray)[0] }
                        first is FloatArray -> first
                        first is Array<*> -> (first as Array<FloatArray>)[0]
                        else -> error("Unexpected duration tensor: ${value.javaClass}")
                    }
                }
                else -> error("Unexpected duration tensor: ${value?.javaClass}")
            }
        return transform(raw)
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun ensureOpen() {
        check(!closed) { "Supertonic engine is closed" }
    }

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
            dpSession.close()
            textEncSession.close()
            vectorEstSession.close()
            vocoderSession.close()
            sessionOptions.close()
        }
    }

    companion object {
        /** Half the cores keeps the LIVE pipeline responsive while the denoiser borrows CPU. */
        val DEFAULT_INTRA_OP_THREADS: Int = max(1, Runtime.getRuntime().availableProcessors() / 2)
    }
}

/** Thrown when a newer request superseded the in-flight synthesis, so the audio is discarded. */
class SpeechSupersededException : Exception("Speech request superseded")
