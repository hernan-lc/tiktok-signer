package dev.nglmercer.tiktools.tts.supertonic

import dev.nglmercer.tiktools.tts.supertonic.SupertonicModels.Integrity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The pinned model manifest, voice catalog and integrity helpers. Pure JVM — no downloads. */
class SupertonicModelsTest {

    @get:Rule val folder = TemporaryFolder()

    private fun spec(
        size: Long,
        integrity: Integrity,
    ): SupertonicModels.FileSpec = SupertonicModels.FileSpec("onnx/x", "x", size, integrity)

    @Test
    fun manifestHasSixRuntimeFiles() {
        assertEquals(6, SupertonicModels.FILES.size)
        val locals = SupertonicModels.FILES.map { it.local }
        assertEquals(locals.size, locals.toSet().size)
        for (name in
            listOf(
                "duration_predictor.onnx",
                "text_encoder.onnx",
                "vector_estimator.onnx",
                "vocoder.onnx",
                "tts.json",
                "unicode_indexer.json",
            )) {
            assertTrue("missing $name", name in locals)
        }
    }

    @Test
    fun manifestIsPinnedToAVerifiedRevisionWithRealDigests() {
        assertEquals("Supertone/supertonic-3", SupertonicModels.REPO)
        assertEquals(40, SupertonicModels.REVISION.length)
        assertTrue(SupertonicModels.BASE_URL.contains(SupertonicModels.REPO))
        for (file in SupertonicModels.FILES) {
            val url = SupertonicModels.url(file)
            assertTrue(url, url.startsWith(SupertonicModels.BASE_URL))
            assertTrue(url, url.contains(SupertonicModels.REVISION))
            assertTrue(url, url.endsWith(file.remote))
            assertTrue("${file.local} has no size", file.size > 0)
            val digest = SupertonicIntegrity.expected(file)
            when (file.integrity) {
                is Integrity.Sha256 -> assertEquals(file.local, 64, digest.length)
                is Integrity.BlobSha1 -> assertEquals(file.local, 40, digest.length)
            }
        }
        // The weights are LFS objects, so they must be checked by SHA-256, not a git blob hash.
        assertTrue(
            SupertonicModels.FILES
                .filter { it.local.endsWith(".onnx") }
                .all { it.integrity is Integrity.Sha256 }
        )
    }

    @Test
    fun everyPublishedVoiceIsAvailableAndSharesTheRuntimeFiles() {
        assertEquals(
            listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"),
            SupertonicModels.VOICES,
        )
        assertEquals("F1", SupertonicModels.DEFAULT_VOICE)
        assertEquals("style_F1.json", SupertonicModels.STYLE_FILE)
        for (name in SupertonicModels.VOICES) {
            val voice = SupertonicModels.voiceSpec(name)
            assertEquals("voice_styles/$name.json", voice.remote)
            assertEquals("style_$name.json", voice.local)
            assertTrue(voice.size > 0)
            assertTrue(voice.integrity is Integrity.BlobSha1)
            // Voices are separate style files, never a second copy of the ONNX weights.
            assertFalse(voice.local.endsWith(".onnx"))
        }
    }

    @Test
    fun unknownVoiceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SupertonicModels.voiceSpec("X9") }
        assertThrows(IllegalArgumentException::class.java) { SupertonicModels.voiceSpec("f1") }
    }

    @Test
    fun progressSpreadsEvenlyAcrossFiles() {
        val count = SupertonicModels.FILES.size
        assertEquals(
            0.5f / count,
            SupertonicModels.Progress(0, count, "a", 50, 100).overall(),
            0.0001f,
        )
        assertEquals(
            3f / count,
            SupertonicModels.Progress(2, count, "c", 100, 100).overall(),
            0.0001f,
        )
    }

    @Test
    fun progressToleratesUnknownTotals() {
        val count = SupertonicModels.FILES.size
        assertEquals(
            1f / count,
            SupertonicModels.Progress(1, count, "b", 500, -1).overall(),
            0.0001f,
        )
    }

    @Test
    fun progressClamps() {
        val count = SupertonicModels.FILES.size
        assertEquals(1f, SupertonicModels.Progress(count - 1, count, "z", 200, 100).overall(), 0f)
        assertEquals(0f, SupertonicModels.Progress(0, count, "a", -5, 100).overall(), 0f)
    }

    // ------------------------------------------------------------------ integrity

    @Test
    fun sha256MatchesTheKnownDigestOfHello() {
        val file = folder.newFile("hello.bin")
        file.writeText("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            SupertonicIntegrity.hex(SupertonicIntegrity.sha256(file)),
        )
    }

    @Test
    fun gitBlobSha1MatchesGitHashObject() {
        val file = folder.newFile("hello.bin")
        file.writeText("hello")
        // `git hash-object` of "hello".
        assertEquals(
            "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0",
            SupertonicIntegrity.hex(SupertonicIntegrity.gitBlobSha1(file)),
        )
    }

    @Test
    fun matchesRequiresExactSizeAndDigest() {
        val file = folder.newFile("hello.bin")
        file.writeText("hello")
        val sha = spec(5L, Integrity.Sha256("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
        val blob = spec(5L, Integrity.BlobSha1("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"))
        assertTrue(SupertonicIntegrity.matches(sha, file))
        assertTrue(SupertonicIntegrity.matches(blob, file))

        // Wrong declared size, wrong digest, absent file and corrupt content all fail closed.
        assertFalse(SupertonicIntegrity.matches(spec(6L, sha.integrity), file))
        assertFalse(SupertonicIntegrity.matches(spec(5L, Integrity.Sha256("00".repeat(32))), file))
        assertFalse(SupertonicIntegrity.matches(sha, File(folder.root, "missing.bin")))
        val corrupt = folder.newFile("corrupt.bin")
        corrupt.writeText("hellp")
        assertFalse(SupertonicIntegrity.matches(sha, corrupt))
    }

    @Test
    fun digestNamesAreUserFacing() {
        assertEquals("SHA-256", spec(1L, Integrity.Sha256("a".repeat(64))).digestName)
        assertEquals("git blob SHA-1", spec(1L, Integrity.BlobSha1("a".repeat(40))).digestName)
    }
}
