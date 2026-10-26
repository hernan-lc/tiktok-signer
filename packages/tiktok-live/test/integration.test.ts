// Opt-in transport proof. Ordinary CI stays offline; run with
// TIKTOK_LIVE_INTEGRATION=1 TIKTOK_LIVE_UNIQUE_ID=... to exercise a public live room.

import assert from 'node:assert/strict';
import test from 'node:test';
import NodeWebSocket from 'ws';

import { carriesEvents, decodePushFrame } from '../dist/frames.js';
import { TikTokLive } from '../dist/index.js';
import type { WebSocketConstructor, WebSocketLike, WebSocketOptions } from '../dist/index.js';

const enabled = process.env.TIKTOK_LIVE_INTEGRATION === '1';

test('the real transport accepts the generated enter-room frame', {
  skip: !enabled || !process.env.TIKTOK_LIVE_UNIQUE_ID
    ? 'set TIKTOK_LIVE_INTEGRATION=1 and TIKTOK_LIVE_UNIQUE_ID to run'
    : false,
}, async () => {
  const uniqueId = process.env.TIKTOK_LIVE_UNIQUE_ID;
  if (!uniqueId) throw new Error('TIKTOK_LIVE_UNIQUE_ID is required');

  let enterRoomSent = false;
  let eventReceived = false;
  const WebSocketImpl = recordingWebSocket(NodeWebSocket as unknown as WebSocketConstructor, () => { enterRoomSent = true; });
  const live = new TikTokLive(uniqueId, {
    roomId: process.env.TIKTOK_LIVE_ROOM_ID,
    sessionCookie: process.env.TIKTOK_LIVE_COOKIE,
    bundlePath: process.env.TTL_BUNDLE,
    fetchGifts: false,
    fetchRoomInfo: false,
    WebSocketImpl,
  });
  live.once('event', () => { eventReceived = true; });

  try {
    await live.connect();
    const deadline = Date.now() + 20_000;
    while (!eventReceived && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    assert.equal(enterRoomSent, true, 'the generated enter-room message was sent');
    assert.equal(eventReceived, true, 'the live room returned an event batch');
  } finally {
    live.disconnect();
  }
});

function recordingWebSocket(
  NativeWebSocket: WebSocketConstructor,
  onEnterRoom: () => void,
): WebSocketConstructor {
  class RecordingWebSocket implements WebSocketLike {
    readonly binaryType = 'arraybuffer';
    readonly #native: WebSocketLike;

    constructor(url: string, options: WebSocketOptions) {
      this.#native = new NativeWebSocket(url, options);
    }

    send(data: string | ArrayBuffer | ArrayBufferView): void {
      if (typeof data === 'string') {
        this.#native.send(data);
        return;
      }
      const frame = decodePushFrame(data);
      if (frame.payloadType === 'im_enter_room' && !carriesEvents(frame)) onEnterRoom();
      this.#native.send(data as ArrayBuffer);
    }

    close(): void { this.#native.close(); }

    addEventListener(type: 'open', listener: () => void): void;
    addEventListener(
      type: 'message',
      listener: (event: { data: ArrayBuffer | ArrayBufferView }) => void,
    ): void;
    addEventListener(type: 'error', listener: (event: { message?: string }) => void): void;
    addEventListener(
      type: 'close',
      listener: (event: { code: number; reason?: string }) => void,
    ): void;
    addEventListener(type: string, listener: (...args: never[]) => void): void {
      (this.#native.addEventListener as (type: string, listener: (...args: never[]) => void) => void)(type, listener);
    }
  }
  return RecordingWebSocket;
}
