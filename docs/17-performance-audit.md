# LIVE performance and reliability audit

Baseline revision: `886157c` (latest origin/main fetched 2026-10-13).
Branch: `perf/live-pipeline`. No public API or ABI changes are authorized without
compatibility justification. All benchmarks use committed offline fixtures.

## Progress

- [ ] Phase 0: complete baseline, lifecycle audit and issue inventory
- [ ] Phase 1: decoder modes and allocation reduction
- [ ] Phase 2: ACK and transport lifecycle
- [ ] Phase 3: bounded embedded signing and cancellation
- [ ] Phase 4: native callback delivery and ownership
- [ ] Phase 5: Node bounds and lifecycle
- [ ] Phase 6: broker/relay resource limits and metrics
- [ ] Phase 7: Android/JNI handoff and history batching
- [ ] Phase 8: shared overload and lifecycle documentation
- [ ] Phase 9: stress, soak and benchmark comparisons
- [ ] Phase 10: quality checks and documentation

Unchecked items are incomplete, including tests not run on supported platforms.

## Verified inventory at baseline

| Component | Finding | Status |
| --- | --- | --- |
| Rust decoder | 16 MiB batch preflight and 8192 message cap before Prost allocations | Already implemented; retain |
| Rust schema decoder | Shared batch field budget, per-event size/depth budgets | Already implemented; retain |
| Rust transport | Gzip reads at most 16 MiB + 1 byte | Already implemented; retain |
| Rust transport | Uncompressed payload cloned; ACK uses initial connection extension | Confirmed code path; protocol regression test required |
| Embedded signer | Unbounded mpsc request channel; cancelled queued work still signs | Confirmed code path |
| Embedded signer | Context/bootstrap initialized once per worker | Already implemented; retain |
| Node frames | Unbounded gunzipSync output and no batch preflight | Confirmed code path |
| Node live-sdk | Reconnect timer stored and cleared on disconnect | Already implemented; validate remaining races |
| API room cache | Capacity bound and TTL | Already implemented; retain |
| API rate limiter | Bounded customer and creator tracking | Already implemented; retain |
| API relay | Setup permit released before socket lifetime ends | Confirmed code path |
| Native callbacks | Active invocation count, condition variable and worker reentrancy check | Already implemented; regression validation needed |
| Android bridge | One main-thread Runnable per event | Confirmed code path |
| Android history | Bounded 300 records, copied on each event | Bound already implemented; batching pending |

## Reproduction and measurement

`cargo run --release -p ttl-live-events --example decode_bench` warms the decoder
and measures 10,000 complete batch decode/drop cycles. It reports events/second
and batch p50/p95/p99, including destruction. Run several times on an idle host;
these are batch latencies, not per-event or network ACK latencies.

Use `/usr/bin/time -v` for process peak RSS and CPU. Process peak RSS includes
runtime/allocator retention and is not evidence of a live-object leak. Allocation
counts, signer throughput, queue metrics, callback timing and cleanup soak
measurements remain pending. Do not infer improvements from unmeasured changes.

Initial baseline validation: `cargo test --workspace` passed; `cargo fmt --all
-- --check` passed. Node `tiktok-live`: 51 passed, 5 signing tests skipped because
that suite looks for `/tmp/webmssdk.js`. Decoder first run: 35,116 events/s;
batch p50/p95/p99 331,631 / 564,309 / 830,383 ns. This is one shared-host sample,
not a stable comparative result. `/usr/bin/time` is unavailable on this host,
so RSS/CPU measurements have not been collected with that tool.

## Selective decoder

`decode_batch_with_mode` is additive and returns `SelectedBatch`; legacy
`decode_batch` and event JSON remain unchanged. `Normalized` builds typed events
and stores unknown payloads once. `Schema` builds only descriptor fields. `Raw`
keeps only envelope metadata and bounded bytes. `Full` keeps all representations.
Metadata and ordering remain available in every mode. Schema size/depth/field
budgets apply whenever schema is requested; raw/normalized modes do not build a
schema tree. Batch byte/message caps and per-event 4 MiB truncation apply to all.
Full mode necessarily duplicates unknown payloads to preserve independent owned
raw and legacy typed representations. No change to those public Vec layouts.

Validated: decoder tests (including four mode regression tests), golden fixture
parity and package all-target Clippy passed. First normalized sample: 195,600
events/s, batch p50/p95/p99 57,589 / 90,151 / 100,037 ns. This mode does less work;
it is not a claim of that speedup for legacy full consumers. Allocation counts
and sustained RSS still require measurement.

## Rust transport changes

