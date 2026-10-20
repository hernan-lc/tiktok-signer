package io.github.nglmercer.tiktoklive.internal;

import com.fasterxml.jackson.databind.*;

import io.github.nglmercer.tiktoklive.*;

import okhttp3.*;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.*;

public final class ConnectApi implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OkHttpClient http;
    private final HttpUrl base;
    private final String key;
    private final HttpJsonClient json;
    private volatile boolean closed;

    public ConnectApi(OkHttpClient http, String base, String key) {
        this.http = http;
        this.json = new HttpJsonClient(http);
        this.base = HttpUrl.get(base);
        this.key = key;
        if (!this.base.username().isEmpty() || !this.base.password().isEmpty())
            throw new IllegalArgumentException("API URL must not contain credentials");
    }

    public CompletableFuture<ConnectResponse> connect(String uniqueId) {
        String id = UniqueId.normalize(uniqueId);
        var builder =
                new Request.Builder()
                        .url(
                                base.newBuilder()
                                        .encodedPath("/v1/connect")
                                        .query(null)
                                        .fragment(null)
                                        .build())
                        .header("Accept", "application/json")
                        .post(
                                RequestBody.create(
                                        "{\"uniqueId\":\"" + id + "\"}",
                                        MediaType.get("application/json")));
        if (key != null && !key.isEmpty()) builder.header("Authorization", "Bearer " + key);
        var source = json(builder.build());
        var result = source.thenApply(body -> parse(body, id));
        result.whenComplete(
                (v, e) -> {
                    if (result.isCancelled()) source.cancel(false);
                });
        return result;
    }

    public CompletableFuture<JsonNode> json(Request request) {
        return json.json(request);
    }

    public static ConnectResponse parse(JsonNode body, String id) {
        if (!body.isObject()
                || !body.path("version").isIntegralNumber()
                || body.path("version").asInt() != 1
                || !body.path("uniqueId").isTextual()
                || !body.path("uniqueId").asText().equals(id))
            throw new ProtocolException("Mismatched connection metadata");
        String status = body.path("status").asText();
        String room = body.path("roomId").isTextual() ? body.path("roomId").asText() : "";
        if (status.equals("offline"))
            return new ConnectResponse(
                    1, id, room.matches("[0-9]+") ? room : "", ConnectionStatus.OFFLINE, null);
        if (!status.equals("live") || !room.matches("[0-9]+") || room.equals("0"))
            throw new ProtocolException("Invalid live connection response");
        try {
            if (Long.parseLong(room) <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new ProtocolException("Room id outside protocol int64 range");
        }
        var c = body.path("connection");
        if (!c.path("url").isTextual()
                || !c.path("cookies").isTextual()
                || !c.path("userAgent").isTextual()
                || !allowedSocket(c.path("url").asText(), room))
            throw new ProtocolException("Invalid connection descriptor");
        return new ConnectResponse(
                1,
                id,
                room,
                ConnectionStatus.LIVE,
                new ConnectionDescriptor(
                        c.path("url").asText(),
                        c.path("cookies").asText(),
                        c.path("userAgent").asText()));
    }

    public static boolean allowedSocket(String url, String room) {
        try {
            var uri = URI.create(url);
            var parsed = HttpUrl.get(url.replaceFirst("^wss:", "https:"));
            return uri.getScheme().equals("wss")
                    && uri.getRawUserInfo() == null
                    && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && Set.of(
                                    "webcast-ws.tiktok.com",
                                    "webcast-ws.us.tiktok.com",
                                    "webcast-ws.eu.tiktok.com")
                            .contains(uri.getHost())
                    && uri.getPath().equals("/webcast/im/ws_proxy/ws_reuse_supplement/")
                    && room.equals(parsed.queryParameter("room_id"))
                    && parsed.queryParameter("X-Gnarly") != null
                    && !parsed.queryParameter("X-Gnarly").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static TikTokLiveException error(int status, String requestId, JsonNode body) {
        return HttpJsonClient.error(status, requestId, body);
    }

    public synchronized void cancelPending() {
        json.cancelPending();
    }

    public synchronized void close() {
        closed = true;
        json.close();
    }
}
