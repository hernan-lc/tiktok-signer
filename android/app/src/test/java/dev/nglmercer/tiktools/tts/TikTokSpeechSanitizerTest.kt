package dev.nglmercer.tiktools.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for chat cleanup before speech. No Android device, no ONNX model, no engine.
 *
 * The contract is conservative: nothing is deleted unless the event's own metadata confirms it.
 * Unknown bracketed text, numbers, emoji and multilingual text survive by default.
 */
class TikTokSpeechSanitizerTest {

    private val defaults = SanitizerSettings()
    private fun clean(
        text: String,
        settings: SanitizerSettings = defaults,
        confirmed: Set<String> = emptySet(),
    ) = TikTokSpeechSanitizer.sanitize(text, settings, confirmed)

    @Test
    fun unconfirmedShortcodesAreNeverRemoved() {
        // The provisional catalog is not evidence: without event metadata nothing is deleted.
        assertEquals("Hello [laughcry]", clean("Hello [laughcry]"))
        assertEquals("[laughcry][laughcry]", clean("[laughcry][laughcry]"))
        assertEquals("Hola [happy] amigo", clean("Hola [happy] amigo"))
        assertEquals("Hello 😂", clean("Hello 😂"))
        assertEquals("Array [1,2,3]", clean("Array [1,2,3]"))
        assertEquals("Debug [test]", clean("Debug [test]"))
        assertEquals("こんにちは [laughcry]", clean("こんにちは [laughcry]"))
    }

    @Test
    fun confirmedMetadataRemovesExactlyThatShortcode() {
        assertEquals("Hello", clean("Hello [laughcry]", confirmed = setOf("laughcry")))
        assertEquals("こんにちは", clean("こんにちは [laughcry]", confirmed = setOf("laughcry")))
        // Matching is case-insensitive and whitespace-tolerant.
        assertEquals("Debug", clean("Debug [WOW]", confirmed = setOf("wow")))
        assertEquals("Debug", clean("Debug [ wow ]", confirmed = setOf("wow")))
        // A shortcode that is not confirmed is a hypothesis that failed; it survives.
        assertEquals("Debug [nope]", clean("Debug [nope]", confirmed = setOf("wow")))
        assertEquals(
            "Debug [laughcryfan]",
            clean("Debug [laughcryfan]", confirmed = setOf("laughcry")),
        )
    }

    @Test
    fun nestedOrUnclosedBracketsAreLeftAlone() {
        // A well-formed token is still a token, even inside stray brackets; a
        // nested or unclosed bracket pair is never matched as one unit.
        assertEquals("a [b", clean("a [b [laughcry]", confirmed = setOf("laughcry")))
        assertEquals("unclosed [laughcry", clean("unclosed [laughcry", confirmed = setOf("laughcry")))
        assertEquals("empty [] stays", clean("empty [] stays", confirmed = setOf("laughcry")))
    }

    @Test
    fun structuredMetadataCanConfirmAShortcode() {
        assertEquals(
            "Hola",
            clean("Hola [custom]", confirmed = setOf("custom")),
        )
        assertEquals(
            "Debug [nope]",
            clean("Debug [nope]", confirmed = setOf("custom")),
        )
    }

    @Test
    fun unicodeAndMultilingualTextSurvive() {
        for (text in
            listOf(
                "Hola señor, ¿qué tal?",
                "こんにちは世界",
                "안녕하세요",
                "你好，世界",
                "مرحبا بالعالم",
                "नमस्ते दुनिया",
            )) {
            assertEquals(text, clean(text))
        }
    }

    @Test
    fun emojiAreKeptByDefaultAndRemovedOnlyWhenAsked() {
        assertEquals("Hello 😂", clean("Hello 😂"))
        assertEquals(
            "Hello",
            clean("Hello 😂", SanitizerSettings(removeUnicodeEmoji = true)),
        )
        // Joiners are meaningful in several scripts and are never stripped when
        // they are not joining two emoji.
        assertEquals(
            "a\u200Db",
            clean("a\u200Db", SanitizerSettings(removeUnicodeEmoji = true)),
        )
    }

    @Test
    fun emojiSequencesAreRemovedAsWholeUnits() {
        val emoji = SanitizerSettings(removeUnicodeEmoji = true)
        // Flag: a pair of regional indicators.
        assertEquals("flag", clean("flag 🇺🇸", emoji))
        // Keycap: digit + variation selector + combining enclosing keycap.
        assertEquals("key", clean("key 1️⃣", emoji))
        // Skin tone modifier stays attached to its base.
        assertEquals("wave", clean("wave 👋🏽", emoji))
        // Variation selector.
        assertEquals("love", clean("love ❤️", emoji))
        // Zero-width-joiner family.
        assertEquals("family", clean("family 👨‍👩‍👧", emoji))
        // A bare digit/#/* is not a keycap and must survive.
        assertEquals("1 2 3", clean("1 2 3", emoji))
        assertEquals("# and *", clean("# and *", emoji))
        // Combining characters are not emoji and survive intact.
        assertEquals("e\u0301", clean("e\u0301", emoji))
    }

    @Test
    fun whitespaceCollapsesOnlyWhenEnabled() {
        assertEquals("Hola amigo", clean("Hola   amigo"))
        assertEquals(
            "Hola   amigo",
            clean("Hola   amigo", SanitizerSettings(collapseWhitespace = false)),
        )
        assertEquals("line one line two", clean("  line one\nline two  "))
    }

    @Test
    fun eachCleanupCanBeTurnedOffIndependently() {
        assertEquals(
            "[laughcry]",
            clean(
                "[laughcry]",
                SanitizerSettings(removeConfirmedEmotes = false),
                confirmed = setOf("laughcry"),
            ),
        )
        assertFalse(TikTokSpeechSanitizer.sanitize("x", defaults).isBlank())
    }

    @Test
    fun theCatalogIsProvisionalNotAssumed() {
        // Documented hypotheses; the catalog is never used to delete text.
        assertTrue("laughcry" in EmoteCatalog.provisional)
        assertEquals("text [laughcry]", clean("text [laughcry]"))
    }
}
