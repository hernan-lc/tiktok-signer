// Emote parity and dispatch: the Rust fixtures, the degradation rules, and the
// two listeners a client exposes for emotes (`chat.emotes` and `emoteChat`).

import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';

import { create, toBinary } from '@bufbuild/protobuf';

import { TikTokLive } from '../dist/client.js';
import {
  EVENT,
  MAX_EMOTES_PER_EVENT,
  METHOD,
  decodeEvent,
  normalizeEmote,
} from '../dist/events.js';
import { validateJson } from '../dist/json-validation.js';
import { LiveEmoteSchema } from '../dist/gen/json/public/live-emote.js';
import { EmoteChatEventSchema } from '../dist/gen/json/public/emote-chat-event.js';
import { EmoteModelSchema, EmoteWithIndexSchema } from '../dist/gen/webcast/model/base/emoji_pb.js';
import { ImageModelSchema } from '../dist/gen/webcast/model/base/messages_pb.js';
import { UserSchema } from '../dist/gen/webcast/model/base/user_2_pb.js';
import {
  WebcastChatMessageSchema,
  WebcastEmoteChatMessageSchema,
} from '../dist/gen/webcast/model/message/messages_pb.js';
import {
  BaseProtoMessageSchema,
  ProtoMessageFetchResultSchema,
} from '../dist/gen/webcast/shared/message_pb.js';
import { WebcastPushFrameSchema } from '../dist/gen/webcast/synthetic_proto_pb.js';
import type { WebSocketConstructor, WebSocketOptions } from '../dist/types.js';

const fixtures = new URL('../../../fixtures/events/', import.meta.url);

function fixture(name: string): Uint8Array {
  return new Uint8Array(fs.readFileSync(new URL(name, fixtures)));
}

function expected(name: string): Record<string, unknown> {
  const file = name.replace(/\.pb$/, '.json');
  return JSON.parse(fs.readFileSync(new URL(`expected/${file}`, fixtures), 'utf8'));
}

function emote(id: string, url: string, extra: { packageId?: string } = {}) {
  return create(EmoteModelSchema, {
    emoteId: id,
    image: create(ImageModelSchema, { urlList: [url] }),
    ...extra,
  });
}

test('an inline emote fixture normalises exactly like the Rust golden', () => {
  const event = decodeEvent(METHOD.chat, fixture('chat-inline-emote.pb'));
  if (event.type !== EVENT.chat) assert.fail(`expected chat, got ${event.type}`);
  assert.equal(event.comment, 'Hello [laughcry]');
  assert.deepEqual(event.emotes, expected('chat-inline-emote.pb').emotes);
});

test('multiple and unicode emote fixtures stay in wire order', () => {
  for (const name of ['chat-multiple-emotes.pb', 'chat-unicode-emote.pb']) {
    const event = decodeEvent(METHOD.chat, fixture(name));
    if (event.type !== EVENT.chat) assert.fail(`expected chat, got ${event.type}`);
    assert.deepEqual(event.emotes, expected(name).emotes, name);
  }
});

test('a plain chat comment has no emotes key at all', () => {
  const event = decodeEvent(METHOD.chat, fixture('chat.pb'));
  if (event.type !== EVENT.chat) assert.fail(`expected chat, got ${event.type}`);
  assert.equal(Object.hasOwn(event, 'emotes'), false, 'empty lists are omitted');
});

test('malformed emote metadata degrades per emote, never per message', () => {
  const event = decodeEvent(METHOD.chat, fixture('chat-invalid-emote.pb'));
  if (event.type !== EVENT.chat) assert.fail(`expected chat, got ${event.type}`);
  assert.equal(event.comment, 'Bad [emote]');
  assert.deepEqual(event.emotes, expected('chat-invalid-emote.pb').emotes);
});

test('a standalone emote message is its own event type', () => {
  const event = decodeEvent(METHOD.emoteChat, fixture('emote-chat.pb'));
  if (event.type !== EVENT.emoteChat) assert.fail(`expected emoteChat, got ${event.type}`);
  assert.equal(event.user.uniqueId, 'golden.tester');
  assert.deepEqual(event.emotes, expected('emote-chat.pb').emotes);
  assert.equal(Object.hasOwn(event, 'comment'), false, 'no fabricated comment');
});

