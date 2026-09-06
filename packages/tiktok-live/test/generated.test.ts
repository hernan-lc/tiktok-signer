import assert from 'node:assert/strict';
import test from 'node:test';

import { create, fromBinary, toBinary } from '@bufbuild/protobuf';
import { WebcastChatMessageSchema } from '../dist/gen/webcast/model/message/messages_pb.js';
import { BaseProtoMessageSchema } from '../dist/gen/webcast/shared/message_pb.js';

test('the generated TikTok chat schema decodes a small protobuf payload', () => {
  // field 3 (`content`) = "hello". This is intentionally independent of the live transport.
  const message = fromBinary(WebcastChatMessageSchema, Uint8Array.from([
    0x1a, 0x05, 0x68, 0x65, 0x6c, 0x6c, 0x6f,
  ]));
  assert.equal(message.content, 'hello');
});

test('generated int64 fields retain exact values until an explicit string boundary', () => {
  const encoded = toBinary(BaseProtoMessageSchema, create(BaseProtoMessageSchema, {
    method: 'WebcastChatMessage',
    msgId: 7300000000000000000n,
  }));
  const message = fromBinary(BaseProtoMessageSchema, encoded);
  assert.equal(message.msgId.toString(), '7300000000000000000');
});
