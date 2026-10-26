import assert from 'node:assert/strict';
import { once } from 'node:events';
import test from 'node:test';
import { WebSocketServer } from 'ws';
import { TikTokLive } from '../dist/client.js';
import { decodePushFrame } from '../dist/frames.js';

test('the default Node transport forwards identity headers and sends room entry', { timeout: 5_000 }, async () => {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  const address = server.address();
  assert.ok(address && typeof address !== 'string');
  const received = new Promise<void>((resolve, reject) => {
    server.once('connection', (socket, request) => {
      try {
        assert.equal(request.headers.cookie, 'ttwid=fixture; sessionid=fixture');
        assert.equal(request.headers['user-agent'], 'fixture-user-agent');
        assert.equal(request.headers.origin, 'https://www.tiktok.com');
        assert.match(request.url ?? '', /X-Gnarly=fixture/);
        socket.once('message', (bytes) => {
          try {
            assert.equal(decodePushFrame(bytes as Buffer).payloadType, 'im_enter_room');
            resolve();
          } catch (error) { reject(error); }
        });
      } catch (error) { reject(error); }
    });
  });
  const live = new TikTokLive('fixture', {
    roomId: '1', sessionCookie: 'ttwid=fixture; sessionid=fixture',
    userAgent: 'fixture-user-agent', socketHost: `ws://127.0.0.1:${address.port}`,
    signer: { sign: (url) => `${url}&X-Gnarly=fixture` },
    fetchRoomInfo: false, fetchGifts: false,
  });
  try {
    await Promise.all([live.connect(), received]);
  } finally {
    live.disconnect();
    for (const socket of server.clients) socket.terminate();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