ACK extension now comes from each decompressed batch using borrowed wire metadata,
not the initial fetch response. An absent extension clears old state and retains
the existing `-` ACK fallback. Duplicate scalar fields use protobuf last-value
semantics. Uncompressed frame payloads transfer ownership. Existing ACK frequency
is unchanged: the captured fixture is an initial HTTP fetch, not evidence that
WebSocket need_ack=false disables ACKs. That protocol verification remains open.
The regression compares the redacted real batch with FetchResult decoding and
checks changed/empty/malformed extensions. All 10 transport tests and all-target
Clippy passed. Local-socket ACK/heartbeat/cancellation stress is still pending.

## Embedded signing

Default capacity is 64 queued requests plus one executing request; overload returns
`DiscoveryError::Signer` without blocking the submitting executor thread.
`Signer::with_config` configures capacity and request timeout (default 30 seconds).
Cancelled/expired queued requests are skipped. `metrics()` exposes queue depth,
rejections and worker panic failures without identity labels. Warm contexts remain
isolated per worker. Drop closes the queue and joins after the in-progress engine
call; use `shutdown().await` to move that join off an executor. Deadlines cannot
preempt a JS engine call already in progress. No worker pool was introduced.

Validated: embedded tests and all-target Clippy passed, including queue saturation,
receiver cancellation, timeout-before-execution, worker panic, failed initialization
and repeated create/drop. Engine fixture tests may skip without their expected
bundle; QuickJS/V8 real-bundle parity and pool benchmarks remain pending.

## Node transport bounds

Both SDKs share frame helpers. Frames/compressed payloads and decompressed batches
are limited to 16 MiB. `gunzipSync` uses `maxOutputLength`; top-level protobuf
preflight rejects >8192 messages before allocating generated message objects.
Malformed lengths/tags fail explicitly. Unknown compression passthrough remains.
Validated: `tiktok-live` 53 passed/5 signer skips; `live-sdk` 8 passed.

Offline Node benchmark (`npm run build`, then
`node packages/tiktok-live/scripts/decode-bench.mjs`): before/after samples
44,466 / 44,973 events/s; batch p50 0.272 / 0.271 ms, p95 0.414 / 0.400 ms,
p99 0.566 / 0.562 ms. CPU user/system 2,914,064/27,033 vs 2,903,677/13,045 µs;
process max RSS 104,356 vs 105,672 KiB. These single-run variations do not
establish an improvement or leak. Benchmark includes protobuf and normalization,
not listener dispatch or network ACK. Persistent worker decoding remains unevaluated.

Selective decoding follow-up: borrowed descriptor preflight now applies the same
4096 per-event / 65,536 per-batch field and depth-8 limits even when schema trees
are omitted. Five decoder mode regressions and Clippy passed. This additional
validation supersedes the earlier normalized timing: 61,214 events/s,
p50/p95/p99 208,663 / 223,581 / 316,104 ns in the latest shared-host sample.

## Native representation

Native schema trees initialize once on first `ttl_event_root` access, with stable
pointers until event release. Metadata/raw/JSON access does not materialize them.
Construction moves child fields instead of cloning whole subtrees at each level.
Clones own independent envelopes and lazily build independent roots. Opaque C
handles, exported symbols, borrowed pointer lifetimes and public ABI layouts stay
unchanged. Native unit/offline tests and all-target Clippy passed.

Offline native event construction/drop benchmark, against origin/main in an
isolated worktree: metadata-only baseline/after samples 14,728 / 15,027 events/s,
p50 65,189 / 65,459 ns, p95 89,998 / 72,378 ns, p99 109,477 / 91,490 ns.
No percentage improvement is inferred from these single-run samples. Callback
queues, asynchronous dispatch and sanitizer checks remain incomplete.

## Node lifecycle

Connection generations invalidate stale discovery and socket work. Manual and
automatic connects share single-flight; reconnect timers are owned and cleared.
Pending handshakes reject on disconnect, and stale close/error/message callbacks
cannot reset a new connection. Event listener exceptions are routed to optional
error handlers and do not stop subsequent messages; both SDKs avoid process-fatal
unhandled error events. ACK precedes application normalization and dispatch.
Validated: tiktok-live 56 passed/5 signer skips, live-sdk 12 passed; both package
builds, test compilation and example type checks passed. Bounded asynchronous
event dispatch remains incomplete.

## Relay lifetime limits

