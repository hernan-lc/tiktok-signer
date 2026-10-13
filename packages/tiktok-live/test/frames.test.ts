// The transport envelope, and the one frame that is a reply.

import assert from 'node:assert/strict';
import test from 'node:test';
import { gzipSync } from 'node:zlib';

import { create, fromBinary, toBinary } from '@bufbuild/protobuf';
import {
  ackFrame,
  ackFrameForBatch,
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

test('gzip bombs and oversized raw inputs are bounded before protobuf allocations', () => {
  const oversized = Buffer.alloc(16 * 1024 * 1024 + 1);
  const frame = create(WebcastPushFrameSchema, {
    headers: [create(PushHeaderSchema, { key: 'compress_type', value: 'gzip' })],
    payload: gzipSync(oversized),
  });
  assert.throws(() => decompress(frame), /larger than|size|length/i);
  assert.throws(() => decodePushFrame(oversized), /frame exceeds/);
  assert.throws(() => decodeBatch(oversized), /batch exceeds/);
  assert.throws(() => decompress(create(WebcastPushFrameSchema, { payload: oversized })), /payload exceeds/);
});

test('batch message count is checked before generated repeated-message allocation', () => {
  // Empty length-delimited messages are valid protobuf and exercise the preflight cap.
  const allowed = Buffer.alloc(8192 * 2);
  for (let i = 0; i < allowed.length; i += 2) allowed[i] = 10;
  assert.equal(decodeBatch(allowed).messages.length, 8192);
  assert.throws(() => decodeBatch(Buffer.concat([allowed, Buffer.from([10, 0])])), /8192 messages/);
  for (const malformed of [[10, 255], [10, 5, 0], [8, 1], [15], [18, 128]]) {
    assert.throws(() => decodeBatch(Uint8Array.from(malformed)));
  }
});

test('player per-batch ACK leaves an empty extension empty while legacy helper stays compatible', () => {
  const incoming = create(WebcastPushFrameSchema, { logId: 44n });
  const ack = decodePushFrame(ackFrameForBatch(incoming, ''));
  assert.equal(ack.logId, 44n);
  assert.equal(ack.payloadType, 'ack');
  assert.equal(ack.payload.length, 0);
  assert.equal(Buffer.from(decodePushFrame(ackFrame(incoming, '')).payload).toString(), '-');
});

test('per-batch ACK bytes match the shared public-player reference fixture', async () => {
  const { readFileSync } = await import('node:fs');
  const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/research/player-ack-protocol-v1.json', import.meta.url), 'utf8')) as {
    synthetic_cases: Array<{ need_ack: boolean; extension: string; log_id: number; send_ack: boolean; ack_hex: string | null }>;
  };
  for (const item of fixture.synthetic_cases) {
    const batch = decodeBatch(toBinary(ProtoMessageFetchResultSchema, create(ProtoMessageFetchResultSchema, {
      needAck: item.need_ack, internalExt: item.extension,
    })));
    assert.equal(batch.needAck, item.send_ack);
    if (batch.needAck) {
      const frame = create(WebcastPushFrameSchema, { logId: BigInt(item.log_id) });
      assert.equal(ackFrameForBatch(frame, batch.internalExt).toString('hex'), item.ack_hex);
    }
  }
});
