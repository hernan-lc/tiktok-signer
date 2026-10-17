// Stable normalized event API reused from the repository's existing Node implementation.
export {
  EVENT,
  METHOD,
  MAX_EMOTES_PER_EVENT,
  MAX_EMOTE_ID_LENGTH,
  MAX_EMOTE_URL_LENGTH,
  SOCIAL_ACTION,
  classifySocialAction,
  decodeEvent,
  decodeUser,
  label,
  normalizeEmote,
  normalizeInlineEmotes,
  normalizeStandaloneEmotes,
  safeCount,
} from '../../tiktok-live/src/events.js';
export type {
  SocialClassification,
  SocialConfidence,
  SocialKind,
} from '../../tiktok-live/src/events.js';
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
} from '../../tiktok-live/src/types.js';
