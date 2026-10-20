package io.github.nglmercer.tiktoklive;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;

/** Loads only the pinned upstream bytes. No proprietary bundle is packaged with this module. */
public final class SigningBundleManager {
    public static final String VERSION = "1.0.0.388";
    public static final String SHA256 =
            "dee22566273d398e074df6db40f39cfd827f6b8efd6fc382de03c44c501299ac";
    public static final URI SOURCE =
            URI.create(
                    "https://sf16-website-login.neutral.ttwstatic.com/obj/tiktok_web_login_static/webmssdk/1.0.0.388/webmssdk.js");
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final Object CACHE_LOCK = new Object();
    private final Path cacheDirectory;

    public SigningBundleManager(Path cacheDirectory) {
        this.cacheDirectory = java.util.Objects.requireNonNull(cacheDirectory).toAbsolutePath();
    }

    /** Explicit local files are verified and never overwritten. */
    public String loadLocal(Path path) throws IOException {
        return verify(readBounded(Files.newInputStream(path)));
    }

    public String load() throws IOException, InterruptedException {
        synchronized (CACHE_LOCK) {
            Files.createDirectories(cacheDirectory);
            Path target = cacheDirectory.resolve("webmssdk-" + VERSION + ".js");
            // OS lock also coordinates independent provider instances and JVMs.
            try (var lockFile =
                            java.nio.channels.FileChannel.open(
                                    cacheDirectory.resolve("bundle.lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    var lock = lockFile.lock()) {
                if (Files.exists(target)) return loadLocal(target);
                byte[] bytes;
                try (var client =
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(15))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build()) {
                    // A buffered body with a hard byte limit avoids an unbounded stalled stream.
                    var request =
                            HttpRequest.newBuilder(SOURCE)
                                    .timeout(Duration.ofSeconds(30))
                                    .GET()
                                    .build();
                    var response = client.send(request, info -> new LimitedBody());
                    if (response.statusCode() != 200)
                        throw new IOException(
                                "Bundle download refused: HTTP " + response.statusCode());
                    bytes = response.body();
                }
                verify(bytes);
                Path temporary = Files.createTempFile(cacheDirectory, "bundle-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private long received;

        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) received += buffer.remaining();
            if (received > LIMIT) {
                subscription.cancel();
                delegate.onError(new IOException("Signing bundle exceeds size limit"));
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IOException("Signing bundle exceeds size limit");
            return bytes;
        }
    }

    static String verify(byte[] bytes) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("Signing bundle exceeds size limit");
        try {
            String actual =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!SHA256.equals(actual)) throw new IOException("Signing bundle SHA-256 mismatch");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
