package io.github.nglmercer.tiktoklive.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.github.nglmercer.tiktoklive.TikTokLive;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DeliveryOverloadTest {
    @Test
    void saturationStopsDeliveryAndReportsTerminalError() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failed = new CountDownLatch(1);
        try (var client = TikTokLive.builder("creator").build()) {
            client.onEvent(event -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            client.onError(error -> {
                assertTrue(error.getMessage().contains("overloaded"));
                failed.countDown();
            });
            var emit = TikTokLive.class.getDeclaredMethod("emit", String.class, Object.class);
            emit.setAccessible(true);
            emit.invoke(client, "event", null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 1100; i++) emit.invoke(client, "event", null);
            assertFalse(client.state().connected());
            assertEquals(1025, client.droppedCallbacks());
            release.countDown();
            assertTrue(failed.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
}
