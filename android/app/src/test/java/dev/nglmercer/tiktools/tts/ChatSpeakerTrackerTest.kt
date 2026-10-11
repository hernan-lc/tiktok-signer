package dev.nglmercer.tiktools.tts

import dev.nglmercer.tiktools.data.preferences.ChatNameMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSpeakerTrackerTest {
    @Test
    fun announcesFirstSpeakerAndOnlyRepeatsSameSpeakerAfterIdleGap() {
        val tracker = ChatSpeakerTracker(repeatNameAfterMs = 15_000)
        assertTrue(tracker.shouldAnnounce("viewer", 10_000, ChatNameMode.WHEN_CHANGED))
        assertFalse(tracker.shouldAnnounce("VIEWER", 20_000, ChatNameMode.WHEN_CHANGED))
        assertTrue(tracker.shouldAnnounce("viewer", 35_001, ChatNameMode.WHEN_CHANGED))
    }

    @Test
    fun announcesWhenStableUserChangesAndForMissingIds() {
        val tracker = ChatSpeakerTracker()
        assertTrue(tracker.shouldAnnounce("first", 1_000, ChatNameMode.WHEN_CHANGED))
        assertTrue(tracker.shouldAnnounce("second", 1_001, ChatNameMode.WHEN_CHANGED))
        assertTrue(tracker.shouldAnnounce("", 1_002, ChatNameMode.WHEN_CHANGED))
        assertTrue(tracker.shouldAnnounce("second", 1_003, ChatNameMode.WHEN_CHANGED))
    }

    @Test
    fun alwaysAndNeverModesRespectSelection() {
        val tracker = ChatSpeakerTracker()
        assertTrue(tracker.shouldAnnounce("viewer", 1_000, ChatNameMode.ALWAYS))
        assertTrue(tracker.shouldAnnounce("viewer", 1_001, ChatNameMode.ALWAYS))
        assertFalse(tracker.shouldAnnounce("viewer", 1_002, ChatNameMode.NEVER))
    }

    @Test
    fun resetStartsANewSpeakerSequence() {
        val tracker = ChatSpeakerTracker()
        assertTrue(tracker.shouldAnnounce("viewer", 1_000, ChatNameMode.WHEN_CHANGED))
        assertFalse(tracker.shouldAnnounce("viewer", 1_001, ChatNameMode.WHEN_CHANGED))
        tracker.reset()
        assertTrue(tracker.shouldAnnounce("viewer", 1_002, ChatNameMode.WHEN_CHANGED))
    }
}
