package dev.nglmercer.tiktools.tts

import dev.nglmercer.tiktools.core.model.LiveEmote
import dev.nglmercer.tiktools.core.model.LiveEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Event → spoken text mapping. Pure; no Android TTS needed. */
class SpeechTextTest {

    @Test
    fun chatReadsWhoAndComment() {
        val event = LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "hello!", 0, 0)
        assertEquals("Ay says: hello!", SpeechText.forEvent(event))
        val noNick = event.copy(nickname = "")
        assertEquals("ay says: hello!", SpeechText.forEvent(noNick))
    }

    @Test
    fun giftFollowAndShareAnnounce() {
        assertEquals(
            "Ay sent Rose",
            SpeechText.forEvent(LiveEvent(LiveEvent.Category.GIFT, "ay", "Ay", "Rose", 1, 0)),
        )
        assertEquals(
            "ay followed",
            SpeechText.forEvent(LiveEvent(LiveEvent.Category.FOLLOW, "ay", "", "", 0, 0)),
        )
        assertEquals(
            "ay shared the live",
            SpeechText.forEvent(LiveEvent(LiveEvent.Category.SHARE, "ay", "", "", 0, 0)),
        )
    }

    @Test
    fun noisyCategoriesStaySilent() {
        for (category in
            listOf(
                LiveEvent.Category.LIKE,
                LiveEvent.Category.MEMBER,
                LiveEvent.Category.ROOM,
                LiveEvent.Category.UNKNOWN,
            )) {
            assertEquals("", SpeechText.forEvent(LiveEvent(category, "ay", "Ay", "x", 1, 0)))
        }
    }

    @Test
    fun joinsSpeakOnlyWhenAsked() {
        val join = LiveEvent(LiveEvent.Category.JOIN, "ay", "Ay", "", 0, 0)
        assertEquals("", SpeechText.forEvent(join))
        assertEquals("Ay joined", SpeechText.forEvent(join, speakJoins = true))
    }

    @Test
    fun blankChatStaysSilent() {
        assertEquals(
            "",
            SpeechText.forEvent(LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "  ", 0, 0)),
        )
    }

    @Test
    fun chatNameCanBeSuppressedAndPrefixCanBeLocalizedOrCustomized() {
        val chat = LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "hello!", 0, 0)
        assertEquals("hello!", SpeechText.forEvent(chat, includeChatName = false))
        assertEquals("Ay dice: hello!", SpeechText.forEvent(chat, chatPrefix = "dice"))
        assertEquals(
            "Ay dice: hello!",
            SpeechText.forEvent(
                chat,
                chatPrefix = "",
                phrases = SpeechPhrases("dice", "envió", "un regalo", "siguió", "compartió el LIVE", "se unió", "alguien"),
            ),
        )
    }

    @Test
    fun recognizedEmotesAreRemovedBeforeTheNameIsPrepended() {
        val chat = LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "Hello [laughcry]", 0, 0)
        assertEquals("Ay says: Hello", SpeechText.forEvent(chat))
        assertEquals("Hola amigo", SpeechText.forEvent(chat.copy(text = "Hola [happy] amigo"), includeChatName = false))
    }

    @Test
    fun anEmoteOnlyCommentStaysSilentInsteadOfSayingJustTheName() {
        val only = LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "[laughcry][laughcry]", 0, 0)
        assertEquals("", SpeechText.forEvent(only))
        // With the guard off the sender's name is still all there is to say.
        assertEquals(
            "Ay says: ",
            SpeechText.forEvent(only, sanitizer = SanitizerSettings(skipEmptyMessages = false)),
        )
    }

    @Test
    fun unknownBracketedTextAndUnicodeArePreserved() {
        assertEquals("Ay says: Array [1,2,3]", SpeechText.forEvent(
            LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "Array [1,2,3]", 0, 0),
        ))
        assertEquals("Ay says: Debug [test]", SpeechText.forEvent(
            LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "Debug [test]", 0, 0),
        ))
        assertEquals("Ay says: Hello 😂", SpeechText.forEvent(
            LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "Hello 😂", 0, 0),
        ))
        assertEquals("Ay says: こんにちは", SpeechText.forEvent(
            LiveEvent(LiveEvent.Category.CHAT, "ay", "Ay", "こんにちは [laughcry]", 0, 0),
        ))
    }

    @Test
    fun shortcodesFromEventMetadataAreAlsoRemoved() {
        val chat =
            LiveEvent(
                LiveEvent.Category.CHAT,
                "ay",
                "Ay",
                "Hola [custom]",
                0,
                0,
                emotes = listOf(LiveEmote(id = "1", shortcode = "custom")),
            )
        assertEquals("Ay says: Hola", SpeechText.forEvent(chat))
    }

    @Test
    fun aStandaloneEmoteEventStaysSilent() {
        val emoteChat =
            LiveEvent(
                LiveEvent.Category.EMOTE,
                "ay",
                "Ay",
                "",
                2,
                0,
                emotes = listOf(LiveEmote(id = "1"), LiveEmote(id = "2")),
            )
        assertEquals("", SpeechText.forEvent(emoteChat))
    }
}
