// The transport envelope: generated protobuf decoding plus the transport-specific adapter.
//
// Every WebSocket message is a generated `WebcastPushFrame`. Only `payload_type: "msg"` carries
// events; `hb`, `ack` and `im_enter_room_resp` are transport messages. A `msg` frame's payload is
// a generated `ProtoMessageFetchResult` — usually gzipped — and it must be acknowledged.
//
// The encoders (`pushFrame`, `enterRoomFrame`, `heartbeatFrame`) remain in `player.ts`, beside the
// player constants they serialise. This module only converts generated schema messages to the
// package's stable transport adapter types, plus the one frame that is a reply.

import { gunzipSync } from 'node:zlib';

import { fromBinary } from '@bufbuild/protobuf';

import { WebcastPushFrameSchema } from './gen/webcast/synthetic_proto_pb.js';
import { ProtoMessageFetchResultSchema } from './gen/webcast/shared/message_pb.js';
import { FRAME_TYPE, pushFrame } from './player.js';

/// `payload_type` of a frame that carries events. Everything else is transport.
export const MESSAGE_PAYLOAD_TYPE = 'msg';

/// Header the server sets when the payload is compressed.
const COMPRESS_TYPE_HEADER = 'compress_type';

export interface PushFrame {
  seqId: string;
  logId: string;
  headers: Map<string, string>;
  payloadEncoding: string;
  payloadType: string;
  payload: Uint8Array;
  compressType: string;
  carriesEvents: boolean;
}
export interface BatchMessage {
  method: string;
  payload: Uint8Array;
  msgId: string;
  isHistory: boolean;
}
export interface EventBatch {
  messages: BatchMessage[];
  cursor: string;
  internalExt: string;
  heartbeatDuration: number;
  needAck: boolean;
  pushServer: string;
}

function bytes(input: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (input instanceof Uint8Array) return input;
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  return new Uint8Array(input);
}

/// Convert a generated signed protobuf integer to the package's non-negative count convention.
function asCount(value: bigint): number {
  return value > 0n ? Number(value) : 0;
}

/// Read one generated WebSocket frame and expose the package's stable transport shape.
export function decodePushFrame(input: ArrayBuffer | ArrayBufferView): PushFrame {
  const frame = fromBinary(WebcastPushFrameSchema, bytes(input));
  const headers = new Map(frame.headers.map((entry) => [entry.key, entry.value]));
  return {
    seqId: frame.seqId.toString(),
    logId: frame.logId.toString(),
    headers,
    payloadEncoding: frame.payloadEncoding,
    payloadType: frame.payloadType,
    payload: frame.payload,
    compressType: headers.get(COMPRESS_TYPE_HEADER) ?? '',
    carriesEvents: frame.payloadType === MESSAGE_PAYLOAD_TYPE,
  };
}

/// Decompress a frame's payload according to its own header.
///
/// An unrecognised `compress_type` is passed through rather than refused: the payload is still the
/// envelope, and a new compression name should degrade to "cannot read this batch", not "the
/// connection is broken".
export function decompress(frame: PushFrame): Uint8Array {
  if (frame.compressType === 'gzip') return gunzipSync(frame.payload);
  return frame.payload;
}

/// Read the generated event batch inside a `msg` frame's payload.
export function decodeBatch(payload: ArrayBuffer | ArrayBufferView): EventBatch {
  const batch = fromBinary(ProtoMessageFetchResultSchema, bytes(payload));
  return {
    messages: batch.messages.map((message) => ({
      method: message.method,
      payload: message.payload,
      msgId: message.msgId.toString(),
      isHistory: message.isHistory,
    })),
    cursor: batch.cursor,
    internalExt: batch.internalExt,
    heartbeatDuration: asCount(batch.heartbeatDuration),
    needAck: batch.needAck,
    pushServer: batch.pushServer,
  };
}

/// The acknowledgement for a frame.
///
/// The payload is the batch's `internal_ext`, or `-` when it is empty — the server rejects an
/// empty one. Unacknowledged frames stop the push after a few seconds, which looks exactly like a
/// quiet room.
export function ackFrame(frame: PushFrame, internalExt: string): Buffer {
  return pushFrame(FRAME_TYPE.ack, internalExt || '-', { logId: frame.logId });
}

/// Whether a frame is one of the transport's own, for logging.
export function isTransportFrame(frame: PushFrame): boolean {
  return !frame.carriesEvents;
}
