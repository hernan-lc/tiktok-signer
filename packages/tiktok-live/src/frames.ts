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
  return fromBinary(WebcastPushFrameSchema, bytes(input));
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
  if (frameCompressType(frame) === 'gzip') return gunzipSync(frame.payload);
  return frame.payload;
}

/// Read the generated event batch inside a `msg` frame's payload.
export function decodeBatch(payload: ArrayBuffer | ArrayBufferView): ProtoMessageFetchResult {
  return fromBinary(ProtoMessageFetchResultSchema, bytes(payload));
}

/// The acknowledgement for a frame.
///
/// The payload is the batch's `internal_ext`, or `-` when it is empty — the server rejects an
/// empty one. Unacknowledged frames stop the push after a few seconds, which looks exactly like a
/// quiet room.
export function ackFrame(frame: WebcastPushFrame, internalExt: string): Buffer {
  return pushFrame(FRAME_TYPE.ack, internalExt || '-', { logId: frame.logId });
}

/// Whether a frame is one of the transport's own, for logging.
export function isTransportFrame(frame: WebcastPushFrame): boolean {
  return !carriesEvents(frame);
}
