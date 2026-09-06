// Everything about a room that needs no signature.
//
// Which is nearly everything: `unique_id` → `room_id`, room metadata, the gift table, and the list
// of who is broadcasting now. All four were signed here until a one-character tamper test showed
// none of them verifies a signature — a correct signature, a corrupted one, and none at all return
// the same data. Only the socket checks. `scripts/headless/verify-probe.mjs` reproduces it.
//
// The Rust statement of these URLs and shapes is `crates/ttl-sign-core/src/room.rs`; this is the
// same set, and `test/discovery.test.ts` pins the parsing against recorded shapes.

import { USER_AGENT } from './session.js';
import { validateJson } from './json-validation.js';
import {
  TikTokGiftListResponseSchema,
  type TikTokGiftListResponse,
} from './gen/json/tiktok/gift-list.js';
import {
  TikTokLiveSearchResponseSchema,
  type TikTokLiveSearchResponse,
} from './gen/json/tiktok/live-search.js';
import {
  TikTokRoomInfoResponseSchema,
  type TikTokRoomInfoResponse,
} from './gen/json/tiktok/room-info.js';
import {
  TikTokRoomLookupResponseSchema,
  type TikTokRoomLookupResponse,
} from './gen/json/tiktok/room-lookup.js';
import type { TikTokImage } from './gen/json/tiktok/image.js';
import {
  TikTokSearchRoomSchema,
  type TikTokSearchRoom,
} from './gen/json/tiktok/search-room.js';
import type { Gift, LiveRoom, RoomInfo, RoomLookup } from './types.js';

const WEBCAST_BASE = 'https://webcast.tiktok.com/webcast';
const AID = '1988';

/// TikTok reports `2` while broadcasting and `4` once a session ends. The `room_id` survives the
/// end of a broadcast, so "has a room id" is not "is live": signing a finished room yields a
/// handshake that is refused in a way indistinguishable from a bad signature.
export const ROOM_STATUS_LIVE = 2;

export const roomLookupUrl = (uniqueId: string): string =>
  `https://www.tiktok.com/api-live/user/room/?aid=${AID}&sourceType=54&uniqueId=${encodeURIComponent(strip(uniqueId))}`;

const webcastUrl = (path: string, roomId: string): string =>
  `${WEBCAST_BASE}/${path}/?aid=${AID}&app_language=en&device_platform=web&room_id=${encodeURIComponent(roomId)}`;

export const roomInfoUrl = (roomId: string): string => webcastUrl('room/info', roomId);
export const giftListUrl = (roomId: string): string => webcastUrl('gift/list', roomId);

/// The live search endpoint, which returns as JSON what `/live` renders with JavaScript.
export function liveSearchUrl(keyword = 'live', offset = 0): string {
  const pairs: ReadonlyArray<readonly [string, string]> = [
    ['aid', AID], ['app_language', 'en'], ['app_name', 'tiktok_web'],
    ['browser_language', 'en-US'], ['browser_name', 'Mozilla'],
    ['browser_platform', 'Linux x86_64'], ['browser_version', '5.0 (X11)'],
    ['cookie_enabled', 'true'], ['count', '20'], ['device_platform', 'web_pc'],
    ['focus_state', 'true'], ['from_page', 'search'], ['history_len', '4'],
    ['is_fullscreen', 'false'], ['is_page_visible', 'true'], ['keyword', keyword],
    ['offset', String(offset)], ['os', 'linux'], ['priority_region', 'US'], ['region', 'US'],
    ['screen_height', '1080'], ['screen_width', '1920'], ['tz_name', 'America/New_York'],
    ['webcast_language', 'en'],
  ];
  return `https://www.tiktok.com/api/search/live/full/?${pairs
    .map(([key, value]) => `${key}=${encodeURIComponent(value)}`)
    .join('&')}`;
}

const strip = (uniqueId: string): string => uniqueId.replace(/^@/, '');

/// A refusal TikTok reports while still answering 200.
export class WebcastRefusal extends Error {
  readonly statusCode: number;

