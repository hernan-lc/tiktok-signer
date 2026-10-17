package dev.nglmercer.tiktools.tts

import java.util.Locale

/** How a chat comment is prepared before any speech engine reads it. */
data class SanitizerSettings(
    /** Remove bracketed tokens that the event's own metadata confirms are emotes. */
    val removeConfirmedEmotes: Boolean = true,
    /** Strip Unicode emoji. Off by default: `Hello 😂` is spoken as sent. */
    val removeUnicodeEmoji: Boolean = false,
    /** Collapse runs of whitespace and trim the result. */
    val collapseWhitespace: Boolean = true,
    /** When the cleaned comment is empty, say nothing instead of just the sender's name. */
    val skipEmptyMessages: Boolean = true,
)

/**
 * Candidate shortcodes seen in the wild, kept for documentation only.
 *
 * These are **provisional**: they were never confirmed against captured LIVE traffic, and TikTok
 * does not document them. They are deliberately **not** consulted by [TikTokSpeechSanitizer] — a
 * guess that turns out to be a normal word would silently delete real text from what a viewer
 * hears. Only a shortcode carried by the event itself (or a future confirmed mapping) is ever
 * removed.
 */
object EmoteCatalog {
    val provisional: Set<String> = setOf(
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
 * blanket `\[[^]]+\]` deletion would destroy both. The provisional [EmoteCatalog] is not trusted:
 * only shortcodes confirmed by the event's own metadata (or a future verified mapping) are removed.
 */
object TikTokSpeechSanitizer {

    /** A candidate emote token: one bracketed name, no nesting, bounded so a stray `[` is ignored. */
    private val TOKEN = Regex("\\[([^\\[\\]]{1,64})\\]")

    /**
     * @param text the original chat comment
     * @param settings which cleanups are enabled
     * @param confirmedShortcodes shortcodes confirmed by structured event metadata; these, and only
     *   these, are removed
     */
    fun sanitize(
        text: String,
        settings: SanitizerSettings,
        confirmedShortcodes: Set<String> = emptySet(),
    ): String {
        var result = text
        if (settings.removeConfirmedEmotes && confirmedShortcodes.isNotEmpty()) {
            result = removeConfirmedEmotes(result, confirmedShortcodes)
        }
        if (settings.removeUnicodeEmoji) result = removeEmoji(result)
        if (settings.collapseWhitespace) result = collapseWhitespace(result)
        return result
    }

    private fun removeConfirmedEmotes(text: String, confirmedShortcodes: Set<String>): String {
        if (!text.contains('[')) return text
        val confirmed = confirmedShortcodes.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        return TOKEN.replace(text) { match ->
            val name = match.groupValues[1].trim().lowercase(Locale.ROOT)
            if (name in confirmed) "" else match.value
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

    /** A code point that starts an emoji: the base of a sequence, never an optional modifier. */
    private fun isEmojiBase(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF ||
            codePoint in 0x2B00..0x2BFF ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isSkinTone(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

    private fun isVariationSelector(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

    private fun isKeycapBase(codePoint: Int): Boolean =
        codePoint in 0x30..0x39 || codePoint == 0x23 || codePoint == 0x2A

    /**
     * Removes whole emoji sequences, not lone code points: a flag keeps its pair of regional
     * indicators, a skin tone stays with its base, a keycap keeps `FE0F 20E3`, and a ZWJ family is
     * removed as one unit.
     *
     * Zero-width joiners and non-joiners are meaningful in several writing systems and are never
     * stripped on their own. A ZWJ is only consumed when it actually joins two emoji; `a\u200Db`
     * therefore survives untouched. Combining marks and multilingual text are left alone because
     * their code points are not emoji bases.
     */
    private fun removeEmoji(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (isKeycapSequenceAt(text, index)) {
                index = consumeKeycap(text, index)
            } else if (isEmojiBase(codePoint)) {
                index = consumeEmojiCluster(text, index)
            } else {
                builder.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
        return builder.toString()
    }

    /** A digit, `#` or `*` followed by an optional `FE0F` and a combining enclosing keycap. */
    private fun isKeycapSequenceAt(text: String, index: Int): Boolean {
        val base = text.codePointAt(index)
        if (!isKeycapBase(base)) return false
        var cursor = index + Character.charCount(base)
        if (cursor < text.length && text.codePointAt(cursor) == 0xFE0F) cursor += 1
        return cursor < text.length && text.codePointAt(cursor) == 0x20E3
    }

    private fun consumeKeycap(text: String, index: Int): Int {
        var cursor = index + 1
        if (cursor < text.length && text.codePointAt(cursor) == 0xFE0F) cursor += 1
        if (cursor < text.length && text.codePointAt(cursor) == 0x20E3) cursor += 1
        return cursor
    }

    /** Consumes one emoji base plus its modifiers, regional-indicator pair and ZWJ continuations. */
    private fun consumeEmojiCluster(text: String, index: Int): Int {
        var cursor = index + Character.charCount(text.codePointAt(index))
        if (isRegionalIndicator(text.codePointAt(index))) {
            if (cursor < text.length && isRegionalIndicator(text.codePointAt(cursor))) {
                cursor += Character.charCount(text.codePointAt(cursor))
            }
        }
        while (cursor < text.length) {
            val codePoint = text.codePointAt(cursor)
            when {
                isSkinTone(codePoint) || isVariationSelector(codePoint) || codePoint == 0x20E3 ->
                    cursor += Character.charCount(codePoint)
                codePoint == 0x200D -> {
                    val next = cursor + 1
                    if (next < text.length && isEmojiBase(text.codePointAt(next))) {
                        cursor = next + Character.charCount(text.codePointAt(next))
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        return cursor
    }
}
