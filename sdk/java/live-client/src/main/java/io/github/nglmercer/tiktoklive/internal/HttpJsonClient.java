package io.github.nglmercer.tiktoklive.internal;

import com.fasterxml.jackson.databind.*;

import io.github.nglmercer.tiktoklive.*;

import okhttp3.*;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;

/** Generic bounded HTTP JSON requests, independent of broker configuration. */
public final class HttpJsonClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OkHttpClient http;
    private final Set<Call> pending = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public HttpJsonClient(OkHttpClient http) {
        this.http = http;
    }

    public CompletableFuture<JsonNode> json(Request request) {
        var result = new CompletableFuture<JsonNode>();
        Call call = http.newCall(request);
        synchronized (this) {
            if (closed)
                return CompletableFuture.failedFuture(new IllegalStateException("Client closed"));
            pending.add(call);
        }
        result.whenComplete(
                (v, e) -> {
                    pending.remove(call);
                    if (result.isCancelled()) call.cancel();
                });
        call.enqueue(
                new Callback() {
                    public void onFailure(Call call, IOException error) {
                        var failure = new DiscoveryException("HTTP request failed");

                        result.completeExceptionally(failure);
                    }

                    public void onResponse(Call call, Response response) {
                        try (response) {
                            JsonNode body;
                            try {
                                body =
                                        JSON.readTree(
                                                response.body() == null
                                                        ? ""
                                                        : bounded(response.body()));
                            } catch (Exception e) {
                                if (!response.isSuccessful())
                                    throw error(
                                            response.code(), response.header("x-request-id"), null);
                                throw new ProtocolException("Invalid JSON response");
                            }
                            if (!response.isSuccessful())
                                throw error(response.code(), response.header("x-request-id"), body);
                            if (body == null) throw new ProtocolException("Empty JSON response");
                            result.complete(body);
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    }
                });
        return result;
    }

    public static TikTokLiveException error(int status, String requestId, JsonNode body) {
        var e = body == null ? JSON.createObjectNode() : body.path("error");
        String code = e.path("code").asText(status == 429 ? "RATE_LIMITED" : "INTERNAL_ERROR");
        String message = e.path("message").asText("HTTP " + status);
        boolean retry =
                e.path("retryable").isBoolean()
                        ? e.path("retryable").asBoolean()
                        : status >= 500 || status == 429;
        Long after =
                e.path("retryAfterMs").isIntegralNumber()
                                && e.path("retryAfterMs").canConvertToLong()
                        ? Math.max(0, e.path("retryAfterMs").asLong())
                        : null;
        return switch (code) {
            case "AUTHENTICATION_REQUIRED", "INVALID_API_KEY" ->
                    new AuthenticationException(code, message, retry, after, status, requestId);
            case "RATE_LIMITED" ->
                    new RateLimitException(code, message, retry, after, status, requestId);
            case "SIGNER_UNAVAILABLE", "SIGN_FAILED" ->
                    new SignerUnavailableException(code, message, retry, after, status, requestId);
            case "DISCOVERY_FAILED", "USER_NOT_FOUND", "TIKTOK_REFUSED" ->
                    new DiscoveryException(code, message, retry, after, status, requestId);
            default ->
                    status == 401 || status == 403
                            ? new AuthenticationException(
                                    code, message, retry, after, status, requestId)
                            : status == 429
                                    ? new RateLimitException(
                                            code, message, retry, after, status, requestId)
                                    : new ApiException(
                                            code, message, retry, after, status, requestId);
        };
    }

    private static String bounded(ResponseBody body) throws IOException {
        byte[] bytes = body.byteStream().readNBytes(4 * 1024 * 1024 + 1);
        if (bytes.length > 4 * 1024 * 1024) throw new IOException("HTTP response exceeds limit");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public synchronized void cancelPending() {
        pending.forEach(Call::cancel);
    }

    public synchronized void close() {
        closed = true;
        cancelPending();
    }
}
