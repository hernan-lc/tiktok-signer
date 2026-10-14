// Offline bounded-delivery lifecycle soak. Run after npm run build; no network or real signing.
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { setTimeout as sleep } from 'node:timers/promises';
import { create, toBinary } from '@bufbuild/protobuf';
import { TikTokLive } from '../dist/client.js';
import { WebcastPushFrameSchema } from '../dist/gen/webcast/synthetic_proto_pb.js';
const seconds = Number(process.argv[2] ?? 1800);
if (!Number.isInteger(seconds) || seconds < 3 || seconds > 3600) throw new Error('seconds must be 3..3600');
const payload = readFileSync(new URL('../../../fixtures/events/batch.pb', import.meta.url));
const frame = toBinary(WebcastPushFrameSchema, create(WebcastPushFrameSchema, { payloadType: 'msg', logId: 42n, payload }));
class Socket {
  static current;
  static active = 0;
  listeners = new Map();
  closed = false;
  binaryType = '';
  constructor() { Socket.current = this; Socket.active++; queueMicrotask(() => this.dispatch('open')); }
  addEventListener(type, listener) { this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]); }
  send() {}
  dispatch(type, value) { for (const listener of this.listeners.get(type) ?? []) listener(value); }
  close() { if (!this.closed) { this.closed = true; Socket.active--; this.dispatch('close', { code: 1000 }); } }
}
let callbacks = 0, errors = 0, cycles = 0;
const live = new TikTokLive('offline', {
  roomId: '7300', sessionCookie: 'sessionid=offline', fetchGifts: false, fetchRoomInfo: false,
  signer: { sign: (url) => url }, WebSocketImpl: Socket,
  eventDelivery: { capacity: 8192 }, reconnect: { attempts: 0 },
});
live.on('event', () => { callbacks++; });
live.on('error', () => { errors++; });
const started = performance.now();
console.log(JSON.stringify({ stage: 'baseline', memory: process.memoryUsage() }));
for (const rate of [1000, 5000, 20000]) {
  const deadline = performance.now() + seconds * 1000 / 3;
  const before = callbacks;
  while (performance.now() < deadline) {
    await live.connect();
    const socket = Socket.current;
    const cycleDeadline = Math.min(deadline, performance.now() + 1000);
    let frames = 0;
    const cycleStart = performance.now();
    while (performance.now() < cycleDeadline && live.connected) {
      const due = Math.floor((performance.now() - cycleStart) * rate / 13000);
      // Catch up bounded bursts rather than retaining scheduled work or spawning per-event timers.
      for (let n = 0; frames < due && n < 8 && live.connected; n++, frames++) {
        socket.dispatch('message', { data: frame });
      }
      await sleep(1);
    }
    live.disconnect();
    const stopped = callbacks;
    socket.dispatch('message', { data: frame });
    await sleep(1);
    if (callbacks !== stopped || Socket.active !== 0) throw new Error('stale callback or live socket after disconnect');
    cycles++;
    if (cycles % 10 === 0) console.log(JSON.stringify({ rate, cycles, callbacks, errors, metrics: live.deliveryMetrics(), memory: process.memoryUsage() }));
  }
  console.log(JSON.stringify({ rate, delivered: callbacks - before, elapsed: (performance.now() - started) / 1000 }));
}
await sleep(100);
console.log(JSON.stringify({ stage: 'cleanup', cycles, callbacks, errors, active: Socket.active, memory: process.memoryUsage() }));
