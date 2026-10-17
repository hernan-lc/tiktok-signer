export { TikTokLive } from './client.js';
export type {
  LiveState,
  ReconnectPolicy,
  TikTokLiveEvents,
  TikTokLiveOptions,
} from './client.js';
export { ConnectApi } from './api.js';
export type {
  ConnectApiOptions,
  ConnectResponse,
  ConnectionDescriptor,
  ConnectionStatus,
  FetchLike,
} from './api.js';
export {
  LiveApiError,
  ProtocolError,
  SocketError,
} from './errors.js';
export type { LiveApiErrorCode } from './errors.js';
export { DEFAULT_RECONNECT, reconnectDelay } from './reconnect.js';
export { normalizeUniqueId } from './unique-id.js';

// Public transport/protobuf helpers are available for advanced Node integrations, but the normal
// class API keeps signing and room-id handling out of the caller's control.
export {
  ackFrame,
  ackFrameForBatch,
  carriesEvents,
  decodeBatch,
  decodePushFrame,
  decompress,
  enterRoomFrame,
  frameCompressType,
  frameHeaders,
  heartbeatFrame,
} from './protocol.js';
export {
  EVENT,
  METHOD,
  MAX_EMOTES_PER_EVENT,
  MAX_EMOTE_ID_LENGTH,
  MAX_EMOTE_URL_LENGTH,
  SOCIAL_ACTION,
  decodeEvent,
  decodeUser,
  label,
  normalizeEmote,
  normalizeInlineEmotes,
  normalizeStandaloneEmotes,
  safeCount,
} from './events.js';
export type {
  ChatEvent,
  EmoteChatEvent,
  EventUser,
  Gift,
  GiftEvent,
  LikeEvent,
  LiveEmote,
  LiveEvent,
  MemberEvent,
  RoomUserEvent,
  SocialEvent,
  TopViewer,
  UnknownEvent,
} from './events.js';
export type { WebSocketConstructor, WebSocketLike, WebSocketOptions } from './protocol.js';

export type { DeliveryOptions, DeliveryMetrics } from '../../tiktok-live/src/delivery.js';
