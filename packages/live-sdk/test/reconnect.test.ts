import assert from 'node:assert/strict';
import test from 'node:test';

import { DEFAULT_RECONNECT, reconnectDelay } from '../dist/live-sdk/src/index.js';

test('reconnect delay uses exponential backoff with bounded positive jitter', () => {
  assert.equal(reconnectDelay(DEFAULT_RECONNECT, 1, () => 0), 2_000);
  assert.equal(reconnectDelay(DEFAULT_RECONNECT, 1, () => 1), 3_000);
  assert.equal(reconnectDelay(DEFAULT_RECONNECT, 3, () => 0), 8_000);
  assert.equal(reconnectDelay(DEFAULT_RECONNECT, 30, () => 1), 60_000);
});
