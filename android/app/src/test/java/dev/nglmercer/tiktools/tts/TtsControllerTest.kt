package dev.nglmercer.tiktools.tts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TtsController] repeat / skip / visibility rules. Pure; no Android TTS needed. */
class TtsControllerTest {

    private class FakeSpeaker : Speaker {
        override val enabled: Boolean = true
        val spoken = mutableListOf<String>()
        var stops = 0

        override fun speak(text: String) {
            spoken.add(text)
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() = Unit
    }

    @After
    fun tearDown() {
        TtsController.resetForTest()
    }

    @Test
    fun speakRemembersLastLine() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.DEVICE)
        TtsController.speak("hello")
        assertEquals("hello", TtsController.lastSpoken.value)
        assertEquals(listOf("hello"), fake.spoken)
    }

    @Test
    fun blankLinesAreNeitherSpokenNorRemembered() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.DEVICE)
        TtsController.speak("  ")
        assertNull(TtsController.lastSpoken.value)
        assertTrue(fake.spoken.isEmpty())
    }

    @Test
    fun offEngineStaysSilent() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.OFF)
        TtsController.speak("hello")
        assertFalse(TtsController.enabled)
        assertNull(TtsController.lastSpoken.value)
        assertTrue(fake.spoken.isEmpty())
    }

    @Test
    fun repeatReplaysLastLineAfterStopping() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.DEVICE)
        TtsController.speak("first")
        TtsController.speak("second")
        assertTrue(TtsController.repeatLast())
        assertEquals(listOf("first", "second", "second"), fake.spoken)
        assertEquals(1, fake.stops)
    }

    @Test
    fun repeatWithNothingSpokenReturnsFalse() {
        TtsController.attach(FakeSpeaker(), TtsEngine.DEVICE)
        assertFalse(TtsController.repeatLast())
    }

    @Test
    fun repeatWhileOffReturnsFalse() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.DEVICE)
        TtsController.speak("hello")
        TtsController.attach(fake, TtsEngine.OFF)
        assertFalse(TtsController.repeatLast())
    }

    @Test
    fun engineSwitchForgetsLastLine() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.DEVICE)
        TtsController.speak("hello")
        TtsController.attach(fake, TtsEngine.SUPERTONIC)
        assertNull(TtsController.lastSpoken.value)
        assertFalse(TtsController.repeatLast())
    }

    @Test
    fun skipStopsSpeaker() {
        val fake = FakeSpeaker()
        TtsController.attach(fake, TtsEngine.DEVICE)
        TtsController.skip()
        assertEquals(1, fake.stops)
    }

    @Test
    fun controlsShowOnlyWhenEngineEnabled() {
        assertFalse(TtsController.controlsVisible(TtsEngine.OFF))
        assertTrue(TtsController.controlsVisible(TtsEngine.DEVICE))
        assertTrue(TtsController.controlsVisible(TtsEngine.SUPERTONIC))
    }

    /**
     * Models the old Supertonic defect: a speaker that silently drops a request while it is already
     * synthesizing or playing. Repeat must therefore always unblock the speaker with `stop()` first.
     */
    private class BusyAwareSpeaker : Speaker {
        override val enabled: Boolean = true
        val spoken = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var stops = 0
        private var busy = false

        override fun speak(text: String) {
            if (busy) {
                dropped.add(text)
                return
            }
            spoken.add(text)
            busy = true
        }

        override fun stop() {
            stops++
            busy = false
        }

        override fun shutdown() = Unit

        fun finish() {
            busy = false
        }
    }

    @Test
    fun repeatUnblocksABusySpeakerInsteadOfDroppingTheRequest() {
        val busy = BusyAwareSpeaker()
        TtsController.attach(busy, TtsEngine.SUPERTONIC)
        TtsController.speak("first")
        // Still synthesizing/playing the first line.
        assertTrue(TtsController.repeatLast())
        assertEquals(listOf("first", "first"), busy.spoken)
        assertTrue("repeat must not be dropped", busy.dropped.isEmpty())
        assertEquals(1, busy.stops)
    }

    @Test
    fun repeatKeepsTheRememberedLineSoItCanBeRepeatedAgain() {
        val busy = BusyAwareSpeaker()
        TtsController.attach(busy, TtsEngine.SUPERTONIC)
        TtsController.speak("hello")
        assertTrue(TtsController.repeatLast())
        busy.finish()
        assertTrue(TtsController.repeatLast())
        assertEquals(listOf("hello", "hello", "hello"), busy.spoken)
        assertEquals("hello", TtsController.lastSpoken.value)
    }

    @Test
    fun skipDuringPlaybackOnlyStopsAndKeepsTheRememberedLine() {
        val busy = BusyAwareSpeaker()
        TtsController.attach(busy, TtsEngine.SUPERTONIC)
        TtsController.speak("hello")
        TtsController.skip()
        assertEquals(1, busy.stops)
        assertEquals("hello", TtsController.lastSpoken.value)
        assertTrue(TtsController.repeatLast())
    }

    @Test
    fun aNewerRequestIsDeliveredOnceTheSpeakerIsUnblocked() {
        val busy = BusyAwareSpeaker()
        TtsController.attach(busy, TtsEngine.SUPERTONIC)
        TtsController.speak("one")
        // The interrupt itself lives in SupertonicSpeaker.speak(); a bare [Speaker] needs stop().
        TtsController.skip()
        TtsController.speak("two")
        assertEquals(listOf("one", "two"), busy.spoken)
        assertTrue(busy.dropped.isEmpty())
        assertEquals("two", TtsController.lastSpoken.value)
    }
}
