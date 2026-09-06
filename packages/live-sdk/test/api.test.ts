import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ConnectApi,
  LiveApiError,
  normalizeUniqueId,
} from '../dist/live-sdk/src/index.js';

test('only the canonical uniqueId is sent to the broker', async () => {
  let request: RequestInit | undefined;
  const api = new ConnectApi({
    apiUrl: 'https://broker.example.test/',
    apiKey: 'secret-key',
    fetchImpl: async (_url, init) => {
      request = init;
      return new Response(JSON.stringify({
        version: 1,
        uniqueId: 'creator',
        roomId: '7300',
        status: 'live',
        connection: {
          url: 'wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300&X-Gnarly=test',
          cookies: 'ttwid=test',
          userAgent: 'fixture-agent',
        },
      }), { status: 200 });
    },
  });

  const response = await api.connect('@creator');
  assert.equal(response.uniqueId, 'creator');
  assert.equal(request?.method, 'POST');
  assert.deepEqual(JSON.parse(String(request?.body)), { uniqueId: 'creator' });
  assert.equal((request?.headers as Record<string, string>).authorization, 'Bearer secret-key');
});

test('rate limit errors preserve retry metadata', async () => {
  const api = new ConnectApi({
    fetchImpl: async () => new Response(JSON.stringify({
      error: {
        code: 'RATE_LIMITED',
        message: 'slow down',
        retryable: true,
        retryAfterMs: 1_500,
      },
    }), {
      status: 429,
      headers: { 'x-request-id': '42' },
    }),
  });
  await assert.rejects(
    () => api.connect('creator'),
    (error: unknown) => error instanceof LiveApiError
      && error.code === 'RATE_LIMITED'
      && error.retryAfterMs === 1_500
      && error.requestId === '42',
  );
});

test('identifier validation rejects URLs and accepts the two handle spellings', () => {
  assert.equal(normalizeUniqueId(' creator '), 'creator');
  assert.equal(normalizeUniqueId('@creator'), 'creator');
  assert.throws(() => normalizeUniqueId('https://tiktok.com/@creator'));
  assert.throws(() => normalizeUniqueId('@creator/other'));
});
