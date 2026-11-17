package me.spotifypanel;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Общий HttpClient мода. Если в конфиге задан HTTP-прокси ("host:port") — все запросы идут через него. */
public final class Net {
    private static volatile HttpClient client;

    private Net() {}

    public static HttpClient http() {
        HttpClient c = client;
        if (c == null) {
            synchronized (Net.class) {
                if (client == null) client = build(SpotifyPanelMod.config == null ? "" : SpotifyPanelMod.config.proxy);
                c = client;
            }
        }
        return c;
    }

    /** Применить прокси ("host:port" или пусто = без прокси). Бросает IllegalArgumentException при кривом формате. */
    public static void setProxy(String hostPort) {
        String v = hostPort == null ? "" : hostPort.trim();
        HttpClient c = build(v);
        synchronized (Net.class) {
            client = c;
        }
    }

    /** Parse Retry-After in either delta-seconds or RFC 1123 HTTP-date form. */
    public static long retryAfterDelayMillis(String value, long fallbackMs, long maxMs) {
        if (value == null || value.isBlank()) return Math.min(Math.max(0L, fallbackMs), Math.max(0L, maxMs));
        String v = value.trim();
        long delay;
        try {
            delay = Math.multiplyExact(Math.max(0L, Long.parseLong(v)), 1_000L);
        } catch (NumberFormatException | ArithmeticException numericFailure) {
            try {
                Instant target = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                delay = Math.max(0L, Duration.between(Instant.now(), target).toMillis());
            } catch (DateTimeParseException dateFailure) {
                delay = Math.max(0L, fallbackMs);
            }
        }
        return Math.min(Math.max(0L, delay), Math.max(0L, maxMs));
    }

    private static HttpClient build(String hostPort) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8));
        if (hostPort != null && !hostPort.isBlank()) {
            String v = hostPort.trim().replaceFirst("^https?://", "");
            int i = v.lastIndexOf(':');
            if (i <= 0) throw new IllegalArgumentException("формат: host:port");
            int port;
            try {
                port = Integer.parseInt(v.substring(i + 1).replaceAll("/.*$", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("порт должен быть числом");
            }
            b.proxy(ProxySelector.of(new InetSocketAddress(v.substring(0, i), port)));
        }
        return b.build();
    }
}
