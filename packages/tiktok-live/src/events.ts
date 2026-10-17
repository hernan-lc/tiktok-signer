// Stable event normalisation over the generated v3 protobuf schemas.
//
// The generated messages are the wire/schema source of truth. This file is deliberately still a
// separate adapter: the package's listener API uses compact, stable names and number/string
// conventions that should not change whenever TikTok adds a protobuf field.

import { fromBinary } from '@bufbuild/protobuf';

import {
  WebcastChatMessageSchema,
  WebcastEmoteChatMessageSchema,
  WebcastGiftMessageSchema,
  WebcastLikeMessageSchema,
  WebcastMemberMessageSchema,
  WebcastRoomUserSeqMessageSchema,
  WebcastSocialMessageSchema,
} from './gen/webcast/model/message/messages_pb.js';
import { UserSchema } from './gen/webcast/model/base/user_2_pb.js';
import type { EmoteModel, EmoteWithIndex } from './gen/webcast/model/base/emoji_pb.js';
import type { ImageModel } from './gen/webcast/model/base/messages_pb.js';
import type { User } from './gen/webcast/model/base/user_2_pb.js';
import type { Contributor } from './gen/webcast/model/message/messages_pb.js';
import type {
  ChatEvent,
  EmoteChatEvent,
  EventUser,
  GiftEvent,
  LikeEvent,
  LiveEmote,
  LiveEvent,
  MemberEvent,
  RoomUserEvent,
  SocialEvent,
  TopViewer,
  UnknownEvent,
} from './types.js';

function methodName<T extends string>(schema: { readonly typeName: `${string}.${T}` }): T {
  return schema.typeName.slice(schema.typeName.lastIndexOf('.') + 1) as T;
}

/// Schema method names for the events normalised here, derived from generated descriptors.
export const METHOD = Object.freeze({
  chat: methodName(WebcastChatMessageSchema),
  emoteChat: methodName(WebcastEmoteChatMessageSchema),
  gift: methodName(WebcastGiftMessageSchema),
  like: methodName(WebcastLikeMessageSchema),
  member: methodName(WebcastMemberMessageSchema),
  social: methodName(WebcastSocialMessageSchema),
  roomUser: methodName(WebcastRoomUserSeqMessageSchema),
});

/// The event names a client emits, including the two that are not schema messages.
export const EVENT = Object.freeze({
  chat: 'chat',
  emoteChat: 'emoteChat',
  gift: 'gift',
  like: 'like',
  member: 'member',
  social: 'social',
  roomUser: 'roomUser',
  unknown: 'unknown',
});

/// Emote bounds, mirroring `ttl_live_events::{MAX_EMOTES_PER_EVENT, MAX_EMOTE_ID_LENGTH,
/// MAX_EMOTE_URL_LENGTH}`. Counted in UTF-8 bytes so both implementations bound the same wire data.
export const MAX_EMOTES_PER_EVENT = 64;
export const MAX_EMOTE_ID_LENGTH = 128;
export const MAX_EMOTE_URL_LENGTH = 2048;

/// UTF-8 byte length without allocating an intermediate array. Surrogate pairs
/// count as the four bytes Rust would see, so both sides bound the same wire
/// data; this runs on every emote id and URL, so it must not allocate.
function utf8Length(value: string): number {
  let bytes = 0;
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code < 0x80) bytes += 1;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff && index + 1 < value.length) {
      bytes += 4;
      index++;
    } else bytes += 3;
  }
  return bytes;
}

// --- generated message adapters ----------------------------------------------------------------

/// Convert a generated protobuf count without silently losing precision.
///
/// Counts are exposed as numbers for compatibility with the listener API. Values outside the
/// safe integer range are intentionally clamped, while negative values normalize to zero.
export function safeCount(value: bigint | number): number {
  if (typeof value === 'number' && !Number.isInteger(value)) {
    throw new RangeError('protobuf counts must be integers');
  }
  const integer = typeof value === 'bigint' ? value : BigInt(value);
  if (integer <= 0n) return 0;
  const maximum = BigInt(Number.MAX_SAFE_INTEGER);
  if (integer > maximum) return Number.MAX_SAFE_INTEGER;
  return Number(integer);
}

/// Generated int64 values remain bigint until this explicit public boundary.
const asId = (value: bigint): string => value.toString();

function firstImageUrl(image: ImageModel | undefined): string {
  return image?.urlList[0] ?? '';
}

