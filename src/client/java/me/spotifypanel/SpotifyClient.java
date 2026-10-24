package me.spotifypanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Опрашивает Spotify Web API и отправляет команды управления. */
public final class SpotifyClient {
    public static final int GRID = 12;

    public enum Status { NO_CLIENT_ID, LOGGED_OUT, IDLE, ACTIVE, ERROR }

    public record Track(String title, String artists, String coverUrl, long durationMs) {}

    public record Snapshot(Status status, boolean playing, Track track, long progressMs,
                           long fetchedAtMs, String device, int volume, String error) {
        static Snapshot of(Status s) {
            return new Snapshot(s, false, null, 0, System.currentTimeMillis(), "", 0, "");
        }

        static Snapshot error(String m) {
            return new Snapshot(Status.ERROR, false, null, 0, System.currentTimeMillis(), "", 0, m);
        }

        public long currentProgress() {
            if (track == null) return 0;
            long p = progressMs + (playing ? System.currentTimeMillis() - fetchedAtMs : 0);
            return Math.max(0, Math.min(p, track.durationMs()));
        }
    }

    private final SpotifyAuth auth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "spotifypanel");
        t.setDaemon(true);
        return t;
    });

    private volatile Snapshot snapshot = Snapshot.of(Status.LOGGED_OUT);
    private volatile int[] cover; // GRID*GRID*2 цветов (две грани на ячейку)
    private volatile String coverUrl = "";

    public SpotifyClient(SpotifyAuth auth) {
        this.auth = auth;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::poll, 1, 2, TimeUnit.SECONDS);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public int[] cover() {
        return cover;
    }

    // ------------------------------------------------------------- polling

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                .header("Authorization", "Bearer " + auth.accessToken())
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void poll() {
        try {
            if (auth.clientId().isEmpty()) {
                snapshot = Snapshot.of(Status.NO_CLIENT_ID);
                return;
            }
            if (!auth.isLoggedIn()) {
                snapshot = Snapshot.of(Status.LOGGED_OUT);
                return;
            }
            HttpResponse<String> r = send("GET", "/me/player");
            int sc = r.statusCode();
            if (sc == 204) {
                snapshot = Snapshot.of(Status.IDLE);
            } else if (sc == 401) {
                auth.invalidate();
            } else if (sc == 429) {
                snapshot = Snapshot.error("Слишком много запросов (429)");
            } else if (sc != 200) {
                snapshot = Snapshot.error("HTTP " + sc);
            } else {
                parse(r.body());
            }
        } catch (Exception e) {
            if (!auth.isLoggedIn()) {
                snapshot = Snapshot.of(Status.LOGGED_OUT);
            } else {
                snapshot = Snapshot.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    private void parse(String body) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonElement itemEl = o.get("item");
        if (itemEl == null || itemEl.isJsonNull()) {
            snapshot = Snapshot.of(Status.IDLE);
            return;
        }
        JsonObject item = itemEl.getAsJsonObject();

        String title = str(item, "name");
        long duration = lng(item, "duration_ms");

        StringBuilder artists = new StringBuilder();
        if (item.has("artists") && item.get("artists").isJsonArray()) {
            for (JsonElement a : item.getAsJsonArray("artists")) {
                if (artists.length() > 0) artists.append(", ");
                artists.append(str(a.getAsJsonObject(), "name"));
            }
        } else if (item.has("show") && item.get("show").isJsonObject()) {
            artists.append(str(item.getAsJsonObject("show"), "name"));
        }

        String cover = "";
        JsonObject imgHolder = item.has("album") && item.get("album").isJsonObject() ? item.getAsJsonObject("album")
                : item.has("images") ? item : null;
        if (imgHolder != null && imgHolder.has("images") && imgHolder.get("images").isJsonArray()) {
            JsonArray imgs = imgHolder.getAsJsonArray("images");
            if (imgs.size() > 0) {
                cover = str(imgs.get(imgs.size() - 1).getAsJsonObject(), "url"); // самая маленькая
            }
        }

        String device = "";
        int volume = 0;
        if (o.has("device") && o.get("device").isJsonObject()) {
            JsonObject d = o.getAsJsonObject("device");
            device = str(d, "name");
            volume = (int) lng(d, "volume_percent");
        }

        boolean playing = o.has("is_playing") && !o.get("is_playing").isJsonNull() && o.get("is_playing").getAsBoolean();
        long progress = lng(o, "progress_ms");

        snapshot = new Snapshot(Status.ACTIVE, playing, new Track(title, artists.toString(), cover, duration),
                progress, System.currentTimeMillis(), device, volume, "");

        if (!cover.equals(coverUrl)) {
            coverUrl = cover;
            if (cover.isEmpty()) {
                this.cover = null;
            } else {
                final String url = cover;
                exec.submit(() -> loadCover(url));
            }
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    // --------------------------------------------------------------- cover

    private void loadCover(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) return;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.body()));
            if (img == null || !url.equals(coverUrl)) return;
            this.cover = mosaic(img);
        } catch (Exception ignored) {
        }
    }

    /** Режем обложку на ячейки GRIDxGRID, каждая ячейка = два треугольника со своим усреднённым цветом. */
    private static int[] mosaic(BufferedImage img) {
        int g = GRID;
        int w = img.getWidth(), h = img.getHeight();
        int[] out = new int[g * g * 2];
        for (int cy = 0; cy < g; cy++) {
            for (int cx = 0; cx < g; cx++) {
                int x0 = cx * w / g, x1 = Math.max(x0 + 1, (cx + 1) * w / g);
                int y0 = cy * h / g, y1 = Math.max(y0 + 1, (cy + 1) * h / g);
                long[] lo = new long[4], hi = new long[4];
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int rgb = img.getRGB(x, y);
                        boolean low = (long) (x - x0) * (y1 - y0) <= (long) (y - y0) * (x1 - x0);
                        long[] a = low ? lo : hi;
                        a[0] += (rgb >> 16) & 0xFF;
                        a[1] += (rgb >> 8) & 0xFF;
                        a[2] += rgb & 0xFF;
                        a[3]++;
                    }
                }
                int i = (cy * g + cx) * 2;
                int cLo = avg(lo), cHi = avg(hi);
                if (lo[3] == 0) cLo = cHi;
                if (hi[3] == 0) cHi = cLo;
                out[i] = cLo;
                out[i + 1] = cHi;
            }
        }
        return out;
    }

    private static int avg(long[] a) {
        if (a[3] == 0) return 0xFF000000;
        return 0xFF000000 | ((int) (a[0] / a[3]) << 16) | ((int) (a[1] / a[3]) << 8) | (int) (a[2] / a[3]);
    }

    // ------------------------------------------------------------ controls

    public void togglePlay(Consumer<String> err) {
        if (snapshot.playing()) pause(err); else play(err);
    }

    public void play(Consumer<String> err) {
        control("PUT", "/me/player/play", err);
    }

    public void pause(Consumer<String> err) {
        control("PUT", "/me/player/pause", err);
    }

    public void next(Consumer<String> err) {
        control("POST", "/me/player/next", err);
    }

    public void previous(Consumer<String> err) {
        control("POST", "/me/player/previous", err);
    }

    public void volume(int percent, Consumer<String> err) {
        control("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)), err);
    }

    private void control(String method, String path, Consumer<String> err) {
        exec.submit(() -> {
            try {
                if (!auth.isLoggedIn()) {
                    err.accept("Сначала войди: /spotify login");
                    return;
                }
                HttpResponse<String> r = send(method, path);
                int sc = r.statusCode();
                if (sc == 404) {
                    err.accept("Нет активного устройства: открой Spotify и запусти любой трек.");
                } else if (sc == 403) {
                    err.accept("Spotify отказал (нужен Premium или действие недоступно).");
                } else if (sc >= 300) {
                    err.accept("Spotify: HTTP " + sc);
                } else {
                    exec.schedule(this::poll, 500, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                err.accept("Ошибка: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }
}
