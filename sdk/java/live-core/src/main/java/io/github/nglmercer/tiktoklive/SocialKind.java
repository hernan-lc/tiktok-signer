package io.github.nglmercer.tiktoklive;

/**
 * What a {@link SocialEvent} semantically is. Mirrors
 * {@code ttl_live_events::social::SocialKind}.
 */
public enum SocialKind {
    FOLLOW("follow"),
    SHARE("share"),
    /** A social action with a code we have not confirmed. Never folded into follow/share. */
    OTHER("other"),
    /** No usable action code. proto3 cannot tell an absent field from an explicit 0. */
    UNKNOWN("unknown");

    private final String wireName;

    SocialKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
