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
  to be the textual shortcode**, so the Rust normalizer emits `null` today; a
  producer that does carry a confirmed shortcode may set it. A numeric or opaque
  id is never rewritten into `[<id>]`.
* `imageUrl` — the first usable URL from `EmoteModel.image.url_list`. Only
  absolute `http(s)` URLs pass; unsafe schemes (`javascript:`, `data:`, `file:`),
  control characters, spaces, embedded credentials, a missing host, an explicit
  port above `65535`, and values past `MAX_EMOTE_URL_LENGTH` are dropped
  **without dropping the emote**. A value must contain `://` with a real
  authority, so `http:example.com` and `https:///no-host` are rejected rather
  than repaired. IPv6 hosts with a port, explicit `http` ports and Unicode
  (IDNA) hostnames are accepted. The raw pre-parse authority is checked in
  every language before handing the string to the native parser, so no
  implementation can silently normalise a malformed value into a valid one.
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

`fixtures/events/captured-inline-emote.pb` is a reduced, sanitized **real inline
emote** observed on 2026-10-25. Rust and Node verify its content, normalized emote
metadata and original wire presence against the Node oracle. The multi-room
audit retains private original captures for full replay. This does not establish
real standalone `WebcastEmoteChatMessage` coverage or the general unit of `index`.

## Android speech

`TikTokSpeechSanitizer` removes recognised shortcode tokens from a chat comment
before it reaches *any* speech engine (system `TextToSpeech` and Supertonic
alike). The original event is never modified.

* Only tokens confirmed by the event's own metadata (`emotes[].shortcode`) are
  removed. The provisional `EmoteCatalog` is never treated as evidence, so
  `[laughcry]` spoken in a message whose event did not carry that emote is kept.
  `[1,2,3]` and `[test]` are preserved: a blanket `\[[^]]+\]` deletion is
  explicitly rejected.
* The cleanup runs **before** the empty-message check and before the sender's
  name is prepended, so a comment that consists only of confirmed emotes says
  nothing rather than just the sender's name.
* Unicode emoji removal is opt-in and never strips zero-width joiners or
  non-joiners. Flag pairs, keycaps, skin-tone modifiers and ZWJ sequences are
  removed as whole units; combining marks on ordinary letters survive.
* The setting is `removeConfirmedEmotes` (key
  `speech_emote_remove_confirmed`). Installs that stored the old
  `speech_emote_remove_known` key keep their choice through a read fallback.

`EmoteCatalog.provisional` is a list of **hypotheses**: the shortcodes have not
been confirmed against captured LIVE traffic, and TikTok does not document them.
A name dropping out of that list must not change what is spoken, because the
list is never used for deletion.

## Android rendering

`EventRow` draws the artwork carried by an event instead of only summarising it:

* a standalone emote event shows a bounded strip of emote images (the count text
  is dropped once real artwork exists);
* a chat comment keeps its original text and shows a bounded strip of the
  confirmed inline emotes underneath it.

Only emotes with a valid `imageUrl` are drawn, at most 8 per row, with a
placeholder while loading and an icon fallback for a broken URL. Coil caches by
URL, so identical emotes across rows share one download and recomposition does
not restart it. Nothing is fetched during decoding, and the text is never
rewritten or spliced by emote index.

Android mirrors the same URL rule with `java.net.URI` + `java.net.IDN` (rather
than `android.net.Uri`, which is not usable in plain JVM tests); Java uses
OkHttp's `HttpUrl` and Rust/TypeScript use `url`/`URL`. All four enforce the
same length caps, scheme, credentials, authority and port checks.