`MAX_ACTIVE_RELAYS` (default 1024) and `MAX_RELAYS_PER_CUSTOMER` (default 32)
reserve lifetime permits before setup. A permit stays captured through upgrade
and relay completion, releasing on offline/error/cancellation/close. Customer
accounting holds only customers with active permits, so its size is globally
bounded. Capacity rejection uses existing retryable RATE_LIMITED JSON (429).
`active_live_connections` counts reserved setup plus upgraded relay leases;
`live_connection_rejections_total` counts global/customer capacity denials. Neither
metric has identity labels. `RELAY_IDLE_TIMEOUT_SECONDS` defaults to 60 per leg;
`RELAY_WRITE_TIMEOUT_SECONDS` defaults to 15. Dial uses request timeout, both
WebSockets cap frames/messages at 16 MiB, and upstream write buffering is bounded.
No whole-session buffering. A silent leg or stalled write closes the relay.

Validated: API unit/integration tests and all-target Clippy. Tests include
customer/global bounds, 1000 lease create/drop cycles, error-path permit release,
and an offline local WebSocket integration proving the permit survives upgrade,
a second connection gets 429, idle cleanup closes both legs and returns the gauge
to zero. Slow-reader/writer load, shared observability and broker soak remain open.

## Android history and JNI cleanup

Application-scoped EventsRepository batches UI history/statistic snapshots every
50 ms (configurable). It counts every business event and retains the existing
300-record newest-first history. Reward/action processing remains per event in
the ordered application collector. Unscoped construction retains immediate
publication for source compatibility. A session reset cancels pending snapshot
publication; the application scope owns the single pending job.

Three new JVM tests cover 1000 events, exact totals, history ordering/bounds,
pause/reset behavior and immediate legacy publication. Android unit tests,
assembleDebug and assembleRelease passed. Existing Kotlin deprecation and ONNX
unchecked-cast warnings remain outside these changes. Android main-thread native
handoff is still incomplete; this history change does not bound Handler posting.
Mobile disconnect now avoids joining its own callback thread (Drop already had
that safeguard); a no-network self-disconnect regression and mobile default/JNI
feature tests and Clippy passed. Device navigation/instrumentation remains open.

## Additional validation

Java SDK offline tests and example compilation passed with JDK 21/Gradle 8.14.3.
Workspace tests passed. Workspace Clippy passes on Rust 1.99 after replacing an
existing parity helper's constant chunks_exact with MSRV-compatible as_chunks.
The workspace webmssdk.js SHA-256 matches the pinned digest. QuickJS and V8
feature test suites passed using TTL_BUNDLE pointing at that verified local file,
including pinned parity against Node. Node real-bundle tests now execute rather
than skipping; one network integration test stays intentionally skipped offline.
Bootstrap compilation is cached once as an immutable vm.Script; each identity
still runs it in its own mutable context, verified with an offline isolation test.

## Native asynchronous dispatch

`ttl_live_client_set_async_delivery(client, capacity)` is additive; configure
while disconnected. Zero preserves legacy synchronous callbacks. 1..256 bounds
queued batch/state/error deliveries on one ordered dispatcher thread. On overflow
the network worker receives cancellation immediately; queued events are released
and one terminal error reports overflow after any in-progress callback returns.
No default silent-drop policy. Ordinary disconnect disables callbacks and clears
pending delivery; callback-initiated disconnect is a stop request requiring later
join/destroy outside callbacks. A stopped asynchronous client must disconnect
before reconnecting. Delivery metrics expose depth, received/dispatched/discarded
events and rejection counts, including explicitly cancelled pending deliveries.
New symbols/metrics structs do not alter existing ABI layouts.

Validated: native tests, all-target Clippy, reentrant disconnect in both modes,
slow callback saturation with exact delivery/discard accounting, C/C++ smoke,
C AddressSanitizer/UndefinedBehaviorSanitizer smoke, and exported symbol/header
comparison (100 declarations). Full Rust ThreadSanitizer native lifecycle tests
passed (six tests); Miri passed ten pure protobuf tests. C smoke instruments the
C caller, not the Rust implementation. Rust ASan/fuzz/extended soak remain open.

## Allocation tooling

Benchmark-only global allocator instrumentation counts allocation/reallocation
calls and cumulative requested bytes (not live heap bytes). Libraries do not link
this allocator. Linux /proc reports process RSS/high-water RSS and thread count.
Instrumented normalized fixture sample: 39.62 calls and 14,179.77 requested bytes
per event, RSS/HWM 4,636 KiB, one thread; 54,145 events/s. Native metadata sample:
831 calls / 106,463 requested bytes per event, RSS/HWM 4,540 KiB, one thread;
13,292 events/s. Instrumentation adds overhead; do not compare throughput directly
with uninstrumented samples or infer leaks from allocator-retained RSS.
