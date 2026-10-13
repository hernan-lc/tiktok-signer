import assert from 'node:assert/strict';
import test from 'node:test';

import { TikTokLive } from '../dist/client.js';
import { GuestSessionError } from '../dist/session.js';
import type { WebSocketConstructor, WebSocketOptions } from '../dist/types.js';

test('the supplied session drives both the handshake and device query', async () => {
  let unsignedUrl = '';
  let handshake: WebSocketOptions | undefined;
  let socketCount = 0;

  class FakeWebSocket {
    binaryType = '';
    readonly #listeners = new Map<string, Function[]>();

    constructor(_url: string, options: WebSocketOptions) {
      socketCount += 1;
      handshake = options;
      queueMicrotask(() => this.#dispatch('open'));
    }

    send(_data: string | ArrayBuffer | ArrayBufferView): void {}
    close(): void { this.#dispatch('close', { code: 1000, reason: 'done' }); }

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
    addEventListener(type: string, listener: Function): void {
      const listeners = this.#listeners.get(type) ?? [];
      listeners.push(listener);
      this.#listeners.set(type, listeners);
    }

    #dispatch(type: string, event?: unknown): void {
      for (const listener of this.#listeners.get(type) ?? []) listener(event);
    }
  }

  const live = new TikTokLive('@someone', {
    roomId: '7300000000000000001',
    sessionCookie: 'sessionid=test; tt_webid_v2=7999000000000000001',
    fetchGifts: false,
    fetchRoomInfo: false,
    signer: {
      sign(url) {
        unsignedUrl = url;
        return `${url}&X-Gnarly=test`;
      },
    },
    WebSocketImpl: FakeWebSocket as WebSocketConstructor,
  });

  const firstConnect = live.connect();
  const secondConnect = live.connect();
  assert.equal(secondConnect, firstConnect, 'concurrent callers must share one connection attempt');
  await firstConnect;
  assert.equal(socketCount, 1);
  assert.match(unsignedUrl, /device_id=7999000000000000001/);
  assert.equal(handshake?.headers.cookie, 'sessionid=test; tt_webid_v2=7999000000000000001');
  live.disconnect();
});

test('no supplied session bootstraps a guest jar and reuses its device identity', async () => {
  let unsignedUrl = '';
  let handshake: WebSocketOptions | undefined;
  const previousFetch = globalThis.fetch;
  const previousSessionFile = process.env.TTL_SESSION_FILE;
  process.env.TTL_SESSION_FILE = `/tmp/ttl-live-missing-session-${process.pid}`;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    headers: {
      getSetCookie: () => [
        'ttwid=guest-value; Path=/; Domain=.tiktok.com',
        'tt_webid_v2=7999000000000000001; Path=/; Domain=.tiktok.com',
      ],
    },
    arrayBuffer: async () => new ArrayBuffer(0),
  } as unknown as Response);

  class FakeWebSocket {
    binaryType = '';
    readonly #listeners = new Map<string, Function[]>();

    constructor(url: string, options: WebSocketOptions) {
      unsignedUrl = url;
      handshake = options;
      queueMicrotask(() => this.#dispatch('open'));
    }

    send(_data: string | ArrayBuffer | ArrayBufferView): void {}
    close(): void { this.#dispatch('close', { code: 1000, reason: 'done' }); }

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
    addEventListener(type: string, listener: Function): void {
      const listeners = this.#listeners.get(type) ?? [];
      listeners.push(listener);
      this.#listeners.set(type, listeners);
    }

    #dispatch(type: string, event?: unknown): void {
      for (const listener of this.#listeners.get(type) ?? []) listener(event);
    }
  }

  try {
    const live = new TikTokLive('@someone', {
      roomId: '7300000000000000001',
      fetchGifts: false,
      fetchRoomInfo: false,
      signer: {
        sign(url) {
          unsignedUrl = url;
          return `${url}&X-Gnarly=test`;
        },
      },
      WebSocketImpl: FakeWebSocket as WebSocketConstructor,
    });

    await live.connect();
    assert.match(unsignedUrl, /device_id=7999000000000000001/);
    assert.equal(
      handshake?.headers.cookie,
      'ttwid=guest-value; tt_webid_v2=7999000000000000001',
    );
    assert.doesNotMatch(handshake?.headers.cookie ?? '', /sessionid/);
    live.disconnect();
  } finally {
    globalThis.fetch = previousFetch;
    if (previousSessionFile === undefined) delete process.env.TTL_SESSION_FILE;
    else process.env.TTL_SESSION_FILE = previousSessionFile;
  }
});

test('a rejected anonymous handshake is typed and is not retried', async () => {
  const previousFetch = globalThis.fetch;
  const previousSessionFile = process.env.TTL_SESSION_FILE;
  process.env.TTL_SESSION_FILE = `/tmp/ttl-live-missing-session-${process.pid}`;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    headers: { getSetCookie: () => ['ttwid=guest-value; Path=/; Domain=.tiktok.com'] },
    arrayBuffer: async () => new ArrayBuffer(0),
  } as unknown as Response);

  class RejectingWebSocket {
    binaryType = '';
    readonly #listeners = new Map<string, Function[]>();

    constructor(_url: string, _options: WebSocketOptions) {
      queueMicrotask(() => {
        for (const listener of this.#listeners.get('close') ?? []) listener({ code: 1006 });
      });
    }

    send(_data: string | ArrayBuffer | ArrayBufferView): void {}
    close(): void {}
    addEventListener(type: string, listener: Function): void {
      const listeners = this.#listeners.get(type) ?? [];
      listeners.push(listener);
      this.#listeners.set(type, listeners);
    }
  }

  try {
    const live = new TikTokLive('@someone', {
      roomId: '7300000000000000001',
      fetchGifts: false,
      fetchRoomInfo: false,
      signer: { sign: (url) => `${url}&X-Gnarly=test` },
      WebSocketImpl: RejectingWebSocket as unknown as WebSocketConstructor,
    });

    await assert.rejects(
      () => live.connect(),
      (error: unknown) => error instanceof GuestSessionError
        && error.code === 'WS_HANDSHAKE_REJECTED'
        && error.closeCode === 1006,
    );
    assert.equal(live.connected, false);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousSessionFile === undefined) delete process.env.TTL_SESSION_FILE;
    else process.env.TTL_SESSION_FILE = previousSessionFile;
  }
});

