package io.github.nglmercer.tiktoklive;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized, warm embedded signer. Guest failures never expose guest text or signed URLs. */
public final class GraalJsSigner implements AutoCloseable {
    public enum Product {
        WS,
        FETCH,
        FRONTIER
    }

    public record SignedUrl(String url) {
        @Override
        public String toString() {
            return "SignedUrl[redacted]";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String bundle;
    private final boolean pinned;
    private String cookie = "";
    private String userAgent = DirectSocketUrlBuilder.USER_AGENT;
    private final long timeoutMillis;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService timer;
    private final ConcurrentHashMap<CompletableFuture<SignedUrl>, Boolean> pending =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Context context;
    private volatile CompletableFuture<SignedUrl> executing;

    boolean isClosed() {
        return closed.get();
    }

    /** Production profiles always use fresh entropy and an unfrozen clock. */
    public GraalJsSigner(String bundle) {
        this(verified(bundle), false, Duration.ofSeconds(30), 16);
    }

    private static String verified(String bundle) {
        try {
            return SigningBundleManager.verify(bundle.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Unverified signing bundle");
        }
    }

    GraalJsSigner(String bundle, String cookie, String userAgent) {
        this(bundle);
        this.cookie = cookie;
        this.userAgent = userAgent;
    }

    // Determinism is intentionally available only to package tests.
    GraalJsSigner(String bundle, boolean pinned, Duration timeout, int capacity) {
        if (bundle == null
                || bundle.isBlank()
                || timeout.isNegative()
                || timeout.isZero()
                || capacity < 1)
            throw new IllegalArgumentException("Invalid signing configuration");
        this.bundle = bundle;
        this.pinned = pinned;
        this.timeoutMillis = Math.max(1, timeout.toMillis());
        worker =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(capacity),
                        r -> daemon(r, "ttl-graal-signer"),
                        new ThreadPoolExecutor.AbortPolicy());
        var deadlines = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "ttl-graal-timeout"));
        deadlines.setRemoveOnCancelPolicy(true);
        timer = deadlines;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public CompletableFuture<SignedUrl> sign(String url, Product product) {
        if (url == null || product == null)
            throw new IllegalArgumentException("URL and product required");
        var result = new CompletableFuture<SignedUrl>();
        if (closed.get()) {
            result.completeExceptionally(new IllegalStateException("Signer closed"));
            return result;
        }
        pending.put(result, true);
        try {
            var deadline =
                    timer.schedule(
                            () -> {
                                if (result.completeExceptionally(
                                        new TimeoutException(
                                                "Signing deadline exceeded; signer retired")))
                                    close();
                            },
                            timeoutMillis,
                            TimeUnit.MILLISECONDS);
            result.whenComplete(
                    (v, e) -> {
                        deadline.cancel(false);
                        pending.remove(result);
                        if (result.isCancelled() && executing == result) close();
                    });
            worker.execute(
                    () -> {
                        if (result.isDone() || closed.get()) return;
                        executing = result;
                        if (result.isDone()) {
                            executing = null;
                            return;
                        }
                        try {
                            initialize();
                            var out =
                                    JSON.readTree(
                                            context.getBindings("js")
                                                    .getMember("ttlSignUrl")
                                                    .execute(
                                                            url,
                                                            product.name()
                                                                    .toLowerCase(
                                                                            java.util.Locale.ROOT))
                                                    .asString());
                            if (out.has("error") || !out.path("signed").isTextual())
                                throw new IllegalStateException();
                            result.complete(new SignedUrl(out.get("signed").asText()));
                        } catch (Throwable failure) {
                            closed.set(true);
                            result.completeExceptionally(
                                    new IllegalStateException(
                                            "Embedded JavaScript signing failed"));
                            retire();
                        } finally {
                            executing = null;
                        }
                    });
        } catch (RejectedExecutionException e) {
            pending.remove(result);
            result.completeExceptionally(new IllegalStateException("Signer closed or queue full"));
        }
        return result;
    }

    private void initialize() throws Exception {
        if (context != null) return;
        Context created =
                Context.newBuilder("js")
                        .allowAllAccess(false)
                        .allowHostAccess(HostAccess.NONE)
                        .allowHostClassLookup(n -> false)
                        .allowHostClassLoading(false)
                        .allowCreateThread(false)
                        .allowCreateProcess(false)
                        .allowNativeAccess(false)
                        .allowEnvironmentAccess(EnvironmentAccess.NONE)
                        .allowIO(IOAccess.NONE)
                        .allowPolyglotAccess(PolyglotAccess.NONE)
                        .option("engine.WarnInterpreterOnly", "false")
                        .build();
        context = created;
        if (closed.get()) {
            created.close(true);
            throw new IllegalStateException();
        }
        SecureRandom random = new SecureRandom();
        created.getBindings("js")
                .putMember(
                        "__ttl_random_bytes",
                        (ProxyExecutable)
                                args -> {
                                    int length = args[0].asInt();
                                    if (length < 0 || length > 65536)
                                        throw new IllegalArgumentException();
                                    byte[] bytes = new byte[length];
                                    random.nextBytes(bytes);
                                    Object[] values = new Object[length];
                                    for (int i = 0; i < length; i++) values[i] = bytes[i] & 255;
                                    return ProxyArray.fromArray(values);
                                });
        try (var in = GraalJsSigner.class.getResourceAsStream("bootstrap.js")) {
            if (in == null) throw new IllegalStateException();
            created.eval("js", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        var prepared =
                JSON.readTree(
                        created.getBindings("js")
                                .getMember("ttlPrepare")
                                .execute(
                                        bundle,
                                        JSON.writeValueAsString(
                                                java.util.Map.of(
                                                        "pinned",
                                                        pinned,
                                                        "cookie",
                                                        cookie,
                                                        "userAgent",
                                                        userAgent)))
                                .asString());
        if (!prepared.path("ok").asBoolean()) throw new IllegalStateException();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        retire();
    }

    private void retire() {
        pending.keySet()
                .forEach(f -> f.completeExceptionally(new CancellationException("Signer closed")));
        worker.shutdownNow();
        timer.shutdownNow();
        Context active = context;
        if (active != null) active.close(true);
    }
}