/// Map one generated v3 user to the package's intentionally small stable user API.
function normalizeUser(user: User | undefined): EventUser {
  const avatarUrl =
    firstImageUrl(user?.avatarThumb) ||
    firstImageUrl(user?.avatarMedium) ||
    firstImageUrl(user?.avatarLarge) ||
    firstImageUrl(user?.avatarJpg) ||
    '';
  const normalized: EventUser = {
    userId: user ? asId(user.id) : '0',
    nickname: user?.nickname ?? '',
    /// The `@handle`. `display_id` in the schema, `uniqueId` in the Node connector.
    uniqueId: user?.displayId ?? '',
    secUid: user?.secUid ?? '',
  };
  if (avatarUrl) normalized.avatarUrl = avatarUrl;
  return normalized;
}

/// Decode a generated v3 User message, or return the same blank user for no nested message.
export function decodeUser(payload?: Uint8Array): EventUser {
  return normalizeUser(fromBinary(UserSchema, payload ?? new Uint8Array(), { readUnknownFields: false }));
}

/**
 * An absolute `http(s)` URL with a real hostname, no credentials, no control
 * characters or spaces, and within the length bound. Every emote field is
 * untrusted, so anything else is dropped while the emote itself is kept.
 *
 * Validation uses the platform URL parser so IPv6 literals
 * (`http://[::1]:8080/x`), explicit ports, and Unicode hostnames (decoded via
 * IDNA) are handled the same way as Rust's `url` crate. The raw authority check
 * keeps `https:///no-host` from being quietly repaired into `https://no-host/`.
 * Mirrors `normalize::sanitize_image_url` in Rust.
 */
function sanitizeImageUrl(url: string): string | undefined {
  if (utf8Length(url) > MAX_EMOTE_URL_LENGTH) return undefined;
  for (const character of url) {
    const code = character.codePointAt(0) as number;
    const control = code < 0x20 || (code >= 0x7f && code <= 0x9f);
    if (control || character === ' ') return undefined;
  }
  const separator = url.indexOf('://');
  if (separator === -1) return undefined;
  const authority = url.slice(separator + '://'.length);
  if (authority.length === 0 || authority.startsWith('/')) return undefined;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return undefined;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return undefined;
  if (parsed.username.length > 0 || parsed.password.length > 0) return undefined;
  if (parsed.hostname.length === 0) return undefined;
  return url;
}

/**
 * Map one generated emote onto the stable public shape.
 *
 * Optional fields are omitted, never sent as a "confirmed" default, and `shortcode` is never
 * fabricated: the v3 schema has no field verified to be the textual one, so a numeric id must not
 * become a display name. Mirrors `normalize::normalize_emote` in Rust.
 */
export function normalizeEmote(emote: EmoteModel | undefined, index?: number): LiveEmote | undefined {
  if (!emote) return undefined;
  if (utf8Length(emote.emoteId) > MAX_EMOTE_ID_LENGTH) return undefined;
  // Find the first usable artwork URL without building an intermediate array.
  let imageUrl: string | undefined;
  for (const candidate of emote.image?.urlList ?? []) {
    imageUrl = sanitizeImageUrl(candidate);
    if (imageUrl !== undefined) break;
  }
  if (emote.emoteId.length === 0 && imageUrl === undefined) return undefined;

  const emoteType = Number(emote.emoteType);
  const normalized: LiveEmote = { id: emote.emoteId };
  if (imageUrl !== undefined) normalized.imageUrl = imageUrl;
  if (index !== undefined) normalized.index = index;
  if (emoteType !== 0) normalized.emoteType = emoteType;
  if (emote.packageId.length > 0) normalized.packageId = emote.packageId;
  return normalized;
}

/**
 * Shared collector: normalise in wire order, drop unusable emotes, and stop as
 * soon as `MAX_EMOTES_PER_EVENT` usable ones have been collected. Bounded
 * iteration (rather than `map().filter().slice()`) means a hostile payload never
 * materialises one intermediate array per emote, and the source is abandoned
 * once the cap is reached.
 */
function collectEmotes<T>(source: readonly T[], normalize: (item: T) => LiveEmote | undefined): LiveEmote[] {
  const collected: LiveEmote[] = [];
  for (const item of source) {
    if (collected.length >= MAX_EMOTES_PER_EVENT) break;
    const normalized = normalize(item);
    if (normalized !== undefined) collected.push(normalized);
  }
  return collected;
}