test('a rejected guest reconnect refreshes identity once instead of cycling the old jar', async () => {
  let fetchCalls = 0;
  const previousFetch = globalThis.fetch;
  const previousSessionFile = process.env.TTL_SESSION_FILE;
  process.env.TTL_SESSION_FILE = `/tmp/ttl-live-missing-session-${process.pid}-${Date.now()}`;
  globalThis.fetch = async () => {
    fetchCalls += 1;
    const suffix = fetchCalls === 1 ? 'one' : 'two';
    const webId = fetchCalls === 1 ? '7999000000000000001' : '7999000000000000002';
    return {
      ok: true,
      status: 200,
      headers: {
        getSetCookie: () => [
          `ttwid=guest-${suffix}; Path=/; Domain=.tiktok.com`,
          `tt_webid_v2=${webId}; Path=/; Domain=.tiktok.com`,
        ],
      },
      arrayBuffer: async () => new ArrayBuffer(0),
    } as unknown as Response;
  };

  const cookies: string[] = [];
  const signedInputs: string[] = [];
  const socketUrls: string[] = [];
  const sockets: GuestReconnectWebSocket[] = [];

  class GuestReconnectWebSocket {
    binaryType = '';
    readonly #listeners = new Map<string, Function[]>();

    constructor(url: string, options: WebSocketOptions) {
      sockets.push(this);
      cookies.push(options.headers.cookie ?? '');
      socketUrls.push(url);
      const index = sockets.length;
      queueMicrotask(() => {
        if (index === 2) this.#dispatch('close', { code: 1006 });
        else this.#dispatch('open');
      });
    }

    send(_data: string | ArrayBuffer | ArrayBufferView): void {}
    close(): void { this.#dispatch('close', { code: 1000, reason: 'done' }); }
    closeWith(code: number): void { this.#dispatch('close', { code }); }

    addEventListener(type: string, listener: Function): void {
      const listeners = this.#listeners.get(type) ?? [];
      listeners.push(listener);
      this.#listeners.set(type, listeners);
    }

    #dispatch(type: string, event?: unknown): void {
      for (const listener of this.#listeners.get(type) ?? []) listener(event);
    }
  }

  try {
    const live = new TikTokLive('@someone', {
      roomId: '7300000000000000001',
      fetchGifts: false,
      fetchRoomInfo: false,
      reconnect: { attempts: 4, initialMs: 0, maxMs: 0 },
      signer: {
        sign(url) {
          signedInputs.push(url);
          return `${url}&X-Gnarly=test`;
        },
      },
      WebSocketImpl: GuestReconnectWebSocket as unknown as WebSocketConstructor,
    });

    await live.connect();
    const reconnected = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('guest refresh did not reconnect')), 1_000);
      live.once('connected', () => {
        clearTimeout(timer);
        resolve();
      });
      live.once('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
    });
    sockets[0]?.closeWith(1000);
    await reconnected;

    assert.equal(fetchCalls, 2, 'refresh should bootstrap exactly one new guest jar');
    assert.deepEqual(cookies, [
      'ttwid=guest-one; tt_webid_v2=7999000000000000001',
      'ttwid=guest-one; tt_webid_v2=7999000000000000001',
      'ttwid=guest-two; tt_webid_v2=7999000000000000002',
    ]);
    assert.match(signedInputs[0] ?? '', /device_id=7999000000000000001/);
    assert.match(signedInputs[2] ?? '', /device_id=7999000000000000002/);
    live.disconnect();
  } finally {
    globalThis.fetch = previousFetch;
    if (previousSessionFile === undefined) delete process.env.TTL_SESSION_FILE;
    else process.env.TTL_SESSION_FILE = previousSessionFile;
  }
});

