package dev.nglmercer.tiktools.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for chat cleanup before speech. No Android device, no ONNX model, no engine.
 *
 * The required behavior table is the contract: recognized tokens disappear, everything else is
 * preserved exactly.
 */
class TikTokSpeechSanitizerTest {

    private val defaults = SanitizerSettings()
    private fun clean(text: String, settings: SanitizerSettings = defaults) =
        TikTokSpeechSanitizer.sanitize(text, settings)

    @Test
    fun theRequiredBehaviourTableHolds() {
        assertEquals("Hello", clean("Hello [laughcry]"))
        assertEquals("", clean("[laughcry][laughcry]"))
        assertEquals("Hola amigo", clean("Hola [happy] amigo"))
        assertEquals("Hello 😂", clean("Hello 😂"))
        assertEquals("Array [1,2,3]", clean("Array [1,2,3]"))
        assertEquals("Debug [test]", clean("Debug [test]"))
        assertEquals("こんにちは", clean("こんにちは [laughcry]"))
    }

    @Test
    fun onlyKnownShortcodesAreRemoved() {
        assertEquals("Debug", clean("Debug [wow]"))
        // An unknown shortcode is a hypothesis that failed; it survives.
        assertEquals("Debug [nope]", clean("Debug [nope]"))
        assertEquals("Debug [laughcryfan]", clean("Debug [laughcryfan]"))
        assertEquals("Debug [laugh cry]", clean("Debug [laugh cry]"))
    }

    @Test
    fun nestedOrUnclosedBracketsAreLeftAlone() {
        // A well-formed token is still a token, even inside stray brackets; a
        // nested or unclosed bracket pair is never matched as one unit.
        assertEquals("a [b", clean("a [b [laughcry]"))
        assertEquals("unclosed [laughcry", clean("unclosed [laughcry"))
        assertEquals("empty [] stays", clean("empty [] stays"))
    }

    @Test
    fun structuredMetadataCanConfirmAShortcode() {
        // A shortcode that is not in the provisional catalog is preserved...
        assertEquals("Hola [custom]", clean("Hola [custom]"))
        // ...unless the event's own metadata confirms it.
        assertEquals(
            "Hola",
            TikTokSpeechSanitizer.sanitize("Hola [custom]", defaults, setOf("custom")),
        )
        assertEquals(
            "Debug [nope]",
            TikTokSpeechSanitizer.sanitize("Debug [nope]", defaults, setOf("custom")),
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
        // Joiners are meaningful in several scripts and are never stripped.
        assertEquals(
            "a\u200Db",
            clean("a\u200Db", SanitizerSettings(removeUnicodeEmoji = true)),
        )
    }

    @Test
    fun whitespaceCollapsesOnlyWhenEnabled() {
        assertEquals("Hola amigo", clean("Hola [happy] amigo"))
        assertEquals(
            "Hola  amigo",
            clean("Hola [happy] amigo", SanitizerSettings(collapseWhitespace = false)),
        )
        assertEquals("Hola amigo", clean("Hola   [happy]   amigo"))
        assertEquals("line one line two", clean("  line one\nline two  "))
    }

    @Test
    fun eachCleanupCanBeTurnedOffIndependently() {
        assertEquals("[laughcry]", clean("[laughcry]", SanitizerSettings(removeKnownEmotes = false)))
        assertFalse(TikTokSpeechSanitizer.sanitize("x", defaults).isBlank())
    }

    @Test
    fun theCatalogIsProvisionalNotAssumed() {
        // Documented hypotheses; a name dropping out of the catalog must not break the function.
        assertTrue("laughcry" in EmoteCatalog.known)
        assertEquals("text", clean("text"))
    }
}
