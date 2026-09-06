// Stable listener-facing application types.
//
// The JSON Schema files under schema/json/public are the source of truth for normalized JSON
// objects. These aliases keep the package's existing public import surface while making the
// generated contracts the types consumers autocomplete against. Protobuf transport types remain
// under gen/webcast and are intentionally not redefined here.

import type { BaseEvent as GeneratedBaseEvent } from './gen/json/public/base-event.js';
import type { ChatEvent as GeneratedChatEvent } from './gen/json/public/chat-event.js';
import type { GiftEvent as GeneratedGiftEvent } from './gen/json/public/gift-event.js';
import type { LikeEvent as GeneratedLikeEvent } from './gen/json/public/like-event.js';
import type { MemberEvent as GeneratedMemberEvent } from './gen/json/public/member-event.js';
import type { RoomUserEvent as GeneratedRoomUserEvent } from './gen/json/public/room-user-event.js';
import type { SocialEvent as GeneratedSocialEvent } from './gen/json/public/social-event.js';

export type { BaseEvent } from './gen/json/public/base-event.js';
export type { ChatEvent } from './gen/json/public/chat-event.js';
export type { ClientState } from './gen/json/public/client-state.js';
export type { EventUser } from './gen/json/public/event-user.js';
export type { Gift } from './gen/json/public/gift.js';
export type { GiftEvent } from './gen/json/public/gift-event.js';
export type { LikeEvent } from './gen/json/public/like-event.js';
export type { LiveRoom } from './gen/json/public/live-room.js';
export type { MemberEvent } from './gen/json/public/member-event.js';
export type { ReconnectPolicy } from './gen/json/public/reconnect-policy.js';
export type { RoomInfo } from './gen/json/public/room-info.js';
export type { RoomLookup } from './gen/json/public/room-lookup.js';
export type { RoomOwner } from './gen/json/public/room-owner.js';
export type { RoomUserEvent } from './gen/json/public/room-user-event.js';
export type { SocialEvent } from './gen/json/public/social-event.js';
export type { TopViewer } from './gen/json/public/top-viewer.js';

/// Binary payloads are intentionally kept as Uint8Array; this is not a JSON contract.
export type UnknownEvent = GeneratedBaseEvent & {
  type: 'unknown';
  payload: Uint8Array;
};

export type LiveEvent =
  | GeneratedChatEvent
  | GeneratedGiftEvent
  | GeneratedLikeEvent
  | GeneratedMemberEvent
  | GeneratedSocialEvent
  | GeneratedRoomUserEvent
  | UnknownEvent;

export interface WebSocketOptions {
  headers: Record<string, string>;
}

export interface WebSocketLike {
  binaryType: string;
  send(data: string | ArrayBuffer | ArrayBufferView): void;
  close(): void;
  addEventListener(type: 'open', listener: () => void): void;
  addEventListener(type: 'message', listener: (event: { data: ArrayBuffer | ArrayBufferView }) => void): void;
  addEventListener(type: 'error', listener: (event: { message?: string }) => void): void;
  addEventListener(type: 'close', listener: (event: { code: number; reason?: string }) => void): void;
}

export interface WebSocketConstructor {
  new (url: string, options: WebSocketOptions): WebSocketLike;
}
