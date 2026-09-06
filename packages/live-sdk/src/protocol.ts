// The SDK's protocol boundary is shared with the existing Node LIVE client. These exports are
// adapters, not a second protobuf implementation: generated schemas, gzip behavior, frame ACKs,
// and enter-room/heartbeat encoders all remain in packages/tiktok-live.

export {
  ackFrame,
  carriesEvents,
  decodeBatch,
  decodePushFrame,
  decompress,
  frameCompressType,
  frameHeaders,
} from '../../tiktok-live/src/frames.js';
export { enterRoomFrame, heartbeatFrame } from '../../tiktok-live/src/player.js';
export type { WebSocketConstructor, WebSocketLike, WebSocketOptions } from '../../tiktok-live/src/types.js';
