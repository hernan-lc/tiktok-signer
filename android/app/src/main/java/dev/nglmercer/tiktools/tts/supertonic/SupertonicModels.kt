package dev.nglmercer.tiktools.tts.supertonic

import android.content.Context
import com.google.gson.JsonParser
import dev.nglmercer.tiktools.core.diagnostics.Logger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation

/**
 * The SuperTonic 3 file manifest, integrity verification and on-demand downloader.
 *
 * Model bytes never ship in the APK — they are fetched once from Hugging Face into
 * `files/supertonic3/`, pinned to a **verified revision** so files from different model revisions can
 * never be mixed. Every file is checked against real published metadata before the engine may load
 * it: the four ONNX weights by SHA-256 (Hugging Face LFS object ids), and the JSON sidecars and voice
 * styles by byte length plus their git blob SHA-1 (Hugging Face stores them as plain git blobs).
 * No checksum is invented; the values come from the repository's own metadata.
 */
object SupertonicModels {

    const val REPO = "Supertone/supertonic-3"

    /** Pinned revision of [REPO] these digests were taken from. */
    const val REVISION = "3cadd1ee6394adea1bd021217a0e650ede09a323"

    const val BASE_URL = "https://huggingface.co/$REPO/resolve/"

    const val DIR_NAME = "supertonic3"

    /** Bumped when the manifest shape or digests change, invalidating older local manifests. */
    const val MANIFEST_VERSION = 2

    private const val MANIFEST_FILE = "manifest.json"

    const val DEFAULT_VOICE = "F1"

    /** Every published voice style for this revision; all share the same four ONNX sessions. */
    val VOICES: List<String> = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

    /** Back-compat: the file name of the default voice style. */
    const val STYLE_FILE = "style_$DEFAULT_VOICE.json"

    sealed interface Integrity {
        data class Sha256(val hash: String) : Integrity

        data class BlobSha1(val hash: String) : Integrity
    }

    data class FileSpec(
        val remote: String,
        val local: String,
        val size: Long,
        val integrity: Integrity,
    ) {
        val digestName: String
            get() =
                when (integrity) {
                    is Integrity.Sha256 -> "SHA-256"
                    is Integrity.BlobSha1 -> "git blob SHA-1"
                }
    }

    private fun onnx(remote: String, local: String, size: Long, sha256: String) =
        FileSpec("onnx/$remote", local, size, Integrity.Sha256(sha256))

    /** The six runtime files. */
    val FILES: List<FileSpec> =
        listOf(
            onnx(
                "duration_predictor.onnx",
                "duration_predictor.onnx",
                3_700_147L,
                "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
            ),
            onnx(
                "text_encoder.onnx",
                "text_encoder.onnx",
                36_416_150L,
                "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
            ),
            onnx(
                "vector_estimator.onnx",
                "vector_estimator.onnx",
                256_534_781L,
                "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
            ),
            onnx(
                "vocoder.onnx",
                "vocoder.onnx",
                101_424_195L,
                "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
            ),
            FileSpec(
                "onnx/tts.json",
                "tts.json",
                8_253L,
                Integrity.BlobSha1("28575b0793c92607a0f0a292550df9bd17709a79"),
            ),
            FileSpec(
                "onnx/unicode_indexer.json",
                "unicode_indexer.json",
                277_676L,
                Integrity.BlobSha1("3aaf4d33024aff0be455633c24b3636b3a810150"),
            ),
        )

    private val voiceSizes: Map<String, Pair<Long, String>> =
        mapOf(
            "F1" to (292_046L to "421365f307ed1535d8da16845031d0e9fa3e60c5"),
            "F2" to (292_423L to "b09a3a433df8a4856d1d7d64736a56ddf37a4ea3"),
            "F3" to (290_794L to "a366f94c77c4b440404071f1a52bed3ccf83a5ff"),
            "F4" to (291_808L to "39c78a52795320c6101b7eaf984c0e2352fbdc18"),
            "F5" to (291_479L to "06983f8816ad00a8eeb7efd37a33d457587e16f8"),
            "M1" to (291_748L to "bddfff85a7c4c140c11620bd7005d86681dccdac"),
            "M2" to (292_055L to "602ff6c4d6d48cdb71c37461f7c175d71fe8b34b"),
            "M3" to (290_198L to "000b60436b77619b933fdc33628ad737595e6794"),
            "M4" to (291_522L to "aae253ae81185fa06a6469c3f1cdff04ed121ddf"),
            "M5" to (291_469L to "0536b252698830f8536c077edcb94e1ce4768ab6"),
        )

