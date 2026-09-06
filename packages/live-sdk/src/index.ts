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
  carriesEvents,
  decodeBatch,
  decodePushFrame,
  decompress,
  enterRoomFrame,
  frameCompressType,
  frameHeaders,
  heartbeatFrame,
} from './protocol.js';
export { EVENT, METHOD, SOCIAL_ACTION, decodeEvent, decodeUser, label, safeCount } from './events.js';
export type {
  ChatEvent,
  EventUser,
  Gift,
  GiftEvent,
  LikeEvent,
  LiveEvent,
  MemberEvent,
  RoomUserEvent,
  SocialEvent,
  TopViewer,
  UnknownEvent,
} from './events.js';
export type { WebSocketConstructor, WebSocketLike, WebSocketOptions } from './protocol.js';
