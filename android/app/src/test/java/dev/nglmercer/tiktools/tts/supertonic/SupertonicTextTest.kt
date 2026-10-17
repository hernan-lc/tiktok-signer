package dev.nglmercer.tiktools.tts.supertonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentence-aware chunking and the LIVE speech-length policy. Reference: `py/helper.py` `chunk_text`. */
class SupertonicTextTest {

    @Test
    fun shortTextIsASingleChunk() {
        assertEquals(listOf("First. Second."), SupertonicText.chunk("First. Second.", maxLen = 300))
        assertEquals(listOf("Hello."), SupertonicText.chunk("  Hello.  ", maxLen = 300))
    }

    @Test
    fun splitsOnSentenceBoundariesOnlyWhenOverTheLimit() {
        assertEquals(
            listOf("First. Second.", "Third."),
            SupertonicText.chunk("First. Second. Third.", maxLen = 14),
        )
        // Without a limit the same text packs into one chunk.
        assertEquals(
            listOf("First. Second. Third."),
            SupertonicText.chunk("First. Second. Third.", maxLen = 300),
        )
    }

    @Test
    fun abbreviationsDoNotSplitSentences() {
        assertEquals(
            listOf("Mr. Smith went home.", "He left."),
            SupertonicText.chunk("Mr. Smith went home. He left.", maxLen = 25),
        )
        assertEquals(
            listOf("Use e.g. this one.", "Done."),
            SupertonicText.chunk("Use e.g. this one. Done.", maxLen = 20),
        )
        assertEquals(
            listOf("That is, yes."),
            SupertonicText.chunk("That is, yes.", maxLen = 300),
        )
    }

    @Test
    fun paragraphsBecomeSeparateChunks() {
        assertEquals(
            listOf("One para.", "Two para."),
            SupertonicText.chunk("One para.\n\nTwo para.", maxLen = 300),
        )
    }

    @Test
    fun anOversizedSentenceIsKeptWholeRatherThanCutMidWord() {
        val text = "word ".repeat(80).trim()
        val chunks = SupertonicText.chunk(text, maxLen = 100)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single())
        assertTrue(chunks.single().length > 100)
    }

    @Test
    fun emptyInputProducesNoChunks() {
        assertTrue(SupertonicText.chunk("").isEmpty())
        assertTrue(SupertonicText.chunk("   \n  ").isEmpty())
        assertTrue(SupertonicText.plan("", 300, 0).isEmpty())
    }

    @Test
    fun koreanAndJapaneseUseTheShorterReferenceChunkLength() {
        assertEquals(120, SupertonicText.chunkLengthFor("ko"))
        assertEquals(120, SupertonicText.chunkLengthFor("ja"))
        assertEquals(300, SupertonicText.chunkLengthFor("en"))
        assertEquals(300, SupertonicText.chunkLengthFor("na"))
    }

    @Test
    fun planWithoutACapKeepsEveryChunk() {
        val text = "First. Second. Third. Fourth."
        assertEquals(
            SupertonicText.chunk(text, maxLen = 14),
            SupertonicText.plan(text, maxLen = 14, maxChars = 0),
        )
        assertEquals(2, SupertonicText.plan(text, maxLen = 14, maxChars = 0).size)
    }

    @Test
    fun planAppliesTheCapAtWholeChunkBoundaries() {
        val text = "First. Second. Third. Fourth."
        assertEquals(
            listOf("First. Second."),
            SupertonicText.plan(text, maxLen = 14, maxChars = 14),
        )
        assertEquals(listOf("First. Second.", "Third. Fourth."), SupertonicText.plan(text, maxLen = 14, maxChars = 28))
    }

    @Test
    fun planAlwaysKeepsSomethingAndPreservesWholeWords() {
        assertEquals(listOf("alpha"), SupertonicText.plan("alpha beta gamma", maxLen = 300, maxChars = 10))
        assertEquals(listOf("hel"), SupertonicText.plan("hello", maxLen = 300, maxChars = 3))
        assertTrue(SupertonicText.plan("hello", maxLen = 300, maxChars = 1).isNotEmpty())
    }

    @Test
    fun truncateAtWordBoundaryKeepsWordsIntact() {
        assertEquals("alpha", SupertonicText.truncateAtWordBoundary("alpha beta gamma", 10))
        assertEquals("short", SupertonicText.truncateAtWordBoundary("short", 10))
        assertEquals("abc", SupertonicText.truncateAtWordBoundary("abcdef", 3))
        assertEquals("", SupertonicText.truncateAtWordBoundary("abc def", 0))
    }

    @Test
    fun codePointLengthCountsCodePointsNotUtf16Units() {
        assertEquals(3, SupertonicText.codePointLength("a\uD840\uDC00b"))
        assertEquals(4, "a\uD840\uDC00b".length)
    }
}
