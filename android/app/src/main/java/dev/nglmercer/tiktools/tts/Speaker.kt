package dev.nglmercer.tiktools.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import dev.nglmercer.tiktools.core.model.LiveEvent
import dev.nglmercer.tiktools.data.preferences.ChatNameMode
import java.util.Locale

/**
 * What the reader speaks for one event. Pure: chat reads the comment, gifts and follows announce
 * themselves, noisy categories (likes, room stats, emotes) stay silent by default. Empty string
 * means "say nothing".
 *
 * A chat comment is sanitized **before** the empty-message check and before the sender's name is
 * prepended, so a comment made only of emotes stays silent instead of announcing a bare name. Only
 * the text handed to the engine is cleaned; the stored event keeps the original comment.
 *
 * Text is **not** truncated here: long messages are chunked sentence-aware by the Supertonic engine
 * and bounded by the explicit `maxSpeechChars` LIVE policy, so words are never silently cut.
 */
object SpeechText {
    fun forEvent(
        event: LiveEvent,
        speakJoins: Boolean = false,
        includeChatName: Boolean = true,
        chatPrefix: String = "",
        phrases: SpeechPhrases = SpeechPhrases.ENGLISH,
        sanitizer: SanitizerSettings = SanitizerSettings(),
    ): String {
        val who = event.nickname.ifEmpty { event.user }.ifEmpty { phrases.someone }
        return when (event.category) {
            LiveEvent.Category.CHAT -> {
                val cleaned =
                    TikTokSpeechSanitizer.sanitize(
                        event.text,
                        sanitizer,
                        event.shortcodes(),
                    )
                if (cleaned.isBlank() && sanitizer.skipEmptyMessages) ""
                else if (!includeChatName) cleaned
                else "$who ${chatPrefix.trim().ifEmpty { phrases.chatPrefix }}: $cleaned"
            }
            LiveEvent.Category.GIFT -> "$who ${phrases.sent} ${event.text.ifEmpty { phrases.gift }}"
            LiveEvent.Category.FOLLOW -> "$who ${phrases.followed}"
            LiveEvent.Category.SHARE -> "$who ${phrases.shared}"
            LiveEvent.Category.JOIN -> if (speakJoins) "$who ${phrases.joined}" else ""
            LiveEvent.Category.EMOTE,
            LiveEvent.Category.LIKE,
            LiveEvent.Category.SOCIAL,
            LiveEvent.Category.MEMBER,
            LiveEvent.Category.ROOM,
            LiveEvent.Category.UNKNOWN -> ""
        }
    }
}

data class SpeechPhrases(
    val chatPrefix: String,
    val sent: String,
    val gift: String,
    val followed: String,
    val shared: String,
    val joined: String,
    val someone: String,
) {
    companion object {
        val ENGLISH =
            SpeechPhrases("says", "sent", "a gift", "followed", "shared the live", "joined", "someone")
    }
}

class ChatSpeakerTracker(private val repeatNameAfterMs: Long = 15_000) {
    private var lastSpeaker: String? = null
    private var lastSpokenAt: Long? = null

    fun shouldAnnounce(userId: String, eventAt: Long, mode: ChatNameMode): Boolean {
        val stableId = userId.trim().lowercase(Locale.ROOT)
        val previousAt = lastSpokenAt
        val shouldAnnounce =
            when (mode) {
                ChatNameMode.ALWAYS -> true
                ChatNameMode.NEVER -> false
                ChatNameMode.WHEN_CHANGED ->
                    stableId.isBlank() ||
                        stableId != lastSpeaker ||
                        previousAt == null ||
                        eventAt < previousAt ||
                        eventAt - previousAt > repeatNameAfterMs
            }
        if (stableId.isNotBlank()) {
            lastSpeaker = stableId
            lastSpokenAt = eventAt
        } else {
            reset()
        }
        return shouldAnnounce
    }

    fun reset() {
        lastSpeaker = null
        lastSpokenAt = null
    }
}

/**
 * TTS frontier: every component that may speak takes a [Speaker], so the future full TTS feature
 * (voices, per-event toggles, queue) plugs in without touching the event pipeline. Today the app
 * ships [NoopSpeaker] by default and [AndroidSpeaker] behind Speech settings.
 */
interface Speaker {
    val enabled: Boolean

    fun speak(text: String)

    /** Stop the current utterance, if any. The default is a no-op. */
    fun stop() = Unit

    fun shutdown()
}

/** The selectable speech engines: off, the platform voice, SuperTonic 3. */
enum class TtsEngine {
    OFF,
    DEVICE,
    SUPERTONIC,
}

/** Silent speaker: keeps the pipeline wired while TTS is off. */
class NoopSpeaker : Speaker {
    override val enabled: Boolean = false

    override fun speak(text: String) = Unit

    override fun shutdown() = Unit
}

/** Thin `TextToSpeech` wrapper: lazy init, teardown on demand, no queue. */
class AndroidSpeaker(context: Context) : Speaker {
    private var tts: TextToSpeech? = null

    override val enabled: Boolean = true

    init {
        tts =
            TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = context.resources.configuration.locales[0] ?: Locale.getDefault()
                }
            }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tiktools-${System.currentTimeMillis()}")
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
