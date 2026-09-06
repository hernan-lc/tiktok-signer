// The live player's transport, transcribed once.
//
// The web player builds its message-socket URL itself and signs the query bytes, so every byte of
// that query — key spelling, key order, the absence of percent-encoding — is part of the signature.
// This module is the single JavaScript statement of it: the constants, the serializer, and the two
// frames the socket exchanges.
//
// Nothing here is invented. Every value is read out of the player's own chunk, and
// `player-audit.mjs` re-reads them from the shipped app on demand and fails when they move. The
// frame schemas are generated from the same protobuf definitions used by the SDK.
//
// `crates/ttl-sign-core/src/params.rs` is the Rust statement of the same thing, and the two are held
// together by a test rather than by discipline: `TTL_PRINT_QUERY=1 node ws-direct.mjs` prints the
// query this module builds, and `direct_socket_query_matches_the_player` asserts the Rust builder
// produces it byte for byte.

import { create, toBinary } from '@bufbuild/protobuf';

import { USER_AGENT } from './session.js';
import {
  HeartBeatMessageSchema,
  WebcastImEnterRoomMessageSchema,
  WebcastPushFrameSchema,
} from './gen/webcast/synthetic_proto_pb.js';

/// Socket hosts, by cluster region. The player picks between exactly these three.
export const SOCKET_HOST = Object.freeze({
  global: 'wss://webcast-ws.tiktok.com',
  us: 'wss://webcast-ws.us.tiktok.com',
  eu: 'wss://webcast-ws.eu.tiktok.com',
});

/// The webcast paths the transport knows.
export const PATH = Object.freeze({
  fetch: '/webcast/im/fetch/',
  fetchHistory: '/webcast/im/fetch/history/',
  fetchPreview: '/webcast/im/fetch/preview/',
  /// What `wsDirect` opens. This is the transport.
  wsReuseSupplement: '/webcast/im/ws_proxy/ws_reuse_supplement/',
  wsFromPreview: '/webcast/im/ws_proxy/from_preview/',
});

/// The two `version_code` values, which are different and both present.
///
/// The SDK's browser block carries `SDK_VERSION_CODE` under a snake_case key and the page config
/// `CONFIG_VERSION_CODE` under a camelCase one, so the serializer emits `version_code` twice. It
/// looks like a bug and is not: a query with only one of them is not the query that gets signed.
export const SDK_VERSION_CODE = '180800';
export const CONFIG_VERSION_CODE = '270000';
/// The IM SDK's own version, sent alongside both.
export const UPDATE_VERSION_CODE = '2.0.0';

export const AID = '1988';
export const APP_NAME = 'tiktok_web';
export const LIVE_ID = '12';
export const HEARTBEAT_MS = '10000';

/// Who the client claims to be in the room.
export const IDENTITY = Object.freeze({ audience: 'audience', anchor: 'anchor' });
export type Identity = (typeof IDENTITY)[keyof typeof IDENTITY];

/// Frame compression the socket will negotiate.
export const COMPRESSION = Object.freeze({ gzip: 'gzip', none: '' });
export type Compression = (typeof COMPRESSION)[keyof typeof COMPRESSION];

/// `payload_type` values on a `PushFrame`.
export const FRAME_TYPE = Object.freeze({
  enterRoom: 'im_enter_room',
  previewRoom: 'im_preview_room',
  heartbeat: 'hb',
  ack: 'ack',
});

/// `payload_encoding`: protobuf, for every frame this module builds.
const PAYLOAD_ENCODING_PB = 'pb';

export type QueryPrimitive = string | number | boolean | bigint;
export type QueryValue = QueryPrimitive | null | undefined;
type QueryRecord = Record<string, unknown>;

export interface BrowserBlockOptions {
  userAgent?: string;
  screenWidth?: number;
  screenHeight?: number;
  browserLanguage?: string;
  browserPlatform?: string;
  tzName?: string;
}

export interface SocketConfigOptions {
  roomId: string;
  deviceId?: string;
  identity?: Identity;
  compress?: Compression;
  socketHost?: string;
  appLanguage?: string;
}

