// Evaluate persistent worker decode cost before adding a production worker API.
import { isMainThread, parentPort, Worker } from 'node:worker_threads';
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { decodeBatch } from '../dist/frames.js';
import { decodeEvent } from '../dist/events.js';
function decode(bytes) {
  const batch = decodeBatch(bytes);
  return batch.messages.map(message => decodeEvent(message.method, message.payload));
}
if (!isMainThread) {
  parentPort.on('message', bytes => parentPort.postMessage(decode(bytes)));
} else {
  const iterations = Number(process.argv[2] ?? 1000);
  if (!Number.isInteger(iterations) || iterations < 100 || iterations > 100000) throw new Error('iterations must be 100..100000');
  const payload = readFileSync(new URL('../../../fixtures/events/batch.pb', import.meta.url));
  const worker = new Worker(new URL(import.meta.url));
  // Single-flight bounds both request and response retention. Worker is reused throughout.
  const request = () => new Promise((resolve, reject) => {
    const failure = error => { worker.off('message', success); reject(error); };
    const success = value => { worker.off('error', failure); resolve(value); };
    worker.once('message', success); worker.once('error', failure); worker.postMessage(payload);
  });
  try {
    for (let i = 0; i < 100; i++) { decode(payload); await request(); }
    for (const mode of ['sync', 'worker']) {
      const samples = [];
      let events = 0;
      const started = performance.now();
      for (let i = 0; i < iterations; i++) {
        const before = performance.now();
        const decoded = mode === 'sync' ? decode(payload) : await request();
        events += decoded.length;
        samples.push(performance.now() - before);
      }
      samples.sort((a,b) => a-b);
      console.log(JSON.stringify({ mode, eventsPerSecond: events * 1000 / (performance.now()-started),
        batchMsP50: samples[Math.floor(iterations*.5)], batchMsP95: samples[Math.floor(iterations*.95)],
        batchMsP99: samples[Math.floor(iterations*.99)], memory: process.memoryUsage() }));
    }
  } finally { await worker.terminate(); }
}
