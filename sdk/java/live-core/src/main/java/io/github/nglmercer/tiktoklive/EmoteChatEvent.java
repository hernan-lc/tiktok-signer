package io.github.nglmercer.tiktoklive;

import java.util.List;

/**
 * A standalone emote message ({@code WebcastEmoteChatMessage}): one or more emotes with no
 * accompanying text comment. It is kept distinct from {@link ChatEvent} on purpose, so a text
 * comment's meaning is never invented from emotes.
 */
public record EmoteChatEvent(
        String method, String msgId, boolean isHistory, EventUser user, List<LiveEmote> emotes)
        implements LiveEvent {

    public EmoteChatEvent {
        emotes = emotes == null ? List.of() : List.copyOf(emotes);
    }

    public String type() {
        return "emoteChat";
    }
}
