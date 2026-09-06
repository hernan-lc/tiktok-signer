// Stable normalized event API reused from the repository's existing Node implementation.
export {
  EVENT,
  METHOD,
  SOCIAL_ACTION,
  decodeEvent,
  decodeUser,
  label,
  safeCount,
} from '../../tiktok-live/src/events.js';
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
} from '../../tiktok-live/src/types.js';
