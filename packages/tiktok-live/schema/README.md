# ttl-live contracts

This directory contains the machine-readable contracts for the package.

- `json/tiktok/` describes the small subset of upstream TikTok JSON responses consumed by the
  discovery client. Unknown upstream properties remain accepted for forward compatibility.
- `json/public/` describes normalized objects exposed by `ttl-live`.
- Protobuf/WebSocket messages remain defined by the `.proto` files under
  `crates/ttl-live-proto/proto/v3/`; their TypeScript output lives in `src/gen/webcast/`.

`schema/json` is the source of truth for JSON. Run `npm run schema:generate` after changing a
schema, and use `npm run schema:check` in CI to ensure generated files are current. This package
does not expose an HTTP service, so it intentionally has no OpenAPI document.

The dependency-free validator intentionally supports only the schema keywords implemented in
`src/json-validation.ts`; the generator rejects other validation keywords. The supported `uri`
format is asserted at runtime.
