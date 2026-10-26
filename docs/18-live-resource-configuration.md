# LIVE resource configuration and compatibility

## Rust decoding

`ttl_live_events::decode_batch` keeps its existing full envelope and serialization behavior.
`decode_batch_with_mode(payload, DecodeMode)` returns selective owned representations:

| Mode | Raw bytes | Normalized event | Schema fields |
| --- | --- | --- | --- |
| `Full` | Yes | Yes | Yes |
| `Normalized` | Unknown events only | Yes | No |
| `Schema` | No | No | Yes |
| `Raw` | Yes | No | No |

Message IDs, method names, history flags and batch transport metadata are retained.
All modes retain existing batch, event, descriptor depth and field budgets. Unknown
normalized events retain their payload exactly once. Selective results use a new
public type; existing envelope JSON is unchanged. Raw mode still validates resource
budgets and protobuf framing before delivery.

## Signing

`EmbeddedSigner::with_config` accepts `SignerConfig`: the default is 64 waiting
requests and a 30-second deadline, plus the one active worker. Submission never
blocks a Tokio executor thread: a full queue returns an explicit signer error.
Cancelled and expired requests are skipped before execution. Queue depth,
rejections and worker failures are available through `metrics()`.

The mobile signer uses the same default capacity and deadline. `with_capacity`
accepts 1–65536 queued requests. Each worker owns its own JS context. Contexts are
warmed once and released on shutdown. An executing JS call cannot be preempted;
a timeout stops waiting but cleanup may wait for that call to finish. Use async
`shutdown` where available to move thread joining off an executor thread.

Node reuses immutable compiled bootstrap code in a fresh context for each signer
identity. Bundle download single-flight entries are capped at 16, evicted on
completion, and never cache mutable contexts. Downloaded source is capped at
8 MiB and checked against the existing expected digest before cache replacement.
`loadBundle` accepts `timeoutMs` (30 seconds by default); expired downloads release
their single-flight entry. Guest bootstrap and retry waits are cancelled on disconnect.
Sandbox pending fake timers are capped at 1024; diagnostic access keys at 4096.

## Native callback delivery

The C API defaults to one ordered dispatcher with capacity 32. Call
`ttl_live_client_set_async_delivery(client, capacity)` while disconnected to select
1–256 queued batch/state/error deliveries; capacity 0 explicitly selects legacy
synchronous callbacks, which must return promptly. Asynchronous delivery also
limits queued and active deliveries to 8 MiB of estimated retained memory,
including cursor/internal-extension metadata. Each batch remains subject to
the decoder's byte/message limits. Slow callbacks may fill the queue. Overflow
cancels reception, releases queued deliveries, and reports a terminal error after
the active callback returns. Financial events are never silently discarded while
the connection continues.

`ttl_live_client_metrics_prometheus` returns owned label-free Prometheus text;
release it with `ttl_string_free`. It exposes event/queue counters, active
connections, reconnects and ACK/decode/callback duration histograms. Core sinks
can opt into decode/ACK timing hooks without changing existing implementations.
`ttl_live_core::run` invokes inline sinks; `run_buffered` owns one dispatcher
thread with 32 queued notifications and an 8 MiB estimated memory budget. Mobile
LIVE clients use `run_buffered` by default. Queue saturation stops reception and
reports `Backpressure`; callbacks must eventually return for shutdown to join.

`ttl_live_client_delivery_metrics` reports queue depth, received/dispatched/
discarded event counts and rejections. Disconnect disables callbacks and joins
workers; when called from a callback, it avoids self-join. Destroy from a callback
is refused: destroy after callback return. Applications must eventually return
from callbacks for external disconnect/destroy to finish. Existing symbols and
opaque pointer ownership are preserved; event accessors return pointers valid
only for their event lifetime, and clones own independent storage.

## Node event delivery

