// The transport envelope, and the one frame that is a reply.

import assert from 'node:assert/strict';
import test from 'node:test';
import { gzipSync } from 'node:zlib';

import { create, fromBinary, toBinary } from '@bufbuild/protobuf';
import {
  ackFrame,
  carriesEvents,
  decodeBatch,
  decodePushFrame,
  decompress,
  frameCompressType,
  frameHeaders,
} from '../dist/frames.js';
import {
  BaseProtoMessageSchema,
  ProtoMessageFetchResultSchema,
} from '../dist/gen/webcast/shared/message_pb.js';
import {
  HeartBeatMessageSchema,
  PushHeaderSchema,
  WebcastImEnterRoomMessageSchema,
  WebcastPushFrameSchema,
} from '../dist/gen/webcast/synthetic_proto_pb.js';
import { enterRoomFrame, heartbeatFrame, pushFrame } from '../dist/player.js';

const ROOM_ID = 7_675_590_159_819_148_052n;
const LOG_ID = 9_007_199_254_740_993n;

test('pushFrame serializes a generated WebcastPushFrame', () => {
  const encoded = pushFrame('msg', Uint8Array.from([1, 2, 3]), { logId: LOG_ID });
  const frame = fromBinary(WebcastPushFrameSchema, encoded);

  assert.equal(frame.logId, LOG_ID);
  assert.equal(frame.payloadEncoding, 'pb');
  assert.equal(frame.payloadType, 'msg');
  assert.deepEqual([...frame.payload], [1, 2, 3]);

  const decoded = decodePushFrame(encoded);
  assert.equal(decoded.logId, LOG_ID);
  assert.equal(carriesEvents(decoded), true);
});

test('only msg frames carry events', () => {
  for (const type of ['hb', 'ack', 'im_enter_room_resp']) {
    assert.equal(carriesEvents(decodePushFrame(pushFrame(type, ''))), false, type);
  }
});

test('a gzipped payload is decompressed from the generated repeated headers', () => {
  const body = Buffer.from('the batch would be here');
  const encoded = toBinary(WebcastPushFrameSchema, create(WebcastPushFrameSchema, {
    payloadEncoding: 'pb',
    payloadType: 'msg',
    headers: [create(PushHeaderSchema, { key: 'compress_type', value: 'gzip' })],
    payload: gzipSync(body),
  }));
  const frame = decodePushFrame(encoded);

  assert.equal(frameCompressType(frame), 'gzip');
  assert.deepEqual(frameHeaders(frame), new Map([['compress_type', 'gzip']]));
  assert.equal(decompress(frame).toString(), body.toString());
});

// An unrecognised compression should degrade to "cannot read this batch", not to a dead socket.
test('an unknown compression passes the payload through', () => {
  const frame = decodePushFrame(toBinary(WebcastPushFrameSchema, create(WebcastPushFrameSchema, {
    payloadEncoding: 'pb',
    payloadType: 'msg',
    headers: [create(PushHeaderSchema, { key: 'compress_type', value: 'brotli-someday' })],
    payload: Buffer.from('plain'),
  })));
  assert.equal(Buffer.from(decompress(frame)).toString(), 'plain');
});

// Unacknowledged frames stop the push a few seconds later, which looks exactly like a quiet room.
test('an ack accepts a generated frame and preserves its exact log id', () => {
  const received = create(WebcastPushFrameSchema, {
    logId: LOG_ID,
    payloadEncoding: 'pb',
    payloadType: 'msg',
  });
  const empty = fromBinary(WebcastPushFrameSchema, ackFrame(received, ''));
  assert.equal(empty.payloadType, 'ack');
  assert.equal(empty.logId, LOG_ID);
  assert.equal(Buffer.from(empty.payload).toString(), '-');

  const carried = fromBinary(
    WebcastPushFrameSchema,
    ackFrame(received, 'internal-ext-value'),
  );
  assert.equal(carried.logId, LOG_ID);
  assert.equal(Buffer.from(carried.payload).toString(), 'internal-ext-value');
});

test('a generated batch preserves messages, bigint ids, and ack state', () => {
  const messageId = 9_007_199_254_740_994n;
  const batch = decodeBatch(
    toBinary(ProtoMessageFetchResultSchema, create(ProtoMessageFetchResultSchema, {
      messages: [create(BaseProtoMessageSchema, {
        method: 'WebcastChatMessage',
        payload: Uint8Array.from([9]),
        msgId: messageId,
        isHistory: true,
      })],
      cursor: 'cursor-1',
      internalExt: 'ext-1',
      heartbeatDuration: 10_001n,
      needAck: true,
    })),
  );
  assert.equal(batch.messages.length, 1);
  const message = batch.messages[0];
  assert.ok(message);
  assert.equal(message.method, 'WebcastChatMessage');
  assert.equal(message.msgId, messageId);
  assert.deepEqual([...message.payload], [9]);
  assert.equal(message.isHistory, true);
  assert.equal(batch.cursor, 'cursor-1');
  assert.equal(batch.internalExt, 'ext-1');
  assert.equal(batch.heartbeatDuration, 10_001n);
  assert.equal(batch.needAck, true);
});

// These frames are decoded with the same generated schemas that encode them. In particular,
// proto3's omitted empty cursor and zero account_type recover the SDK's expected default values.
test('the generated enter-room frame preserves the SDK field semantics', () => {
  const outer = fromBinary(
    WebcastPushFrameSchema,
    enterRoomFrame({ roomId: ROOM_ID.toString(), identity: 'audience', liveId: '12' }),
  );
  const enter = fromBinary(WebcastImEnterRoomMessageSchema, outer.payload);

  assert.equal(outer.payloadType, 'im_enter_room');
  assert.equal(enter.roomId, ROOM_ID);
  assert.equal(enter.liveId, 12n);
  assert.equal(enter.identity, 'audience');
  assert.equal(enter.cursor, '');
  assert.equal(enter.accountType, 0n);
  assert.equal(enter.filterWelcomeMsg, '0');
});

test('the generated heartbeat frame preserves the exact room id', () => {
  const outer = fromBinary(
    WebcastPushFrameSchema,
    heartbeatFrame(ROOM_ID.toString()),
  );
  const heartbeat = fromBinary(HeartBeatMessageSchema, outer.payload);

  assert.equal(outer.payloadType, 'hb');
  assert.equal(heartbeat.roomId, ROOM_ID);
  assert.equal(heartbeat.sendPacketSeqId, 0n);
});

test('frame encoders reject negative integer values', () => {
  assert.throws(() => pushFrame('ack', '-', { logId: -1n }), /non-negative/);
  assert.throws(() => heartbeatFrame('-1'), /non-negative/);
});
