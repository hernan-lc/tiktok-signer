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
