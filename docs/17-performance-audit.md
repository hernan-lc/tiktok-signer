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

## Additional lifecycle and retained-resource verification

- Mobile signing now has a configurable bounded queue (64 by default), nonblocking submission, 30-second request deadlines, cancelled/expired work skipping, and owned worker shutdown. Host JNI/live tests and Clippy pass with the local pinned bundle.
- Embedded queue configuration rejects impossible Tokio capacities and overflowing deadlines rather than panicking. Sandbox fake timers are capped at 1024; diagnostic access keys at 4096. QuickJS, V8 and Node signing tests pass after regenerating both bootstrap artifacts.
- Node bundle downloads use at most 16 concurrent distinct single-flight entries, remove entries on success/failure, and use asynchronous cache filesystem operations. Concurrent download and failed-flight regression tests pass. Identity contexts remain independent.
- Both Node clients offer ordered asynchronous delivery with explicit count/byte capacity, bounded drain work, one owned immediate timer, and terminal disconnect on overflow. Synchronous delivery remains the default. Byte charges are conservative estimates, not measured heap usage. Generic/typed listener failures and lifecycle/error listener failures are isolated. A reentrant signer cannot open a socket after cancellation.
- Discovery single-flight entries now have RAII ownership. Cancellation previously retained entries indefinitely; the failing offline reproduction is replaced by a passing cancellation/resource-release test. Entries also have an explicit configured bound.
- Android defaults: at most 1024 handoff notices / 8 MiB estimated UTF-16 retention, 64 per main-thread drain, 16 ms interval. Saturation ends the session with an explicit error; business SharedFlow saturation also ends the session. Disconnect removes pending main work and joins asynchronous native cleanup. JVM tests cover 1000-event ordering, count/byte saturation, callback failure and stalled business consumers.
- All four Android JNI ABI builds completed. Updated-native debug/release assembly, JVM tests, lint and instrumentation APK compilation passed. Device instrumentation execution remains incomplete: `adb devices` has no connected device or emulator.
- Java's existing 1024-task callback queue silently discarded rejected business events. Saturation now invalidates the connection, cancels pending transport work, clears retained tasks and delivers a terminal overload error after the active callback. Offline saturation regression and live-client tests pass.

Thirty-minute QuickJS signing and native callback lifecycle soaks are running; their results are not yet verified or acceptance claims.

## Repeated final decoder comparison

Three alternating runs pinned to the same allowed CPU, 10,000 batches/run, 13
events/batch. Both binaries use the same benchmark-only allocator instrumentation.
The baseline binary was built from an isolated worktree at `886157c`. Reported
latency columns are medians of the three runs' batch percentiles, not pooled
percentiles. Shared-host timings remain workload-specific observations.

| Path | Events/sec | Batch p50/p95/p99 ns | Allocation calls/event | Requested bytes/event |
| --- | ---: | --- | ---: | ---: |
| Baseline default | 38,572 | 326,416 / 421,283 / 503,925 | 152.92 | 36,417.54 |
| Current default | 38,314 | 329,186 / 417,012 / 520,703 | 152.92 | 36,417.54 |
| Selected full | 38,384 | 325,350 / 432,893 / 497,083 | 150.92 | 34,486.77 |
| Selected normalized | 66,167 | 185,589 / 274,733 / 309,921 | 39.62 | 14,195.77 |
| Selected schema | 48,521 | 258,224 / 331,642 / 378,688 | 116.15 | 24,610.92 |
| Selected raw | 96,304 | 125,643 / 190,819 / 208,615 | 5.38 | 4,495.77 |

Default-path throughput stayed within observed run variation. These results
support offering selective modes rather than changing the default serialization.
Requested bytes count cumulative allocation/reallocation traffic, not live heap.

