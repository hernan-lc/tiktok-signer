// The six normalisers, and the fallback that keeps the seventh from being lost.

import assert from 'node:assert/strict';
import test from 'node:test';

import { create, toBinary } from '@bufbuild/protobuf';
import { EVENT, METHOD, decodeEvent, decodeUser, label } from '../dist/events.js';
import { UserSchema } from '../dist/gen/webcast/model/base/user_2_pb.js';
import { GiftSchema } from '../dist/gen/webcast/model/color_group_pb.js';
import {
  WebcastChatMessageSchema,
  WebcastGiftMessageSchema,
  WebcastRoomUserSeqMessageSchema,
} from '../dist/gen/webcast/model/message/messages_pb.js';

const USER_ID = 6810000000000000123n;

test('a chat message carries its user and its text', () => {
  const event = decodeEvent(
    METHOD.chat,
    toBinary(WebcastChatMessageSchema, create(WebcastChatMessageSchema, {
      user: user('someone', 'Some One'),
      content: 'hola',
    })),
  );
  assert.equal(event.type, EVENT.chat);
  assert.equal(event.comment, 'hola');
  assert.equal(event.user.uniqueId, 'someone');
  assert.equal(event.user.nickname, 'Some One');
  assert.equal(event.user.userId, USER_ID.toString());
});

test('a gift reads its detail block when there is one', () => {
  const event = decodeEvent(
    METHOD.gift,
    toBinary(WebcastGiftMessageSchema, create(WebcastGiftMessageSchema, {
      giftId: 5655n,
      repeatCount: 3,
      user: user('someone'),
      repeatEnd: 1,
      gift: create(GiftSchema, { id: 5655n, diamondCount: 1, name: 'Rose' }),
    })),
  );
  if (event.type !== EVENT.gift) assert.fail(`expected gift, got ${event.type}`);
  assert.equal(event.giftId, '5655');
  assert.equal(event.giftName, 'Rose');
  assert.equal(event.diamondCount, 1);
  assert.equal(event.repeatCount, 3);
  assert.equal(event.repeatEnd, true);
});

// Every repeat of a streak omits the detail block, so a gift with no name is normal, not broken —
// the client fills it in from the room's gift table.
test('a gift without a detail block still decodes', () => {
  const event = decodeEvent(
    METHOD.gift,
    toBinary(WebcastGiftMessageSchema, create(WebcastGiftMessageSchema, {
      giftId: 5655n,
      user: user('someone'),
    })),
  );
  if (event.type !== EVENT.gift) assert.fail(`expected gift, got ${event.type}`);
  assert.equal(event.giftId, '5655');
  assert.equal(event.giftName, '');
  assert.equal(event.diamondCount, 0);
  assert.equal(event.repeatEnd, false);
});

test('viewer counts come off the room-user message', () => {
  const event = decodeEvent(
    METHOD.roomUser,
    toBinary(WebcastRoomUserSeqMessageSchema, create(WebcastRoomUserSeqMessageSchema, {
      total: 35075n,
      popularity: 41n,
    })),
  );
  assert.equal(event.type, EVENT.roomUser);
  assert.equal(event.viewers, 35075);
  assert.equal(event.popularity, 41);
});

// The room sends dozens of methods this package does not model — link-mic state, gift-panel
// updates. They must arrive whole rather than be dropped, so a caller can decode one without
// waiting for this file to grow.
test('an unmodelled method keeps its bytes', () => {
  const payload = Uint8Array.from([1, 2, 3]);
  const event = decodeEvent('WebcastLinkMicMethod', payload);
  assert.equal(event.type, EVENT.unknown);
  assert.equal(event.method, 'WebcastLinkMicMethod');
  assert.deepEqual([...event.payload], [1, 2, 3]);
});

test('a payload that cannot be read degrades instead of throwing', () => {
  const event = decodeEvent(METHOD.chat, Uint8Array.from([0x0a, 0xff]));
  assert.ok(event.type === EVENT.chat || event.type === EVENT.unknown);
});

test('a missing user is a blank user, not a crash', () => {
  const event = decodeEvent(
    METHOD.chat,
    toBinary(WebcastChatMessageSchema, create(WebcastChatMessageSchema, { content: 'orphan comment' })),
  );
  if (event.type !== EVENT.chat) assert.fail(`expected chat, got ${event.type}`);
  assert.equal(event.user.uniqueId, '');
  assert.equal(label(event.user), 'unknown');
  assert.deepEqual(decodeUser(), { userId: '0', nickname: '', uniqueId: '', secUid: '' });
});

function user(uniqueId: string, nickname = '') {
  return create(UserSchema, { id: USER_ID, nickname, displayId: uniqueId });
}
