import assert from 'node:assert/strict';
import test from 'node:test';
import { EventDelivery } from '../dist/delivery.js';
const tick = () => new Promise<void>((resolve) => setImmediate(resolve));

test('optional delivery is FIFO, bounded, and clears retained events/timers', async () => {
  const events: number[] = [];
  const delivery = new EventDelivery<number>({ capacity: 1000, batchSize: 64 },
    (value) => { events.push(value); return true; }, () => assert.fail('unexpected overflow'));
  for (let i = 0; i < 1000; i++) delivery.submit(i, 1, 4);
  assert.equal(events.length, 0);
  assert.equal(delivery.metrics().queueDepth, 1000);
  while (delivery.metrics().queueDepth) await tick();
  assert.deepEqual(events, Array.from({ length: 1000 }, (_, i) => i));
  assert.equal(delivery.metrics().queuedBytes, 0);
  delivery.submit(1001, 1, 4);
  delivery.clear();
  await tick();
  assert.equal(events.length, 1000);
  assert.equal(delivery.metrics().discarded, 1);
});

test('capacity and byte saturation explicitly report overflow and release the queue', async () => {
  let overflows = 0;
  const delivery = new EventDelivery<number>({ capacity: 2, maxBytes: 16 },
    () => assert.fail('overflowed queue must not dispatch'), () => { overflows++; });
  delivery.submit(1, 1, 8);
  delivery.submit(2, 1, 8);
  delivery.submit(3, 1, 8);
  assert.equal(overflows, 1);
  assert.equal(delivery.metrics().queueDepth, 0);
  assert.equal(delivery.metrics().discarded, 3);
  delivery.submit(4, 1, 17);
  assert.equal(overflows, 2);
  assert.equal(delivery.metrics().queuedBytes, 0);
  await tick();
});

test('synchronous default preserves immediate dispatch and stale generations can be discarded', () => {
  let calls = 0;
  const delivery = new EventDelivery<number>(undefined, (_value, generation) => {
    if (generation !== 1) return false;
    calls++; return true;
  }, () => assert.fail('synchronous delivery cannot saturate'));
  delivery.submit(1, 1, 0);
  delivery.submit(2, 2, 0);
  assert.equal(calls, 1);
  assert.equal(delivery.metrics().dispatched, 1);
  assert.equal(delivery.metrics().discarded, 1);
  assert.equal(delivery.metrics().queueDepth, 0);
});
