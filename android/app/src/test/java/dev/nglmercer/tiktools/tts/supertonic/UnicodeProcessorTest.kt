package dev.nglmercer.tiktools.tts.supertonic

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reference-pinned tests for [UnicodeProcessor].
 *
 * The expectations come from the official implementation
 * ([supertone-oss-archive/supertonic](https://github.com/supertone-oss-archive/supertonic),
 * `py/helper.py`). The identity indexer used here maps code point N to id N, so expected token ids are
 * simply the code points of the normalized, language-tagged string.
 */
class UnicodeProcessorTest {

    @get:Rule val folder = TemporaryFolder()

    /** Identity indexer covering the whole BMP, like the shipped `unicode_indexer.json`. */
    private fun processor(size: Int = 0x10000): UnicodeProcessor {
        val file = folder.newFile("unicode_indexer.json")
        file.writeText((0 until size).joinToString(",", "[", "]"))
        return UnicodeProcessor(file)
    }

    private fun expectedIds(processed: String): LongArray =
        processed.codePoints().toArray().map { it.toLong() }.toLongArray()

    // ------------------------------------------------------------------ language tags

    @Test
    fun preprocessWrapsTextInLanguageTags() {
        val p = processor()
        assertEquals("<en>Hello.</en>", p.preprocess("Hello", "en"))
        assertEquals("<es>Hola.</es>", p.preprocess("Hola", "es"))
        assertEquals("<ja>こんにちは。</ja>", p.preprocess("こんにちは。", "ja"))
        // NFKD decomposes Hangul syllables into jamo, exactly like `unicodedata.normalize("NFKD")`.
        assertEquals(
            "<ko>${java.text.Normalizer.normalize("안녕하세요", java.text.Normalizer.Form.NFKD)}.</ko>",
            p.preprocess("안녕하세요", "ko"),
        )
        assertEquals("<ar>مرحبا.</ar>", p.preprocess("مرحبا", "ar"))
    }

    @Test
    fun unknownLanguageUsesNaFallback() {
        val p = processor()
        assertEquals("<na>Some text.</na>", p.preprocess("Some text", "na"))
    }

    @Test
    fun invalidLanguageCodeIsRejected() {
        val p = processor()
        val failure =
            assertThrows(IllegalArgumentException::class.java) { p.preprocess("hi", "xx") }
        assertTrue(failure.message.orEmpty().contains("xx"))
        assertThrows(IllegalArgumentException::class.java) { p.process(listOf("hi"), listOf("en-US")) }
    }

    @Test
    fun languageTagsArePresentInTokenIds() {
        val p = processor()
        val spanish = p.process(listOf("Hola"), listOf("es"))
        assertArrayEquals(expectedIds("<es>Hola.</es>"), spanish.textIds[0])
        assertEquals("<es>Hola.</es>", spanish.processed[0])

        val english = p.process(listOf("Hola"), listOf("en"))
        // `<en>` and `<es>` differ at index 2: 'n' vs 's'.
        assertNotEquals(spanish.textIds[0][2], english.textIds[0][2])
        assertEquals(110L, english.textIds[0][2]) // 'n' in <en>
        assertEquals(115L, spanish.textIds[0][2]) // 's' in <es>
    }

    // ------------------------------------------------------------------ accents & normalization

    @Test
    fun combiningMarksSurviveNfkdInsteadOfBeingStripped() {
        val p = processor()
        // NFKD splits the precomposed letters: niño -> nin + U+0303 + o.
        assertEquals("<es>El nin\u0303o esta\u0301 aqui\u0301.</es>", p.preprocess("El niño está aquí.", "es"))
        assertEquals("<es>La cancio\u0301n.</es>", p.preprocess("La canción", "es"))
        assertEquals("<es>ra\u0301pido corazo\u0301n.</es>", p.preprocess("rápido corazón", "es"))
        // The old port deleted the marks; the reference keeps them.
        assertTrue(p.preprocess("niño", "es").contains('\u0303'))
    }

    @Test
    fun dashesUnderscoresAndQuotesAreReplaced() {
        val p = processor()
        assertEquals("<en>a-b-c.</en>", p.preprocess("a\u2013b\u2014c", "en"))
        // NFKD turns the non-breaking hyphen into U+2010 before the replacement table runs, so U+2010
        // survives — identical to the reference, which also normalizes first.
        assertEquals("<en>a\u2010b.</en>", p.preprocess("a\u2011b", "en"))
        assertEquals("<en>Hello world.</en>", p.preprocess("  Hello_world  ", "en"))
        // The reference removes the space before a single quote, so the pair collapses together.
        assertEquals("<en>\"Hi\"'there'</en>", p.preprocess("\u201cHi\u201d \u2018there\u2019", "en"))
        assertEquals("<en>'teach'</en>", p.preprocess("`teach`", "en"))
        // U+00B4 NFKD-decomposes to space + combining acute, so the mark survives and the space trims.
        assertEquals("<en>\u0301teach'</en>", p.preprocess("\u00b4teach`", "en"))
    }

    @Test
    fun bracketsBarsSlashesHashesAndArrowsBecomeSpaces() {
        val p = processor()
        assertEquals(
            "<en>x y z w v u.</en>",
            p.preprocess("[x|y/z#w\u2192v\u2190u]", "en"),
        )
    }

    @Test
    fun decorativeSymbolsAreDeleted() {
        val p = processor()
        assertEquals("<en>hi.</en>", p.preprocess("hi\u2665\u2606\u2661\u00a9", "en"))
        assertEquals("<en>.</en>", p.preprocess("\u2665\u2606\u2661\u00a9\\", "en"))
    }

    @Test
    fun knownExpressionsAreExpanded() {
        val p = processor()
        assertEquals(
            "<en>mail me at home.</en>",
            p.preprocess("mail me@home", "en"),
        )
        assertEquals(
            "<en>for example, this.</en>",
            p.preprocess("e.g., this", "en"),
        )
        assertEquals("<en>that is, so.</en>", p.preprocess("i.e., so", "en"))
    }

    @Test
    fun spacingBeforePunctuationIsRemoved() {
        val p = processor()
        assertEquals("<en>Hello, world!</en>", p.preprocess("Hello , world !", "en"))
        assertEquals("<en>Wait; what?</en>", p.preprocess("Wait ; what ?", "en"))
        assertEquals("<en>Note: it's fine.</en>", p.preprocess("Note : it 's fine .", "en"))
    }

    @Test
    fun duplicateQuotesCollapse() {
        val p = processor()
        assertEquals("<en>a \"b\"</en>", p.preprocess("a \u201c\u201cb\u201d\u201d", "en"))
        assertEquals("<en>\"hi\"</en>", p.preprocess("\"\"\"hi\"\"\"", "en"))
        assertEquals("<en>'hi'</en>", p.preprocess("'''hi'''", "en"))
    }

    @Test
    fun whitespaceIsCollapsed() {
        val p = processor()
        assertEquals("<en>a b.</en>", p.preprocess("  a   b  ", "en"))
        assertEquals("<en>a b.</en>", p.preprocess("a\u00a0b", "en"))
        assertEquals("<en>a b.</en>", p.preprocess("a\u3000b", "en"))
    }

    @Test
    fun terminalPunctuationIsPreservedNotDoubled() {
        val p = processor()
        assertEquals("<en>Really?</en>", p.preprocess("Really?", "en"))
        assertEquals("<en>Wow!</en>", p.preprocess("Wow!", "en"))
        assertEquals("<en>Done.</en>", p.preprocess("Done.", "en"))
        assertEquals("<en>End:</en>", p.preprocess("End:", "en"))
        // NFKD expands the horizontal ellipsis into three periods.
        assertEquals("<en>a...</en>", p.preprocess("a…", "en"))
        assertEquals("<en>\u00abquote\u00bb</en>", p.preprocess("\u00abquote\u00bb", "en"))
        assertEquals("<ja>「引用」</ja>", p.preprocess("「引用」", "ja"))
    }

    @Test
    fun emptyAndWhitespaceOnlyInputBecomeAPeriod() {
        val p = processor()
        assertEquals("<en>.</en>", p.preprocess("", "en"))
        assertEquals("<en>.</en>", p.preprocess("    ", "en"))
    }

    // ------------------------------------------------------------------ emoji & unicode indexing

    @Test
    fun emojiAreRemovedBeforeIndexing() {
        val p = processor()
        assertEquals("<en>Hi there.</en>", p.preprocess("Hi \uD83D\uDE00 there", "en"))
        assertArrayEquals(
            expectedIds("<en>Hi.</en>"),
            p.process(listOf("Hi \uD83D\uDE00"), listOf("en")).textIds[0],
        )
        // Heart (U+2764) and regional-indicator flags are inside the reference ranges and are removed.
        assertEquals("<en>ok.</en>", p.preprocess("ok \u2764\ud83c\uddfa\ud83c\uddf8", "en"))
        // U+2B50 sits outside the reference ranges, so it is preserved — reference fidelity, not a bug.
        assertEquals("<en>ok \u2b50.</en>", p.preprocess("ok \u2b50", "en"))
    }

    @Test
    fun zeroWidthJoinerSurvivorsAreDocumentedReferenceBehaviour() {
        // The reference emoji regex removes the pictographs individually; the ZWJ itself is not in any
        // listed range, so it remains. This documents the reference, it is not a bug we introduced.
        val p = processor()
        assertEquals("<en>\u200d\u200d.</en>", p.preprocess("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "en"))
    }

    @Test
    fun maskMatchesCodePointCountNotUtf16Length() {
        val p = processor()
        // U+20000 CJK ext-B: one code point, two UTF-16 chars, no NFKD decomposition, not an emoji.
        val surrogate = "\uD840\uDC00"
        val result = p.process(listOf(surrogate), listOf("en"))
        val processed = "<en>$surrogate.</en>"
        val codePoints = processed.codePointCount(0, processed.length)

        // 4 tag chars + 1 surrogate + '.' + 5 closing tag chars.
        assertEquals(11, codePoints)
        assertEquals(12, processed.length) // UTF-16 reports one extra unit
        assertEquals(codePoints, result.lengths[0])
        assertEquals(codePoints, result.textIds[0].size)
        assertEquals(codePoints, result.textMask[0][0].size)
        assertTrue(result.textMask[0][0].all { it == 1f })
    }

    @Test
    fun supplementaryCodePointIsNotClampedToTheLastIndexerEntry() {
        val p = processor()
        val result = p.process(listOf("\uD840\uDC00"), listOf("en"))
        // Index 4 is the surrogate pair; numpy builds the lookup as uint16, so U+20000 truncates to 0
        // — never the final indexer entry.
        assertEquals(0L, result.textIds[0][4])
        assertNotEquals(0xFFFFL, result.textIds[0][4])
    }

    @Test
    fun indexerMustCoverTheWholeBmp() {
        assertThrows(IllegalArgumentException::class.java) { processor(size = 1024) }
    }

    @Test
    fun indexerFileMustExist() {
        assertThrows(Exception::class.java) {
            UnicodeProcessor(File(folder.root, "missing.json"))
        }
    }

    // ------------------------------------------------------------------ batching & alignment

    @Test
    fun batchPadsShorterRowsWithZeroTokensAndMask() {
        val p = processor()
        val result = p.process(listOf("Hi", "Hello!"), listOf("en", "en"))
        val short = expectedIds("<en>Hi.</en>")
        val long = expectedIds("<en>Hello!</en>")
        assertEquals(2, result.textIds.size)
        assertEquals(long.size, result.textIds[0].size)

        assertArrayEquals(short, result.textIds[0].copyOf(short.size))
        assertEquals(0L, result.textIds[0][short.size])
        assertArrayEquals(long, result.textIds[1])

        assertEquals(1f, result.textMask[0][0][short.size - 1])
        assertEquals(0f, result.textMask[0][0][short.size])
        assertEquals(1f, result.textMask[1][0][long.size - 1])
    }

    @Test
    fun maskDimensionsMatchGeneratedIdsForManyLanguages() {
        val p = processor()
        val codes = listOf("en", "es", "ja", "ko", "ar", "de", "na")
        val texts = codes.map { code -> "text $code" }
        val result = p.process(texts, codes)
        val maxLen = result.textIds[0].size
        assertEquals(result.textIds.size, result.textMask.size)
        result.textIds.forEachIndexed { row, ids ->
            assertEquals(maxLen, ids.size)
            val mask = result.textMask[row][0]
            assertEquals(maxLen, mask.size)
            assertEquals(result.lengths[row], mask.count { it == 1f })
        }
    }

    @Test
    fun veryLongTextStaysAligned() {
        val p = processor()
        val text = "sentence ".repeat(400).trim()
        val result = p.process(listOf(text), listOf("en"))
        val processed = result.processed[0]
        assertEquals(processed.codePointCount(0, processed.length), result.textIds[0].size)
        assertEquals(result.textIds[0].size, result.textMask[0][0].size)
        assertTrue(result.textIds[0][0] == 60L) // '<'
    }
}
