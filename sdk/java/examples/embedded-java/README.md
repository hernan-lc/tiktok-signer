# Headless embedded Java example

Requires standard OpenJDK 21+, network access, and a creator currently LIVE.
From `sdk/java`:

```sh
./gradlew :examples:embedded-java:run --args='@creator'
# Optional verified local signing bundle:
./gradlew :examples:embedded-java:run --args='@creator /opt/tiktok/webmssdk.js'
```

No Rust build, JNI, browser or external signing server is used. The pinned bundle
is verified and cached in `cache/tiktok`. Optionally supply session cookies through
`TTL_SESSION_COOKIE`; never put credentials in arguments or logs. Ctrl-C/SIGTERM
closes the client and caller-owned provider. See [embedded backend details](../../docs/EMBEDDED_JAVA.md).
