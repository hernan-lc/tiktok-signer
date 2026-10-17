package dev.nglmercer.tiktools.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed event parsing over synthetic JSON. No network, no native library. */
class EventParserTest {

    @Test
    fun chatKeepsUserAndComment() {
        val event =
            EventParser.parse(
                """{"type":"chat","comment":"hello","user":{"unique_id":"ay","nickname":"Ay"}}"""
            )
        assertEquals(LiveEvent.Category.CHAT, event.category)
        assertEquals("ay", event.user)
        assertEquals("Ay", event.nickname)
        assertEquals("hello", event.text)
    }

    @Test
    fun giftKeepsNameAndCounts() {
        val event =
            EventParser.parse(
                """{"type":"gift","gift_name":"Rose","repeat_count":5,"diamond_count":2,"user":{"unique_id":"ay"}}"""
            )
        assertEquals(LiveEvent.Category.GIFT, event.category)
        assertEquals("Rose", event.text)
        assertEquals(5, event.count)
        assertEquals(2, event.diamonds)
    }

    @Test
    fun memberActionOneIsJoinOthersStayMember() {
        val join = EventParser.parse("""{"type":"member","action":1,"user":{"unique_id":"ay"}}""")
        assertEquals(LiveEvent.Category.JOIN, join.category)
        val other = EventParser.parse("""{"type":"member","action":9,"user":{"unique_id":"ay"}}""")
        assertEquals(LiveEvent.Category.MEMBER, other.category)
        assertEquals(9, other.count)
    }

    @Test
    fun socialUsesActionNotCumulativeCounters() {
        // A real share still carries the room's cumulative follower total, so
        // the old "first positive counter" rule labelled it a follow.
        val share =
            EventParser.parse(
                """{"type":"social","action":3,"follow_count":9312,"share_count":5,"user":{"unique_id":"ay"}}"""
            )
        assertEquals(LiveEvent.Category.SHARE, share.category)
        val follow =
            EventParser.parse(
                """{"type":"social","action":1,"follow_count":9312,"share_count":5,"user":{"unique_id":"ay"}}"""
            )
        assertEquals(LiveEvent.Category.FOLLOW, follow.category)
        // An unconfirmed action stays generic social, never a forged follow/share.
        val unknown =
            EventParser.parse(
                """{"type":"social","action":97,"follow_count":9312,"user":{"unique_id":"ay"}}"""
            )
        assertEquals(LiveEvent.Category.SOCIAL, unknown.category)
        assertEquals(97, unknown.count)
        // A missing action (proto3 default 0) is unknown, not a follow.
        val bare = EventParser.parse("""{"type":"social","user":{"unique_id":"ay"}}""")
        assertEquals(LiveEvent.Category.SOCIAL, bare.category)
    }

    @Test
    fun roomUserCarriesViewerTotalWithoutAUser() {
        val event = EventParser.parse("""{"type":"room_user","total":187,"popularity":42}""")
        assertEquals(LiveEvent.Category.ROOM, event.category)
        assertEquals(187, event.count)
        assertEquals("", event.user)
    }

    @Test
    fun unknownAndGarbageDegradeToUnknown() {
        val unknown = EventParser.parse("""{"type":"mystery","method":"WebcastNew","bytes":10}""")
        assertEquals(LiveEvent.Category.UNKNOWN, unknown.category)
        assertEquals("WebcastNew", unknown.text)
        val garbage = EventParser.parse("not json")
        assertEquals(LiveEvent.Category.UNKNOWN, garbage.category)
        val empty = EventParser.parse("")
        assertEquals(LiveEvent.Category.UNKNOWN, empty.category)
    }

    @Test
    fun chatKeepsAvatarUrl() {
        val event =
            EventParser.parse(
                """{"type":"chat","comment":"hi","user":{"unique_id":"ay","nickname":"Ay","avatar_url":"https://cdn/a.webp"}}"""
            )
        assertEquals("https://cdn/a.webp", event.avatarUrl)
        assertEquals("", event.giftImageUrl)
    }

