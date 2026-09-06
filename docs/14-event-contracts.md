# Public event contracts

`ttl-live-events` is the canonical source for normalized TikTok LIVE event
values. The public contract includes `EventUser`, `ChatEvent`, `GiftEvent`,
`LikeEvent`, `MemberEvent`, `SocialEvent`, and `RoomUserEvent`.

The contract pipeline is:

```text
Rust normalized event
        │
        ▼
JSON Schema in packages/tiktok-live/schema/json/public
        │
        ▼
generated TypeScript in packages/tiktok-live/src/gen/json
```

The JSON schemas and generated TypeScript must be regenerated together. Public
fields use the JSON camelCase form; numeric identifiers that cross the JSON
boundary remain strings where the generated schema says so. Consumers must not
recreate event interfaces manually.

The runtime validator accepts boolean JSON Schemas (`true` always validates and
`false` always rejects), local `$ref` references, nested objects, arrays, and
the supported composition keywords. Contract tests cover schema generation,
generated bindings, and runtime validation.

When changing a public event, update the Rust struct, regenerate the schemas
and TypeScript bindings, run the package tests, and review downstream
consumers before changing the pinned signer revision.
