package io.github.nglmercer.tiktoklive.examples;

import io.github.nglmercer.tiktoklive.*;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Headless lifecycle example. Pass creator and optionally a verified local bundle path. */
public final class EmbeddedMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1)
            throw new IllegalArgumentException("Usage: embedded-java @creator [bundle.js]");
        var options =
                EmbeddedJavaConnectionProvider.builder().cacheDirectory(Path.of("cache/tiktok"));
        if (args.length > 1) options.localBundle(Path.of(args[1]));
        String cookie = System.getenv("TTL_SESSION_COOKIE");
        if (cookie != null) options.sessionCookies(cookie);
        try (var provider = options.build();
                var live =
                        TikTokLive.builder(args[0])
                                .connectionProvider(provider)
                                .fetchGifts(true)
                                .build()) {
            var stop = new CountDownLatch(1);
            Thread shutdown =
                    new Thread(
                            () -> {
                                live.close();
                                provider.close();
                                stop.countDown();
                            });
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                live.onConnected(s -> System.out.println("Connected to " + s.roomId()));
                live.onOffline(
                        s -> {
                            System.out.println("Creator is offline");
                            stop.countDown();
                        });
                live.onReconnecting(s -> System.out.println("Reconnecting: " + s.attempt()));
                live.onDisconnected(s -> System.out.println("Disconnected"));
                live.onChat(e -> System.out.println(e.user().nickname() + ": " + e.comment()));
                live.onGiftFinal(
                        e ->
                                System.out.println(
                                        e.user().nickname()
                                                + " sent "
                                                + e.repeatCount()
                                                + " "
                                                + e.giftName()));
                live.onLike(e -> System.out.println("Like from " + e.user().nickname()));
                live.onError(
                        e ->
                                System.err.println(
                                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                live.connect().join();
                stop.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    /* JVM owns hook. */
                }
            }
        }
    }
}
