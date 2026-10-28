package me.spotifypanel;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

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
