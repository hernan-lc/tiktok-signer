package dev.nglmercer.tiktools.tts

import android.content.Context
import dev.nglmercer.tiktools.R
import dev.nglmercer.tiktools.core.diagnostics.Logger
import dev.nglmercer.tiktools.core.model.LiveEvent
import dev.nglmercer.tiktools.data.preferences.*
import dev.nglmercer.tiktools.tts.supertonic.*
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
        val error: String? = null,
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
        refreshModels()
        scope.launch {
            preferences.values
                .map { it.engine }
                .distinctUntilChanged()
                .collect { selected -> rebuild(selected) }
        }
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

    fun testVoice() {
        TtsController.speak(context.getString(R.string.speech_test_line))
    }

    fun refreshModels() {
        scope.launch(Dispatchers.IO) {
            val ready = SupertonicModels.status(context) is SupertonicModels.Status.Ready
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
                    detail =
                        context.getString(
                            if (ready) R.string.speech_model_ready
                            else R.string.speech_model_download_hint
                        ),
                )
        }
    }

    fun downloadModels() {
        if (download?.isActive == true) return
        _models.value = _models.value.copy(downloading = true, error = null, progress = 0f)
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
                } catch (e: CancellationException) {
                    _models.value =
                        _models.value.copy(detail = context.getString(R.string.speech_download_canceled))
                    throw e
                } catch (e: Exception) {
                    _models.value = _models.value.copy(
                        error = e.message ?: context.getString(R.string.speech_download_failed)
                    )
                } finally {
                    _models.value = _models.value.copy(downloading = false, progress = null)
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
            check(
                !SupertonicModels.dir(context).exists() ||
                    SupertonicModels.dir(context).deleteRecursively()
            ) {
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