Native metadata benchmark, 10,000 events, same instrumented fixture: baseline
14,827 events/sec, 831 calls/event, 106,463 requested bytes/event, p50/p95/p99
66,383/72,791/91,980 ns; current 22,193 events/sec, 284 calls/event, 51,221
requested bytes/event, 38,385/59,732/69,918 ns. Current schema-access path: 20,644
events/sec, 529 calls/event, 82,371 requested bytes/event. These are single runs.

Signer evaluation (warm independent contexts, bounded synthetic concurrency,
local verified bundle): QuickJS one context/concurrency 100 processed 200
requests at 19.13 requests/sec; two at 31.58, with warm RSS about 8.0/12.8 MiB.
V8 one context/concurrency 100 processed 1000 at 389.92 requests/sec; two at
743.94, with warm RSS 44.0/58.0 MiB. V8 process-global background threads and
allocator memory remain after isolate disposal; worker/isolate disposal does not
promise process-global V8 teardown. The library keeps one worker by default.
The benchmark supports separate contexts to evaluate deployment tradeoffs;
production pool scheduling is not added without representative workload evidence.

Additional checks passed: full Node generated-protobuf/schema checks, typechecks
and tests after `npm ci`; broker SDK typecheck/tests after `npm ci`; Rust 1.88
workspace tests; full Rust-instrumented native AddressSanitizer unit/offline tests;
30-second fuzz smoke for full event envelopes and native decoding, plus a separate
30-second event fuzz run exercising all selective modes. A Node offline lifecycle
soak tool smoke-tested 1000/5000/20000 event/sec targets with zero errors and zero
active sockets after cleanup. Its thirty-minute run is pending.

## Completed thirty-minute Rust soaks and transport measurements

QuickJS: 1800 seconds, 17,963 successful signatures. Baseline RSS 3748 KiB,
peak 10,528 KiB, late-run RSS approximately 6720 KiB, after async shutdown
7044 KiB. Queue depth sampled at zero. One signer worker existed during signing;
post-shutdown process threads include Tokio's blocking join helper. No signing
errors or retained queue growth were observed. RSS after cleanup is not proof
of live JS objects: the allocator can retain freed pages.

Native callbacks: 1802.19 seconds across independent ten-minute target rates.
Baseline RSS 5196 KiB, peak 7844 KiB, cleanup RSS 5152/5112/5168 KiB.
595/594/597 create/disconnect/destroy cycles delivered/accounted 500,378 /
1,578,494 / 4,343,391 events. Actual rates were 833 / 2627 / 7233 events/sec,
not the requested targets of 1000 / 5000 / 20000. Each cycle checked no callbacks
after disconnect/destroy and exact received/dispatched/discarded accounting.
These replay results exercise cloned/serialized native events and callback
ownership, not real network traffic. They do not claim every platform sustains
all target rates.

Loopback transport, 1000 batches, shared synthetic/captured batch fixture:
plain baseline/current ACK roundtrip p50/p95/p99 26,261/29,403/36,057 ns versus
26,370/29,924/63,070 ns; gzip baseline/current 46,644/52,685/89,440 ns versus
47,179/52,866/58,237 ns. These are single runs. Plain allocation traffic including
the loopback peer was 154,647.94 versus 129,018.81 requested bytes/batch.
Gzip current median roundtrip remains below 50 microseconds for this fixture;
this does not justify adding executor offload/worker scheduling overhead to the
small-frame path. Large payload bounds remain mandatory.

Final Rust-native ThreadSanitizer passed seven tests (the long soak is explicitly
ignored under the sanitizer run). Actual JVM/JNI repeated create/sign/close and
concurrent sign/close tests passed with the verified local bundle, then passed
with an offline synthetic bundle for CI. All four Android ABIs were rebuilt
and updated-native debug/release assembly, JVM tests, lint and instrumentation
APK compilation passed again. No Android device is available for execution.

The first Node thirty-minute soak was interrupted before completion; it is not
an acceptance result. Its restarted run is pending and will be recorded separately.