test('an authenticated handshake refusal stops reconnecting with the same session', async () => {
  let socketCount = 0;
  const sockets: AuthReconnectWebSocket[] = [];

  class AuthReconnectWebSocket {
    binaryType = '';
    readonly #listeners = new Map<string, Function[]>();

    constructor(_url: string, _options: WebSocketOptions) {
      sockets.push(this);
      socketCount += 1;
      const index = sockets.length;
      queueMicrotask(() => {
        if (index === 1) this.#dispatch('open');
        else this.#dispatch('close', { code: 1006 });
      });
    }

    send(_data: string | ArrayBuffer | ArrayBufferView): void {}
    close(): void { this.#dispatch('close', { code: 1000, reason: 'done' }); }
    closeWith(code: number): void { this.#dispatch('close', { code }); }

    addEventListener(type: string, listener: Function): void {
      const listeners = this.#listeners.get(type) ?? [];
      listeners.push(listener);
      this.#listeners.set(type, listeners);
    }

    #dispatch(type: string, event?: unknown): void {
      for (const listener of this.#listeners.get(type) ?? []) listener(event);
    }
  }

  const live = new TikTokLive('@someone', {
    roomId: '7300000000000000001',
    sessionCookie: 'sessionid=test; tt_webid_v2=7999000000000000001',
    fetchGifts: false,
    fetchRoomInfo: false,
    reconnect: { attempts: 4, initialMs: 0, maxMs: 0 },
    signer: { sign: (url) => `${url}&X-Gnarly=test` },
    WebSocketImpl: AuthReconnectWebSocket as unknown as WebSocketConstructor,
  });
  const refusal = new Promise<Error>((resolve) => live.once('error', resolve));

  await live.connect();
  sockets[0]?.closeWith(1000);
  const error = await refusal;
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.match(error.message, /provided authenticated session/);
  assert.equal(socketCount, 2);
  live.disconnect();
});

class LifecycleSocket {
  static instances: LifecycleSocket[] = [];
  static autoOpen = true;
  binaryType = '';
  readonly listeners = new Map<string, Function[]>();
  constructor() {
    LifecycleSocket.instances.push(this);
    if (LifecycleSocket.autoOpen) queueMicrotask(() => this.dispatch('open'));
  }
  addEventListener(type: string, listener: Function): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }
  send(): void {}
  close(): void { this.dispatch('close', { code: 1000, reason: 'done' }); }
  dispatch(type: string, value?: unknown): void {
    for (const fn of this.listeners.get(type) ?? []) fn(value);
  }
}
function lifecycleClient(): TikTokLive {
  return new TikTokLive('someone', {
    roomId: '7300', sessionCookie: 'sessionid=test', fetchGifts: false, fetchRoomInfo: false,
    signer: { sign: (url) => url },
    WebSocketImpl: LifecycleSocket as unknown as WebSocketConstructor,
    reconnect: { initialMs: 5, maxMs: 5, attempts: 2 },
  });
}

test('stale sockets cannot close a new connection or emit unhandled errors', async () => {
  LifecycleSocket.instances = [];
  const live = lifecycleClient();
  await live.connect();
  const old = LifecycleSocket.instances[0]!;
  live.disconnect();
  await live.connect();
  const current = LifecycleSocket.instances[1]!;
  old.dispatch('close', { code: 1006 });
  old.dispatch('error', { message: 'stale' });
  assert.equal(live.connected, true);
  assert.doesNotThrow(() => current.dispatch('error', { message: 'current' }));
  await new Promise((resolve) => setTimeout(resolve, 15));
  assert.equal(LifecycleSocket.instances.length, 2);
  live.disconnect();
});

test('disconnect invalidates pending open and allows a new manual connection', async () => {
  LifecycleSocket.instances = [];
  LifecycleSocket.autoOpen = false;
  const live = lifecycleClient();
  try {
    const pending = live.connect();
    const rejected = assert.rejects(pending, /cancelled/);
    for (let i = 0; i < 10 && LifecycleSocket.instances.length === 0; i++) await Promise.resolve();
    const old = LifecycleSocket.instances[0]!;
    assert.ok(old);
    live.disconnect();
    await rejected;
    LifecycleSocket.autoOpen = true;
    await live.connect();
    old.dispatch('open');
    old.dispatch('close', { code: 1006 });
    assert.equal(live.connected, true);
    assert.equal(LifecycleSocket.instances.length, 2);
  } finally {
    LifecycleSocket.autoOpen = true;
    live.disconnect();
  }
});

test('duplicate close notifications schedule one reconnect and disconnect clears it', async () => {
  LifecycleSocket.instances = [];
  const live = lifecycleClient();
  await live.connect();
  const old = LifecycleSocket.instances[0]!;
  old.dispatch('close', { code: 1006 });
  old.dispatch('close', { code: 1006 });
  live.disconnect();
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(LifecycleSocket.instances.length, 1);
});

test('a signer that disconnects cannot create a socket after cancellation', async () => {
  let sockets = 0;
  class Socket { constructor() { sockets += 1; } }
  const live = new TikTokLive('@someone', {
    roomId: '7300000000000000001',
    sessionCookie: 'sessionid=test; tt_webid_v2=7999000000000000001',
    fetchGifts: false,
    fetchRoomInfo: false,
    signer: { sign(url) { live.disconnect(); return url; } },
    WebSocketImpl: Socket as unknown as WebSocketConstructor,
  });
  await assert.rejects(live.connect(), /cancelled/);
  assert.equal(sockets, 0);
});

test('disconnect aborts an in-flight room discovery request', async () => {
  const original = globalThis.fetch;
  let signal: AbortSignal | undefined;
  globalThis.fetch = async (_url, init) => {
    signal = init?.signal as AbortSignal;
    return new Promise<Response>((_resolve, reject) => {
      signal!.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
    });
  };
  const live = new TikTokLive('someone', {
    sessionCookie: 'sessionid=test', fetchGifts: false, fetchRoomInfo: false,
    signer: { sign: (url) => url }, WebSocketImpl: LifecycleSocket as unknown as WebSocketConstructor,
  });
  try {
    const connecting = live.connect();
    for (let i = 0; i < 10 && !signal; i++) await Promise.resolve();
    assert.ok(signal);
    live.disconnect();
    assert.equal(signal.aborted, true);
    await assert.rejects(connecting, /cancelled/);
  } finally { live.disconnect(); globalThis.fetch = original; }
});

test('optional reconnect jitter stays within its configured ceiling', async () => {
  assert.throws(() => new TikTokLive('someone', { reconnectJitter: NaN }), /reconnectJitter/);
  const live = new TikTokLive('someone', {
    roomId: '7300', sessionCookie: 'sessionid=test', fetchGifts: false, fetchRoomInfo: false,
    signer: { sign: (url) => url }, WebSocketImpl: LifecycleSocket as unknown as WebSocketConstructor,
    reconnect: { initialMs: 10, maxMs: 12, attempts: 2 }, reconnectJitter: 1,
  });
  let delay: number | undefined;
  live.on('reconnecting', (event) => { delay = event.delayMs; live.disconnect(); });
  await live.connect();
  LifecycleSocket.instances.at(-1)!.dispatch('close', { code: 1006 });
  assert.ok(delay !== undefined && delay >= 10 && delay <= 12);
  assert.equal(live.connected, false);
});

test('direct async delivery ACKs before callbacks and terminates on queue saturation', async () => {
  const { create, toBinary } = await import('@bufbuild/protobuf');
  const { ProtoMessageFetchResultSchema, BaseProtoMessageSchema } = await import('../dist/gen/webcast/shared/message_pb.js');
  const { WebcastPushFrameSchema } = await import('../dist/gen/webcast/synthetic_proto_pb.js');
  let sends = 0;
  class Socket extends LifecycleSocket { override send(): void { sends++; } }
  const live = new TikTokLive('someone', {
    roomId: '7300', sessionCookie: 'sessionid=test', fetchGifts: false, fetchRoomInfo: false,
    signer: { sign: (url) => url }, WebSocketImpl: Socket as unknown as WebSocketConstructor,
    eventDelivery: { capacity: 1 },
  });
  let callbacks = 0;
  let errors = 0;
  live.on('event', () => callbacks++);
  live.on('error', () => errors++);
  await live.connect();
  const before = sends;
  const payload = toBinary(ProtoMessageFetchResultSchema, create(ProtoMessageFetchResultSchema, {
    needAck: true, internalExt: 'fixture', messages: [0, 1].map(() => create(BaseProtoMessageSchema, {
      method: 'WebcastFutureMessage', payload: new Uint8Array([8, 1]),
    })),
  }));
  const frame = toBinary(WebcastPushFrameSchema, create(WebcastPushFrameSchema, { payloadType: 'msg', payload }));
  LifecycleSocket.instances.at(-1)!.dispatch('message', { data: frame });
  assert.equal(sends, before + 1);
  assert.equal(live.connected, false);
  assert.equal(errors, 1);
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(callbacks, 0);
  assert.equal(live.deliveryMetrics().queueDepth, 0);
  assert.equal(live.deliveryMetrics().discarded, 2);
  live.disconnect();
});
