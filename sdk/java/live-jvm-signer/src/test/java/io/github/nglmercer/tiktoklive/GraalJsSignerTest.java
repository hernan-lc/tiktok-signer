package io.github.nglmercer.tiktoklive;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.*;

class GraalJsSignerTest {
    private static final String URL =
            "wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300000000000000001&aid=1988&version_code=180800";
    private static final String FAKE =
            "window.byted_acrawler={init:function(){},registerWsSigner:function(){var i=0;return"
                + " function(){return {'X-Gnarly':String(++i)}}}};";

    @Test
    void warmContextAndConcurrentRequests() throws Exception {
        try (var signer = new GraalJsSigner(FAKE, true, Duration.ofSeconds(30), 16)) {
            var first = signer.sign(URL, GraalJsSigner.Product.WS);
            var second = signer.sign(URL, GraalJsSigner.Product.WS);
            assertEquals(URL + "&X-Gnarly=1", first.get(40, TimeUnit.SECONDS).url());
            assertEquals(URL + "&X-Gnarly=2", second.get(40, TimeUnit.SECONDS).url());
            assertFalse(second.join().toString().contains("730000"));
        }
    }

    @Test
    void failureIsRedactedAndRetiresSigner() {
        try (var signer =
                new GraalJsSigner(
                        "throw new Error('secret-cookie')", false, Duration.ofSeconds(30), 16)) {
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () -> signer.sign(URL, GraalJsSigner.Product.WS).join());
            assertFalse(failure.toString().contains("secret-cookie"));
            assertThrows(
                    CompletionException.class,
                    () -> signer.sign(URL, GraalJsSigner.Product.WS).join());
        }
    }

    @Test
    void timeoutRetiresWedgedContext() {
        try (var signer = new GraalJsSigner("while(true){}", true, Duration.ofMillis(500), 1)) {
            assertThrows(
                    CompletionException.class,
                    () -> signer.sign(URL, GraalJsSigner.Product.WS).join());
            assertThrows(
                    CompletionException.class,
                    () -> signer.sign(URL, GraalJsSigner.Product.WS).join());
        }
    }

    @Test
    void closeIsIdempotent() {
        var signer = new GraalJsSigner(FAKE, false, Duration.ofSeconds(30), 16);
        signer.close();
        signer.close();
        assertThrows(
                CompletionException.class, () -> signer.sign(URL, GraalJsSigner.Product.WS).join());
    }

    @Test
    void closingBusySignerCancelsTaskAndReleasesWorkers() throws Exception {
        var signer = new GraalJsSigner("while(true){}", true, Duration.ofSeconds(5), 1);
        var pending = signer.sign(URL, GraalJsSigner.Product.WS);
        Thread.sleep(150);
        signer.close();
        assertTrue(pending.isDone());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline
                && Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> t.isAlive() && t.getName().startsWith("ttl-graal-")))
            Thread.sleep(10);
        assertFalse(
                Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> t.isAlive() && t.getName().startsWith("ttl-graal-")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TTL_BUNDLE", matches = ".+")
    void freshContextParityAgainstReferenceForEveryProduct() throws Exception {
        Path bundle = Path.of(System.getenv("TTL_BUNDLE"));
        byte[] bytes = Files.readAllBytes(bundle);
        assertEquals(
                "dee22566273d398e074df6db40f39cfd827f6b8efd6fc382de03c44c501299ac",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        for (var product : GraalJsSigner.Product.values()) {
            String url =
                    product == GraalJsSigner.Product.WS
                            ? URL
                            : "https://webcast.tiktok.com/webcast/im/fetch/?aid=1988&room_id=7300000000000000001";
            // Contributor-only reference subprocess; never used by the production signer.
            Process reference =
                    new ProcessBuilder(
                                    "node",
                                    Path.of(
                                                    System.getProperty("repositoryRoot"),
                                                    "scripts/headless/tools/sign-pinned.mjs")
                                            .toString(),
                                    bundle.toString(),
                                    url,
                                    product.name().toLowerCase(java.util.Locale.ROOT))
                            .start();
            assertTrue(reference.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, reference.exitValue());
            String expected =
                    new String(
                            reference.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            try (var signer =
                    new GraalJsSigner(
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                            true,
                            Duration.ofSeconds(60),
                            16)) {
                // Avoid exposing full signed URLs in assertion diagnostics.
                assertTrue(
                        expected.equals(signer.sign(url, product).get(70, TimeUnit.SECONDS).url()),
                        product + " parity mismatch");
            }
        }
    }
}
