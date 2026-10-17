package io.github.nglmercer.tiktoklive;

/**
 * The canonical {@code WebcastSocialMessage.action} classification.
 *
 * <p>This is the single mapping table every language binding must agree with;
 * it is pinned by {@code fixtures/events/social-classification.json}. Only
 * {@code 1} (follow) and {@code 3} (share) have a confirmed meaning. Anything
 * else stays {@link SocialKind#OTHER} or {@link SocialKind#UNKNOWN} with its raw
 * code preserved.
 *
 * <p>Classification is driven by {@code action} alone: {@code follow_count} and
 * {@code share_count} are cumulative room totals and are <em>not</em> evidence
 * of a new interaction. A share message still carries a non-zero
 * {@code follow_count}, so deciding by "the first positive counter" mislabels
 * every share as a follow.
 */
public final class SocialClassifier {
    /** Confirmed follow action. */
    public static final long FOLLOW_ACTION = 1L;
    /** Confirmed share action. */
    public static final long SHARE_ACTION = 3L;

    private SocialClassifier() {}

    /** Classify a raw action code. */
    public static SocialClassification classify(long action) {
        if (action == FOLLOW_ACTION) return new SocialClassification(SocialKind.FOLLOW, "confirmed", action);
        if (action == SHARE_ACTION) return new SocialClassification(SocialKind.SHARE, "confirmed", action);
        if (action == 0L) return new SocialClassification(SocialKind.UNKNOWN, "unknown", action);
        return new SocialClassification(SocialKind.OTHER, "unknown", action);
    }
}
