import assert from 'node:assert/strict';
import test from 'node:test';
import { readResponseText } from '../dist/http.js';

test('chunked response overflow cancels its reader without retaining later chunks', async () => {
  let cancelled = false;
  const response = new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(8)); controller.enqueue(new Uint8Array(8)); },
    cancel() { cancelled = true; },
  }));
  await assert.rejects(readResponseText(response, 12), /exceeds/);
  assert.equal(cancelled, true);
});

test('bounded HTTP reads preserve UTF-8 split across chunks', async () => {
  const bytes = new TextEncoder().encode('hello 🌎');
  const response = new Response(new ReadableStream({ start(controller) {
    controller.enqueue(bytes.subarray(0, 8)); controller.enqueue(bytes.subarray(8)); controller.close();
  } }));
  assert.equal(await readResponseText(response, bytes.length), 'hello 🌎');
});
