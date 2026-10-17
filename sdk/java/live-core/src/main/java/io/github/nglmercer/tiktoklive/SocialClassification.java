package io.github.nglmercer.tiktoklive;

/**
 * The result of classifying one social message. {@code confidence} is evidence
 * provenance, not a probability.
 */
public record SocialClassification(SocialKind kind, String confidence, long rawAction) {}
