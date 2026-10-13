// The transport envelope: generated protobuf decoding plus small transport helpers.
//
// Every WebSocket message is a generated `WebcastPushFrame`. Only `payload_type: "msg"` carries
// events; `hb`, `ack` and `im_enter_room_resp` are transport messages. A `msg` frame's payload is
// a generated `ProtoMessageFetchResult` — usually gzipped — and it must be acknowledged.
//
// The encoders (`pushFrame`, `enterRoomFrame`, `heartbeatFrame`) remain in `player.ts`, beside the
// player constants they serialise. This module returns generated schema messages directly and
// keeps only transport conveniences that are not part of the protobuf schema.

import { gunzipSync } from 'node:zlib';

import { fromBinary } from '@bufbuild/protobuf';

import type { WebcastPushFrame } from './gen/webcast/synthetic_proto_pb.js';
import { WebcastPushFrameSchema } from './gen/webcast/synthetic_proto_pb.js';
import type { ProtoMessageFetchResult } from './gen/webcast/shared/message_pb.js';
import { ProtoMessageFetchResultSchema } from './gen/webcast/shared/message_pb.js';
import { FRAME_TYPE, pushFrame } from './player.js';

/// `payload_type` of a frame that carries events. Everything else is transport.
export const MESSAGE_PAYLOAD_TYPE = 'msg';

/// Header the server sets when the payload is compressed.
const COMPRESS_TYPE_HEADER = 'compress_type';

// Align offline batch limits with Rust; check before generated protobuf allocation.
export const MAX_FRAME_BYTES = 16 * 1024 * 1024;
export const MAX_BATCH_BYTES = 16 * 1024 * 1024;
export const MAX_BATCH_MESSAGES = 8192;

function bounded(input: Uint8Array, limit: number, kind: string): Uint8Array {
  if (input.byteLength > limit) throw new RangeError(`${kind} exceeds ${limit} bytes`);
  return input;
}

function preflightBatch(input: Uint8Array): void {
  let position = 0;
  let count = 0;
  const varint = (): number => {
    let value = 0;
    let factor = 1;
    for (let i = 0; i < 10; i++) {
      const byte = input[position++];
      if (byte === undefined) throw new Error('truncated batch varint');
      if (i === 9 && byte > 1) throw new Error('overflowing batch varint');
      value += (byte & 127) * factor;
      if (!(byte & 128)) return value;
      factor *= 128;
    }
    throw new Error('overflowing batch varint');
  };
  while (position < input.length) {
    const tag = varint();
    if (tag < 8 || tag > 0xffffffff) throw new Error('invalid batch field tag');
    const wire = tag & 7;
    if (Math.floor(tag / 8) === 1) {
      if (wire !== 2) throw new Error('invalid message wire type');
      if (++count > MAX_BATCH_MESSAGES) throw new RangeError('batch exceeds 8192 messages');
    }
    switch (wire) {
      case 0: varint(); break;
      case 1: position += 8; break;
      case 2: {
        const length = varint();
        if (!Number.isSafeInteger(length) || length > input.length - position) {
          throw new Error('truncated batch field');
        }
        position += length;
        break;
      }
      case 5: position += 4; break;
      default: throw new Error('unsupported batch wire type');
    }
    if (position > input.length) throw new Error('truncated batch field');
  }
}

function bytes(input: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (input instanceof Uint8Array) return input;
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  return new Uint8Array(input);
}

/// Read one generated WebSocket frame. The repeated protobuf headers and int64 values remain
/// exactly as generated; callers may use the helpers below for transport-specific conveniences.
export function decodePushFrame(input: ArrayBuffer | ArrayBufferView): WebcastPushFrame {
  return fromBinary(WebcastPushFrameSchema, bounded(bytes(input), MAX_FRAME_BYTES, 'frame'));
}

/// A convenience view for callers that need keyed header lookup. `frame.headers` remains the
/// authoritative repeated representation and is never replaced by this map.
export function frameHeaders(frame: WebcastPushFrame): Map<string, string> {
  return new Map(frame.headers.map((entry) => [entry.key, entry.value]));
}

/// Read the compression header without changing the generated repeated-header representation.
export function frameCompressType(frame: WebcastPushFrame): string {
  return frame.headers.find((entry) => entry.key === COMPRESS_TYPE_HEADER)?.value ?? '';
}

/// Whether the generated frame carries a protobuf event batch.
export function carriesEvents(frame: WebcastPushFrame): boolean {
  return frame.payloadType === MESSAGE_PAYLOAD_TYPE;
}

/// Decompress a frame's payload according to its own header.
///
/// An unrecognised `compress_type` is passed through rather than refused: the payload is still the
/// envelope, and a new compression name should degrade to "cannot read this batch", not "the
/// connection is broken".
export function decompress(frame: WebcastPushFrame): Uint8Array {
  bounded(frame.payload, MAX_FRAME_BYTES, 'compressed payload');
  if (frameCompressType(frame) === 'gzip') return gunzipSync(frame.payload, { maxOutputLength: MAX_BATCH_BYTES });
  return bounded(frame.payload, MAX_BATCH_BYTES, 'batch');
}

/// Read the generated event batch inside a `msg` frame's payload.
export function decodeBatch(payload: ArrayBuffer | ArrayBufferView): ProtoMessageFetchResult {
  const input = bounded(bytes(payload), MAX_BATCH_BYTES, 'batch');
  preflightBatch(input);
  return fromBinary(ProtoMessageFetchResultSchema, input);
}

/// The acknowledgement for a frame.
///
/// Legacy ACK helper: preserves the historical `-` fallback byte contract.
/// Transport delivery uses ackFrameForBatch, matching the verified player branch.
export function ackFrame(frame: WebcastPushFrame, internalExt: string): Buffer {
  return pushFrame(FRAME_TYPE.ack, internalExt || '-', { logId: frame.logId });
}

/** Per-batch player ACK; an absent extension leaves the payload empty. */
export function ackFrameForBatch(frame: WebcastPushFrame, internalExt: string): Buffer {
  return pushFrame(FRAME_TYPE.ack, internalExt, { logId: frame.logId });
}

/// Whether a frame is one of the transport's own, for logging.
export function isTransportFrame(frame: WebcastPushFrame): boolean {
  return !carriesEvents(frame);
}
