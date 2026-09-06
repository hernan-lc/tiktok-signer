// Browser TikTok LIVE check page (console logs only), web + Rust only.
//
// This module runs in the browser, bundled on demand by Bun's dev server. The
// only backend is the Rust broker: the page fetches a ticket from
// `POST /v1/connect`, then opens the broker's WebSocket relay at
// `GET /v1/live?uniqueId=` (same host, `ws(s)://` scheme). A page socket cannot
// set the Cookie/User-Agent headers TikTok demands, so the broker dials TikTok
// with the ticket headers and pipes raw frames both ways — the page still owns
// the whole protocol (enter-room, heartbeats, ACKs, decode) and only needs the
// relay as a header-capable dialer. No Bun/Node server is involved.
//
// It deliberately imports only browser-safe modules: @bufbuild/protobuf,
// the generated protobuf schemas, and the pure event normaliser. It must NOT
// import the Node SDK (`packages/live-sdk`), `player.ts`, `frames.ts` or
// `session.ts` — those pull in `node:zlib`, `node:fs` and the `ws` package,
// which do not exist in a browser. The small frame builders below mirror
// `packages/tiktok-live/src/player.ts` (`pushFrame`, `enterRoomFrame`,
// `heartbeatFrame`) with `Uint8Array`/`TextEncoder` instead of `Buffer`, and
// gzip is decompressed with the browser's `DecompressionStream`.
//
// Run:
//   bun install --cwd examples/live-sdk-bun
//   bun ./examples/live-sdk-bun/index.html --console
// Open:
//   http://localhost:3000/?uniqueId=@creator
//
// The `--console` flag mirrors every browser console.log/error into the
// terminal. The Rust broker must already be running:
//   cargo run -p ttl-live-api -- --bundle ./webmssdk.js

import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { decodeEvent } from "../../packages/tiktok-live/src/events.js";
import { ProtoMessageFetchResultSchema } from "../../packages/tiktok-live/src/gen/webcast/shared/message_pb.js";
import {
  HeartBeatMessageSchema,
  WebcastImEnterRoomMessageSchema,
  WebcastPushFrameSchema,
} from "../../packages/tiktok-live/src/gen/webcast/synthetic_proto_pb.js";

const DEFAULT_API_URL = "http://127.0.0.1:8080";

// Same handle rule as the SDK (`packages/live-sdk/src/unique-id.ts`).
function normalizeUniqueId(input: string): string {
  const value = input.trim();
  if (value.startsWith("@@")) throw new RangeError("uniqueId must be a TikTok username");
  const uniqueId = value.startsWith("@") ? value.slice(1) : value;
  if (!/^[A-Za-z0-9._]{1,24}$/.test(uniqueId)) {
    throw new RangeError("uniqueId must be a TikTok username");
  }
  return uniqueId;
}

function param(params: URLSearchParams, ...names: string[]): string | undefined {
  for (const name of names) {
    const value = params.get(name)?.trim();
    if (value) return value;
  }
  return undefined;
}

// --- browser ports of player.ts frame builders (Uint8Array, no Buffer) ---------------

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function pushFrame(
  payloadType: string,
  payload: string | ArrayBuffer | ArrayBufferView,
  logId = 0n,
): Uint8Array {
  const bytes =
    typeof payload === "string"
      ? utf8(payload)
      : payload instanceof Uint8Array
        ? payload
        : new Uint8Array(
            payload instanceof ArrayBuffer
              ? payload
              : payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength),
          );
  return toBinary(
    WebcastPushFrameSchema,
    create(WebcastPushFrameSchema, {
      logId,
      payloadEncoding: "pb",
      payloadType,
      payload: bytes,
    }),
  );
}

function enterRoomFrame(roomId: string): Uint8Array {
  // Mirrors player.ts: identity "audience", liveId "12".
  return pushFrame(
    "im_enter_room",
    toBinary(
      WebcastImEnterRoomMessageSchema,
      create(WebcastImEnterRoomMessageSchema, {
        roomId: BigInt(roomId),
        liveId: 12n,
        identity: "audience",
        cursor: "",
        accountType: 0n,
        filterWelcomeMsg: "0",
      }),
    ),
  );
}

function heartbeatFrame(roomId: string): Uint8Array {
  return pushFrame(
    "hb",
    toBinary(HeartBeatMessageSchema, create(HeartBeatMessageSchema, { roomId: BigInt(roomId) })),
  );
}