    @Test
    fun giftKeepsArtworkUrl() {
        val event =
            EventParser.parse(
                """{"type":"gift","gift_name":"Rose","gift_image_url":"https://cdn/rose.webp","user":{"unique_id":"ay"}}"""
            )
        assertEquals(LiveEvent.Category.GIFT, event.category)
        assertEquals("https://cdn/rose.webp", event.giftImageUrl)
    }

    @Test
    fun nullImageUrlsParseAsEmpty() {
        // serde serializes a missing URL as null; older builds omit the keys.
        val nulled =
            EventParser.parse(
                """{"type":"chat","comment":"hi","user":{"unique_id":"ay","avatar_url":null},"gift_image_url":null}"""
            )
        assertEquals("", nulled.avatarUrl)
        assertEquals("", nulled.giftImageUrl)
        val omitted =
            EventParser.parse("""{"type":"chat","comment":"hi","user":{"unique_id":"ay"}}""")
        assertEquals("", omitted.avatarUrl)
        assertEquals("", omitted.giftImageUrl)
    }

    @Test
    fun rawPayloadIsKeptForActionsAndDebugging() {
        val json = """{"type":"like","count":3,"user":{"unique_id":"ay"}}"""
        val event = EventParser.parse(json)
        assertEquals(LiveEvent.Category.LIKE, event.category)
        assertEquals(json, event.raw)
        assertTrue(event.at > 0)
    }

    @Test
    fun chatCarriesItsInlineEmotes() {
        val event =
            EventParser.parse(
                """{"type":"chat","comment":"Hello [laughcry]","user":{"unique_id":"ay"},
                   "emotes":[{"id":"7098","imageUrl":"https://cdn/laughcry.webp","index":6,"emoteType":1,"packageId":"p1"}]}"""
                    .trimIndent()
                    .replace("\n", "")
            )
        assertEquals(LiveEvent.Category.CHAT, event.category)
        assertEquals("Hello [laughcry]", event.text)
        assertEquals(1, event.emotes.size)
        val emote = event.emotes.single()
        assertEquals("7098", emote.id)
        assertEquals("https://cdn/laughcry.webp", emote.imageUrl)
        assertEquals(6, emote.index)
        assertEquals(1, emote.emoteType)
        assertEquals("p1", emote.packageId)
        assertEquals(null, emote.shortcode)
        assertEquals(emptySet<String>(), event.shortcodes())
    }

    @Test
    fun standaloneEmoteMessageBecomesItsOwnCategory() {
        val event =
            EventParser.parse(
                """{"type":"emote_chat","user":{"unique_id":"ay"},
                   "emotes":[{"id":"1"},{"id":"2"}]}"""
                    .replace("\n", "")
            )
        assertEquals(LiveEvent.Category.EMOTE, event.category)
        assertEquals(2, event.count)
        assertEquals("no fabricated comment", "", event.text)
        assertEquals(2, event.emotes.size)
    }

    @Test
    fun malformedEmotesAreSkippedWithoutDroppingTheEvent() {
        val event =
            EventParser.parse(
                """{"type":"chat","comment":"hi","user":{"unique_id":"ay"},
                   "emotes":[{"index":3},{"id":"ok"},{"id":"","imageUrl":""}]}"""
                    .replace("\n", "")
            )
        assertEquals(LiveEvent.Category.CHAT, event.category)
        assertEquals("hi", event.text)
        assertEquals(
            "an emote with no id and no artwork is dropped",
            1,
            event.emotes.size,
        )
        assertEquals("ok", event.emotes.single().id)
    }

    @Test
    fun anEventWithoutEmotesKeepsAnEmptyList() {
        val event = EventParser.parse("""{"type":"chat","comment":"hi","user":{"unique_id":"ay"}}""")
        assertEquals(emptyList<LiveEmote>(), event.emotes)
    }
}
