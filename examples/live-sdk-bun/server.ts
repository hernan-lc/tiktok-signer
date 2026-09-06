// Bun relay for the direct TikTok LIVE SDK.
//
// Browser WebSockets cannot set the Cookie/User-Agent headers TikTok expects,
// so the Bun server owns the TikTokLive connection and relays lifecycle +
// normalized events to the HTML page as Server-Sent Events. The page
// (index.html) only reads ?uniqueId= from its own query string and
// console.log()s everything it receives.
//
// Run:
//   bun install --cwd examples/live-sdk-bun
//   bun run examples/live-sdk-bun/server.ts
// Open:
//   http://127.0.0.1:3000/?uniqueId=@creator
//
// The connection-ticket broker must already be running, by default at
// http://127.0.0.1:8080 (override with TIKTOK_LIVE_API_URL or ?apiUrl=).

import { TikTokLive } from "../../packages/live-sdk/src/index.ts";

const PORT = Number(process.env.PORT ?? 3000);
const DEFAULT_API_URL =
  process.env.TIKTOK_LIVE_API_URL ?? "http://127.0.0.1:8080";
const DEFAULT_API_KEY = process.env.TIKTOK_LIVE_API_KEY;

const INDEX_PATH = `${import.meta.dir}/index.html`;

function queryParam(url: URL, ...names: string[]): string | undefined {
  for (const name of names) {
    const value = url.searchParams.get(name)?.trim();
    if (value) return value;
  }
  return undefined;
}

function send(controller: ReadableStreamDefaultController, event: unknown) {
  try {
    controller.enqueue(`data: ${JSON.stringify(event)}\n\n`);
  } catch {
    // Client already went away.
  }
}

function streamFor(url: URL): Response {
  const uniqueId = queryParam(url, "uniqueId", "unique_id", "id", "handle");
  if (!uniqueId) {
    console.error("[live-sdk-bun] /api/stream missing uniqueId query param");
    return Response.json(
      { error: "missing uniqueId query param (?uniqueId=@creator)" },
      { status: 400 },
    );
  }
  const apiUrl = url.searchParams.get("apiUrl") || DEFAULT_API_URL;
  const apiKey = url.searchParams.get("apiKey") || DEFAULT_API_KEY;
  const fetchGifts = url.searchParams.get("gifts") === "1";

  console.log("[live-sdk-bun] stream requested", { uniqueId, apiUrl });

  let live: TikTokLive | null = null;
  let closed = false;

  const stream = new ReadableStream({
    start(controller) {
      const heartbeat = setInterval(() => {
        if (closed) return;
        try {
          controller.enqueue(": keep-alive\n\n");
        } catch {
          // Client already went away.
        }
      }, 15_000);

      const cleanup = () => {
        if (closed) return;
        closed = true;
        clearInterval(heartbeat);
        live?.disconnect();
        try {
          controller.close();
        } catch {
          // Client already went away.
        }
      };

      live = new TikTokLive(uniqueId, {
        apiUrl,
        ...(apiKey ? { apiKey } : {}),
        fetchGifts,
      });

      const forward = (kind: string, payload: unknown) => {
        const event = { kind, uniqueId, ...asObject(payload) };
        console.log("[live-sdk-bun]", event);
        if (!closed) send(controller, event);
      };

      live.on("connected", (state) => forward("connected", state));
      live.on("offline", () => forward("offline", {}));
      live.on("disconnected", (info) => forward("disconnected", info));
      live.on("reconnecting", (info) => forward("reconnecting", info));
      live.on("error", (error) => forward("error", toJson(error)));
      live.on("event", (event) => forward("event", event));

      send(controller, { kind: "connecting", uniqueId, apiUrl });
      void live
        .connect()
        .then((state) => {
          console.log("[live-sdk-bun] connect resolved", state);
        })
        .catch((error: unknown) => {
          console.error("[live-sdk-bun] connect failed", toJson(error));
          forward("connect_error", toJson(error));
          cleanup();
        });
    },
    cancel() {
      closed = true;
      live?.disconnect();
      console.log("[live-sdk-bun] stream client disconnected", { uniqueId });
    },
  });

  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
    },
  });
}

function asObject(value: unknown): Record<string, unknown> {
  if (typeof value === "object" && value !== null) {
    return { ...(value as Record<string, unknown>) };
  }
  return { value };
}

function toJson(error: unknown): Record<string, unknown> {
  if (error instanceof Error) {
    return { name: error.name, message: error.message };
  }
  return asObject(error);
}

Bun.serve({
  port: PORT,
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/api/stream") return streamFor(url);
    if (url.pathname === "/" || url.pathname === "/index.html") {
      return new Response(Bun.file(INDEX_PATH), {
        headers: { "content-type": "text/html; charset=utf-8" },
      });
    }
    return Response.json({ error: "not found" }, { status: 404 });
  },
});

console.log(`[live-sdk-bun] listening on http://127.0.0.1:${PORT}/?uniqueId=@creator`);
