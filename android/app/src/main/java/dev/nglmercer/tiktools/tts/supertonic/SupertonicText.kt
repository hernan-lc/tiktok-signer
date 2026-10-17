package dev.nglmercer.tiktools.tts.supertonic

/**
 * Sentence-aware text chunking — a port of the reference `chunk_text` from
 * `py/helper.py`, plus the LIVE speech-length policy that replaces the previous silent
 * `text.take(300)` truncation.
 *
 * Long text is split on paragraph breaks, then on sentence boundaries (with the reference's
 * abbreviation guards), then greedily packed up to [maxLen] characters. Nothing is dropped unless an
 * explicit [maxChars] cap is set, and even then whole chunks are preserved.
 */
internal object SupertonicText {

    /** Reference default: 300 characters, 120 for Korean/Japanese. */
    const val DEFAULT_MAX_CHUNK: Int = 300

    const val KOREAN_JAPANESE_MAX_CHUNK: Int = 120

    fun chunkLengthFor(lang: String): Int =
        if (lang == "ko" || lang == "ja") KOREAN_JAPANESE_MAX_CHUNK else DEFAULT_MAX_CHUNK

    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n+")

    /**
     * Sentence boundaries: `[.!?]` followed by whitespace, unless the period belongs to a known
     * abbreviation or a single capital letter initial.
     */
    private val SENTENCE_BOUNDARY =
        Regex(
            """(?<!Mr\.|Mrs\.|Ms\.|Dr\.|Prof\.|Sr\.|Jr\.|Ph\.D\.|etc\.|e\.g\.|i\.e\.|vs\.|Inc\.|Ltd\.|Co\.|Corp\.|St\.|Ave\.|Blvd\.)(?<!\b[A-Z]\.)(?<=[.!?])\s+"""
        )

    /** Merge the reference paragraph/sentence split with the greedy character-bounded pack. */
    fun chunk(text: String, maxLen: Int = DEFAULT_MAX_CHUNK): List<String> {
        require(maxLen > 0) { "maxLen must be positive, was $maxLen" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        for (paragraph in PARAGRAPH_BREAK.split(trimmed)) {
            val body = paragraph.trim()
            if (body.isEmpty()) continue
            var current = ""
            for (sentence in SENTENCE_BOUNDARY.split(body)) {
                val currentLen = codePointLength(current)
                val sentenceLen = codePointLength(sentence)
                if (currentLen + sentenceLen + 1 <= maxLen) {
                    current += (if (current.isEmpty()) "" else " ") + sentence
                } else {
                    if (current.isNotEmpty()) chunks += current.trim()
                    current = sentence
                }
            }
            if (current.isNotEmpty()) chunks += current.trim()
        }
        return chunks
    }

    /**
     * Chunks [text] and applies the LIVE speech-length policy.
     *
     * @param maxChars total characters allowed; `0` (or negative) means "speak everything".
     *   When a cap is set, only whole chunks are kept and at least one chunk is always spoken; a
     *   single over-long chunk is cut at the nearest word boundary.
     */
    fun plan(
        text: String,
        maxLen: Int = DEFAULT_MAX_CHUNK,
        maxChars: Int = 0,
    ): List<String> {
        val chunks = chunk(text, maxLen)
        if (maxChars <= 0 || chunks.isEmpty()) return chunks

        val kept = mutableListOf<String>()
        var total = 0
        for (c in chunks) {
            val length = codePointLength(c)
            if (kept.isEmpty()) {
                val first = if (length > maxChars) truncateAtWordBoundary(c, maxChars) else c
                if (first.isBlank()) break
                kept += first
                total += codePointLength(first)
            } else if (total + length <= maxChars) {
                kept += c
                total += length
            } else {
                break
            }
        }
        return kept
    }

    /** Cut to [limit] characters, backing up to the previous whitespace so words stay whole. */
    fun truncateAtWordBoundary(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (codePointLength(text) <= limit) return text
        val byCodePoints = text.codePoints().toArray()
        val slice = String(byCodePoints, 0, limit)
        val cut = slice.indexOfLast { it.isWhitespace() }
        return (if (cut > 0) slice.substring(0, cut) else slice).trim()
    }

    fun codePointLength(text: String): Int = text.codePointCount(0, text.length)
}