test('unsafe image URLs are rejected while the emote survives', () => {
  for (const url of [
    'javascript:alert(1)',
    'data:image/png;base64,AAAA',
    'file:///etc/passwd',
    'https://',
    'https:///no-host',
    'ftp://example.com/x.webp',
    'https://cdn.example/a b.webp',
  ]) {
    const normalized = normalizeEmote(emote('7', url), 3);
    assert.ok(normalized, url);
    assert.equal(normalized.id, '7', url);
    assert.equal(normalized.imageUrl, undefined, url);
    assert.equal(normalized.index, 3, url);
  }
});

test('URL validation accepts real hosts and rejects credentials and obfuscation', () => {
  const accepted = [
    'http://cdn.example:8080/img/x.webp',
    'https://[::1]:8443/img/x.webp',
    'https://xn--r8jz45g.xn--zckzah/x.webp',
    'https://例え.テスト/x.webp',
  ];
  for (const url of accepted) {
    assert.equal(normalizeEmote(emote('7', url), 0)?.imageUrl, url, url);
  }
  const rejected = [
    'https://user@cdn.example/x.webp',
    'https://user:pass@cdn.example/x.webp',
    'http://cdn.example:99999/x.webp',
    'https:///no-host',
    'https://',
    'http:cdn.example/x.webp',
    'https://cdn.example/\u0007x.webp',
  ];
  for (const url of rejected) {
    const normalized = normalizeEmote(emote('7', url), 0);
    assert.ok(normalized, url);
    assert.equal(normalized.imageUrl, undefined, url);
  }
});

test('an emote with neither id nor image is dropped', () => {
  assert.equal(normalizeEmote(emote('', ''), 0), undefined);
  assert.equal(normalizeEmote(undefined, 0), undefined);
});

test('optional fields are omitted rather than defaulted', () => {
  const plain = normalizeEmote(emote('1', 'https://cdn.example/x.webp'), 0);
  assert.deepEqual(plain, { id: '1', imageUrl: 'https://cdn.example/x.webp', index: 0 });
  assert.equal(Object.hasOwn(plain as object, 'shortcode'), false);
  assert.equal(Object.hasOwn(plain as object, 'emoteType'), false);
  assert.equal(Object.hasOwn(plain as object, 'packageId'), false);
});

test('a long id drops the emote and a long URL only drops the artwork', () => {
  const longId = 'a'.repeat(129);
  assert.equal(normalizeEmote(emote(longId, 'https://cdn.example/x.webp'), 0), undefined);
  const longUrl = `https://cdn.example/${'u'.repeat(2048)}`;
  const kept = normalizeEmote(emote('ok', longUrl), 1);
  assert.equal(kept?.id, 'ok');
  assert.equal(kept?.imageUrl, undefined);
});

test('the emote list is capped', () => {
  const emotes = Array.from({ length: MAX_EMOTES_PER_EVENT + 5 }, (_, index) =>
    create(EmoteWithIndexSchema, { index, emote: emote(`${index}`, 'https://cdn.example/x.webp') }),
  );
  const payload = toBinary(WebcastChatMessageSchema, create(WebcastChatMessageSchema, {
    content: '[flood]',
    emotes,
  }));
  const event = decodeEvent(METHOD.chat, payload);
  if (event.type !== EVENT.chat) assert.fail('expected chat');
  assert.equal(event.emotes?.length, MAX_EMOTES_PER_EVENT);
});

test('an unmodelled method still keeps its bytes', () => {
  const payload = Uint8Array.from([1, 2, 3]);
  const event = decodeEvent('WebcastEmoteSomethingElse', payload);
  assert.equal(event.type, EVENT.unknown);
  assert.equal(event.method, 'WebcastEmoteSomethingElse');
});

test('the standalone emote schema accepts the Rust shape and rejects a bad URL', () => {
  const value = {
    type: 'emoteChat',
    method: METHOD.emoteChat,
    user: { userId: '1', nickname: 'a', uniqueId: 'a', secUid: 's' },
    emotes: [{ id: '1', imageUrl: 'https://cdn.example/x.webp', index: 2 }],
  };
  assert.deepEqual(validateJson(value, EmoteChatEventSchema, 'emote chat'), value);
  assert.deepEqual(validateJson({ id: '1', index: 2 }, LiveEmoteSchema, 'live emote'), { id: '1', index: 2 });
  assert.throws(
    () => validateJson({ id: '1', imageUrl: 'not-a-uri' }, LiveEmoteSchema, 'live emote'),
    /a valid URI/,
  );
  assert.throws(
    () => validateJson({ index: 2 }, LiveEmoteSchema, 'live emote'),
    /id/,
  );
});

