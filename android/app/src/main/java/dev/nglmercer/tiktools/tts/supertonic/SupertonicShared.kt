package dev.nglmercer.tiktools.tts.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import com.google.gson.JsonParser
import java.io.File
import java.io.FileReader
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale

/**
 * Shared SuperTonic 3 inference pieces: config + style loading, tensor builders, and the unicode text
 * processor.
 *
 * The text processor is a faithful port of the official reference
 * ([supertone-oss-archive/supertonic](https://github.com/supertone-oss-archive/supertonic),
 * `py/helper.py`, `UnicodeProcessor`) which is the authority for the normalization order, the
 * language tags, code-point indexing and mask generation. Only the v3 model is supported —
 * filenames and tensor names follow the `Supertone/supertonic-3` package.
 */
data class SupertonicTimings(
    val preprocessMs: Long = 0L,
    val durationMs: Long = 0L,
    val textEncodeMs: Long = 0L,
    val latentMs: Long = 0L,
    val estimatorMs: Long = 0L,
    val vocoderMs: Long = 0L,
    val totalMs: Long = 0L,
) {
    /** Timings for a single inference pass; summing them yields [totalMs]. */
    operator fun plus(other: SupertonicTimings) =
        SupertonicTimings(
            preprocessMs + other.preprocessMs,
            durationMs + other.durationMs,
            textEncodeMs + other.textEncodeMs,
            latentMs + other.latentMs,
            estimatorMs + other.estimatorMs,
            vocoderMs + other.vocoderMs,
            totalMs + other.totalMs,
        )
}

data class SupertonicResult(
    val wav: FloatArray,
    /** Predicted (speed-scaled) duration in seconds, summed across chunks. */
    val duration: FloatArray,
    val sampleRate: Int,
    val timings: SupertonicTimings = SupertonicTimings(),
    val chunkCount: Int = 1,
    /** Language actually sent to the model. */
    val lang: String = SupertonicLanguages.DEFAULT,
    /** Voice style name actually used. */
    val voice: String = "",
)

internal data class SupertonicConfig(
    val version: String,
    val sampleRate: Int,
    val baseChunkSize: Int,
    val chunkCompress: Int,
    val latentDim: Int,
) {
    /** `512 * 6 = 3072` samples per latent frame in the reference package. */
    val latentChunkSize: Int get() = baseChunkSize * chunkCompress

    /** `24 * 6 = 144` latent channels in the reference package. */
    val latentDimTotal: Int get() = latentDim * chunkCompress
}

internal data class LatentBundle(
    val noisy: Array<Array<FloatArray>>,
    val mask: Array<Array<FloatArray>>,
)

/**
 * A loaded voice style. The tensors are batch-size 1 and shared by every request; all voices share
 * the same four ONNX sessions, so switching voice never reloads the model.
 */
data class SupertonicStyle(
    val name: String,
    val ttlTensor: OnnxTensor,
    val dpTensor: OnnxTensor,
    val ttlShape: List<Long>,
    val dpShape: List<Long>,
) : AutoCloseable {
    override fun close() {
        ttlTensor.close()
        dpTensor.close()
    }
}

/**
 * The 31 languages Supertonic 3 conditions on, plus `na`: the model's **unknown/fallback** tag. `na`
 * is a language-agnostic input, not a language detector — the UI must present it as a fallback.
 */
object SupertonicLanguages {
    const val UNKNOWN: String = "na"
    const val DEFAULT: String = UNKNOWN

