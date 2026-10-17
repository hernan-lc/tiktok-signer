package io.github.nglmercer.tiktoklive.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;

import io.github.nglmercer.tiktoklive.*;

import org.junit.jupiter.api.Test;

import webcast.model.base.Messages.ImageModel;
import webcast.model.base.emoji.Emoji.EmoteModel;
import webcast.model.base.emoji.Emoji.EmoteWithIndex;
import webcast.model.message.Messages.WebcastChatMessage;
import webcast.model.message.Messages.WebcastEmoteChatMessage;
import webcast.shared.message.Message.BaseProtoMessage;

import java.util.List;

/** Emote decoding: inline chat emotes, standalone emote messages, and the degradation rules. */
class EmoteDecodeTest {

    private static EmoteModel emote(String id, String url) {
        return EmoteModel.newBuilder()
                .setEmoteId(id)
                .setImage(ImageModel.newBuilder().addUrlList(url))
                .build();
    }

    private static LiveEvent decode(String method, com.google.protobuf.Message message) {
        return EventDecoder.decode(
                BaseProtoMessage.newBuilder()
                        .setMethod(method)
                        .setMsgId(7)
                        .setPayload(ByteString.copyFrom(message.toByteArray()))
                        .build());
    }

    @Test
    void inlineEmoteCarriesItsMetadata() {
        var message =
                WebcastChatMessage.newBuilder()
                        .setContent("Hello [laughcry]")
                        .addEmotes(
                                EmoteWithIndex.newBuilder()
                                        .setIndex(6)
                                        .setEmote(
                                                emote("7098", "https://cdn.example/laughcry.webp")
                                                        .toBuilder()
                                                        .setEmoteTypeValue(1)
                                                        .setPackageId("p1")))
                        .build();
        var event = assertInstanceOf(ChatEvent.class, decode("WebcastChatMessage", message));
        assertEquals("Hello [laughcry]", event.comment());
        assertEquals(1, event.emotes().size());
        var emote = event.emotes().get(0);
        assertEquals("7098", emote.id());
        assertEquals("https://cdn.example/laughcry.webp", emote.imageUrl());
        assertEquals(6, emote.index());
        assertEquals(1, emote.emoteType());
        assertEquals("p1", emote.packageId());
        assertNull(emote.shortcode(), "no shortcode is ever fabricated");
    }

    @Test
    void urlValidationAcceptsRealHostsAndRejectsCredentialsAndObfuscation() {
        for (String url :
                List.of(
                        "http://cdn.example:8080/img/x.webp",
                        "https://[::1]:8443/img/x.webp",
                        "https://xn--r8jz45g.xn--zckzah/x.webp",
                        "https://例え.テスト/x.webp")) {
            var message =
                    WebcastChatMessage.newBuilder()
                            .setContent("[x]")
                            .addEmotes(
                                    EmoteWithIndex.newBuilder()
                                            .setIndex(0)
                                            .setEmote(emote("7", url)))
                            .build();
            var event = assertInstanceOf(ChatEvent.class, decode("WebcastChatMessage", message));
            assertEquals(url, event.emotes().get(0).imageUrl(), url);
        }
        for (String url :
                List.of(
                        "https://user@cdn.example/x.webp",
                        "https://user:pass@cdn.example/x.webp",
                        "http://cdn.example:99999/x.webp",
                        "https:///no-host",
                        "https://",
                        "http:cdn.example/x.webp",
                        "https://cdn.example/\u0007x.webp")) {
            var message =
                    WebcastChatMessage.newBuilder()
                            .setContent("[x]")
                            .addEmotes(
                                    EmoteWithIndex.newBuilder()
                                            .setIndex(0)
                                            .setEmote(emote("7", url)))
                            .build();
            var event = assertInstanceOf(ChatEvent.class, decode("WebcastChatMessage", message));
            assertNull(event.emotes().get(0).imageUrl(), url);
        }
    }

    @Test
    void anEmoteTypeOfZeroIsOmitted() {
        var message =
                WebcastChatMessage.newBuilder()
                        .setContent("[x]")
                        .addEmotes(
                                EmoteWithIndex.newBuilder()
                                        .setIndex(0)
                                        .setEmote(emote("1", "https://cdn.example/x.webp")))
                        .build();
        var event = assertInstanceOf(ChatEvent.class, decode("WebcastChatMessage", message));
        assertNull(event.emotes().get(0).emoteType());
    }