// --- client dispatch -------------------------------------------------------------------------

const USER = { id: 6810000000000000123n, nickname: 'Ay', displayId: 'ay' };

function eventPayloads(): { chat: Uint8Array; emoteChat: Uint8Array } {
  const user = create(UserSchema, USER);
  return {
    chat: toBinary(WebcastChatMessageSchema, create(WebcastChatMessageSchema, {
      user,
      content: 'Hello [laughcry]',
      emotes: [create(EmoteWithIndexSchema, {
        index: 6,
        emote: emote('7098', 'https://cdn.example/laughcry.webp'),
      })],
    })),
    emoteChat: toBinary(WebcastEmoteChatMessageSchema, create(WebcastEmoteChatMessageSchema, {
      user,
      emoteList: [emote('7099', 'https://cdn.example/happy.webp', { packageId: 'p1' })],
    })),
  };
}

/// Wrap raw event payloads in the transport frame a client actually receives.
function frameWith(...payloads: Uint8Array[]): Uint8Array {
  const batch = toBinary(ProtoMessageFetchResultSchema, create(ProtoMessageFetchResultSchema, {
    needAck: false,
    messages: payloads.map((payload, index) => create(BaseProtoMessageSchema, {
      method: index === 0 ? METHOD.chat : METHOD.emoteChat,
      payload,
      msgId: BigInt(index + 1),
      isHistory: index === 1,
    })),
  }));
  return toBinary(WebcastPushFrameSchema, create(WebcastPushFrameSchema, {
    payloadType: 'msg',
    payload: batch,
  }));
}

class FakeSocket {
  static current: FakeSocket | null = null;
  static instances: FakeSocket[] = [];
  binaryType = '';
  readonly #listeners = new Map<string, Array<(event: unknown) => void>>();

  constructor(_url: string, _options: WebSocketOptions) {
    FakeSocket.current = this;
    FakeSocket.instances.push(this);
    queueMicrotask(() => this.dispatch('open'));
  }

  send(_data: string | ArrayBuffer | ArrayBufferView): void {}
  close(): void {}

  addEventListener(type: string, listener: (event: unknown) => void): void {
    this.#listeners.set(type, [...(this.#listeners.get(type) ?? []), listener]);
  }

  dispatch(type: string, event?: unknown): void {
    for (const listener of this.#listeners.get(type) ?? []) listener(event);
  }
}

function dispatchClient(): TikTokLive {
  return new TikTokLive('someone', {
    roomId: '7300',
    sessionCookie: 'sessionid=test',
    fetchGifts: false,
    fetchRoomInfo: false,
    signer: { sign: (url) => url },
    WebSocketImpl: FakeSocket as unknown as WebSocketConstructor,
  });
}

test('a client emits chat.emotes and a dedicated emoteChat event in order', async () => {
  FakeSocket.current = null;
  FakeSocket.instances = [];
  const live = dispatchClient();
  const types: string[] = [];
  const chats: Array<{ emotes?: unknown[] }> = [];
  const emoteChats: Array<{ emotes: unknown[] }> = [];
  live.on('event', (event) => types.push(event.type));
  live.on('chat', (event) => chats.push(event));
  live.on('emoteChat', (event) => emoteChats.push(event));

  await live.connect();
  const { chat, emoteChat } = eventPayloads();
  FakeSocket.current!.dispatch('message', { data: frameWith(chat, emoteChat) });

  assert.deepEqual(types, [EVENT.chat, EVENT.emoteChat], 'event order is preserved');
  assert.equal(chats.length, 1);
  assert.equal(chats[0]?.emotes?.length, 1);
  assert.equal(emoteChats.length, 1);
  assert.equal(emoteChats[0]?.emotes.length, 1);
  live.disconnect();
});

test('the client preserves message identity and the history flag', async () => {
  FakeSocket.current = null;
  FakeSocket.instances = [];
  const live = dispatchClient();
  let emoteChat: { msgId?: string; isHistory?: boolean } | undefined;
  live.on('emoteChat', (event) => { emoteChat = event; });

  await live.connect();
  const { chat, emoteChat: emotePayload } = eventPayloads();
  FakeSocket.current!.dispatch('message', { data: frameWith(chat, emotePayload) });

  assert.equal(emoteChat?.msgId, '2');
  assert.equal(emoteChat?.isHistory, true);
  live.disconnect();
});
