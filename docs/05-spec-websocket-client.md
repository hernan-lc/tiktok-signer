# 05 — WebSocket client specification

The primary client consumes relayed events from TikTok's page-owned WebSocket. The
direct-Rust `SignedFetch` path described below remains available for diagnostics.

Before connecting directly, require a non-empty `push_server`, `cursor`, and `route_params`.
Discard empty route parameters, append client parameters afterward, and preserve duplicate
`version_code` entries.

The handshake must include the signing User-Agent and the complete session cookie jar.
A status-200 handshake is a typed detection rejection and must not be retried. A
`101 Switching Protocols` response only opens the transport; it does not prove that
the room was entered.

Immediately after the handshake, send `im_enter_room` with the room ID. Then send
application heartbeats containing the room ID and an increasing sequence number.
Decode transport frames, acknowledge batches with `need_ack = true` using the
current batch's `internal_ext` and incoming frame log ID, and expose
binary `msg` payloads to the caller.

Signed URIs expire after roughly 30 seconds. This crate deliberately does not reconnect.


The player-code reference in [player-ack-protocol-v1.json](../fixtures/research/player-ack-protocol-v1.json)
records source provenance and synthetic ACK cases. An empty extension produces an
empty ACK payload. Legacy public ACK helpers keep their historical `-` fallback;
transport paths use additive per-batch helpers. ACK encoding precedes application
normalization/delivery, and metadata is scanned once without building event schemas.