    val available: List<String> =
        listOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi",
            "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv",
            "tr", "uk", "vi", UNKNOWN,
        )

    /** `Locale.language` → model code. Nearly all codes are identical; legacy Java tags are not. */
    private val byLocaleLanguage: Map<String, String> =
        available.associateWith { it } + mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    fun isValid(language: String): Boolean = language in available

    /** Best-effort device-locale mapping; unsupported locales fall back to [UNKNOWN]. */
    fun fromLocale(locale: Locale): String =
        byLocaleLanguage[locale.language.lowercase(Locale.ROOT)] ?: UNKNOWN

    /** Accepts null/blank as [UNKNOWN]; rejects anything else that is not a supported code. */
    fun resolve(language: String?): String {
        val candidate = language?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return UNKNOWN
        return require(candidate)
    }

    /** Rejects invalid explicit codes loudly instead of letting them reach inference. */
    fun require(language: String): String {
        require(isValid(language)) {
            "Invalid Supertonic language: '$language'. Available: ${available.joinToString()}"
        }
        return language
    }
}

internal fun loadConfig(modelDir: File): SupertonicConfig {
    val root = JsonParser.parseReader(FileReader(File(modelDir, "tts.json"))).asJsonObject
    val ae = root.getAsJsonObject("ae")
    val ttl = root.getAsJsonObject("ttl")
    return SupertonicConfig(
        version = root.get("tts_version")?.asString ?: "unknown",
        sampleRate = ae.get("sample_rate").asInt,
        baseChunkSize = ae.get("base_chunk_size").asInt,
        chunkCompress = ttl.get("chunk_compress_factor").asInt,
        latentDim = ttl.get("latent_dim").asInt,
    )
}

internal fun createFloatTensor(arr: Array<Array<FloatArray>>, env: OrtEnvironment): OnnxTensor {
    require(arr.isNotEmpty() && arr[0].isNotEmpty()) { "empty tensor" }
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong(), arr[0][0].size.toLong())
    val flat = FloatArray(arr.size * arr[0].size * arr[0][0].size)
    var idx = 0
    for (b in arr.indices) for (d in arr[b].indices) for (t in arr[b][d].indices) {
        flat[idx++] = arr[b][d][t]
    }
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
}

/** 2D long tensor builder for text ids. */
internal fun createLongTensor(arr: Array<LongArray>, env: OrtEnvironment): OnnxTensor {
    val shape = longArrayOf(arr.size.toLong(), arr[0].size.toLong())
    val flat = LongArray(arr.size * arr[0].size)
    var idx = 0
    for (b in arr.indices) for (i in arr[b].indices) flat[idx++] = arr[b][i]
    return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), shape)
}

/**
 * Port of the reference `UnicodeProcessor`.
 *
 * Defects fixed relative to the previous port:
 *  - text is wrapped in `<lang>…</lang>` before indexing, so the model is language-conditioned;
 *  - NFKD combining marks are **kept** (`niño`, `estás`, `canción` survive);
 *  - lengths are counted in Unicode **code points**, and the attention mask matches the ids;
 *  - emoji are removed before indexing and unsupported code points are never clamped onto the last
 *    indexer entry (numpy `uint16` semantics are reproduced instead);
 *  - the full normalization pipeline is implemented (symbol/dash table, decorative-symbol removal,
 *    `@`/`e.g.,`/`i.e.,` expansion, punctuation spacing, duplicate-quote collapse, terminal
 *    punctuation).
 */
internal class UnicodeProcessor(indexerFile: File) {
    private val indexer: LongArray = loadJsonLongArray(indexerFile)

    init {
        require(indexer.size >= CODE_POINT_TABLE_SIZE) {
            "unicode_indexer.json must map every BMP code point " +
                "(expected >= $CODE_POINT_TABLE_SIZE entries, found ${indexer.size}); " +
                "the installed model files are incompatible"
        }
    }

