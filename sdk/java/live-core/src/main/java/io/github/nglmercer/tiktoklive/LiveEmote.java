package io.github.nglmercer.tiktoklive;

/**
 * A graphical emote carried by a LIVE comment or a standalone emote message.
 *
 * <p>Every field except {@link #id()} is nullable, because proto3 cannot distinguish an absent
 * scalar from an explicit zero/empty value: a missing field is {@code null} rather than a
 * confirmed default. {@link #shortcode()} is only ever set when a textual shortcode is reliably
 * known — the v3 schema has no field verified to be the shortcode, so a numeric identifier is
 * never reinterpreted as a display name.
 *
 * @param id the original emote identifier, verbatim
 * @param shortcode the textual shortcode such as {@code [laughcry]}, when known
 * @param imageUrl artwork URL taken from the protobuf {@code ImageModel}, validated as absolute http(s)
 * @param index the raw {@code EmoteWithIndex.index}; its unit is unconfirmed, so it is preserved
 *     unmodified and never used to slice text
 * @param emoteType the raw {@code EmoteModel.emote_type} value when non-zero
 * @param packageId the original package identifier for creator-specific or custom emotes
 */
public record LiveEmote(
        String id,
        String shortcode,
        String imageUrl,
        Integer index,
        Integer emoteType,
        String packageId) {

    /** Upper bound on the emotes carried by one event. */
    public static final int MAX_EMOTES_PER_EVENT = 64;
    /** Upper bound on an emote identifier, counted in UTF-8 bytes. */
    public static final int MAX_EMOTE_ID_LENGTH = 128;
    /** Upper bound on an emote artwork URL, counted in UTF-8 bytes. */
    public static final int MAX_EMOTE_URL_LENGTH = 2048;
}