export interface SocketConfig {
  aid: string;
  appName: string;
  liveId: string;
  versionCode: string;
  appLanguage: string;
  socketHost: string;
  wsDirect: string;
  fetchBeforeWsSuccess: string;
  clientEnter: string;
  roomId: string;
  identity: Identity;
  deviceId: string;
  compress: Compression;
  lastRtt: string;
  cursor: string;
  internalExt: string;
  historyCommentCursor: string;
  heartbeatDuration: string;
  didRule?: QueryValue;
  pushServer?: string;
  routeParamsMap?: Readonly<Record<string, QueryValue>>;
  host?: string;
  debug?: boolean;
  filterByRoomId?: QueryValue;
  [key: string]: unknown;
}

// --- the query the signature covers ----------------------------------------------------------------

/// The SDK's browser block, `k()`. Its `version_code` is the SDK default, which the config shadows.
export function browserBlock({
  userAgent = USER_AGENT,
  screenWidth = 1920,
  screenHeight = 1080,
  browserLanguage = 'en-US',
  browserPlatform = 'Linux x86_64',
  tzName = 'America/New_York',
}: BrowserBlockOptions = {}): Record<string, string> {
  return {
    version_code: SDK_VERSION_CODE,
    device_platform: 'web',
    cookie_enabled: 'true',
    screen_width: String(screenWidth),
    screen_height: String(screenHeight),
    browser_language: browserLanguage,
    browser_platform: browserPlatform,
    // `navigator.appCodeName` and `navigator.appVersion`: always "Mozilla", and the agent minus it.
    browser_name: 'Mozilla',
    browser_version: userAgent.replace(/^Mozilla\//, ''),
    browser_online: 'true',
    tz_name: tzName,
  };
}

/// `F()`: drop empties, objects, and the config keys that are not request parameters.
export function strip(props: QueryRecord): Record<string, QueryValue> {
  const excluded = new Set([
    'socketHost', 'host', 'fetchBeforeWsSuccess', 'debug', 'filterByRoomId',
  ]);
  const out: Record<string, QueryValue> = {};
  for (const [key, value] of Object.entries(props)) {
    if (!excluded.has(key) && isQueryValue(value) && value !== '') out[key] = value;
  }
  return out;
}

/// `V()`: the browser block, then the config, then the fixed tail.
export function withDefaults(
  props: QueryRecord,
  block: QueryRecord = browserBlock(),
): Record<string, QueryValue> {
  const { didRule, deviceId, ...rest } = props;
  const merged: QueryRecord = {
    ...block,
    ...strip(rest),
    supWsDsOpt: '1',
    respContentType: 'protobuf',
    // 3 only when there is no device id to rule on.
    didRule: isQueryValue(didRule) ? didRule : (deviceId ? 0 : 3),
    deviceId: isQueryValue(deviceId) ? deviceId : '',
    webcastLanguage: isQueryValue(rest.appLanguage) ? rest.appLanguage : '',
  };
  const out: Record<string, QueryValue> = {};
  for (const [key, value] of Object.entries(merged)) {
    if (isQueryValue(value) && value !== '') out[key] = value;
  }
  return out;
}

/// `H()`: camelCase to snake_case, and **no** percent-encoding. The signature covers these bytes.
export function serialize(params: Readonly<Record<string, QueryValue>>): string {
  return Object.keys(params).reduce((acc, key) => {
    const name = key
      .replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)
      .replace(/\s+/g, '_')
      .replace(/[^a-zA-Z0-9_]/g, '')
      .toLowerCase();
    return `${acc}${acc ? '&' : ''}${name}=${String(params[key])}`;
  }, '');
}

