package dev.nglmercer.tiktools.tts

import android.content.Context
import dev.nglmercer.tiktools.R
import dev.nglmercer.tiktools.core.diagnostics.Logger
import dev.nglmercer.tiktools.core.model.LiveEvent
import dev.nglmercer.tiktools.data.preferences.*
import dev.nglmercer.tiktools.tts.supertonic.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TtsRepository(
    private val context: Context,
    private val preferences: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    data class ModelState(
        val ready: Boolean = false,
        val bytes: Long = 0,
        val progress: Float? = null,
        val detail: String = "",
        val downloading: Boolean = false,
        val validating: Boolean = false,
        val error: String? = null,
        val voices: List<String> = SupertonicModels.VOICES,
        val downloadedVoices: List<String> = emptyList(),
        val selectedVoice: String = SupertonicModels.DEFAULT_VOICE,
    )

    private val _models = MutableStateFlow(ModelState())
    val models = _models.asStateFlow()
    private val _voice = MutableStateFlow<SupertonicSpeaker.State>(SupertonicSpeaker.State.Idle)
    val voice = _voice.asStateFlow()
    val lastSpoken = TtsController.lastSpoken
    private var speaker: Speaker = NoopSpeaker()
    private var engine = TtsEngine.OFF
    private val chatSpeakerTracker = ChatSpeakerTracker()
    private var watcher: Job? = null
    private var download: Job? = null
    private val engineLock = kotlinx.coroutines.sync.Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            verifyExistingModel()
            refreshModelsNow()
        }
        scope.launch {
            preferences.values
                .map { it.engine }
                .distinctUntilChanged()
                .collect { selected -> rebuild(selected) }
        }
        scope.launch {
            preferences.values
                .map { SupertonicSettings.from(it.speech) }
                .distinctUntilChanged()
                .collect { applySettings(it) }
        }
    }

    /**
     * An installation that predates the manifest (or a partial download) has files but no proven
     * digest set. Hash them once — if they match the pinned revision the model activates without the
     * user re-downloading; if not, they are reported and the user is asked to download again.
     */
    private suspend fun verifyExistingModel() {
        if (SupertonicModels.status(context) !is SupertonicModels.Status.Unverified) return
        _models.value = _models.value.copy(validating = true)
        val failures =
            try {
                SupertonicModels.verifyAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _models.value = _models.value.copy(validating = false, error = e.message)
                return
            }
        _models.value =
            _models.value.copy(
                validating = false,
                error =
                    if (failures.isEmpty()) null
                    else context.getString(R.string.speech_download_failed_detail, failures.joinToString()),
            )
    }

    private fun applySettings(settings: SupertonicSettings) {
        (speaker as? SupertonicSpeaker)?.configure(settings)
        _models.value =
            _models.value.copy(selectedVoice = settings.resolvedVoice)
    }

    private suspend fun rebuild(selected: TtsEngine) {
        engineLock.lock()
        try {
            watcher?.cancel()
            TtsController.attach(NoopSpeaker(), TtsEngine.OFF)
            val previous = speaker
            speaker = NoopSpeaker()
            withContext(Dispatchers.IO) { previous.shutdown() }
            engine = selected
            speaker =
                when (selected) {
                    TtsEngine.OFF -> NoopSpeaker()
                    TtsEngine.DEVICE -> AndroidSpeaker(context)
                    TtsEngine.SUPERTONIC -> SupertonicSpeaker(context)
                }
            (speaker as? SupertonicSpeaker)?.let { current ->
                current.configure(SupertonicSettings.from(preferences.state.value.speech))
                watcher = scope.launch { current.state.collect { _voice.value = it } }
            }
            TtsController.attach(speaker, selected)
            Logger.append("TTS", "Engine ${selected.name.lowercase()}")
        } finally {
            engineLock.unlock()
        }
    }

    suspend fun setEngine(value: TtsEngine) {
        if (preferences.state.value.engine != value) chatSpeakerTracker.reset()
        preferences.setEngine(value)
    }

    suspend fun setSpeech(value: SpeechPreferences) {
        val previous = preferences.state.value.speech
        if (previous.chat != value.chat || previous.chatNameMode != value.chatNameMode)
            chatSpeakerTracker.reset()
        preferences.setSpeech(value)
    }

    fun onEvent(event: LiveEvent) {
        val saved = preferences.state.value
        val p = saved.speech
        if (saved.engine == TtsEngine.OFF || !TtsController.enabled) {
            chatSpeakerTracker.reset()
            return
        }
        if (event.category != LiveEvent.Category.CHAT) chatSpeakerTracker.reset()
        val enabled =
            when (event.category) {
                LiveEvent.Category.CHAT -> p.chat
                LiveEvent.Category.GIFT -> p.gifts
                LiveEvent.Category.FOLLOW -> p.follows
                LiveEvent.Category.SHARE -> p.shares
                LiveEvent.Category.JOIN -> p.joins
                else -> false
            }
        if (!enabled) return
        val includeChatName =
            if (event.category == LiveEvent.Category.CHAT)
                chatSpeakerTracker.shouldAnnounce(event.user, event.at, p.chatNameMode)
            else true
        val phrases =
            SpeechPhrases(
                chatPrefix = context.getString(R.string.speech_chat_default_prefix),
                sent = context.getString(R.string.speech_sent),
                gift = context.getString(R.string.speech_gift_fallback),
                followed = context.getString(R.string.speech_followed),
                shared = context.getString(R.string.speech_shared),
                joined = context.getString(R.string.speech_joined),
                someone = context.getString(R.string.speech_someone),
            )
        TtsController.speak(
            SpeechText.forEvent(
                event,
                speakJoins = p.joins,
                includeChatName = includeChatName,
                chatPrefix = p.chatPrefix,
                phrases = phrases,
            )
        )
    }

    fun resetChatSpeaker() {
        chatSpeakerTracker.reset()
    }

    fun repeat() {
        TtsController.repeatLast()
    }

    fun skip() {
        TtsController.skip()
    }

    /** Preview the default voice without touching repeat history. */
    fun testVoice() {
        previewVoice(context.getString(R.string.speech_test_line))
    }

    /** Preview arbitrary text (and optionally a specific voice) without touching repeat history. */
    fun previewVoice(text: String, voice: String? = null) {
        val supertonic = speaker as? SupertonicSpeaker
        if (supertonic != null) supertonic.preview(text, voice)
        else TtsController.speak(text)
    }

    /** Load a voice style on demand so the user can preview or select it. */
    fun downloadVoice(name: String) {
        scope.launch(Dispatchers.IO) {
            try {
                (speaker as? SupertonicSpeaker)?.let { current ->
                    current.configure(SupertonicSettings.from(preferences.state.value.speech))
                    current.prepareVoice(name)
                } ?: SupertonicModels.downloadVoice(context, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _models.value = _models.value.copy(error = e.message)
            } finally {
                refreshModels()
            }
        }
    }

    suspend fun deleteVoice(name: String) {
        check(download?.isActive != true) { context.getString(R.string.speech_delete_error) }
        check(SupertonicModels.deleteVoice(context, name)) {
            context.getString(R.string.speech_delete_files_error)
        }
        refreshModels()
    }

    /** Force the engine to be rebuilt and re-loaded; survives voice/language changes. */
    fun reloadModel() {
        (speaker as? SupertonicSpeaker)?.reload()
    }

    fun diagnosticsDir(): File = File(context.cacheDir, SupertonicDiagnostics.DIAG_DIR)

    fun refreshModels() {
        scope.launch(Dispatchers.IO) { refreshModelsNow() }
    }

    private suspend fun refreshModelsNow() {
        val status = SupertonicModels.status(context)
        val ready = status is SupertonicModels.Status.Ready
        val bytes =
            SupertonicModels.dir(context)
                .listFiles()
                .orEmpty()
                .filter { !it.name.endsWith(".part") }
                .sumOf { it.length() }
        _models.value =
            _models.value.copy(
                ready = ready,
                bytes = bytes,
                downloadedVoices = SupertonicModels.downloadedVoices(context),
                detail =
                    context.getString(
                        when (status) {
                            is SupertonicModels.Status.Ready -> R.string.speech_model_ready
                            is SupertonicModels.Status.Unverified -> R.string.speech_model_unverified
                            is SupertonicModels.Status.Missing -> R.string.speech_model_download_hint
                        }
                    ),
            )
    }

    fun downloadModels() {
        if (download?.isActive == true) return
        _models.value =
            _models.value.copy(downloading = true, validating = false, error = null, progress = 0f)
        download =
            scope.launch {
                try {
                    SupertonicModels.download(context) { p ->
                        _models.value =
                            _models.value.copy(
                                progress = p.overall(),
                                detail = "${p.fileName} · ${p.fileIndex+1}/${p.fileCount}",
                            )
                    }
                    // Loading the engine here surfaces the Loading phase and validates the install.
                    (speaker as? SupertonicSpeaker)?.let {
                        it.configure(SupertonicSettings.from(preferences.state.value.speech))
                        it.warmup()
                    }
                } catch (e: CancellationException) {
                    _models.value =
                        _models.value.copy(
                            detail = context.getString(R.string.speech_download_canceled)
                        )
                    throw e
                } catch (e: Exception) {
                    _models.value =
                        _models.value.copy(
                            error = e.message ?: context.getString(R.string.speech_download_failed)
                        )
                } finally {
                    _models.value =
                        _models.value.copy(
                            downloading = false,
                            validating = false,
                            progress = null,
                        )
                    refreshModels()
                }
            }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    suspend fun deleteModels() {
        check(download?.isActive != true) { context.getString(R.string.speech_delete_error) }
        preferences.setEngine(TtsEngine.OFF)
        rebuild(TtsEngine.OFF)
        withContext(Dispatchers.IO) {
            check(SupertonicModels.deleteAll(context)) {
                context.getString(R.string.speech_delete_files_error)
            }
        }
        refreshModels()
    }

    suspend fun close() {
        download?.cancelAndJoin()
        watcher?.cancelAndJoin()
        TtsController.attach(NoopSpeaker(), TtsEngine.OFF)
        withContext(Dispatchers.IO) { speaker.shutdown() }
    }
}