    fun voiceSpec(name: String): FileSpec {
        require(name in VOICES) { "Unknown Supertonic voice: '$name'. Available: ${VOICES.joinToString()}" }
        val (size, sha1) = voiceSizes.getValue(name)
        return FileSpec("voice_styles/$name.json", "style_$name.json", size, Integrity.BlobSha1(sha1))
    }

    fun url(spec: FileSpec): String = "$BASE_URL$REVISION/${spec.remote}"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun file(context: Context, spec: FileSpec): File = File(dir(context), spec.local)

    fun voiceFile(context: Context, name: String): File = file(context, voiceSpec(name))

    fun isVoiceDownloaded(context: Context, name: String): Boolean =
        runCatching { SupertonicIntegrity.matches(voiceSpec(name), voiceFile(context, name)) }
            .getOrDefault(false)

    fun downloadedVoices(context: Context): List<String> = VOICES.filter { isVoiceDownloaded(context, it) }

    sealed interface Status {
        data object Ready : Status

        /** Absent, truncated, or the wrong length. */
        data class Missing(val files: List<String>) : Status

        /** Present with the right size, but not yet digest-verified against the pinned revision. */
        data class Unverified(val files: List<String>) : Status
    }

    /**
     * Fast readiness check: exact size for every file, plus a local manifest proving the digests were
     * verified for [REVISION]. Full hashing happens in [verifyAll] (after download and before the
     * engine loads), never on every UI refresh.
     */
    fun status(context: Context): Status {
        val root = dir(context)
        val missing =
            FILES.filter { spec ->
                val file = File(root, spec.local)
                !file.isFile || file.length() != spec.size
            }
        if (missing.isNotEmpty()) return Status.Missing(missing.map { it.local })

        val manifest = readManifest(root)
        if (manifest == null ||
            manifest.manifestVersion != MANIFEST_VERSION ||
            manifest.revision != REVISION ||
            FILES.any { manifest.files[it.local] != digestOf(it) }
        ) {
            return Status.Unverified(FILES.map { it.local })
        }
        return Status.Ready
    }

    /**
     * Hash every runtime file and record the verified manifest. Returns the list of failures (empty
     * on success). Called after downloads and before the engine is loaded.
     */
    suspend fun verifyAll(
        context: Context,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): List<String> = withContext(Dispatchers.IO) {
        val root = dir(context)
        val failures = mutableListOf<String>()
        FILES.forEachIndexed { index, spec ->
            onProgress(index, FILES.size, spec.local)
            val file = File(root, spec.local)
            if (!SupertonicIntegrity.matches(spec, file)) {
                failures += spec.local
            }
        }
        if (failures.isEmpty()) {
            writeManifest(root)
        }
        failures
    }

    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        /** 0..1 across the whole manifest, weighting files equally. */
        fun overall(): Float = ((fileIndex + fraction()) / fileCount).coerceIn(0f, 1f)

        private fun fraction(): Float =
            if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    /**
     * Download the missing or truncated runtime files, then verify every digest. Each file streams to
     * `.part` and is renamed only after its digest matches, so an interrupted or corrupt download
     * never becomes an active model file. Files already present and valid are skipped.
     */
    suspend fun download(context: Context, onProgress: (Progress) -> Unit = {}): Unit =
        withContext(Dispatchers.IO) {
            val target = dir(context)
            if (!target.isDirectory) target.mkdirs()
            FILES.forEachIndexed { index, spec ->
                val out = File(target, spec.local)
                if (SupertonicIntegrity.matches(spec, out)) {
                    onProgress(Progress(index, FILES.size, spec.local, 1, 1))
                    return@forEachIndexed
                }
                fetchVerified(spec, out) { done, total ->
                    onProgress(Progress(index, FILES.size, spec.local, done, total))
                }
            }
            val corrupt = verifyAll(context) { index, total, name ->
                onProgress(Progress(index, total, name, 1, 1))
            }
            check(corrupt.isEmpty()) {
                "Model verification failed for: ${corrupt.joinToString()}"
            }
        }

    /** Download a single voice style on demand. */
    suspend fun downloadVoice(
        context: Context,
        name: String,
        onProgress: (Progress) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            val spec = voiceSpec(name)
            val out = file(context, spec)
            if (SupertonicIntegrity.matches(spec, out)) return@withContext true
            val target = dir(context)
            if (!target.isDirectory) target.mkdirs()
            fetchVerified(spec, out) { done, total -> onProgress(Progress(0, 1, spec.local, done, total)) }
            SupertonicIntegrity.matches(spec, out)
        }

