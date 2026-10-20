package io.github.nglmercer.tiktoklive;

import static org.junit.jupiter.api.Assertions.*;

import okhttp3.*;
import okhttp3.mockwebserver.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.*;

class DirectPreparationTest {
    @TempDir Path cache;

    @Test
    void rawQueryMatchesRustFixtureByteForByte() throws Exception {
        String source =
                Files.readString(
                        Path.of(
                                System.getProperty("repositoryRoot"),
                                "crates/ttl-sign-core/src/params.rs"));
        String fixture = source.substring(source.indexOf("let expected = concat!("));
        fixture = fixture.substring(0, fixture.indexOf(");"));
        var matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(fixture);
        var expected = new StringBuilder();
        while (matcher.find()) expected.append(matcher.group(1));
        String raw = DirectSocketUrlBuilder.rawUrl("7675481361573382932", "7300000000000000001");
        assertEquals(expected.toString(), raw.substring(raw.indexOf('?') + 1));
        assertTrue(raw.contains("browser_platform=Linux x86_64"));
        String signed = raw + "&X-Gnarly=abc%2Fdef%2Bghi%3D";
        String safe = DirectSocketUrlBuilder.validated(signed, raw);
        assertTrue(safe.endsWith("X-Gnarly=abc%2Fdef%2Bghi%3D"));
        assertEquals(safe, DirectSocketUrlBuilder.sanitize(safe));
        assertFalse(safe.contains(" "));
        assertThrows(
                IllegalStateException.class,
                () -> DirectSocketUrlBuilder.validated(signed + "&room_id=2", raw));
        assertThrows(
                IllegalStateException.class,
                () -> DirectSocketUrlBuilder.validated(signed + "#evil", raw));
    }

    @Test
    void largeIdsAndNoDeviceRemainStrings() {
        String raw = DirectSocketUrlBuilder.rawUrl("9223372036854775807", "");
        assertTrue(raw.endsWith("did_rule=3&device_id="));
        assertTrue(raw.contains("room_id=9223372036854775807"));
    }

    @Test
    void localBundlesMustMatchDigestAndAreNeverRewritten() throws Exception {
        Path local = cache.resolve("custom.js");
        Files.writeString(local, "corrupted");
        var manager = new SigningBundleManager(cache);
        assertThrows(java.io.IOException.class, () -> manager.loadLocal(local));
        assertEquals("corrupted", Files.readString(local));
        assertThrows(java.io.IOException.class, () -> manager.loadLocal(cache.resolve("missing")));
    }

    @Test
    void guestBootstrapCollectsCookiesAndConsistentUserAgent() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse()
                            .addHeader("Set-Cookie", "ttwid=private-identity; Path=/")
                            .addHeader("Set-Cookie", "tt_webid_v2=7300000000000000001; Path=/"));
            var session =
                    GuestSessionProvider.bootstrap(
                            new OkHttpClient(), c -> {}, server.url("/live").toString());
            assertEquals("7300000000000000001", session.deviceId());
            assertTrue(session.cookies().contains("ttwid=private-identity"));
            assertFalse(session.toString().contains("private-identity"));
            assertEquals(
                    DirectSocketUrlBuilder.USER_AGENT,
                    server.takeRequest().getHeader("User-Agent"));
        }
    }

    @Test
    void refusedGuestAndMissingCookiesAreAuthenticationErrors() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403));
            assertThrows(
                    AuthenticationException.class,
                    () ->
                            GuestSessionProvider.bootstrap(
                                    new OkHttpClient(), c -> {}, server.url("/").toString()));
            assertThrows(AuthenticationException.class, () -> GuestSessionProvider.fromCookies(""));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GuestSessionProvider.fromCookies("ttwid=x\r\nInjected: x"));
        }
    }

    @Test
    void offlineNeverDownloadsBundleOrRequestsSignature() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse()
                            .setBody(
                                    "{\"status_code\":0,\"data\":{\"user\":{\"uniqueId\":\"creator\",\"status\":4,\"roomId\":\"0\"}}}"));
            try (var provider =
                    EmbeddedJavaConnectionProvider.builder()
                            .cacheDirectory(cache)
                            .discoveryEndpoint(server.url("/room").toString())
                            .build()) {
                assertEquals(
                        ConnectionStatus.OFFLINE,
                        provider.connect("@creator").get(5, TimeUnit.SECONDS).status());
                assertFalse(Files.exists(cache.resolve("bundle.lock")));
                assertEquals("/room", server.takeRequest().getRequestUrl().encodedPath());
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    void discoveryFailuresAreNeverOffline() throws Exception {
        try (var server = new MockWebServer();
                var provider =
                        EmbeddedJavaConnectionProvider.builder()
                                .cacheDirectory(cache)
                                .discoveryEndpoint(server.url("/room").toString())
                                .build()) {
            for (int code : new int[] {403, 429, 500}) {
                server.enqueue(new MockResponse().setResponseCode(code));
                var error =
                        assertThrows(
                                ExecutionException.class,
                                () -> provider.connect("creator").get(5, TimeUnit.SECONDS));
                assertInstanceOf(TikTokLiveException.class, error.getCause());
            }
            for (String body :
                    new String[] {
                        "invalid",
                        "{}",
                        "{\"data\":{\"user\":{\"status\":99}}}",
                        "{\"data\":{\"user\":{\"status\":2,\"roomId\":\"0\"}}}",
                        "{\"challenge\":true}"
                    }) {
                server.enqueue(new MockResponse().setBody(body));
                assertThrows(
                        ExecutionException.class,
                        () -> provider.connect("creator").get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void cancellationAndCloseStopPendingDiscovery() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var provider =
                    EmbeddedJavaConnectionProvider.builder()
                            .cacheDirectory(cache)
                            .discoveryEndpoint(server.url("/room").toString())
                            .build();
            var pending = provider.connect("creator");
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            pending.cancel(false);
            provider.close();
            provider.close();
            assertTrue(pending.isCancelled());
            assertThrows(CompletionException.class, () -> provider.connect("creator").join());
        }
    }
}
