package dev.nglmercer.tiktools.tts.supertonic

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference language table and the `na` fallback contract. */
class SupertonicLanguagesTest {

    @Test
    fun allReferenceCodesAreAvailable() {
        val reference =
            listOf(
                "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi",
                "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv",
                "tr", "uk", "vi", "na",
            )
        assertEquals(reference.toSet(), SupertonicLanguages.available.toSet())
        assertEquals(32, SupertonicLanguages.available.size)
        assertEquals(SupertonicLanguages.available.size, SupertonicLanguages.available.toSet().size)
    }

    @Test
    fun unknownIsTheDocumentedFallbackAndDefault() {
        assertEquals("na", SupertonicLanguages.UNKNOWN)
        assertEquals(SupertonicLanguages.UNKNOWN, SupertonicLanguages.DEFAULT)
        assertTrue(SupertonicLanguages.isValid(SupertonicLanguages.UNKNOWN))
    }

    @Test
    fun resolveTreatsNullAndBlankAsUnknown() {
        assertEquals("na", SupertonicLanguages.resolve(null))
        assertEquals("na", SupertonicLanguages.resolve(""))
        assertEquals("na", SupertonicLanguages.resolve("   "))
    }

    @Test
    fun resolveNormalizesCaseAndWhitespace() {
        assertEquals("es", SupertonicLanguages.resolve(" ES "))
        assertEquals("na", SupertonicLanguages.resolve("NA"))
    }

    @Test
    fun invalidExplicitCodesAreRejected() {
        assertFalse(SupertonicLanguages.isValid("xx"))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SupertonicLanguages.require("xx")
        }
        assertTrue(failure.message.orEmpty().contains("xx"))
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicLanguages.resolve("en-US")
        }
    }

    @Test
    fun deviceLocaleMapsWhenSupportedAndFallsBackOtherwise() {
        assertEquals("es", SupertonicLanguages.fromLocale(Locale.forLanguageTag("es-ES")))
        assertEquals("en", SupertonicLanguages.fromLocale(Locale.US))
        assertEquals("ja", SupertonicLanguages.fromLocale(Locale.JAPAN))
        // Indonesian uses the legacy Java tag `in`.
        assertEquals("id", SupertonicLanguages.fromLocale(Locale.forLanguageTag("id-ID")))
        // Unsupported languages fall back rather than being guessed.
        assertEquals("na", SupertonicLanguages.fromLocale(Locale.forLanguageTag("th-TH")))
        assertEquals("na", SupertonicLanguages.fromLocale(Locale.forLanguageTag("zh-CN")))
    }
}