Both clients accept additive `eventDelivery` options: capacity (zero by default,
meaning synchronous delivery), maximum queued byte charge, drain batch size and
time budget. Asynchronous delivery retains event ordering with a single owned
immediate timer. Defaults for enabled delivery are 64 MiB byte charge, 64 events
per drain and 8 ms per turn. Byte charges are conservative estimates rather than
heap accounting. Count capacity is capped at 65536.

ACK processing precedes delivery. Async draining yields between bounded groups;
a single CPU-blocking listener cannot be preempted on the main JavaScript thread. Overflow clears pending events, disconnects the
transport and reports an error. Disconnect clears pending delivery and reconnect
work. Listener exceptions are reported without corrupting transport state; typed
listeners can still receive an event if a generic listener throws. Delivery
metrics include received, dispatched, discarded, rejections and retained charge;
listener errors are reported separately by the client.

Frames and decoded batches are capped at 16 MiB and 8192 messages. Node gunzip
limits output before allocation. Discovery response bodies are capped at 16 MiB.

## Relay operations

The broker reserves relay capacity through setup, upgrade and the entire socket
lifetime. `MAX_ACTIVE_RELAYS` defaults to 1024; `MAX_RELAYS_PER_CUSTOMER` to 32.
Reservations are released on errors, cancellation and closure. Idle timeout is
`RELAY_IDLE_TIMEOUT_SECONDS` is 60 seconds per direction;
`RELAY_WRITE_TIMEOUT_SECONDS` is 15 seconds. Both WebSocket
message/frame limits are 16 MiB; the upstream write buffer is bounded at 32 MiB.
Use the configuration's environment variables to tune these operational limits.

`active_live_connections` includes reserved setup and established sockets.
`live_connection_rejections_total` counts capacity refusals. These metrics carry
no customer, creator, cookie or signed-URL labels. The broker also exposes live signer queue depth, rejection and worker-failure
counters through a weak observer that does not retain the worker. Existing
request/signing/cache metrics retain their names and semantics.

## Android and Java

Android `LiveClient.connect` retains its signature. `connectWithDelivery` accepts
`DeliveryOptions`: 1024 notices, estimated 8 MiB UTF-16 retention, 64 notices per
drain and 16 ms interval by default. One owned main-thread Runnable delivers
ordered notifications. Saturation or a listener failure terminates the session
with an explicit error. Completed manual disconnect removes pending main work
and waits for native cleanup. Business consumer saturation also stops the session.

History remains bounded at 300 newest-first entries. App history/statistics flow
snapshots publish at 50 ms intervals; counters update for every received event.
The repository's legacy constructor retains immediate publication when no scope
is supplied.

Java retains its existing 1024-task callback queue. Saturation now stops the
connection and reports a terminal error after the active callback returns;
`droppedCallbacks()` includes explicitly discarded pending tasks. Cancelled
owned timers are removed immediately, and presence monitoring is bounded by
`Builder.maxWatches` (1024 by default, configurable 1–65536). User-supplied
callback executors remain user-owned and need their own resource bounds.

Android guest cookies are bounded at 256 names / 65,536 key/value characters;
overflow leaves the prior identity intact. Logging timestamps and listener
registration/removal are synchronized.

Direct Node retry jitter is opt-in through `reconnectJitter` (0..1, default 0).
Rust reconnect wrappers expose `with_jitter` with a caller-owned sample source.
Both retain existing default retry timing and bound jitter to the delay ceiling.

## Protocol and verification limits

Rust, Node and Java transport paths follow the verified public player branch:
ACK only when the current batch requests it, echo that batch's extension, and
preserve the incoming frame log ID. An empty extension leaves payload empty.
Existing public ACK helper bytes remain compatible through separate per-batch
helpers. The [provenance fixture](../fixtures/research/player-ack-protocol-v1.json)
records source URL/hash and synthetic golden cases. This validates player behavior;
it is not a captured server acceptance trace. No real LIVE traffic is required in CI.

See [the performance audit](17-performance-audit.md) for executed tests,
measurements and explicitly incomplete platform checks. Timing samples are not
cross-platform throughput guarantees.
