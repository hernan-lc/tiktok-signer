import { decodeBatch, decodePushFrame, decompress } from './protocol.js';
import { decodeEvent } from '../../tiktok-live/src/events.js';
import type { LiveEvent } from '../../tiktok-live/src/types.js';

export interface DecodedBatchEvent {
  event: LiveEvent;
  msgId: string;
  isHistory: boolean;
}

export function decodeFrameEvents(input: ArrayBuffer | ArrayBufferView): {
  frame: ReturnType<typeof decodePushFrame>;
  events: DecodedBatchEvent[];
  heartbeatDuration: bigint;
  needAck: boolean;
  internalExt: string;
} | null {
  const frame = decodePushFrame(input);
  if (frame.payloadType !== 'msg') return null;
  const batch = decodeBatch(decompress(frame));
  return {
    frame,
    heartbeatDuration: batch.heartbeatDuration,
    needAck: batch.needAck,
    internalExt: batch.internalExt,
    events: batch.messages.map((message) => ({
      event: {
        ...decodeEvent(message.method, message.payload),
        msgId: message.msgId.toString(),
        isHistory: message.isHistory,
      },
      msgId: message.msgId.toString(),
      isHistory: message.isHistory,
    })),
  };
}
