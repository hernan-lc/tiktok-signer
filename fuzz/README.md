# Fuzz targets

Run with `cargo fuzz run uri_sanitizer`, `cargo fuzz run protobuf_transport`, or
`cargo fuzz run fixture_loader`. Targets accept arbitrary bytes and focus on panic resistance
for transport URI escaping, protobuf envelope decoding, and fixture deserialization.

`event_envelope` also checks parity between the legacy and selective decoders:
ordered events, message IDs, timestamps and normalization status. It exercises
the bounded schema-drift audit on decoded batches. Seed it with **copies** of
private real decompressed batches, keeping the original captures and their
manifests unchanged:

```sh
cargo +nightly fuzz run event_envelope /absolute/private/corpus -- \
  -max_total_time=60 -max_len=65536 -rss_limit_mb=1024 -timeout=10 \
  -artifact_prefix=/absolute/private/artifacts/
```

Corpus mutations, dictionaries and crash reports may retain participant content;
keep them private and outside versioned fixtures. A short smoke run does not
prove exhaustive parser correctness.
