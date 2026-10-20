package io.github.nglmercer.tiktoklive;

import okhttp3.*;

import java.io.IOException;
import java.util.*;

/** In-memory guest identity; no credentials are persisted or logged. */
public final class GuestSessionProvider {
    public record Session(String cookies, String deviceId) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }

    private GuestSessionProvider() {}

    static Session bootstrap(OkHttpClient http, java.util.function.Consumer<Call> track)
            throws IOException {
        return bootstrap(http, track, "https://www.tiktok.com/live");
    }

    static Session bootstrap(
            OkHttpClient http, java.util.function.Consumer<Call> track, String endpoint)
            throws IOException {
        Call call =
                http.newCall(
                        new Request.Builder()
                                .url(endpoint)
                                .header("User-Agent", DirectSocketUrlBuilder.USER_AGENT)
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .build());
        track.accept(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful())
                throw new AuthenticationException(
                        "TikTok refused guest bootstrap; configure session cookies (HTTP "
                                + response.code()
                                + ")");
            var values = new LinkedHashMap<String, String>();
            for (String header : response.headers("Set-Cookie")) {
                Cookie cookie = Cookie.parse(response.request().url(), header);
                if (cookie != null && cookie.matches(response.request().url()))
                    values.put(cookie.name(), cookie.value());
            }
            return fromCookies(
                    String.join(
                            "; ",
                            values.entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .toList()));
        }
    }

    public static Session fromCookies(String header) {
        if (header == null
                || header.length() > 32768
                || header.contains("\r")
                || header.contains("\n"))
            throw new IllegalArgumentException("Invalid session cookies");
        var values = new LinkedHashMap<String, String>();
        for (String pair : header.split(";")) {
            int at = pair.indexOf('=');
            if (at <= 0) continue;
            String name = pair.substring(0, at).trim(), value = pair.substring(at + 1).trim();
            if (!name.matches("[A-Za-z0-9_\\-]+")
                    || value.chars().anyMatch(c -> c < 32 || c >= 127))
                throw new IllegalArgumentException("Invalid session cookies");
            values.put(name, value);
        }
        if (values.getOrDefault("ttwid", "").isEmpty())
            throw new AuthenticationException(
                    "No usable ttwid cookie; configure a valid TikTok session");
        String device = values.getOrDefault("tt_webid_v2", values.getOrDefault("tt_webid", ""));
        if (!device.matches("[0-9]+")) device = DirectSocketUrlBuilder.deviceId();
        return new Session(header, device);
    }
}
