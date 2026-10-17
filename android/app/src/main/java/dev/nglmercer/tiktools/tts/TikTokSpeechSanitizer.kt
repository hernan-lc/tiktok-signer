package dev.nglmercer.tiktools.tts

import java.util.Locale

/** How a chat comment is prepared before any speech engine reads it. */
data class SanitizerSettings(
    /** Remove bracketed tokens whose shortcode is in the recognised catalog. */
    val removeKnownEmotes: Boolean = true,
    /** Strip Unicode emoji. Off by default: `Hello 😂` is spoken as sent. */
    val removeUnicodeEmoji: Boolean = false,
    /** Collapse runs of whitespace and trim the result. */
    val collapseWhitespace: Boolean = true,
    /** When the cleaned comment is empty, say nothing instead of just the sender's name. */
    val skipEmptyMessages: Boolean = true,
)

/**
 * The shortcodes this build recognises as graphical emotes.
 *
 * These are **provisional**: they were not confirmed against captured LIVE traffic, and TikTok
 * does not document them. They are treated as candidates rather than facts, which is why the
 * sanitizer only removes a token that appears in this set — a name that turns out to be a normal
 * word would otherwise silently delete real text. Verified structured metadata (a shortcode on the
 * event itself) is unioned in by the caller and always wins.
 */
object EmoteCatalog {
    val known: Set<String> = setOf(
        "laughcry",
        "happy",
        "wow",
        "heart",
        "cry",
        "laugh",
        "angry",
        "shock",
        "thumbsup",
        "fire",
    )
}

/**
 * Pure text preparation for speech. It never touches the stored event: the cleaned string is only
 * ever handed to a [Speaker], so displays and logs keep the original comment.
 *
 * Unknown bracketed text is preserved on purpose. `[1,2,3]` and `[test]` are not emotes, and a
 * blanket `\[[^]]+]\]` deletion would destroy both — only shortcodes present in the catalog (or in
 * the event's own metadata) are removed.
 */
object TikTokSpeechSanitizer {

    /** A candidate emote token: one bracketed name, no nesting, bounded so a stray `[` is ignored. */
    private val TOKEN = Regex("\\[([^\\[\\]]{1,64})\\]")

    /**
     * @param text the original chat comment
     * @param settings which cleanups are enabled
     * @param knownShortcodes shortcodes confirmed by structured event metadata; unioned with
     *   [EmoteCatalog.known]
     */
    fun sanitize(
        text: String,
        settings: SanitizerSettings,
        knownShortcodes: Set<String> = emptySet(),
    ): String {
        var result = text
        if (settings.removeKnownEmotes) result = removeKnownEmotes(result, knownShortcodes)
        if (settings.removeUnicodeEmoji) result = removeEmoji(result)
        if (settings.collapseWhitespace) result = collapseWhitespace(result)
        return result
    }

    private fun removeKnownEmotes(text: String, knownShortcodes: Set<String>): String {
        if (!text.contains('[')) return text
        val catalog = if (knownShortcodes.isEmpty()) EmoteCatalog.known else EmoteCatalog.known + knownShortcodes
        return TOKEN.replace(text) { match ->
            val name = match.groupValues[1].trim().lowercase(Locale.ROOT)
            if (name in catalog) "" else match.value
        }
    }

    private fun collapseWhitespace(text: String): String {
        val builder = StringBuilder(text.length)
        var started = false
        var pendingSpace = false
        for (character in text) {
            if (character.isWhitespace()) {
                if (started) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(character)
                started = true
            }
        }
        return builder.toString()
    }

    /**
     * Uncodepoints that are unambiguously emoji. Zero-width joiners and non-joiners are left alone:
     * they are meaningful in several writing systems, and deleting them to tidy an emoji sequence
     * would corrupt valid text.
     */
    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x2B00..0x2BFF ||
            codePoint in 0xFE00..0xFE0F

    private fun removeEmoji(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!isEmoji(codePoint)) builder.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }
}
