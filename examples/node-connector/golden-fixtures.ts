// Golden oracle for the Rust event decoder.
//
// Node and Rust must agree on the wire format because they read the *same*
// `.proto` sources: this script uses the local `protoc-gen-es` bindings generated
// from `crates/ttl-live-proto/proto/v3`, while Rust generates Prost bindings from
// that same tree. Node is a test-time oracle only — nothing in the Rust runtime
// shells out to it.
//
//   npx tsx golden-fixtures.ts        # from examples/node-connector
//
// It decodes every fixture in `fixtures/events/` and writes the normalised form
// to `fixtures/events/expected/<name>.json`. The Rust test in
// `crates/ttl-live-events/tests/golden.rs` reads the same `.pb` files and must
// produce byte-identical JSON.
//
// Fixtures are produced beforehand by:
//
//   cargo run -p ttl-live-events --example make-fixtures -- fixtures/events
//
// so this script decoding them at all is itself the cross-implementation check:
// Rust (prost) encodes, Node (the checked-in protoc-gen-es bindings) decodes, and vice versa.
//
// All numbers are emitted as strings. TikTok user and message ids exceed 2^53,
// so a JSON number would silently lose precision on the Node side.

import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { fromBinary } from '@bufbuild/protobuf';
import {
    WebcastChatMessageSchema,
    WebcastEmoteChatMessageSchema,
    WebcastGiftMessageSchema,
    WebcastLikeMessageSchema,
    WebcastMemberMessageSchema,
    WebcastRoomUserSeqMessageSchema,
    WebcastSocialMessageSchema,
} from '../../packages/tiktok-live/src/gen/webcast/model/message/messages_pb.js';
import type { EmoteModel } from '../../packages/tiktok-live/src/gen/webcast/model/base/emoji_pb.js';
import type {
    WebcastChatMessage,
    WebcastEmoteChatMessage,
} from '../../packages/tiktok-live/src/gen/webcast/model/message/messages_pb.js';
import type { User } from '../../packages/tiktok-live/src/gen/webcast/model/base/user_2_pb.js';

const here = dirname(fileURLToPath(import.meta.url));
const fixtures = join(here, '..', '..', 'fixtures', 'events');
const expected = join(fixtures, 'expected');

/** Mirrors `EventUser::normalize` in Rust. */
function normalizeUser(u: User | undefined) {
    return {
        id: u?.id?.toString() ?? '0',
        nickname: u?.nickname ?? '',
        unique_id: u?.displayId ?? '',
        sec_uid: u?.secUid ?? '',
    };
}

/**
 * Mirrors `normalize::sanitize_image_url`: absolute http(s) only, no control
 * characters or spaces, and bounded. Anything else is dropped while the emote
 * itself is kept.
 */
function sanitizeImageUrl(url: string): string | undefined {
    if (Buffer.byteLength(url, 'utf8') > 2048) return undefined;
    for (const ch of url) {
        const code = ch.codePointAt(0)!;
        const control = code < 0x20 || (code >= 0x7f && code <= 0x9f);
        if (control || ch === ' ') return undefined;
    }
    const rest = url.startsWith('https://') ? url.slice(8)
        : url.startsWith('http://') ? url.slice(7)
        : undefined;
    if (rest === undefined || rest.length === 0 || rest.startsWith('/')) return undefined;
    return url;
}

/**
 * Mirrors `normalize::normalize_emote`. Optional fields are omitted, never sent
 * as a "confirmed" default, and there is no shortcode: the v3 schema has no
 * field verified to be the textual one.
 */
function normalizeEmote(emote: EmoteModel | undefined, index?: number) {
    if (!emote) return undefined;
    if (Buffer.byteLength(emote.emoteId, 'utf8') > 128) return undefined;
    const imageUrl = emote.image?.urlList.map(sanitizeImageUrl).find((url) => url !== undefined);
    if (emote.emoteId.length === 0 && imageUrl === undefined) return undefined;
    const out: Record<string, unknown> = { id: emote.emoteId };
    if (imageUrl !== undefined) out.imageUrl = imageUrl;
    if (index !== undefined) out.index = index;
    if (emote.emoteType !== 0) out.emoteType = emote.emoteType;
    if (emote.packageId.length > 0) out.packageId = emote.packageId;
    return out;
}

/** `WebcastChatMessage.emotes`, in wire order, capped like Rust does. */
function inlineEmotes(m: Message<'webcast.model.message.WebcastChatMessage'>): unknown[] {
    return m.emotes
        .map((inline) => normalizeEmote(inline.emote, inline.index))
        .filter((emote) => emote !== undefined)
        .slice(0, 64);
}

/** Standalone `emote_list`; these carry no index. */
function standaloneEmotes(m: Message<'webcast.model.message.WebcastEmoteChatMessage'>): unknown[] {
    return m.emoteList
        .map((emote) => normalizeEmote(emote, undefined))
        .filter((emote) => emote !== undefined)
        .slice(0, 64);
}

