import assert from 'node:assert/strict';
import test from 'node:test';

import { create, toBinary } from '@bufbuild/protobuf';
import {
  TikTokLive,
  decodePushFrame,
} from '../dist/live-sdk/src/index.js';
import {
  BaseProtoMessageSchema,
  ProtoMessageFetchResultSchema,
} from '../dist/tiktok-live/src/gen/webcast/shared/message_pb.js';
import { WebcastPushFrameSchema } from '../dist/tiktok-live/src/gen/webcast/synthetic_proto_pb.js';
import type {
  WebSocketConstructor,
  WebSocketLike,
  WebSocketOptions,
} from '../dist/live-sdk/src/index.js';

const LIVE_URL = 'wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300&X-Gnarly=test';

class FakeWebSocket implements WebSocketLike {
  static instances: FakeWebSocket[] = [];
  readonly binaryType = 'arraybuffer';
  readonly sent: Array<ArrayBuffer | ArrayBufferView> = [];
  readonly options: WebSocketOptions;
  readonly #listeners = new Map<string, Function[]>();

  constructor(readonly url: string, options: WebSocketOptions) {
    this.options = options;
    FakeWebSocket.instances.push(this);
    queueMicrotask(() => this.dispatch('open'));
  }

  send(data: string | ArrayBuffer | ArrayBufferView): void {
    if (typeof data !== 'string') this.sent.push(data);
  }

  close(): void {
    this.dispatch('close', { code: 1000, reason: 'done' });
  }

  addEventListener(type: 'open', listener: () => void): void;
  addEventListener(type: 'message', listener: (event: { data: ArrayBuffer | ArrayBufferView }) => void): void;
  addEventListener(type: 'error', listener: (event: { message?: string }) => void): void;
  addEventListener(type: 'close', listener: (event: { code: number; reason?: string }) => void): void;
  addEventListener(type: string, listener: Function): void {
    this.#listeners.set(type, [...(this.#listeners.get(type) ?? []), listener]);
  }

  dispatch(type: string, event?: unknown): void {
    for (const listener of this.#listeners.get(type) ?? []) listener(event);
  }

  receive(data: ArrayBuffer): void {
    this.dispatch('message', { data });
  }
}

const WebSocketImpl = FakeWebSocket as unknown as WebSocketConstructor;

function liveResponse(url = LIVE_URL): Response {
  return new Response(JSON.stringify({
    version: 1,
    uniqueId: 'creator',
    roomId: '7300',
    status: 'live',
    connection: { url, cookies: 'ttwid=test', userAgent: 'fixture-agent' },
  }));
}

test('direct transport sends enter-room, ACKs before emitting unknown events, and preserves bytes', async () => {
  FakeWebSocket.instances = [];
  const live = new TikTokLive('@creator', {
    apiKey: 'test',
    fetchImpl: async () => liveResponse(),
    WebSocketImpl,
    reconnect: { attempts: 0, initialMs: 0, maxMs: 0 },
  });
  const events: unknown[] = [];
  live.on('unknown', (event) => events.push(event));

  await live.connect();
  const socket = FakeWebSocket.instances[0];
  assert.ok(socket);
  assert.equal(decodePushFrame(socket.sent[0]!).payloadType, 'im_enter_room');

  const eventPayload = Uint8Array.from([9, 8, 7]);
  const batch = toBinary(ProtoMessageFetchResultSchema, create(ProtoMessageFetchResultSchema, {
    messages: [create(BaseProtoMessageSchema, {
      method: 'WebcastNewMessageThatIsNotModeled',
      payload: eventPayload,
      msgId: 12n,
    })],
    internalExt: 'ack-token',
    needAck: true,
  }));
  const frame = toBinary(WebcastPushFrameSchema, create(WebcastPushFrameSchema, {
    logId: 99n,
    payloadEncoding: 'pb',
    payloadType: 'msg',
    payload: batch,
  }));
  socket.receive(frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength));

  assert.equal(events.length, 1);
  const unknown = events[0] as { type: string; method: string; payload: Uint8Array };
  assert.equal(unknown.type, 'unknown');
  assert.equal(unknown.method, 'WebcastNewMessageThatIsNotModeled');
  assert.deepEqual([...unknown.payload], [9, 8, 7]);
  const acknowledgement = decodePushFrame(socket.sent[1]!);
  assert.equal(acknowledgement.payloadType, 'ack');
  assert.equal(Buffer.from(acknowledgement.payload).toString(), 'ack-token');
  live.disconnect();
});

test('socket close obtains a fresh descriptor through the API with jittered reconnect', async () => {
  FakeWebSocket.instances = [];
  let apiCalls = 0;
  const connected: string[] = [];
  const live = new TikTokLive('creator', {
    apiKey: 'test',
    fetchImpl: async () => {
      apiCalls += 1;
      return liveResponse(`${LIVE_URL}&ticket=${apiCalls}`);
    },
    WebSocketImpl,
    reconnect: { attempts: 1, initialMs: 0, maxMs: 0 },
  });
  live.on('connected', (state) => connected.push(state.roomId ?? ''));
  await live.connect();
  const reconnected = new Promise<void>((resolve) => live.once('reconnecting', () => resolve()));
  FakeWebSocket.instances[0]?.dispatch('close', { code: 1000, reason: 'restart' });
  await reconnected;
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(apiCalls, 2);
  assert.equal(FakeWebSocket.instances.length, 2);
  assert.deepEqual(connected, ['7300', '7300']);
  assert.equal(FakeWebSocket.instances[1]?.options.headers.cookie, 'ttwid=test');
  live.disconnect();
});

test('offline is a successful SDK state and emits offline', async () => {
  let offline = 0;
  const live = new TikTokLive('creator', {
    fetchImpl: async () => new Response(JSON.stringify({
      version: 1, uniqueId: 'creator', status: 'offline', roomId: '7300',
    })),
    WebSocketImpl,
  });
  live.on('offline', () => { offline += 1; });
  const state = await live.connect();
  assert.equal(state.status, 'offline');
  assert.equal(state.connected, false);
  assert.equal(offline, 1);
});
