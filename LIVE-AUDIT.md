# LIVE audit and real-data verification — 2026-10-25

Baseline: `5dc5e39` (PR #11), already merged into `main`. This audit covers the observed decoding failures, schema drift visibility, transport failures, callback backpressure, and concurrent connection behavior. Original captures and detailed logs remain in the ignored `.local/` directory. The committed [aggregate report](fixtures/research/live-audit-2026-10-25.json) contains no participant identities, cookies, signed URLs, or original chat content.

## Fixed issues

- **A real social value was lost.** Captured events contained `target_user_id = -1`; Rust treated it as a count and clamped it to zero. `SocialEvent` and `SocialEvidence` now retain the original signed `int64`. Their Rust field types change from `u64` to `i64`; positive values retain their previous JSON representation. The public Node JSON Schema also rejected this real value and now accepts signed decimal strings. A reduced regression preserves the observed sentinel without participant data.
- **ACK write failures appeared successful.** ACK success is recorded only after a successful write. A failed write returns `WsError::Closed`, allowing the existing reconnect policy to handle it. A closing-socket regression verifies both the returned error and ACK status.
- **Node authenticated handshakes lost their headers.** The default global WebSocket and the integration-test wrapper ignored the configured cookie, user-agent, and origin. The default Node transport now uses `ws`, forwards those headers, limits messages to 16 MiB, and disables WebSocket-level deflate. Local handshake and real-service integration tests cover the change.
- **Mobile callbacks could block network progress.** Mobile LIVE clients now use `run_buffered`: an ordered callback thread, 32 queued notifications, and an 8 MiB estimated retention budget. Queue overflow stops reception and reports `Backpressure` after accepted notifications drain. Tests hold a callback while subsequent ACKs progress, including on a single-thread runtime. Saturation, callback panic, and callback-initiated disconnect are covered.
- **Native callbacks blocked the network by default.** The C ABI now defaults to its ordered dispatcher with capacity 32. An additional 8 MiB estimated retention limit includes metadata and active delivery. Capacity zero explicitly selects the legacy inline mode. Tests cover count saturation, metadata-only byte saturation, reentrant disconnect, userdata lifetime, and shutdown accounting.
- **Unknown events hid different failure causes.** `normalization_status` distinguishes unsupported methods, malformed wire, typed decoder errors, payload limits, per-event field limits, shared batch field limits, and depth limits. Full JSON includes diagnostics while legacy serialization retains its existing shape.
- **Schema changes lacked actionable telemetry.** `AuditReport` counts unknown methods, numeric field paths/wire types, unknown enum values, unclassified social actions, warnings, truncation, and field presence. Cardinality is bounded. Missing fields are not asserted from a truncated diagnostic view. Raw emote-field presence is inspected independently of normalization and compared with usable normalized emotes.
- **An exact field-limit boundary rejected a valid message.** An empty submessage in the last permitted field no longer fails solely because the field budget reached zero before entering it. The 4,096-field boundary has a regression.
- **Timestamp extraction depended on the diagnostic view.** Timestamp metadata now comes from independent preflight inspection and is checked across decoding modes.
- **Search pagination lost its context.** Offset-only requests could return a terminal-shaped body instead of the next search page. A controlled real request returned 13 rooms with the first page's `search_id`; the otherwise comparable request without it returned no data. `live_channels_context` retains the initial search ID and follows the server cursor. Tests cover advancing cursors, original-context retention, URL escaping, terminal responses, and malformed/refused responses. The multi-room sampler used it to discover 100 distinct live rooms in nine requests.
- **The player drift audit could no longer locate its source.** The served manifest now uses mixed numeric/alphanumeric chunk IDs and runtime name/hash maps. Static resolution supports both observed manifest generations without evaluating downloaded JavaScript. Signing extraction supports literals and both observed signing interfaces. Five deterministic tests cover asset resolution and profile extraction.
- **The headless transport rejected a valid current signing profile.** The served player uses `frontierSign`/`X-Bogus`, while the compatibility profile uses `registerWsSigner`/`X-Gnarly`. Both received 445 events each, including 321 chats, during a simultaneous active-room check. The backend accepts either nonempty signature parameter and rejects empty values or misleading substring matches. The existing default is retained, and the current player fixture records the reviewed profile.

Inline core sinks must still return promptly; blocking consumers should use `run_buffered`. Arbitrary callbacks must eventually return for an external shutdown to join them. Retention charges are conservative estimates rather than exact allocator measurements.

## Real multi-room results

The main run observed **100 distinct real LIVE rooms**, with 20 concurrent sockets and a 90-second listening window per room. All 100 rooms delivered events and completed their windows.

| Observation | Result |
| --- | ---: |
| Decompressed event frames | 5,686 |
| Event observations | 12,829 |
| Successfully normalized events | 11,556 |
| Unsupported canonical methods retained as raw/schema events | 1,273 |
| Batch decoding errors | 0 |
| Capture write errors | 0 |
| Evidence frames rejected by capture quota | 0 |
| Real emote events | 23 |
| Real emotes, counted on both wire and normalized model | 35 / 35 |
| Rooms producing real emotes | 10 |

The original 10-room probe received 8,235 events over two-minute windows without observing an emote. Increasing coverage found them. The initial attempt at 100 rooms reached only 14 before exposing the pagination issue; that partial attempt is not counted as the successful 100-room run.

The sampler retained 364 baseline, novel-schema, and emote-candidate frames. All retained captures were replayed through Rust's Full, Normalized, Schema, and Raw modes. Event order, payloads, schema views, message IDs, history flags, cursors, ACK requirements, normalization status, and timestamps were checked. Independent Node comparison verified **1,356 retained event observations**, with **866 public JSON Schema checks** and **zero discrepancies**. Other frames were decoded and inspected by the Rust audit in memory; they were not all retained or independently replayed.

Rust's precision-safe JSON represents numeric scalars as strings. The cross-language comparison projects the two declared numeric emote fields (`index` and `emoteType`) consistently while preserving value and optional-field presence. It compares shared normalized fields and rankings; it does not compare Rust-only gift artwork or Node-only gift-recipient fields.

Real emotes arrived in `WebcastChatMessage.emotes`. A sticker-only chat with whitespace content and index zero is represented by the committed, reduced [capture-derived fixture](fixtures/events/captured-inline-emote.provenance.json). User identities, text, asset IDs, and URLs are replaced; actual emote count, index, type, and content encoding lengths are preserved. Rust, Node, and Java verify its metadata against the Node oracle. No real `WebcastEmoteChatMessage` was observed, so that specific wire method retains synthetic coverage.

Earlier checks additionally verified discovery, room metadata, gift lists, signing, three overlapping real sockets, and the mobile Rust frontend. The mobile frontend received 271 events, including eight gifts, over 30 seconds without errors or saturation. These are separate observations and may overlap; they are not globally unique event counts. Host JNI tests are not a substitute for device/UI testing.

## Schema and player findings

Observed methods without descriptors include `WebcastHotRoomMessage`, `WebcastUpdateShareRevenueNoticeMessage`, `WebcastAISummaryMessage`, and `WebcastGameServerFeatureMessage`. Their method identities and raw bytes remain available. Numeric paths and unknown enums are exposed without inventing semantic names.

Gift paths `37.4.5`, `37.30.2.5`, `100.1.4.5`, and `100.1.30.2.5` reach an undescribed length-delimited field 5 in `webcast.model.gift.assets.ResourceModel`. Path `37.4` resolves to `asset.resource_url`. One observed field-5 value was seven bytes of valid UTF-8, neither a URL nor a valid nested protobuf message. This does not establish its meaning. Unknown `TagType` values included `71` and `260209099`–`260209103`; chats and gifts still normalized successfully.

The protobuf pin remains `cf7bcd49d59926b44c1c4e2632df5558bf3e8169`. The reviewed [upstream comparison](https://github.com/isaackogan/TikTok-Webcast-Protobuf/compare/cf7bcd49d59926b44c1c4e2632df5558bf3e8169...8e7ccad97b1b9b01058af5f96a6c6ea3230c8c1a) contained 13 additional commits, without definitions for the first two methods above or the observed ResourceModel field. The pin was not updated on the assumption that it would resolve those findings.

The repaired player audit identified a real signing-interface change: `registerWsSigner` with `X-MS-Q`/`X-MS-STUB` and `X-Gnarly` changed to `frontierSign` with `X-MS-PAYLOAD` and `X-Bogus`. The query, host/path, framing, and version constants remained unchanged. The current fixture was updated after source review and live compatibility checks; unknown future signing profiles still fail explicitly.

## Concurrency and stability

The initial debug harness compressed frames and decoded reference events inside the measured loop. Its high round-trip latencies included harness work. The revised harness prepares references and compressed frames before timing, retains exact event/order assertions, and separates ACK write, decoder, callback/verification, and server round-trip measurements.

Release results using captured event bytes:

| Connections | Gzip | Delivery | Batches | Events | Measured time | ACK write p99 | Server round-trip p99 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 32 | No | Inline | 3,200 | 14,176 | 0.304 s | 30 us | 40.6 ms |
| 256 | No | Inline | 25,600 | 113,408 | 3.075 s | 32 us | 45.7 ms |
| 256 | Yes | Inline | 25,600 | 113,408 | 3.368 s | 35 us | 51.1 ms |
| 1,024 | Yes | Inline | 102,400 | 453,632 | 12.226 s | 41 us | 212.9 ms |
| 16 | Yes | Buffered | 1,600 | 7,088 | 0.211 s | 37 us | 40.7 ms |
| 128 | Yes | Buffered | 12,800 | 56,704 | 1.490 s | 49 us | 40.8 ms |

All rows passed delivery, ordering, ACK-correlation, and shutdown assertions. With 1,024 inline connections, sampled process peaks were 97,336 KiB RSS, 2,055 file descriptors, and six threads. With 128 buffered connections there were 134 threads, reflecting one dispatcher per connection. Peaks were sampled every 50 ms and may miss brief transients.

Both loopback endpoints share four Tokio network workers, so server round-trip time includes server scheduling contention. ACK-write timing measures the client write. These are single runs on this workstation with background activity; they do not establish remote-service limits or a universal production capacity. TikTok itself was sampled with 20 concurrent sockets, not 1,024.

The native lifecycle soak completed **1,801.82 seconds**, **1,790 cycles**, and **5,309,755 replayed events**. Each cycle created, received, disconnected, and destroyed a client. It asserted `received = dispatched + discarded` after shutdown and no callbacks after destruction. Between-cycle thread count returned to two. RSS started at 5,304 KiB, peaked at 8,832 KiB, and ended at 7,800 KiB. Target rates were 1,000 / 5,000 / 20,000 events/s; observed phase rates were approximately 783 / 2,860 / 5,200 events/s. This soak uses offline fixtures, not live TikTok traffic.

Controlled failure tests also verify pending-signer/handshake/read cancellation, blocked-consumer ACK progress, explicit queue saturation, callback panic, reentrant shutdown, refused handshakes, malformed-event isolation, and decoder budget boundaries. The workload did not reach an absolute functional connection ceiling; configured resource/queue limits and slow-consumer failure behavior are explicit.

## Verification and reproduction

Executed checks include the Rust workspace with the real signing bundle and mobile LIVE/JNI features; Node tests with real transport integration and no skipped signing tests; broker SDK tests; fresh Java tests, real-bundle Graal signing and host JNI lifecycle tests; Clippy with warnings denied; formatting and whitespace checks. Instrumented fuzzing seeded from copies of real batches completed 9,400 executions in 61 seconds without a crash or sanitizer finding. Fuzz mutations and dictionaries remain private.

```sh
TTL_BUNDLE=/path/webmssdk.js cargo test --workspace --features ttl-sign-mobile/live,ttl-sign-mobile/jni
cargo clippy --workspace --all-targets --features ttl-sign-mobile/live,ttl-sign-mobile/jni -- -D warnings
cargo fmt --all -- --check
node --test scripts/headless/lib/player-assets.test.mjs
node scripts/headless/player-audit.mjs

# Requires the configured local session; creates a new private output directory.
TTL_BUNDLE=/path/webmssdk.js TTL_AUDIT_ROOMS=100 TTL_AUDIT_CONCURRENCY=20 \
  TTL_LISTEN_SECONDS=90 TTL_AUDIT_OUTPUT=.local/new-room-audit \
  cargo run -p ttl-live-discovery --example audit-rooms
# TTL_AUDIT_SIGNING_PRODUCT=frontier selects the verified alternate profile.

cargo run -p ttl-live-events --example audit-replay -- PRIVATE_ROOM_DIRECTORY PRIVATE_EVENTS.jsonl
node packages/tiktok-live/scripts/audit-replay.mjs PRIVATE_ROOM_DIRECTORY PRIVATE_EVENTS.jsonl
STRESS_CAPTURE_DIR=PRIVATE_ROOM_DIRECTORY STRESS_CONNECTIONS=256 STRESS_BATCHES=100 \
  STRESS_GZIP=1 cargo test --release -p ttl-live-core --test stress -- --ignored --nocapture
# STRESS_BUFFERED=1 selects the ordered dispatcher.
TTL_SOAK_SECONDS=1800 cargo test -p ttl-live-native offline_callback_lifecycle_soak -- --ignored --nocapture
```

The sampler deduplicates room IDs, retains the search context, and stops discovery on upstream errors. Capture byte and file quotas are bounded, with separate capacity reserved for emote evidence. Reports distinguish requested room counts from completed observations and expose missing evidence. Original payloads are not committed.

This evidence establishes the repaired behavior for the observed real inputs and controlled failure cases. It does not assign meanings to undocumented fields, prove future TikTok compatibility, establish the general emote-index unit, or replace Android hardware/UI and cross-region testing. Those uncertainties remain explicit rather than being converted into successful assertions.