/** Protobuf counts are signed; the stable API clamps negatives, as Rust does. */
function count(value: bigint | number | undefined): string {
    const n = BigInt(value ?? 0);
    return (n < 0n ? 0n : n).toString();
}

/**
 * Mirrors `normalize::social` in Rust, including the auxiliary fields retained
 * as classification evidence. `share.pb` and `social-unknown.pb` decode through
 * the same shape; only their `action` differs.
 */
function socialFixture(payload: Uint8Array) {
    const m = fromBinary(WebcastSocialMessageSchema, payload);
    return {
        type: 'social',
        user: normalizeUser(m.user),
        action: String(m.action),
        follow_count: count(m.followCount),
        share_count: count(m.shareCount),
        share_type: count(m.shareType),
        share_target: m.shareTarget,
        share_display_style: count(m.shareDisplayStyle),
        follow_type: String(m.followType),
        scene: m.scene,
        target_user_id: count(m.targetUserId),
    };
}

/** Mirrors the Rust normalisers in `crates/ttl-live-events/src/normalize.rs`. */
const normalizers: Record<string, (payload: Uint8Array) => unknown> = {
    'chat.pb': (payload) => {
        const m = fromBinary(WebcastChatMessageSchema, payload);
        return { type: 'chat', user: normalizeUser(m.user), comment: m.content };
    },
    'chat-inline-emote.pb': (payload) => {
        const m = fromBinary(WebcastChatMessageSchema, payload);
        return {
            type: 'chat',
            user: normalizeUser(m.user),
            comment: m.content,
            emotes: inlineEmotes(m),
        };
    },
    'chat-multiple-emotes.pb': (payload) => {
        const m = fromBinary(WebcastChatMessageSchema, payload);
        return {
            type: 'chat',
            user: normalizeUser(m.user),
            comment: m.content,
            emotes: inlineEmotes(m),
        };
    },
    'chat-unicode-emote.pb': (payload) => {
        const m = fromBinary(WebcastChatMessageSchema, payload);
        return {
            type: 'chat',
            user: normalizeUser(m.user),
            comment: m.content,
            emotes: inlineEmotes(m),
        };
    },
    'chat-invalid-emote.pb': (payload) => {
        const m = fromBinary(WebcastChatMessageSchema, payload);
        // A malformed emote degrades per emote; the comment is never dropped.
        return {
            type: 'chat',
            user: normalizeUser(m.user),
            comment: m.content,
            emotes: inlineEmotes(m),
        };
    },
    'emote-chat.pb': (payload) => {
        const m = fromBinary(WebcastEmoteChatMessageSchema, payload);
        return { type: 'emote_chat', user: normalizeUser(m.user), emotes: standaloneEmotes(m) };
    },
    'gift.pb': (payload) => {
        const m = fromBinary(WebcastGiftMessageSchema, payload);
        return {
            type: 'gift',
            user: normalizeUser(m.user),
            gift_id: count(m.giftId),
            gift_name: m.gift?.name ?? '',
            diamond_count: count(m.gift?.diamondCount),
            repeat_count: count(m.repeatCount),
            combo_count: count(m.comboCount),
            group_id: count(m.groupId),
            repeat_end: m.repeatEnd !== 0,
        };
    },
    'like.pb': (payload) => {
        const m = fromBinary(WebcastLikeMessageSchema, payload);
        return {
            type: 'like',
            user: normalizeUser(m.user),
            count: count(m.count),
            total: count(m.total),
        };
    },
    'member.pb': (payload) => {
        const m = fromBinary(WebcastMemberMessageSchema, payload);
        return {
            type: 'member',
            user: normalizeUser(m.user),
            member_count: count(m.memberCount),
            action: String(m.action),
        };
    },
    'social.pb': socialFixture,
    'share.pb': socialFixture,
    'social-unknown.pb': socialFixture,
    'room-user.pb': (payload) => {
        const m = fromBinary(WebcastRoomUserSeqMessageSchema, payload);
        return {
            type: 'room_user',
            total: count(m.total),
            popularity: count(m.popularity),
            total_user: count(m.totalUser),
            anonymous: count(m.anonymous),
        };
    },
};

mkdirSync(expected, { recursive: true });

const present = readdirSync(fixtures).filter((name) => name.endsWith('.pb')).sort();
let written = 0;

for (const name of present) {
    const normalize = normalizers[name];
    if (!normalize) {
        // Fixtures for events Rust reports as Unknown have no expected JSON;
        // the Rust test asserts they survive with method and payload intact.
        console.log(`skipped  ${name} (no normaliser — Unknown fallback)`);
        continue;
    }
    const json = JSON.stringify(normalize(readFileSync(join(fixtures, name))), null, 2);
    writeFileSync(join(expected, name.replace(/\.pb$/, '.json')), `${json}\n`);
    console.log(`expected ${name}`);
    written += 1;
}

console.log(`\n${written} golden files in ${expected}`);
