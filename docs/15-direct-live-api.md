# Direct LIVE connection API

`ttl-live-api` is a short-lived connection-ticket broker. Its only public operation is
`POST /v1/connect` with `{ "uniqueId": "@creator" }`.

The broker normalizes the handle, resolves the current room with `ttl-live-discovery`, caches the
result briefly, coalesces simultaneous lookups for the same creator, and asks the warm embedded
signer for a fresh direct WebSocket descriptor. It never opens or owns a persistent TikTok LIVE
socket. The Node SDK uses the returned Cookie/User-Agent metadata to connect directly to TikTok.

## Run

The binary reads the signing bundle at `SIGNING_BUNDLE` (or `TTL_BUNDLE`), defaulting to
`webmssdk.js` in the working directory. An existing session can be supplied with
`TTL_SESSION_COOKIE` or `TTL_SESSION_FILE`; otherwise the service bootstraps one anonymous web
identity at startup.

```powershell
$env:SIGNING_BUNDLE = "C:\path\to\webmssdk.js"
cargo run -p ttl-live-api
```

Use `cargo run -p ttl-live-api --features v8` when the larger, faster V8 signer is preferred.
The default embedded runtime is QuickJS. Tune bind address, cache TTLs, semaphores, timeouts, and
quotas with the environment variables documented by `AppConfig::from_env` in
`crates/ttl-live-api/src/config.rs`.

With `API_KEYS` configured as `secret=customer`, clients authenticate with a bearer token:

```powershell
curl.exe -X POST http://127.0.0.1:8080/v1/connect `
  -H "Authorization: Bearer secret" `
  -H "Content-Type: application/json" `
  -d '{"uniqueId":"@creator"}'
```

`/healthz`, `/readyz`, and `/metrics` are available for service operation. Signed URLs, cookies,
signatures, and API keys are not written to structured logs.

## SDK

```powershell
cd packages/live-sdk
npm install
npm test
```

The SDK package is Node.js-first because the direct handshake needs Cookie and User-Agent headers.
It accepts only a unique ID, reuses the repository's generated protobufs and frame helpers, ACKs
push batches before emitting normalized events, and obtains a fresh broker descriptor for every
reconnect.
