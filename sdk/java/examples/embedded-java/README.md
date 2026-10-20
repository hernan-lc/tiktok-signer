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

If Gradle fails with just `27`, your default JVM is Java 27, which the pinned
Gradle 8.14.3 Kotlin DSL cannot parse. Run Gradle with an installed JDK 21:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :examples:embedded-java:run --args='@creator'
```

Adjust that JDK path for your installation. This selects Java only for this command.
