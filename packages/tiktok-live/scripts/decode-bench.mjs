// Run after npm run build. Committed offline fixture, no TikTok requests.
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { decodeBatch } from '../dist/frames.js';
import { decodeEvent } from '../dist/events.js';
const payload = readFileSync(new URL('../../../fixtures/events/batch.pb', import.meta.url));
const iterations = Number(process.argv[2] ?? 10_000);
if (!Number.isInteger(iterations) || iterations < 100 || iterations > 1_000_000) throw new Error('iterations must be 100..=1000000');
function decode() {
  const batch = decodeBatch(payload);
  for (const message of batch.messages) decodeEvent(message.method, message.payload);
  return batch.messages.length;
}
for (let i = 0; i < 100; i++) decode();
const samples = [];
const cpuStart = process.cpuUsage();
const start = performance.now();
let events = 0;
for (let i = 0; i < iterations; i++) {
  const before = performance.now();
  events += decode();
  samples.push(performance.now() - before);
}
const elapsed = performance.now() - start;
samples.sort((a, b) => a - b);
console.log(JSON.stringify({ iterations, eventsPerSecond: events * 1000 / elapsed,
  batchMsP50: samples[5000], batchMsP95: samples[9500], batchMsP99: samples[9900],
  cpuMicros: process.cpuUsage(cpuStart), memory: process.memoryUsage(),
  maxRSSKiB: process.resourceUsage().maxRSS }));