    @Test
    void unsafeOrMissingArtworkDegradesPerEmote() {
        var message =
                WebcastChatMessage.newBuilder()
                        .setContent("Bad [emote]")
                        .addEmotes(
                                EmoteWithIndex.newBuilder()
                                        .setIndex(4)
                                        .setEmote(emote("7091", "javascript:alert(1)")))
                        .addEmotes(
                                EmoteWithIndex.newBuilder()
                                        .setIndex(10)
                                        .setEmote(EmoteModel.getDefaultInstance()))
                        .build();
        var event = assertInstanceOf(ChatEvent.class, decode("WebcastChatMessage", message));
        assertEquals("Bad [emote]", event.comment());
        assertEquals(1, event.emotes().size());
        assertEquals("7091", event.emotes().get(0).id());
        assertNull(event.emotes().get(0).imageUrl(), "javascript: URL rejected");
    }

    @Test
    void aNegativeIndexIsPreserved() {
        var message =
                WebcastChatMessage.newBuilder()
                        .setContent("[x]")
                        .addEmotes(
                                EmoteWithIndex.newBuilder()
                                        .setIndex(-1)
                                        .setEmote(emote("9", "https://cdn.example/x.webp")))
                        .build();
        var event = assertInstanceOf(ChatEvent.class, decode("WebcastChatMessage", message));
        assertEquals(-1, event.emotes().get(0).index());
    }

    @Test
    void aStandaloneEmoteMessageBecomesItsOwnEvent() {
        var message =
                WebcastEmoteChatMessage.newBuilder()
                        .addEmoteList(emote("7099", "https://cdn.example/happy.webp"))
                        .addEmoteList(emote("7100", "https://cdn.example/wow.webp"))
                        .build();
        var event = assertInstanceOf(EmoteChatEvent.class, decode("WebcastEmoteChatMessage", message));
        assertEquals("emoteChat", event.type());
        assertEquals(2, event.emotes().size());
        assertEquals("7099", event.emotes().get(0).id());
        assertNull(event.emotes().get(0).index(), "standalone emotes carry no index");
        assertNull(event.emotes().get(0).packageId());
    }

    @Test
    void theEmoteCountIsCapped() {
        var builder = WebcastEmoteChatMessage.newBuilder();
        for (int i = 0; i < LiveEmote.MAX_EMOTES_PER_EVENT + 5; i++)
            builder.addEmoteList(emote(Integer.toString(i), "https://cdn.example/x.webp"));
        var event = assertInstanceOf(EmoteChatEvent.class, decode("WebcastEmoteChatMessage", builder.build()));
        assertEquals(LiveEmote.MAX_EMOTES_PER_EVENT, event.emotes().size());
    }

    @Test
    void theCompatibilityConstructorStillWorks() {
        var user = new EventUser("1", "a", "a", "", "");
        var legacy = new ChatEvent("WebcastChatMessage", "1", false, user, "hi");
        assertEquals(List.of(), legacy.emotes());
        assertEquals("chat", legacy.type());
        // The canonical six-argument form is the one emote-aware callers use.
        var withEmotes =
                new ChatEvent(
                        "WebcastChatMessage",
                        "1",
                        false,
                        user,
                        "hi",
                        List.of(new LiveEmote("1", null, null, 0, null, null)));
        assertEquals(1, withEmotes.emotes().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> withEmotes.emotes().add(new LiveEmote("2", null, null, null, null, null)));
    }

    @Test
    void malformedEmotePayloadsDoNotKillTheEvent() {
        for (String method : new String[] {"WebcastChatMessage", "WebcastEmoteChatMessage"}) {
            var event =
                    EventDecoder.decode(
                            BaseProtoMessage.newBuilder()
                                    .setMethod(method)
                                    .setPayload(ByteString.copyFrom(new byte[] {(byte) 255, (byte) 255}))
                                    .build());
            assertInstanceOf(UnknownEvent.class, event, method);
        }
    }
}
