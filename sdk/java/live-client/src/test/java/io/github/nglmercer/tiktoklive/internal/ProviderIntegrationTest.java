package io.github.nglmercer.tiktoklive.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.github.nglmercer.tiktoklive.*;

import okhttp3.*;
import okhttp3.mockwebserver.*;

import okio.ByteString;

import org.junit.jupiter.api.Test;

import webcast.im.SyntheticProto.*;
import webcast.shared.message.Message.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ProviderIntegrationTest {
    @Test
    void injectedProviderUsesExistingTransportAndWaitsForServerBatch() throws Exception {
        try (var server = new MockWebServer();
                var unusedBroker = new MockWebServer()) {
            var entered = new CountDownLatch(1);
            var peer = new AtomicReference<WebSocket>();
            server.enqueue(
                    new MockResponse()
                            .withWebSocketUpgrade(
                                    new WebSocketListener() {
                                        public void onOpen(WebSocket socket, Response response) {
                                            peer.set(socket);
                                        }

                                        public void onMessage(WebSocket socket, ByteString bytes) {
                                            try {
                                                if (Protocol.decode(bytes.toByteArray())
                                                        .getPayloadType()
                                                        .equals("im_enter_room"))
                                                    entered.countDown();
                                            } catch (Exception failure) {
                                                throw new AssertionError(failure);
                                            }
                                        }
                                    }));
            var providerClosed = new AtomicBoolean();
            ConnectionProvider provider =
                    new ConnectionProvider() {
                        public boolean requiresRoomConfirmation() {
                            return true;
                        }

                        public CompletableFuture<ConnectResponse> connect(String id) {
                            return CompletableFuture.completedFuture(
                                    new ConnectResponse(
                                            1,
                                            id,
                                            "7300",
                                            ConnectionStatus.LIVE,
                                            new ConnectionDescriptor(
                                                    "wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300&X-Gnarly=fresh",
                                                    "ttwid=identity",
                                                    "test-agent")));
                        }

                        public void close() {
                            providerClosed.set(true);
                        }
                    };
            var http =
                    new OkHttpClient.Builder()
                            .addInterceptor(
                                    chain ->
                                            chain.proceed(
                                                    chain.request()
                                                            .newBuilder()
                                                            .url(
                                                                    server.url(
                                                                            "/webcast/im/ws_proxy/ws_reuse_supplement/"))
                                                            .build()))
                            .build();
            try (var live =
                    TikTokLive.builder("creator")
                            .apiUrl(unusedBroker.url("/").toString())
                            .connectionProvider(provider)
                            .httpClient(http)
                            .build()) {
                var pending = live.connect();
                assertSame(pending, live.connect());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(pending.isDone(), "101 and outbound entry alone do not confirm a room");
                var batch =
                        ProtoMessageFetchResult.newBuilder()
                                .setNeedAck(true)
                                .setInternalExt("entry")
                                .build();
                var frame =
                        WebcastPushFrame.newBuilder()
                                .setPayloadType("msg")
                                .setPayload(batch.toByteString())
                                .build();
                peer.get().send(ByteString.of(frame.toByteArray()));
                assertTrue(pending.get(5, TimeUnit.SECONDS).connected());
                assertEquals(0, unusedBroker.getRequestCount());
                live.close();
                assertFalse(providerClosed.get(), "Injected provider is caller-owned");
            } finally {
                http.dispatcher().executorService().shutdownNow();
                http.connectionPool().evictAll();
            }
        }
    }

    @Test
    void disconnectCancelsAcquisitionAndLateCompletionCannotReopen() {
        var acquired = new CompletableFuture<ConnectResponse>();
        ConnectionProvider provider =
                new ConnectionProvider() {
                    public CompletableFuture<ConnectResponse> connect(String id) {
                        return acquired;
                    }

                    public void close() {}
                };
        try (var live = TikTokLive.builder("creator").connectionProvider(provider).build()) {
            var pending = live.connect();
            live.disconnect();
            assertTrue(acquired.isCancelled());
            assertTrue(pending.isCancelled());
            assertFalse(live.state().connected());
        }
    }
}