/// The config the live room page hands its IM SDK, with the caller's room and device filled in.
export function socketConfig({
  roomId,
  deviceId = '',
  identity = IDENTITY.audience,
  compress = COMPRESSION.gzip,
  socketHost = SOCKET_HOST.global,
  appLanguage = 'en',
}: SocketConfigOptions): SocketConfig {
  return {
    aid: AID,
    appName: APP_NAME,
    liveId: LIVE_ID,
    versionCode: CONFIG_VERSION_CODE,
    appLanguage,
    socketHost,
    wsDirect: '1',
    fetchBeforeWsSuccess: '1',
    clientEnter: '1',
    roomId,
    identity,
    deviceId,
    compress,
    // `createClient` seeds these from the message state, which starts empty on a cold connect.
    lastRtt: '-1',
    cursor: '',
    internalExt: '',
    historyCommentCursor: '',
    heartbeatDuration: HEARTBEAT_MS,
  };
}

/// The query string the socket URL carries, exactly as the SDK builds it.
export function socketQuery(config: SocketConfig, block: QueryRecord = browserBlock()): string {
  const { appName, didRule, routeParamsMap, pushServer, ...rest } = config;
  return serialize(withDefaults({
    appName,
    didRule,
    supWsDsOpt: '1',
    updateVersionCode: UPDATE_VERSION_CODE,
    compress: config.compress,
    webcastLanguage: config.appLanguage,
    ...block,
    ...(routeParamsMap ?? {}),
    ...strip(rest),
  }, block));
}

/// The unsigned socket URL. The signature is appended as `&X-Gnarly=<percent-encoded>`.
export function socketUrl(config: SocketConfig, block: QueryRecord = browserBlock()): string {
  return `${config.socketHost}${PATH.wsReuseSupplement}?${socketQuery(config, block)}`;
}

// --- the frames ------------------------------------------------------------------------------------

function bytes(value: string | ArrayBuffer | ArrayBufferView): Uint8Array {
  if (typeof value === 'string') return Buffer.from(value, 'utf8');
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
}

function nonNegativeInt64(value: string): bigint {
  const integer = BigInt(value);
  if (integer < 0n) throw new RangeError('protobuf int64 values must be non-negative');
  return integer;
}

/// Wrap a payload in the `PushFrame` the socket expects.
///
/// `logId` is echoed back on an acknowledgement so the server can match it to the frame it sent;
/// frames the client originates leave it at zero, which is what the SDK does.
export function pushFrame(
  payloadType: string,
  payload: string | ArrayBuffer | ArrayBufferView,
  { logId = 0n }: { logId?: bigint } = {},
): Buffer {
  if (logId < 0n) throw new RangeError('protobuf int64 values must be non-negative');
  const frame = create(WebcastPushFrameSchema, {
    logId,
    payloadEncoding: PAYLOAD_ENCODING_PB,
    payloadType,
    payload: bytes(payload),
  });
  return Buffer.from(toBinary(WebcastPushFrameSchema, frame));
}

/// The frame that makes the server start pushing. Without it a healthy socket stays silent.
export function enterRoomFrame({
  roomId,
  identity = IDENTITY.audience,
  liveId = LIVE_ID,
}: { roomId: string; identity?: Identity; liveId?: string }): Buffer {
  const message = create(WebcastImEnterRoomMessageSchema, {
    roomId: nonNegativeInt64(roomId),
    liveId: nonNegativeInt64(liveId),
    identity,
    // Proto3 serialization omits these defaults. The server observes the same decoded values,
    // and no protocol evidence requires presence for either field.
    cursor: '',
    accountType: 0n,
    filterWelcomeMsg: '0',
  });
  return pushFrame(FRAME_TYPE.enterRoom, toBinary(WebcastImEnterRoomMessageSchema, message));
}

/// The application keepalive. The socket closes without it; protocol pings are not answered.
export function heartbeatFrame(roomId: string): Buffer {
  const message = create(HeartBeatMessageSchema, { roomId: nonNegativeInt64(roomId) });
  return pushFrame(FRAME_TYPE.heartbeat, toBinary(HeartBeatMessageSchema, message));
}

function isQueryValue(value: unknown): value is QueryPrimitive {
  return typeof value === 'string'
    || typeof value === 'number'
    || typeof value === 'boolean'
    || typeof value === 'bigint';
}