/// Inline `WebcastChatMessage.emotes` (each with its raw index).
export function normalizeInlineEmotes(emotes: readonly EmoteWithIndex[]): LiveEmote[] {
  return collectEmotes(emotes, (inline) => normalizeEmote(inline.emote, inline.index));
}

/// Standalone `WebcastEmoteChatMessage.emote_list` (no index on the wire).
export function normalizeStandaloneEmotes(emotes: readonly EmoteModel[]): LiveEmote[] {
  return collectEmotes(emotes, (emote) => normalizeEmote(emote, undefined));
}

/// Best available label for a user, preferring the stable handle.
export const label = (user: EventUser): string => user.uniqueId || user.nickname || 'unknown';

function normalizeContributor(contributor: Contributor): TopViewer {
  return {
    rank: safeCount(contributor.rank),
    score: safeCount(contributor.score),
    delta: safeCount(contributor.delta),
    user: normalizeUser(contributor.user),
  };
}

/// `WebcastSocialMessage.action`, which is how a follow and a share arrive on the same message.
/// Only these two values have a confirmed meaning; see {@link classifySocialAction}.
export const SOCIAL_ACTION = Object.freeze({ follow: 1, share: 3 });

/// What a social message semantically is. Mirrors `ttl_live_events::social::SocialKind`.
export type SocialKind = 'follow' | 'share' | 'other' | 'unknown';

/// Evidence provenance, not a probability. Mirrors `ttl_live_events::social::SocialConfidence`.
export type SocialConfidence = 'confirmed' | 'inferred' | 'unknown';

export interface SocialClassification {
  kind: SocialKind;
  confidence: SocialConfidence;
  /// The raw `action` value exactly as it arrived.
  rawAction: number;
}

/**
 * Classify a `WebcastSocialMessage.action` code. This is the same table as
 * `classify_action` in `crates/ttl-live-events/src/social.rs`, pinned by
 * `fixtures/events/social-classification.json`.
 *
 * Classification is driven by `action` alone. `followCount`/`shareCount` are
 * cumulative room totals and are *not* evidence of a new interaction — a share
 * message still carries a non-zero `followCount`, so deciding by "the first
 * positive counter" mislabels every share as a follow.
 *
 * `0` is `unknown` rather than a guess: proto3 cannot distinguish an absent
 * field from an explicit zero.
 */
export function classifySocialAction(action: number): SocialClassification {
  if (action === SOCIAL_ACTION.follow) return { kind: 'follow', confidence: 'confirmed', rawAction: action };
  if (action === SOCIAL_ACTION.share) return { kind: 'share', confidence: 'confirmed', rawAction: action };
  if (action === 0) return { kind: 'unknown', confidence: 'unknown', rawAction: action };
  return { kind: 'other', confidence: 'unknown', rawAction: action };
}

type NormalizedEvent =
  | Omit<ChatEvent, 'method'>
  | Omit<EmoteChatEvent, 'method'>
  | Omit<GiftEvent, 'method'>
  | Omit<LikeEvent, 'method'>
  | Omit<MemberEvent, 'method'>
  | Omit<SocialEvent, 'method'>
  | Omit<RoomUserEvent, 'method'>;
type Normalizer = (payload: Uint8Array) => NormalizedEvent;

