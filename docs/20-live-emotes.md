# TikTok LIVE emotes

Emotes are graphical tokens embedded in a comment or sent on their own. This
document is the cross-language contract: Rust (`ttl-live-events`) is the
canonical implementation, and the TypeScript, Java and Android layers mirror it.

## What is decoded

| Source | Field | Normalised as |
| --- | --- | --- |
| `WebcastChatMessage` | `emotes` (`EmoteWithIndex`) | `ChatEvent.emotes` |
| `WebcastEmoteChatMessage` | `emote_list` (`EmoteModel`) | `EmoteChatEvent` (`LiveEvent::EmoteChat`) |

The generated Prost/TypeScript/Java bindings are reused directly; no protobuf
message is redefined anywhere.

## The normalised emote

```json
{
  "id": "7098",
  "shortcode": "[laughcry]",
  "imageUrl": "https://cdn.example/laughcry.webp",
  "index": 6,
  "emoteType": 1,
  "packageId": "7100000000000000001"
}
```

This is the shape, not real traffic. Every field except `id` is optional and is
**omitted** when the wire did not carry it — proto3 cannot tell an absent scalar
from an explicit zero, and a guessed default is worse than a missing key.

* `id` — `EmoteModel.emote_id`, verbatim.
* `shortcode` — only when reliably known. The v3 schema has **no field verified
  to be the textual shortcode**, so this is `null` today. A numeric or opaque id
  is never rewritten into `[<id>]`.
* `imageUrl` — the first usable URL from `EmoteModel.image.url_list`. Only
  absolute `http(s)` URLs pass; unsafe schemes (`javascript:`, `data:`, `file:`),
  control characters, spaces, a missing host, and values past
  `MAX_EMOTE_URL_LENGTH` are dropped **without dropping the emote**.
* `index` — the raw `EmoteWithIndex.index`. Its unit is **not confirmed**
  (UTF-8 bytes, code points and UTF-16 units are all plausible), so it is
  preserved unmodified, text is never sliced with it, and the original comment is
  never rewritten. Standalone emotes carry no index.
* `emoteType` — the raw `EmoteModel.emote_type` value, omitted when `0`
  (`EMOTE_TYPE_NORMAL` is indistinguishable from absent).
* `packageId` — `EmoteModel.package_id` when non-empty (creator-specific and
  custom emote packages).

## Bounds and isolation

| Constant | Value |
| --- | --- |
| `MAX_EMOTES_PER_EVENT` | 64 |
| `MAX_EMOTE_ID_LENGTH` | 128 bytes |
| `MAX_EMOTE_URL_LENGTH` | 2048 bytes |

An emote with an over-long id is dropped; an over-long or invalid URL is dropped
while the emote stays; an emote with neither an id nor artwork is dropped. None
of these paths fail the message — a malformed emote must never take a LIVE
connection down, and the original `comment` is always preserved unchanged. No
image is ever fetched during decoding.

## TypeScript and Java

* TypeScript (`packages/tiktok-live`) adds `LiveEmote`, `ChatEvent.emotes`, the
  `emoteChat` event and `METHOD.emoteChat`, plus the JSON Schema source of truth
  (`schema/json/public/live-emote.schema.json`,
  `emote-chat-event.schema.json`). `chat-event.schema.json` gained an optional
  `emotes` array.
* Java (`sdk/java/live-core`) adds `LiveEmote`, `EmoteChatEvent`, and the
  `ChatEvent.emotes` component, with a compatibility constructor for the
  pre-emote five-argument form. The decoder lives in
  `live-client/.../internal/EventDecoder.java`.

## Fixtures

`fixtures/events/{chat-inline-emote,chat-multiple-emotes,chat-unicode-emote,chat-invalid-emote,emote-chat}.pb`
are **synthetic**. Their `expected/*.json` are produced by the Node oracle, so
Rust, TypeScript and Java are compared against another implementation rather
than against themselves.

## Android speech

`TikTokSpeechSanitizer` removes recognised shortcode tokens from a chat comment
before it reaches *any* speech engine (system `TextToSpeech` and Supertonic
alike). The original event is never modified.

* Only tokens present in the `EmoteCatalog` catalog — or confirmed by the
  event's own metadata — are removed. `[1,2,3]` and `[test]` are preserved: a
  blanket `\[[^]]+\]` deletion is explicitly rejected.
* The cleanup runs **before** the empty-message check and before the sender's
  name is prepended, so `[laughcry][laughcry]` says nothing rather than just the
  sender's name.
* Unicode emoji removal is opt-in and never strips zero-width joiners or
  non-joiners.

The catalog is **provisional**: the shortcodes in `EmoteCatalog.known` have not
been confirmed against captured LIVE traffic, and TikTok does not document them.
They are candidates, not facts, and a live capture is what would confirm them.
