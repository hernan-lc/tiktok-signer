package io.github.nglmercer.tiktoklive.internal;

import io.github.nglmercer.tiktoklive.*;

import webcast.model.base.Messages.ImageModel;
import webcast.model.base.emoji.Emoji.EmoteModel;
import webcast.model.base.user.User2.User;
import webcast.model.message.Messages.*;
import webcast.shared.message.Message.BaseProtoMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class EventDecoder {
    private EventDecoder() {}

    public static long count(long value) {
        return Math.min(9007199254740991L, Math.max(0, value));
    }

    private static String image(ImageModel image) {
        return image.getUrlListCount() == 0 ? "" : image.getUrlList(0);
    }

    /**
     * An absolute {@code http(s)} URL with no control characters or spaces, within the length
     * bound. Every emote field is untrusted, so anything else is dropped while the emote is kept.
     * Mirrors {@code normalize::sanitize_image_url} in Rust.
     */
    private static String sanitizeImageUrl(String url) {
        if (url == null || url.getBytes(StandardCharsets.UTF_8).length > LiveEmote.MAX_EMOTE_URL_LENGTH)
            return null;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ' ' || c < 0x20 || (c >= 0x7f && c <= 0x9f)) return null;
        }
        String rest;
        if (url.startsWith("https://")) rest = url.substring("https://".length());
        else if (url.startsWith("http://")) rest = url.substring("http://".length());
        else return null;
        return rest.isEmpty() || rest.startsWith("/") ? null : url;
    }

    /** First usable artwork URL, or {@code null} when the message carried none. */
    private static String emoteImageUrl(EmoteModel emote) {
        if (!emote.hasImage()) return null;
        var image = emote.getImage();
        for (int i = 0; i < image.getUrlListCount(); i++) {
            String safe = sanitizeImageUrl(image.getUrlList(i));
            if (safe != null) return safe;
        }
        return null;
    }

    /**
     * Maps one generated {@code EmoteModel} (plus the enclosing index when there is one) onto the
     * public {@link LiveEmote}. Returns {@code null} only when nothing usable can be kept — no id
     * and no artwork. Mirrors {@code normalize::normalize_emote} in Rust.
     */
    private static LiveEmote emote(EmoteModel emote, Integer index) {
        if (emote == null) return null;
        String id = emote.getEmoteId();
        if (id.getBytes(StandardCharsets.UTF_8).length > LiveEmote.MAX_EMOTE_ID_LENGTH) return null;
        String imageUrl = emoteImageUrl(emote);
        if (id.isEmpty() && imageUrl == null) return null;
        int emoteType = emote.getEmoteTypeValue();
        String packageId = emote.getPackageId();
        return new LiveEmote(
                id,
                null,
                imageUrl,
                index,
                emoteType != 0 ? emoteType : null,
                packageId.isEmpty() ? null : packageId);
    }

    /** Inline {@code WebcastChatMessage.emotes}, in wire order and bounded. */
    private static List<LiveEmote> inlineEmotes(WebcastChatMessage message) {
        var emotes = new ArrayList<LiveEmote>();
        for (var inline : message.getEmotesList()) {
            if (emotes.size() >= LiveEmote.MAX_EMOTES_PER_EVENT) break;
            var normalized = emote(inline.getEmote(), inline.getIndex());
            if (normalized != null) emotes.add(normalized);
        }
        return emotes;
    }

    /** Standalone {@code emote_list}; these carry no index. */
    private static List<LiveEmote> standaloneEmotes(WebcastEmoteChatMessage message) {
        var emotes = new ArrayList<LiveEmote>();
        for (var listed : message.getEmoteListList()) {
            if (emotes.size() >= LiveEmote.MAX_EMOTES_PER_EVENT) break;
            var normalized = emote(listed, null);
            if (normalized != null) emotes.add(normalized);
        }
        return emotes;
    }

    private static EventUser user(User u) {
        String avatar = image(u.getAvatarThumb());
        if (avatar.isEmpty()) avatar = image(u.getAvatarMedium());
        if (avatar.isEmpty()) avatar = image(u.getAvatarLarge());
        if (avatar.isEmpty()) avatar = image(u.getAvatarJpg());
        return new EventUser(
                Long.toString(u.getId()), u.getNickname(), u.getDisplayId(), u.getSecUid(), avatar);
    }

    public static LiveEvent decode(BaseProtoMessage envelope) {
        String method = envelope.getMethod(), id = Long.toString(envelope.getMsgId());
        boolean history = envelope.getIsHistory();
        var payload = envelope.getPayload();
        try {
            return switch (method) {
                case "WebcastChatMessage" -> {
                    var m = WebcastChatMessage.parseFrom(payload);
                    yield new ChatEvent(
                            method, id, history, user(m.getUser()), m.getContent(), inlineEmotes(m));
                }
                case "WebcastEmoteChatMessage" -> {
                    var m = WebcastEmoteChatMessage.parseFrom(payload);
                    yield new EmoteChatEvent(
                            method,
                            id,
                            history,
                            user(m.getUser()),
                            standaloneEmotes(m));
                }
                case "WebcastGiftMessage" -> {
                    var m = WebcastGiftMessage.parseFrom(payload);
                    var g = m.getGift();
                    yield new GiftEvent(
                            method,
                            id,
                            history,
                            user(m.getUser()),
                            user(m.getToUser()),
                            Long.toString(m.getGiftId()),
                            g.getName(),
                            count(g.getDiamondCount()),
                            count(m.getRepeatCount()),
                            count(m.getComboCount()),
                            Long.toString(m.getGroupId()),
                            m.getRepeatEnd() > 0,
                            "",
                            null);
                }
                case "WebcastLikeMessage" -> {
                    var m = WebcastLikeMessage.parseFrom(payload);
                    yield new LikeEvent(
                            method,
                            id,
                            history,
                            user(m.getUser()),
                            count(m.getCount()),
                            count(m.getTotal()));
                }
                case "WebcastMemberMessage" -> {
                    var m = WebcastMemberMessage.parseFrom(payload);
                    yield new MemberEvent(
                            method,
                            id,
                            history,
                            user(m.getUser()),
                            count(m.getMemberCount()),
                            count(m.getActionValue()));
                }
                case "WebcastSocialMessage" -> {
                    var m = WebcastSocialMessage.parseFrom(payload);
                    yield new SocialEvent(
                            method,
                            id,
                            history,
                            user(m.getUser()),
                            count(m.getAction()),
                            count(m.getFollowCount()),
                            count(m.getShareCount()),
                            // Retained verbatim as classification evidence; see
                            // SocialClassifier. Zero/empty means "absent or zero".
                            count(m.getShareType()),
                            m.getShareTarget(),
                            count(m.getShareDisplayStyle()),
                            count(m.getFollowType()),
                            m.getScene(),
                            // `target_user_id` is a snowflake id, so it crosses
                            // the API boundary as a string like the other ids.
                            Long.toString(m.getTargetUserId()));
                }
                case "WebcastRoomUserSeqMessage" -> {
                    var m = WebcastRoomUserSeqMessage.parseFrom(payload);
                    List<TopViewer> ranks =
                            m.getRanksList().stream()
                                    .map(
                                            r ->
                                                    new TopViewer(
                                                            count(r.getRank()),
                                                            count(r.getScore()),
                                                            count(r.getDelta()),
                                                            user(r.getUser())))
                                    .toList();
                    yield new RoomUserEvent(
                            method,
                            id,
                            history,
                            count(m.getTotal()),
                            count(m.getPopularity()),
                            count(m.getTotalUser()),
                            count(m.getAnonymous()),
                            ranks);
                }
                default -> new UnknownEvent(method, id, history, payload.toByteArray());
            };
        } catch (Exception malformed) {
            return new UnknownEvent(method, id, history, payload.toByteArray());
        }
    }
}