    /** Convenience for a single utterance; [langs] is one entry per [texts] entry. */
    fun process(texts: List<String>, langs: List<String>): TextProcessResult {
        require(texts.isNotEmpty()) { "No input text" }
        require(texts.size == langs.size) {
            "text/lang count mismatch: ${texts.size} vs ${langs.size}"
        }
        val processed = texts.mapIndexed { index, text -> preprocess(text, langs[index]) }
        val codePoints = processed.map { it.codePoints().toArray() }
        val maxLen = codePoints.maxOfOrNull { it.size } ?: 0
        val textIds = Array(processed.size) { LongArray(maxLen) }
        codePoints.forEachIndexed { row, points ->
            for (i in points.indices) {
                // numpy builds this array as uint16, so supplementary code points truncate to 16 bits.
                textIds[row][i] = indexer[points[i] and CODE_POINT_MASK]
            }
        }
        return TextProcessResult(
            textIds = textIds,
            textMask = getTextMask(codePoints.map { it.size }.toIntArray()),
            lengths = codePoints.map { it.size }.toIntArray(),
            processed = processed,
        )
    }

    fun process(text: String, lang: String): TextProcessResult = process(listOf(text), listOf(lang))

    /**
     * Normalization before indexing; internal so JVM tests can pin it against the reference. The
     * language tag is appended last, exactly like the reference.
     */
    internal fun preprocess(text: String, lang: String): String {
        SupertonicLanguages.require(lang)
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)

        t = EMOJI.replace(t, "")

        for ((from, to) in SYMBOL_REPLACEMENTS) t = t.replace(from, to)

        t = DECORATIVE_SYMBOLS.replace(t, "")

        for ((from, to) in EXPRESSION_REPLACEMENTS) t = t.replace(from, to)

        t = t.replace(" ,", ",")
        t = t.replace(" .", ".")
        t = t.replace(" !", "!")
        t = t.replace(" ?", "?")
        t = t.replace(" ;", ";")
        t = t.replace(" :", ":")
        t = t.replace(" '", "'")

        t = DUPLICATE_DOUBLE_QUOTES.replace(t, "\"")
        t = DUPLICATE_SINGLE_QUOTES.replace(t, "'")
        t = DUPLICATE_BACKTICKS.replace(t, "`")

        t = WHITESPACE.replace(t, " ").trim()

        if (t.isEmpty() || t.last() !in TERMINAL_PUNCTUATION) t += "."

        return "<$lang>$t</$lang>"
    }

    private fun getTextMask(lengths: IntArray): Array<Array<FloatArray>> {
        val maxLen = lengths.maxOrNull() ?: 0
        return Array(lengths.size) { b ->
            Array(1) { FloatArray(maxLen) { idx -> if (idx < lengths[b]) 1f else 0f } }
        }
    }

    data class TextProcessResult(
        val textIds: Array<LongArray>,
        val textMask: Array<Array<FloatArray>>,
        val lengths: IntArray,
        /** Normalized, language-tagged text — one entry per input. Useful for tests/diagnostics. */
        val processed: List<String>,
    )

    internal companion object {
        const val CODE_POINT_TABLE_SIZE: Int = 0x10000
        const val CODE_POINT_MASK: Int = 0xFFFF

        private val EMOJI: Regex =
            Regex(
                """[\x{1F600}-\x{1F64F}\x{1F300}-\x{1F5FF}\x{1F680}-\x{1F6FF}""" +
                    """\x{1F700}-\x{1F77F}\x{1F780}-\x{1F7FF}\x{1F800}-\x{1F8FF}""" +
                    """\x{1F900}-\x{1F9FF}\x{1FA00}-\x{1FA6F}\x{1FA70}-\x{1FAFF}""" +
                    """\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}]+"""
            )

        private val SYMBOL_REPLACEMENTS: List<Pair<String, String>> =
            listOf(
                "\u2013" to "-",
                "\u2011" to "-",
                "\u2014" to "-",
                "_" to " ",
                "\u201c" to "\"",
                "\u201d" to "\"",
                "\u2018" to "'",
                "\u2019" to "'",
                "\u00b4" to "'",
                "`" to "'",
                "[" to " ",
                "]" to " ",
                "|" to " ",
                "/" to " ",
                "#" to " ",
                "\u2192" to " ",
                "\u2190" to " ",
            )

        private val DECORATIVE_SYMBOLS: Regex = Regex("[♥☆♡©\\\\]")

        private val EXPRESSION_REPLACEMENTS: List<Pair<String, String>> =
            listOf(
                "@" to " at ",
                "e.g.," to "for example, ",
                "i.e.," to "that is, ",
            )

        private val DUPLICATE_DOUBLE_QUOTES: Regex = Regex("\"{2,}")
        private val DUPLICATE_SINGLE_QUOTES: Regex = Regex("'{2,}")
        private val DUPLICATE_BACKTICKS: Regex = Regex("`{2,}")

        /** Java's `\s` is ASCII-only; the reference compiles Python's Unicode `\s`. */
        private val WHITESPACE: Regex =
            Regex("[\\s\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+")

        /** Reference terminal set: `[.!?;:,'\"')\]}…。」』】〉》›»]`. */
        private val TERMINAL_PUNCTUATION: Set<Char> =
            (
                ".\u0021\u003f\u003b\u003a\u002c\u0027\u0022\u0029\u005d\u007d\u2026\u3002\u300d" +
                    "\u300f\u3011\u3009\u300b\u203a\u00bb"
            )
                .toSet()
    }
}

