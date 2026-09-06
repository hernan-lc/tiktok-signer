import { heartbeatFrame } from './protocol.js';

const MAX_TIMER_MS = 2_147_483_647;

export function boundedHeartbeatMs(value: bigint, fallback = 10_000): number {
  if (value <= 0n) return fallback;
  const maximum = BigInt(MAX_TIMER_MS);
  return Number(value > maximum ? maximum : value);
}

export function startHeartbeat(
  roomId: string,
  everyMs: number,
  send: (frame: Buffer) => void,
): NodeJS.Timeout {
  const timer = setInterval(() => {
    try {
      send(heartbeatFrame(roomId));
    } catch {
      // The WebSocket close/error handler owns reconnect decisions.
    }
  }, Math.max(1, Math.floor(everyMs)));
  timer.unref?.();
  return timer;
}