const NORMALIZE: Record<string, Normalizer> = {
  [METHOD.chat]: (payload) => {
    const message = fromBinary(WebcastChatMessageSchema, payload, { readUnknownFields: false });
    const emotes = normalizeInlineEmotes(message.emotes);
    return {
      type: EVENT.chat,
      user: normalizeUser(message.user),
      comment: message.content,
      // Omitted when empty so a text-only comment is unchanged from the previous release.
      ...(emotes.length > 0 ? { emotes } : {}),
    } satisfies Omit<ChatEvent, 'method'>;
  },
  [METHOD.emoteChat]: (payload) => {
    const message = fromBinary(WebcastEmoteChatMessageSchema, payload, { readUnknownFields: false });
    // A standalone emote message has no text comment; it stays a first-class event of its own.
    return {
      type: EVENT.emoteChat,
      user: normalizeUser(message.user),
      emotes: normalizeStandaloneEmotes(message.emoteList),
    } satisfies Omit<EmoteChatEvent, 'method'>;
  },
  [METHOD.gift]: (payload) => {
    const message = fromBinary(WebcastGiftMessageSchema, payload, { readUnknownFields: false });
    // The nested detail block is omitted on repeat messages of a streak, so the name and price
    // fall back rather than failing: a gift with no name is still a gift.
    return {
      type: EVENT.gift,
      user: normalizeUser(message.user),
      toUser: normalizeUser(message.toUser),
      giftId: asId(message.giftId),
      giftName: message.gift?.name ?? '',
      diamondCount: safeCount(message.gift?.diamondCount ?? 0),
      repeatCount: safeCount(message.repeatCount),
      comboCount: safeCount(message.comboCount),
      groupId: asId(message.groupId),
      /// A streak sends one message per gift; only the last one is final. Counting the others
      /// double-counts diamonds, which is the classic bug in a gift tally.
      repeatEnd: message.repeatEnd > 0,
    } satisfies Omit<GiftEvent, 'method'>;
  },
  [METHOD.like]: (payload) => {
    const message = fromBinary(WebcastLikeMessageSchema, payload, { readUnknownFields: false });
    return {
      type: EVENT.like,
      user: normalizeUser(message.user),
      count: safeCount(message.count),
      total: safeCount(message.total),
    } satisfies Omit<LikeEvent, 'method'>;
  },
  [METHOD.member]: (payload) => {
    const message = fromBinary(WebcastMemberMessageSchema, payload, { readUnknownFields: false });
    return {
      type: EVENT.member,
      user: normalizeUser(message.user),
      memberCount: safeCount(message.memberCount),
      action: safeCount(message.action),
    } satisfies Omit<MemberEvent, 'method'>;
  },
  [METHOD.social]: (payload) => {
    const message = fromBinary(WebcastSocialMessageSchema, payload, { readUnknownFields: false });
    return {
      type: EVENT.social,
      user: normalizeUser(message.user),
      action: safeCount(message.action),
      followCount: safeCount(message.followCount),
      shareCount: safeCount(message.shareCount),
      // Retained as classification evidence; see `classifySocialAction`. The
      // three below are proto3 scalars, so `0`/`''` means "absent or zero".
      shareType: safeCount(message.shareType),
      shareTarget: message.shareTarget,
      shareDisplayStyle: safeCount(message.shareDisplayStyle),
      followType: safeCount(message.followType),
      scene: message.scene,
      targetUserId: asId(message.targetUserId),
    } satisfies Omit<SocialEvent, 'method'>;
  },
  [METHOD.roomUser]: (payload) => {
    const message = fromBinary(WebcastRoomUserSeqMessageSchema, payload, { readUnknownFields: false });
    const ranks = message.ranks.map(normalizeContributor);
    return {
      type: EVENT.roomUser,
      viewers: safeCount(message.total),
      popularity: safeCount(message.popularity),
      totalUser: safeCount(message.totalUser),
      anonymous: safeCount(message.anonymous),
      // Preserve the existing public API, which exposes the ranking under both names.
      topViewers: ranks,
      rankedViewers: ranks,
    } satisfies Omit<RoomUserEvent, 'method'>;
  },
};

/// Normalise one generated message. Never throws: an unmodelled method, or a payload that does
/// not decode, becomes `unknown` with its bytes kept, so one bad event cannot take a batch down.
export function decodeEvent(method: typeof METHOD.chat, payload: Uint8Array): ChatEvent | UnknownEvent;
export function decodeEvent(method: typeof METHOD.emoteChat, payload: Uint8Array): EmoteChatEvent | UnknownEvent;
export function decodeEvent(method: typeof METHOD.gift, payload: Uint8Array): GiftEvent | UnknownEvent;
export function decodeEvent(method: typeof METHOD.like, payload: Uint8Array): LikeEvent | UnknownEvent;
export function decodeEvent(method: typeof METHOD.member, payload: Uint8Array): MemberEvent | UnknownEvent;
export function decodeEvent(method: typeof METHOD.social, payload: Uint8Array): SocialEvent | UnknownEvent;
export function decodeEvent(method: typeof METHOD.roomUser, payload: Uint8Array): RoomUserEvent | UnknownEvent;
export function decodeEvent(method: string, payload: Uint8Array): LiveEvent;
export function decodeEvent(method: string, payload: Uint8Array): LiveEvent {
  const normalize = Object.hasOwn(NORMALIZE, method) ? NORMALIZE[method] : undefined;
  if (!normalize) return { type: EVENT.unknown, method, payload };
  try {
    return { ...normalize(payload), method };
  } catch {
    return { type: EVENT.unknown, method, payload };
  }
}
