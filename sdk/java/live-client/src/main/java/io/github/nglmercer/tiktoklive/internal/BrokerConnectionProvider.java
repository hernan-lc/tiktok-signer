package io.github.nglmercer.tiktoklive.internal;

import io.github.nglmercer.tiktoklive.*;

import okhttp3.OkHttpClient;

import java.util.concurrent.CompletableFuture;

/** Existing broker backend. The supplied HTTP client remains caller-owned. */
public final class BrokerConnectionProvider implements ConnectionProvider {
    private final ConnectApi api;

    public BrokerConnectionProvider(OkHttpClient http, String url, String key) {
        api = new ConnectApi(http, url, key);
    }

    public CompletableFuture<ConnectResponse> connect(String id) {
        return api.connect(id);
    }

    public void close() {
        api.close();
    }
}