  constructor(statusCode: number, message?: string) {
    super(message || `TikTok refused the request (status_code=${statusCode})`);
    this.name = 'WebcastRefusal';
    this.statusCode = statusCode;
  }
}

/// Convert a JSON-boundary identifier without accepting a precision-unsafe number.
export function parseId(value: unknown): string {
  if (typeof value === 'string' && /^[0-9]+$/.test(value)) return value;
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return String(value);
  throw new TypeError('invalid or precision-unsafe id');
}

/// Reads the unsigned endpoints.
export class Discovery {
  cookie: string;
  readonly userAgent: string;
  readonly timeoutMs: number;

  constructor({ cookie = '', userAgent = USER_AGENT, timeoutMs = 10_000 }: DiscoveryOptions = {}) {
    this.cookie = cookie;
    this.userAgent = userAgent;
    this.timeoutMs = timeoutMs;
  }

  /// Update the jar after an anonymous bootstrap. The discovery object is created in the client
  /// constructor, before the first asynchronous connection has a chance to visit `/live`.
  setCookie(cookie: string): void {
    this.cookie = cookie;
  }

  async #json<T>(
    url: string,
    endpoint: string,
    validator: (value: unknown) => T,
  ): Promise<T> {
    const response = await fetch(url, {
      headers: {
        'user-agent': this.userAgent,
        referer: 'https://www.tiktok.com/',
        ...(this.cookie ? { cookie: this.cookie } : {}),
      },
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status} from ${new URL(url).pathname}`);
    let raw: unknown;
    try {
      raw = await response.json();
    } catch {
      throw new TypeError(`Invalid ${endpoint}: $ expected valid JSON`);
    }
    return validator(raw);
  }

  /// `@handle` → room. Returns `{ uniqueId, roomId, nickname, status, title, isLive }`.
  async roomLookup(uniqueId: string): Promise<RoomLookup> {
    const endpoint = 'TikTok room lookup response';
    const body = await this.#json<TikTokRoomLookupResponse>(
      roomLookupUrl(uniqueId),
      endpoint,
      (raw) => validateJson<TikTokRoomLookupResponse>(raw, TikTokRoomLookupResponseSchema, endpoint),
    );
    const user = body.data?.user;
    if (!user) throw new Error(`no room data for @${strip(uniqueId)}`);
    const liveRoom = body.data?.liveRoom;
    const status = user.status ?? liveRoom?.status ?? 0;
    const roomId = user.roomId ? parseId(user.roomId) : '';
    return {
      uniqueId: user.uniqueId ?? strip(uniqueId),
      roomId,
      nickname: user.nickname ?? '',
      status,
      title: liveRoom?.title ?? '',
      isLive: status === ROOM_STATUS_LIVE && roomId !== '' && roomId !== '0',
    };
  }

  /// Room metadata: title, owner, counters.
  async roomInfo(roomId: string): Promise<RoomInfo> {
    const endpoint = 'TikTok room info response';
    const body = await this.#json<TikTokRoomInfoResponse>(
      roomInfoUrl(roomId),
      endpoint,
      (raw) => validateJson<TikTokRoomInfoResponse>(raw, TikTokRoomInfoResponseSchema, endpoint),
    );
    if (body.status_code !== 0) throw new WebcastRefusal(body.status_code, body.data?.message);
    const data = body.data ?? {};
    const stats = data.stats ?? {};
    const owner = data.owner ?? {};
    return {
      roomId: data.id_str ?? roomId,
      title: data.title ?? '',
      status: data.status ?? 0,
      viewers: stats.total_user ?? data.user_count ?? 0,
      likes: stats.like_count ?? 0,
      comments: stats.comment_count ?? 0,
      shares: stats.share_count ?? 0,
      follows: stats.follow_count ?? 0,
      coverUrl: firstUrl(data.cover),
      shareUrl: data.share_url ?? '',
      owner: {
        userId: owner.id_str ?? '',
        uniqueId: owner.display_id ?? '',
        nickname: owner.nickname ?? '',
        secUid: owner.sec_uid ?? '',
        avatarUrl: firstUrl(owner.avatar_thumb),
        followerCount: owner.follow_info?.follower_count ?? 0,
      },
    };
  }

  /// Every gift the room offers, keyed by id.
  ///
  /// About 2.6 MB for 600-odd gifts, so read it once per connection rather than per event. It is
  /// what turns a `gift` event into a diamond value when the event's own detail block is omitted,
  /// which happens on every repeat of a streak.
  async giftList(roomId: string): Promise<Map<string, Gift>> {
    const endpoint = 'TikTok gift list response';
    const body = await this.#json<TikTokGiftListResponse>(
      giftListUrl(roomId),
      endpoint,
      (raw) => validateJson<TikTokGiftListResponse>(raw, TikTokGiftListResponseSchema, endpoint),
    );
    if (body.status_code !== 0) throw new WebcastRefusal(body.status_code, body.data?.message);
    const gifts = new Map<string, Gift>();
    for (const gift of body.data?.gifts ?? []) {
      const id = parseId(gift.id);
      gifts.set(id, {
        id,
        name: gift.name ?? '',
        describe: gift.describe ?? '',
        diamondCount: gift.diamond_count ?? 0,
        /// Streakable gifts arrive as a burst with a rising `repeatCount`, and only the last one
        /// is the real total. Counting every message multiplies what the sender actually spent.
        combo: Boolean(gift.combo),
        giftType: gift.type ?? 0,
        iconUrl: firstUrl(gift.icon),
      });
    }
    return gifts;
  }

  /// Rooms broadcasting now, most viewers first.
  ///
  /// Results follow the keyword, so this samples live rooms rather than enumerating them. Each
  /// entry's real content is a JSON *string* under `live_info.raw_data` — the search response
  /// carries the room object serialised inside itself, which is why the outer fields look empty.
  async liveChannels(keyword = 'live'): Promise<LiveRoom[]> {
    const endpoint = 'TikTok live search response';
    const body = await this.#json<TikTokLiveSearchResponse>(
      liveSearchUrl(keyword),
      endpoint,
      (raw) => validateJson<TikTokLiveSearchResponse>(raw, TikTokLiveSearchResponseSchema, endpoint),
    );
    // Search is the one unsigned endpoint that wants a session: without one it answers 200 with
    // `status_code: 2483, "Please login your account first"`, which as an empty list would look
    // like "nobody is live".
    if (body.status_code) throw new WebcastRefusal(body.status_code, body.status_msg);
    const rooms: LiveRoom[] = [];
    for (const item of body.data ?? []) {
      const rawData = item.live_info?.raw_data;
      if (!rawData) continue;
      let rawRoom: unknown;
      try {
        rawRoom = JSON.parse(rawData);
      } catch {
        continue;
      }
      let room: TikTokSearchRoom;
      try {
        room = validateJson<TikTokSearchRoom>(rawRoom, TikTokSearchRoomSchema, 'TikTok live search room');
      } catch {
        // Search results are a mixed, unstable feed. Ignore one malformed room while preserving
        // the valid rooms in the same response.
        continue;
      }
      if (room.status !== ROOM_STATUS_LIVE || !room.id_str) continue;
      const uniqueId = room.owner?.display_id ?? '';
      const roomId = parseId(room.id_str);
      if (!uniqueId || !roomId || roomId === '0') continue;
      rooms.push({
        uniqueId,
        roomId,
        nickname: room.owner?.nickname ?? '',
        title: room.title ?? '',
        viewers: room.user_count ?? 0,
      });
    }
    rooms.sort((left, right) => right.viewers - left.viewers);
    return rooms;
  }
}

const firstUrl = (image?: TikTokImage): string => image?.url_list?.[0] ?? '';

export interface DiscoveryOptions {
  cookie?: string;
  userAgent?: string;
  timeoutMs?: number;
}
