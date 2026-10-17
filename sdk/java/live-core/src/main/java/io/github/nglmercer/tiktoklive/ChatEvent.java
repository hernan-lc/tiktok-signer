package io.github.nglmercer.tiktoklive;

import java.util.List;

public record ChatEvent(
        String method,
        String msgId,
        boolean isHistory,
        EventUser user,
        String comment,
        List<LiveEmote> emotes)
        implements LiveEvent {

    public ChatEvent {
        emotes = emotes == null ? List.of() : List.copyOf(emotes);
    }

    /**
     * Source-compatible constructor for callers written before emote support.
     *
     * <p>Adding a record component changes the canonical constructor, which is a binary
     * incompatibility for anyone who compiled against the old signature; this overload keeps the
     * five-argument source form working.
     */
    public ChatEvent(
            String method, String msgId, boolean isHistory, EventUser user, String comment) {
        this(method, msgId, isHistory, user, comment, List.of());
    }

    public String type() {
        return "chat";
    }
}
