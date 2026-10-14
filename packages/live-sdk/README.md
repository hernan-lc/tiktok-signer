# @company/tiktok-live

Node.js SDK for direct TikTok LIVE connections.

The package uses the `ws` client by default so the broker-provided Cookie and User-Agent headers
can be attached to the TikTok handshake. A compatible `WebSocketImpl` can be supplied for another
Node runtime.

```ts
import { TikTokLive } from '@company/tiktok-live';

const live = new TikTokLive('@creator', {
  apiKey: process.env.TIKTOK_LIVE_API_KEY!,
});

live.on('chat', (event) => console.log(event.user.nickname, event.comment));
live.on('gift', (event) => console.log(event.user.nickname, event.giftName));
live.on('offline', () => console.log('creator is offline'));

await live.connect();
```

The SDK sends only `{ uniqueId }` to the broker. Once it receives a connection descriptor, it opens
the returned URL directly to TikTok, handles the application heartbeat and ACK frames, decodes the
repository's generated protobufs, and reconnects with a fresh descriptor after a close. Rust never
proxies the event stream.

The canonical unique id is the handle without `@`; both input spellings are accepted. Room IDs and
signing parameters are intentionally not accepted as SDK options.

### Delivery and shutdown limits

Use `eventDelivery: { capacity: 1024, maxBytes: 64 * 1024 * 1024 }` for optional
bounded ordered asynchronous delivery; synchronous delivery remains the default.
Overflow stops the connection with an error. `deliveryMetrics()` exposes queue and
delivery counters plus listener failures. Disconnect aborts pending broker/gift
HTTP requests, invalidates stale sockets and clears heartbeat/reconnect/delivery
timers. Broker response bodies are capped at 1 MiB; LIVE frame/batch limits are
16 MiB / 8192 messages. See [resource configuration](../../docs/18-live-resource-configuration.md).
