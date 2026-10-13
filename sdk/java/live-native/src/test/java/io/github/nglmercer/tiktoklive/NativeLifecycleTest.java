package io.github.nglmercer.tiktoklive;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class NativeLifecycleTest {
    @Test
    void realJniRepeatedCreateSignCloseAndConcurrentClose() throws Exception {
        String library = System.getenv("TTL_NATIVE_LIBRARY");
        String bundlePath = System.getenv("TTL_BUNDLE");
        assumeTrue(library != null, "local JNI library required");
        System.setProperty("tiktok.live.native.library", library);
        String bundle = bundlePath == null
                ? "window.byted_acrawler = { init: function() {}, registerWsSigner: function() { return function() { return {\"X-Gnarly\": \"offline\"}; }; } };"
                : Files.readString(Path.of(bundlePath));
        for (int cycle = 0; cycle < 20; cycle++) {
            var signer = TikTokSigner.open(bundle, "{}");
            assertTrue(signer.sign("https://example.invalid/?room_id=7300", SignProduct.WS).contains("X-Gnarly="));
            signer.close();
            signer.close();
            assertThrows(IllegalStateException.class, () -> signer.sign("https://example.invalid/", SignProduct.WS));
        }
        var signer = TikTokSigner.open(bundle, "{}");
        var workers = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var requests = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) requests.add(workers.submit(() -> {
                try {
                    start.await();
                    try { assertTrue(signer.sign("https://example.invalid/?room_id=7300", SignProduct.WS).contains("X-Gnarly=")); }
                    catch (IllegalStateException closed) { /* Close won the race. */ }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            start.countDown();
            signer.close();
            for (var request : requests) request.get(10, TimeUnit.SECONDS);
        } finally {
            signer.close(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