internal fun loadJsonLongArray(file: File): LongArray {
    val root = JsonParser.parseReader(FileReader(file)).asJsonArray
    val arr = LongArray(root.size())
    for (i in 0 until root.size()) arr[i] = root[i].asLong
    return arr
}

internal inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    var thrown: Throwable? = null
    try {
        return block(this)
    } catch (t: Throwable) {
        thrown = t
        throw t
    } finally {
        try {
            this.close()
        } catch (closeEx: Throwable) {
            if (thrown == null) throw closeEx
        }
    }
}

/** Load one voice style (`voice_styles/<name>.json`), validating its declared tensor shapes. */
fun loadSupertonicStyle(
    path: File,
    env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    verbose: Boolean = false,
): SupertonicStyle {
    val root = JsonParser.parseReader(FileReader(path)).asJsonObject
    val ttlDims = root.getAsJsonObject("style_ttl").getAsJsonArray("dims").map { it.asLong }
    val dpDims = root.getAsJsonObject("style_dp").getAsJsonArray("dims").map { it.asLong }
    require(ttlDims.size == 3 && dpDims.size == 3) {
        "voice style ${path.name} declares an unexpected shape: ttl=$ttlDims dp=$dpDims"
    }
    require(ttlDims[0] == 1L && dpDims[0] == 1L) {
        "voice style ${path.name} must be single-speaker (ttl=${ttlDims[0]}, dp=${dpDims[0]})"
    }

    val ttl1 = ttlDims[1].toInt()
    val ttl2 = ttlDims[2].toInt()
    val dp1 = dpDims[1].toInt()
    val dp2 = dpDims[2].toInt()

    val ttlFlat = FloatArray(ttl1 * ttl2)
    val dpFlat = FloatArray(dp1 * dp2)

    var offset = 0
    root.getAsJsonObject("style_ttl").getAsJsonArray("data").forEach { batch ->
        batch.asJsonArray.forEach { row -> row.asJsonArray.forEach { v -> ttlFlat[offset++] = v.asFloat } }
    }
    offset = 0
    root.getAsJsonObject("style_dp").getAsJsonArray("data").forEach { batch ->
        batch.asJsonArray.forEach { row -> row.asJsonArray.forEach { v -> dpFlat[offset++] = v.asFloat } }
    }

    val ttlShape = longArrayOf(1L, ttl1.toLong(), ttl2.toLong())
    val dpShape = longArrayOf(1L, dp1.toLong(), dp2.toLong())
    if (verbose) Log.d("Supertonic", "Loaded voice style ${path.name} ttl=$ttlShape dp=$dpShape")

    return SupertonicStyle(
        name = path.nameWithoutExtension,
        ttlTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(ttlFlat), ttlShape),
        dpTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(dpFlat), dpShape),
        ttlShape = ttlShape.toList(),
        dpShape = dpShape.toList(),
    )
}
