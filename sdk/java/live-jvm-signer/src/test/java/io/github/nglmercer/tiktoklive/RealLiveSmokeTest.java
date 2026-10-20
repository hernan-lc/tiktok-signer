package io.github.nglmercer.tiktoklive;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit opt-in only. No cookies, signatures or complete URLs enter reports. */
@EnabledIfEnvironmentVariable(named = "TTL_LIVE_CREATOR", matches = ".+")
class RealLiveSmokeTest {
    @Test
    void directJavaConnectionReceivesEvents() throws Exception {
        var options = EmbeddedJavaConnectionProvider.builder();
        if (System.getenv("TTL_BUNDLE") != null)
            options.localBundle(Path.of(System.getenv("TTL_BUNDLE")));
        if (System.getenv("TTL_SESSION_COOKIE") != null)
            options.sessionCookies(System.getenv("TTL_SESSION_COOKIE"));
        try (var provider = options.build();
                var live =
                        TikTokLive.builder(System.getenv("TTL_LIVE_CREATOR"))
                                .connectionProvider(provider)
                                .reconnectPolicy(
                                        new ReconnectPolicy(
                                                0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                                .build()) {
            var event = new LinkedBlockingQueue<Boolean>(128);
            var count = new AtomicInteger();
            live.onEvent(
                    e -> {
                        count.incrementAndGet();
                        event.offer(true);
                    });
            live.onError(
                    e ->
                            System.out.println(
                                    "live_error="
                                            + e.getClass().getSimpleName()
                                            + " message="
                                            + e.getMessage()));
            for (int attempt = 0; attempt < 2; attempt++) {
                event.clear();
                long start = System.nanoTime();
                assertTrue(live.connect().get(90, TimeUnit.SECONDS).connected());
                assertNotNull(
                        event.poll(20, TimeUnit.SECONDS),
                        "No events received from selected LIVE room");
                System.out.printf(
                        "direct_attempt=%d connect_ms=%.2f total_events=%d%n",
                        attempt + 1, (System.nanoTime() - start) / 1e6, count.get());
                live.disconnect();
            }
        }
    }
}