    fun deleteVoice(context: Context, name: String): Boolean {
        val file = voiceFile(context, name)
        return !file.exists() || file.delete()
    }

    fun deleteAll(context: Context): Boolean {
        val root = dir(context)
        return !root.exists() || root.deleteRecursively()
    }

    private suspend fun fetchVerified(
        spec: FileSpec,
        out: File,
        onBytes: (done: Long, total: Long) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                fetch(URL(url(spec)), out, onBytes)
                if (SupertonicIntegrity.matches(spec, out)) return
                lastError =
                    IllegalStateException(
                        "${spec.local} failed ${spec.digestName} verification after download"
                    )
                // A corrupt body must never be reused; drop it and try once more.
                out.delete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                out.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                out.delete()
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS * (attempt + 1))
        }
        throw IllegalStateException(
            "Could not download ${spec.local} from ${url(spec)}: ${lastError?.message}",
            lastError,
        )
    }

    private suspend fun fetch(url: URL, out: File, onBytes: (done: Long, total: Long) -> Unit) =
        coroutineScope {
            val tmp = File(out.parent, out.name + ".part")
            val connection = url.openConnection() as HttpURLConnection
            val cancellation =
                launch(Dispatchers.IO) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
            try {
                ensureActive()
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "TikTools-Studio/1.0")
                connection.connect()
                val code = connection.responseCode
                check(code in 200..299) { "HTTP $code for ${url.path.substringAfterLast('/')}" }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                var done = 0L
                var lastReport = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 512 * 1024) {
                                lastReport = done
                                onBytes(done, total)
                            }
                        }
                    }
                }
                onBytes(done, total)
                check(tmp.renameTo(out)) { "could not save ${out.name}" }
            } finally {
                cancellation.cancel()
                connection.disconnect()
                if (tmp.isFile && !out.isFile) tmp.delete()
            }
        }

    private data class LocalManifest(
        val manifestVersion: Int,
        val revision: String,
        val files: Map<String, String>,
    )

    private fun digestOf(spec: FileSpec): String =
        when (val integrity = spec.integrity) {
            is Integrity.Sha256 -> integrity.hash
            is Integrity.BlobSha1 -> integrity.hash
        }

    private fun readManifest(root: File): LocalManifest? {
        val file = File(root, MANIFEST_FILE)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
                val json = JsonParser.parseReader(file.reader()).asJsonObject
                val files =
                    json.getAsJsonObject("files").entrySet().associate { (key, value) ->
                        key to value.asString
                    }
                LocalManifest(
                    manifestVersion = json.get("manifestVersion").asInt,
                    revision = json.get("revision").asString,
                    files = files,
                )
            }
            .getOrElse {
                Logger.append("TTS", "Ignoring unreadable model manifest: ${it.message}")
                null
            }
    }

    private fun writeManifest(root: File) {
        val files = FILES.joinToString(",") { "\"${it.local}\":\"${digestOf(it)}\"" }
        val body =
            """{"manifestVersion":$MANIFEST_VERSION,"revision":"$REVISION","files":{$files}}"""
        runCatching { File(root, MANIFEST_FILE).writeText(body) }
            .onFailure { Logger.append("TTS", "Could not write model manifest: ${it.message}") }
    }

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 750L
}

/** Digest helpers, split out so they are exercised by plain JVM tests. */
internal object SupertonicIntegrity {

    /** True when [file] exists with exactly the expected length and digest. */
    fun matches(spec: SupertonicModels.FileSpec, file: File): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        val actual = runCatching { digest(spec, file) }.getOrNull() ?: return false
        return actual.equals(expected(spec), ignoreCase = true)
    }

    fun expected(spec: SupertonicModels.FileSpec): String =
        when (val integrity = spec.integrity) {
            is SupertonicModels.Integrity.Sha256 -> integrity.hash
            is SupertonicModels.Integrity.BlobSha1 -> integrity.hash
        }

    fun digest(spec: SupertonicModels.FileSpec, file: File): String =
        when (spec.integrity) {
            is SupertonicModels.Integrity.Sha256 -> hex(sha256(file))
            is SupertonicModels.Integrity.BlobSha1 -> hex(gitBlobSha1(file))
        }

    fun sha256(file: File): ByteArray = digestOf("SHA-256", file)

    /** Git object hash: `sha1("blob " + length + "\0" + content)`. */
    fun gitBlobSha1(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII))
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    private fun digestOf(algorithm: String, file: File): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
