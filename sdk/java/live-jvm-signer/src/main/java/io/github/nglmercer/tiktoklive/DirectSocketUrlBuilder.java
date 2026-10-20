package io.github.nglmercer.tiktoklive;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Raw ordered serialization from ttl-sign-core/params.rs, with a distinct transport form. */
public final class DirectSocketUrlBuilder {
    public static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/131.0.0.0 Safari/537.36";
    private static final String PATH = "/webcast/im/ws_proxy/ws_reuse_supplement/";
    private static final Set<String> HOSTS =
            Set.of("webcast-ws.tiktok.com", "webcast-ws.us.tiktok.com", "webcast-ws.eu.tiktok.com");

    private DirectSocketUrlBuilder() {}

    public static String deviceId() {
        var random = new SecureRandom();
        var out = new StringBuilder().append(1 + random.nextInt(9));
        for (int i = 1; i < 19; i++) out.append(random.nextInt(10));
        return out.toString();
    }

    public static String rawUrl(String room, String device) {
        if (room == null
                || !room.matches("[1-9][0-9]*")
                || device == null
                || !device.matches("[0-9]*"))
            throw new IllegalArgumentException("Invalid room or device identifier");
        return "wss://webcast-ws.tiktok.com"
                + PATH
                + "?"
                + "version_code=180800&device_platform=web&cookie_enabled=true"
                + "&screen_width=1920&screen_height=1080&browser_language=en-US"
                + "&browser_platform=Linux x86_64&browser_name=Mozilla&browser_version="
                + USER_AGENT.substring(8)
                + "&browser_online=true&tz_name=America/New_York"
                + "&app_name=tiktok_web&sup_ws_ds_opt=1&update_version_code=2.0.0&compress=gzip"
                + "&webcast_language=en&aid=1988&live_id=12&version_code=270000&app_language=en"
                + "&ws_direct=1&client_enter=1&room_id="
                + room
                + "&identity=audience"
                + "&last_rtt=-1&heartbeat_duration=10000&resp_content_type=protobuf&did_rule="
                + (device.isEmpty() ? "3" : "0")
                + "&device_id="
                + device;
    }

    public static String sanitize(String raw) {
        var out = new StringBuilder();
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0) out.append((char) c);
            else
                out.append('%')
                        .append("0123456789ABCDEF".charAt(c >> 4))
                        .append("0123456789ABCDEF".charAt(c & 15));
        }
        return out.toString();
    }

    static String validated(String signed, String unsigned) {
        // The SDK may only append exactly one signature to the original signing bytes.
        if (!signed.startsWith(unsigned + "&X-Gnarly=")
                || !signed.substring(unsigned.length() + 10).matches("[A-Za-z0-9%_.~!()*'\\-]+"))
            throw new IllegalStateException("Invalid signed socket descriptor");
        String safe = sanitize(signed);
        URI uri = URI.create(safe);
        if (!"wss".equals(uri.getScheme())
                || !HOSTS.contains(uri.getHost())
                || !PATH.equals(uri.getPath())
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() != -1)
            throw new IllegalStateException("Invalid socket destination");
        return safe;
    }
}
