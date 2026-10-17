package dev.nglmercer.tiktools.tts.supertonic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.nglmercer.tiktools.core.diagnostics.Logger
import dev.nglmercer.tiktools.tts.Speaker
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A [Speaker] that synthesizes with the on-device SuperTonic 3 engine and plays the result through
 * [AudioTrack].
 *
 * Speech lifecycle is split so LIVE traffic behaves predictably:
 *
 *  - **Requests** land in a single-slot conflated queue: the newest line always wins and the queue can
 *    never grow. A request arriving while the engine is busy is *replaced*, not dropped.
 *  - **Inference** is serialized on one worker. A single ONNX `run` is not interruptible, so a
 *    superseded request is detected through a monotonic request id and its audio is discarded
 *    ([SpeechSupersededException]) rather than played.
 *  - **Playback** writes PCM16 to a streaming [AudioTrack] in bounded blocks, honouring partial
 *    writes, and the track is always released in a `finally`.
 *  - **Shutdown** cancels the queue, waits for the worker to leave inference, and only then closes the
 *    voice styles and ONNX sessions, so a late `run` can never touch a closed session.
 *
 * The engine loads lazily on first speech (sessions take seconds and ~1 GB RAM); until the model
 * files are installed and verified, requests are rejected with a visible [State.NotInstalled].
 */
class SupertonicSpeaker(context: Context) : Speaker {

    sealed interface State {
        data object Idle : State

        /** Model files absent, truncated, or not verified. */
        data object NotInstalled : State

        data object Loading : State

        data object Ready : State

        data class Generating(val text: String) : State

        data class Playing(val text: String) : State

        data class Error(val detail: String) : State
    }

    override val enabled: Boolean = true

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private sealed interface Command {
        data class Speak(val id: Long, val text: String, val voiceOverride: String?) : Command

        data object Reload : Command
    }

    private val commands = Channel<Command>(Channel.CONFLATED)
    private val requestSeq = AtomicLong(0)
    private val latestRequest = AtomicLong(0)
    private val stopRequested = AtomicBoolean(false)

    private val engineMutex = Mutex()
    private val engineLock = Any()
    private var engine: SupertonicEngine? = null
    private var baseStyle: SupertonicStyle? = null
    private val styleCache = LinkedHashMap<String, SupertonicStyle>()

    private val trackLock = Any()

    @Volatile private var currentTrack: AudioTrack? = null

