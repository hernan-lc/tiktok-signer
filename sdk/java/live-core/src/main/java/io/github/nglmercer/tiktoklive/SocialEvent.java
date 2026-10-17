package io.github.nglmercer.tiktoklive;

/**
 * A follow or share.
 *
 * <p>{@code action} is the discriminator; see {@link SocialClassifier}. The
 * remaining fields are retained verbatim as classification evidence. They are
 * proto3 scalars, so {@code 0}/empty means "absent or zero", never a confirmed
 * zero.
 */
public record SocialEvent(
        String method,
        String msgId,
        boolean isHistory,
        EventUser user,
        long action,
        long followCount,
        long shareCount,
        long shareType,
        String shareTarget,
        long shareDisplayStyle,
        long followType,
        String scene,
        String targetUserId)
        implements LiveEvent {

    public String type() {
        return "social";
    }

    /** The canonical classification of this message. */
    public SocialClassification classification() {
        return SocialClassifier.classify(action);
    }

    /** Convenience: the classified kind on its own. */
    public SocialKind kind() {
        return classification().kind();
    }
}