async function gunzip(payload: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream === "undefined") {
    throw new Error("this browser has no DecompressionStream for gzip payloads");
  }
  const stream = new Blob([payload as BlobPart]).stream().pipeThrough(new DecompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

// --- check flow ------------------------------------------------------------------------

interface ConnectionDescriptor {
  url: string;
  cookies: string;
  userAgent: string;
}

async function fetchDescriptor(apiUrl: string, apiKey: string | undefined, uniqueId: string) {
  const headers: Record<string, string> = {
    accept: "application/json",
    "content-type": "application/json",
  };
  if (apiKey) headers.authorization = `Bearer ${apiKey}`;
  const response = await fetch(new URL("/v1/connect", apiUrl).toString(), {
    method: "POST",
    headers,
    body: JSON.stringify({ uniqueId }),
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`broker HTTP ${response.status}: ${JSON.stringify(body)}`);
  }
  return body as {
    version: number;
    uniqueId: string;
    roomId?: string;
    status: "live" | "offline";
    connection?: ConnectionDescriptor;
  };
}

async function main() {
  const params = new URLSearchParams(location.search);
  console.log("[live-sdk] page", location.href);

  let uniqueId: string;
  try {
    const raw = param(params, "uniqueId", "unique_id", "id", "handle") ?? "";
    uniqueId = normalizeUniqueId(raw);
  } catch (error) {
    console.error("[live-sdk] missing/invalid uniqueId query param. Use /?uniqueId=@creator");
    console.error("[live-sdk]", error instanceof Error ? error.message : String(error));
    return;
  }
  const apiUrl = param(params, "apiUrl") ?? DEFAULT_API_URL;
  const apiKey = param(params, "apiKey");
  console.log("[live-sdk] connecting", { uniqueId, apiUrl });

  let ticket;
  try {
    ticket = await fetchDescriptor(apiUrl, apiKey, uniqueId);
  } catch (error) {
    console.error("[live-sdk] broker request failed", error instanceof Error ? error.message : error);
    return;
  }
  console.log("[live-sdk] ticket", { uniqueId: ticket.uniqueId, roomId: ticket.roomId, status: ticket.status });

  if (ticket.status === "offline" || !ticket.roomId) {
    console.log("[live-sdk] creator is offline", { uniqueId });
    return;
  }
  const roomId = ticket.roomId;

  // The relay lives on the broker itself: same host as apiUrl, ws(s) scheme.
  // The broker resolves a fresh ticket per socket and dials TikTok with the
  // session headers a page is not allowed to set.
  const relay = new URL("/v1/live", apiUrl);
  relay.protocol = relay.protocol === "https:" ? "wss:" : "ws:";
  relay.searchParams.set("uniqueId", uniqueId);
  if (apiKey) relay.searchParams.set("apiKey", apiKey);

  const socket = new WebSocket(relay.toString());
  socket.binaryType = "arraybuffer";
  let heartbeat: number | undefined;

  socket.onopen = () => {
    console.log("[live-sdk] relay open, sending enter-room", { roomId });
    socket.send(enterRoomFrame(roomId));
    heartbeat = window.setInterval(() => {
      try {
        socket.send(heartbeatFrame(roomId));
      } catch (error) {
        console.error("[live-sdk] heartbeat send failed", error);
      }
    }, 10_000);
  };

  socket.onmessage = async (message: MessageEvent) => {
    const raw = message.data as ArrayBuffer;
    const frame = fromBinary(WebcastPushFrameSchema, new Uint8Array(raw));
    if (frame.payloadType !== "msg") {
      // payloadType: "hb"
      //console.log("[live-sdk] transport frame", { payloadType: frame.payloadType });
      return;
    }
    const compressType = frame.headers.find((entry) => entry.key === "compress_type")?.value ?? "";
    let payload = frame.payload;
    try {
      if (compressType === "gzip") payload = await gunzip(frame.payload);
    } catch (error) {
      console.error("[live-sdk] decompress failed", error instanceof Error ? error.message : error);
      return;
    }
    const batch = fromBinary(ProtoMessageFetchResultSchema, payload);
    if (batch.heartbeatDuration > 0n && heartbeat !== undefined) {
      const everyMs = Number(batch.heartbeatDuration);
      if (Number.isFinite(everyMs) && everyMs > 0) {
        window.clearInterval(heartbeat);
        heartbeat = window.setInterval(() => {
          try {
            socket.send(heartbeatFrame(roomId));
          } catch (error) {
            console.error("[live-sdk] heartbeat send failed", error);
          }
        }, everyMs);
        console.log("[live-sdk] heartbeat interval updated", { everyMs });
      }
    }
    if (batch.needAck) {
      try {
        socket.send(pushFrame("ack", batch.internalExt || "-", frame.logId));
      } catch (error) {
        console.error("[live-sdk] ack send failed", error);
      }
    }
    for (const item of batch.messages) {
      console.log("[live-sdk]", {
        ...decodeEvent(item.method, item.payload),
        msgId: item.msgId.toString(),
        isHistory: item.isHistory,
      });
    }
  };

  socket.onerror = () => {
    console.error("[live-sdk] websocket error");
  };

  socket.onclose = (event: CloseEvent) => {
    if (heartbeat !== undefined) window.clearInterval(heartbeat);
    console.log("[live-sdk] websocket closed", { code: event.code, reason: event.reason });
  };
}

void main();