    @Volatile private var settings: SupertonicSettings = SupertonicSettings()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val worker: Job = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Speak -> handleSpeak(command)
                Command.Reload -> reloadEngine()
            }
        }
    }

    // ---------------------------------------------------------------- public API

    /** Apply new speech settings. Takes effect on the next utterance; the voice loads lazily. */
    fun configure(next: SupertonicSettings) {
        settings = next
    }

    /** Queue [text], replacing any pending request and cutting short the current playback. */
    override fun speak(text: String) {
        if (text.isBlank()) return
        enqueue(text, voiceOverride = null)
    }

    /** Speak [text] with an explicit voice without touching the repeat history. */
    fun preview(text: String, voice: String? = null) {
        if (text.isBlank()) return
        enqueue(text, voiceOverride = voice)
    }

    private fun enqueue(text: String, voiceOverride: String?) {
        val id = requestSeq.incrementAndGet()
        latestRequest.set(id)
        stopCurrentPlayback()
        commands.trySend(Command.Speak(id, text, voiceOverride))
    }

    /** Skip the current utterance. A single ONNX `run` cannot be aborted, so its result is discarded. */
    override fun stop() {
        latestRequest.set(requestSeq.incrementAndGet())
        stopRequested.set(true)
        stopCurrentPlayback()
        if (_state.value is State.Generating || _state.value is State.Playing) {
            _state.value = State.Ready
        }
    }

    /** Preload the engine and default voice. Returns false when the model is not installed. */
    suspend fun warmup(): Boolean =
        try {
            ensureEngine() != null
        } catch (e: SpeechSupersededException) {
            false
        } catch (e: Exception) {
            Logger.append("TTS", "supertonic warmup failed: ${e.message}")
            false
        }

    /** Verify, download and cache a voice style. Safe to call from the UI. */
    suspend fun prepareVoice(name: String): Boolean =
        try {
            if (!SupertonicModels.isVoiceDownloaded(app, name)) {
                SupertonicModels.downloadVoice(app, name)
            }
            styleFor(name) != null
        } catch (e: Exception) {
            _state.value = State.Error("voice $name: ${e.message}")
            false
        }

    /** Drop the engine and every cached style; the next utterance reloads from disk. */
    fun reload() {
        commands.trySend(Command.Reload)
    }

    override fun shutdown() {
        latestRequest.set(requestSeq.incrementAndGet())
        stopRequested.set(true)
        stopCurrentPlayback()
        commands.close()
        scope.cancel()
        // Wait for the worker to leave any blocking inference before releasing native resources.
        runBlocking { runCatching { worker.join() } }
        closeEngine()
        scope.cancel()
    }

    // ---------------------------------------------------------------- worker

    private suspend fun handleSpeak(command: Command.Speak) {
        if (command.id != latestRequest.get()) return
        stopRequested.set(false)
        val voiceName =
            command.voiceOverride?.takeIf { it in SupertonicModels.VOICES } ?: settings.resolvedVoice
        val display = command.text.take(60)
        try {
            val loaded =
                ensureEngine() ?: run {
                    _state.value = State.NotInstalled
                    return
                }
            val style = if (voiceName == loaded.second.name) loaded.second
                else styleFor(voiceName) ?: loaded.second
            if (command.id != latestRequest.get()) return

            val requestedAt = System.nanoTime()
            _state.value = State.Generating(display)
            val lang = settings.languageFor(deviceLocale())
            val result =
                loaded.first.synthesize(
                    text = command.text,
                    lang = lang,
                    style = style,
                    totalStep = settings.steps,
                    speed = settings.speed,
                    maxChunkLength = SupertonicQuality.resolveChunkLength(settings.chunkLength, lang),
                    silenceDuration = settings.silenceDuration,
                    maxChars = settings.maxSpeechChars,
                    onChunk = { _, _ -> command.id == latestRequest.get() && !stopRequested.get() },
                )

            if (command.id != latestRequest.get() || stopRequested.get()) return

            val stats = SupertonicDiagnostics.analyze(result.wav, result.sampleRate)
            if (settings.exportWav) {
                SupertonicDiagnostics.export(
                    File(app.cacheDir, SupertonicDiagnostics.DIAG_DIR),
                    result.wav,
                    result.sampleRate,
                    "supertonic-${System.currentTimeMillis()}",
                )
            }
            if (stats.samples == 0) {
                _state.value = State.Error("empty waveform")
                return
            }
            if (!stats.finite) {
                _state.value =
                    State.Error("waveform contains ${stats.nanCount} NaN / ${stats.infiniteCount} Inf samples")
                return
            }
            if (stats.silent) {
                _state.value = State.Error("waveform is silent")
                return
            }

            _state.value = State.Playing(display)
            val playbackStarted = play(result.wav, result.sampleRate, command.id)
            if (command.id != latestRequest.get()) return

            if (settings.diagnosticLogging) {
                logDiagnostics(result, stats, requestedAt, playbackStarted)
            }
            _state.value = State.Ready
        } catch (e: SpeechSupersededException) {
            // A newer request replaced this one; drop the audio silently.
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            _state.value = State.Error("out of memory loading or running the model")
            closeEngine()
        } catch (e: Exception) {
            Logger.append("TTS", "supertonic speak failed: ${e.message}")
            _state.value = State.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun logDiagnostics(
        result: SupertonicResult,
        stats: SupertonicDiagnostics.WaveformStats,
        requestedAt: Long,
        playbackStartNanos: Long,
    ) {
        val totalMs = (playbackStartNanos - requestedAt) / 1_000_000
        val rtf =
            if (stats.durationSeconds > 0) totalMs / 1000.0 / stats.durationSeconds else 0.0
        Logger.append(
            "TTS",
            buildString {
                append("supertonic lang=${result.lang} voice=${result.voice} ")
                append("chunks=${result.chunkCount} steps=${settings.steps} speed=${settings.speed} ")
                append("prep=${result.timings.preprocessMs}ms dur=${result.timings.durationMs}ms ")
                append("enc=${result.timings.textEncodeMs}ms lat=${result.timings.latentMs}ms ")
                append("est=${result.timings.estimatorMs}ms voc=${result.timings.vocoderMs}ms ")
                append("synthTotal=${result.timings.totalMs}ms startLatency=${totalMs}ms ")
                append("rtf=%.2f ".format(Locale.US, rtf))
                append("sr=${result.sampleRate} samples=${stats.samples} ")
                append("dur=${"%.2f".format(Locale.US, stats.durationSeconds)}s ")
                append("predicted=${"%.2f".format(Locale.US, result.duration.firstOrNull() ?: 0f)}s ")
                append("peak=${"%.3f".format(Locale.US, stats.peak)} ")
                append("rms=${"%.4f".format(Locale.US, stats.rms)} clipped=${stats.clippedCount}")
            },
        )
    }

    // ---------------------------------------------------------------- engine + styles

    private fun deviceLocale(): Locale =
        app.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }

    private suspend fun ensureEngine(): Pair<SupertonicEngine, SupertonicStyle>? {
        engineMutex.withLock {
            synchronized(engineLock) {
                val current = engine
                val style = baseStyle
                if (current != null && style != null) return current to style
            }
            if (SupertonicModels.status(app) != SupertonicModels.Status.Ready) return null
            _state.value = State.Loading
            val created = SupertonicEngine(SupertonicModels.dir(app))
            try {
                val style = created.loadStyle(SupertonicModels.voiceFile(app, settings.resolvedVoice))
                synchronized(engineLock) {
                    engine = created
                    baseStyle = style
                    styleCache[style.name] = style
                }
                Logger.append(
                    "TTS",
                    "supertonic ${created.name} ${created.version} ready " +
                        "(${created.sampleRate} Hz, ${created.provider}, voice ${style.name})",
                )
                _state.value = State.Ready
                return created to style
            } catch (e: Exception) {
                created.close()
                throw e
            }
        }
    }

    /** Load a non-default voice, reusing the loaded sessions and caching the style tensors. */
    private suspend fun styleFor(name: String): SupertonicStyle? = engineMutex.withLock {
        synchronized(engineLock) { styleCache[name] }?.let { return it }
        val current = synchronized(engineLock) { engine } ?: return null
        val base = synchronized(engineLock) { baseStyle } ?: return null
        if (!SupertonicModels.isVoiceDownloaded(app, name)) {
            _state.value = State.Loading
            SupertonicModels.downloadVoice(app, name)
        }
        val style = current.loadStyle(SupertonicModels.voiceFile(app, name))
        check(current.isCompatible(base, style)) {
            "Voice $name is not compatible with the installed model"
        }
        synchronized(engineLock) {
            styleCache[name] = style
            evictStylesLocked(keep = name)
        }
        style
    }

    /** Keep the cache small; never evict the voice currently in use. */
    private fun evictStylesLocked(keep: String) {
        val maxStyles = 3
        while (styleCache.size > maxStyles) {
            val victim =
                styleCache.keys.firstOrNull { it != keep && it != baseStyle?.name } ?: return
            styleCache.remove(victim)?.let { runCatching { it.close() } }
        }
    }

    private fun reloadEngine() {
        closeEngine()
        _state.value = State.Idle
    }

    private fun closeEngine() {
        val styles =
            synchronized(engineLock) {
                val all = styleCache.values.toList()
                styleCache.clear()
                val eng = engine
                val base = baseStyle
                engine = null
                baseStyle = null
                all to eng
            }
        for (style in styles.first) runCatching { style.close() }
        runCatching { styles.second?.close() }
    }

    // ---------------------------------------------------------------- playback

    /** Stop whatever is playing so a new utterance can start immediately. */
    private fun stopCurrentPlayback() {
        val track = synchronized(trackLock) { currentTrack } ?: return
        runCatching { track.stop() }
        runCatching { track.flush() }
    }

    /**
     * Stream [wav] to the device and wait for it to finish. Returns the nanosecond timestamp at which
     * playback started, or `-1` when nothing was played.
     */
    private suspend fun play(wav: FloatArray, sampleRate: Int, id: Long): Long {
        if (wav.isEmpty() || sampleRate <= 0) return -1L
        val pcm = SupertonicDiagnostics.toPcm16(wav)
        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBuffer <= 0) {
            _state.value = State.Error("device rejected ${sampleRate}Hz mono PCM16 playback")
            return -1L
        }
        // Bounded streaming buffer; never allocate the whole utterance as the track buffer.
        val blockSize = min(pcm.size, (minBuffer * 2).coerceAtLeast(4096)) and 1.inv()

        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            _state.value = State.Error("AudioTrack failed to initialize")
            return -1L
        }

        synchronized(trackLock) { currentTrack = track }
        val startedAt = System.nanoTime()
        try {
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                if (id != latestRequest.get() || stopRequested.get()) return startedAt
                val written =
                    try {
                        track.write(pcm, offset, min(blockSize, pcm.size - offset))
                    } catch (e: IllegalStateException) {
                        // Another thread stopped the track (skip or a newer request); normal exit.
                        return startedAt
                    }
                if (written < 0) {
                    _state.value = State.Error("AudioTrack write failed ($written)")
                    return startedAt
                }
                if (written == 0) {
                    delay(5)
                    continue
                }
                offset += written
            }
            // Stream mode has no completion callback: wait for the head to reach the end, bounded by
            // the audio's own length so a stuck device cannot hang the worker forever.
            val frames = pcm.size / 2
            val timeoutNanos = ((frames.toLong() * 1_000_000_000L) / sampleRate) + 5_000_000_000L
            val deadline = System.nanoTime() + timeoutNanos
            while (id == latestRequest.get() &&
                !stopRequested.get() &&
                track.playbackHeadPosition < frames &&
                System.nanoTime() < deadline
            ) {
                delay(20)
            }
        } finally {
            synchronized(trackLock) { if (currentTrack === track) currentTrack = null }
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        return startedAt
    }
}
