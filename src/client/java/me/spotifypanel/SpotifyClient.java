package me.spotifypanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.texture.NativeImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;

/** Spotify Web API: состояние плеера, плейлисты, треки, обложки и команды управления. */
public final class SpotifyClient {
    private static final Logger LOG = LoggerFactory.getLogger("spotifypanel");
    private static final int RAW = 24; // обложка сжимается до 24x24, из неё строятся мозаики 6/8/12

    public enum Status { NO_CLIENT_ID, LOGGED_OUT, IDLE, ACTIVE, ERROR }

    public record Track(String title, String artists, String coverUrl, long durationMs, String uri) {}

    public record Playlist(String id, String name, String owner, int total, String coverUrl, String uri) {}

    public record Item(String title, String artists, long durationMs, String uri) {}

    public record Snapshot(Status status, boolean playing, Track track, long progressMs, long fetchedAtMs,
                           String device, int volume, boolean shuffle, String repeat, String error) {
        static Snapshot of(Status s) {
            return new Snapshot(s, false, null, 0, System.currentTimeMillis(), "", 0, false, "off", "");
        }

        static Snapshot error(String m) {
            return new Snapshot(Status.ERROR, false, null, 0, System.currentTimeMillis(), "", 0, false, "off", m);
        }

        public long currentProgress() {
            if (track == null) return 0;
            long p = progressMs + (playing ? System.currentTimeMillis() - fetchedAtMs : 0);
            return Math.max(0, Math.min(p, track.durationMs()));
        }
    }

    private final SpotifyAuth auth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "spotifypanel");
        t.setDaemon(true);
        return t;
    });

    private volatile Snapshot snapshot = Snapshot.of(Status.LOGGED_OUT);

    // плейлисты и треки
    private volatile List<Playlist> playlists = List.of();
    private volatile String playlistsError = "";
    private volatile long lastPlaylistTry = 0;
    private final Map<String, List<Item>> items = new ConcurrentHashMap<>();
    private final Map<String, String> itemErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> itemsTime = new ConcurrentHashMap<>();
    private final Set<String> itemsLoading = ConcurrentHashMap.newKeySet();

    // обложки
    private final Map<String, int[]> rawCache = new ConcurrentHashMap<>();
    private final Map<String, int[]> mosaics = new ConcurrentHashMap<>();
    private final Set<String> coverLoading = ConcurrentHashMap.newKeySet();
    private final Set<String> coverFailed = ConcurrentHashMap.newKeySet();

    public SpotifyClient(SpotifyAuth auth) {
        this.auth = auth;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::poll, 1, 2, TimeUnit.SECONDS);
    }

    public Snapshot snapshot() { return snapshot; }

    public List<Playlist> playlists() { return playlists; }

    public String playlistsError() { return playlistsError; }

    public List<Item> items(String playlistId) { return items.get(playlistId); }

    public String itemError(String playlistId) { return itemErrors.getOrDefault(playlistId, ""); }

    // ------------------------------------------------------------- HTTP

    private HttpResponse<String> send(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                .header("Authorization", "Bearer " + auth.accessToken())
                .timeout(Duration.ofSeconds(10));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String errText(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ------------------------------------------------------------ polling

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

            long now = System.currentTimeMillis();
            long every = playlists.isEmpty() ? 8_000 : 120_000;
            if (now - lastPlaylistTry > every) {
                lastPlaylistTry = now;
                exec.submit(this::loadPlaylists);
            }

            HttpResponse<String> r = send("GET", "/me/player", null);
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
                snapshot = Snapshot.error(errText(e));
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
        String uri = str(item, "uri");
        String artists = artistsOf(item);

        String cover = "";
        JsonObject imgHolder = item.has("album") && item.get("album").isJsonObject() ? item.getAsJsonObject("album")
                : item.has("images") ? item : null;
        if (imgHolder != null) cover = lastImage(imgHolder);

        String device = "";
        int volume = 0;
        if (o.has("device") && o.get("device").isJsonObject()) {
            JsonObject d = o.getAsJsonObject("device");
            device = str(d, "name");
            volume = (int) lng(d, "volume_percent");
        }

        boolean playing = bool(o, "is_playing");
        boolean shuffle = bool(o, "shuffle_state");
        String repeat = str(o, "repeat_state");
        if (repeat.isEmpty()) repeat = "off";

        snapshot = new Snapshot(Status.ACTIVE, playing, new Track(title, artists, cover, duration, uri),
                lng(o, "progress_ms"), System.currentTimeMillis(), device, volume, shuffle, repeat, "");
    }

    private static String artistsOf(JsonObject item) {
        StringBuilder artists = new StringBuilder();
        if (item.has("artists") && item.get("artists").isJsonArray()) {
            for (JsonElement a : item.getAsJsonArray("artists")) {
                if (!a.isJsonObject()) continue;
                if (artists.length() > 0) artists.append(", ");
                artists.append(str(a.getAsJsonObject(), "name"));
            }
        } else if (item.has("show") && item.get("show").isJsonObject()) {
            artists.append(str(item.getAsJsonObject("show"), "name"));
        }
        return artists.toString();
    }

    private static String lastImage(JsonObject holder) {
        if (holder.has("images") && holder.get("images").isJsonArray()) {
            JsonArray imgs = holder.getAsJsonArray("images");
            if (imgs.size() > 0 && imgs.get(imgs.size() - 1).isJsonObject()) {
                return str(imgs.get(imgs.size() - 1).getAsJsonObject(), "url");
            }
        }
        return "";
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    // ---------------------------------------------------------- playlists

    private void loadPlaylists() {
        try {
            HttpResponse<String> r = send("GET", "/me/playlists?limit=50", null);
            int sc = r.statusCode();
            if (sc == 401 || sc == 403) {
                playlistsError = "Нужен повторный вход: /spotify login";
                return;
            }
            if (sc != 200) {
                playlistsError = "Плейлисты: HTTP " + sc;
                return;
            }
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            List<Playlist> out = new ArrayList<>();
            if (o.has("items") && o.get("items").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("items")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject p = el.getAsJsonObject();
                    String id = str(p, "id");
                    if (id.isEmpty()) continue;
                    String owner = "";
                    if (p.has("owner") && p.get("owner").isJsonObject()) {
                        owner = str(p.getAsJsonObject("owner"), "display_name");
                    }
                    int total = 0;
                    if (p.has("items") && p.get("items").isJsonObject()) {
                        total = (int) lng(p.getAsJsonObject("items"), "total");
                    } else if (p.has("tracks") && p.get("tracks").isJsonObject()) {
                        total = (int) lng(p.getAsJsonObject("tracks"), "total");
                    }
                    out.add(new Playlist(id, str(p, "name"), owner, total, lastImage(p), str(p, "uri")));
                }
            }
            playlists = out;
            playlistsError = "";
        } catch (Exception e) {
            playlistsError = "Плейлисты: " + errText(e);
        }
    }

    public void requestItems(String playlistId, boolean force) {
        long now = System.currentTimeMillis();
        Long t = itemsTime.get(playlistId);
        if (!force && t != null && now - t < 60_000) return;
        if (!itemsLoading.add(playlistId)) return;
        exec.submit(() -> {
            try {
                HttpResponse<String> r = send("GET", "/playlists/" + playlistId + "/items?limit=100", null);
                int sc = r.statusCode();
                if (sc == 403 || sc == 404) {
                    itemErrors.put(playlistId, "Spotify не отдаёт треки этого плейлиста");
                    itemsTime.put(playlistId, System.currentTimeMillis());
                    return;
                }
                if (sc != 200) {
                    itemErrors.put(playlistId, "Треки: HTTP " + sc);
                    return;
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                List<Item> out = new ArrayList<>();
                if (o.has("items") && o.get("items").isJsonArray()) {
                    for (JsonElement el : o.getAsJsonArray("items")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject entry = el.getAsJsonObject();
                        JsonElement inner = entry.get("item");
                        if (inner == null || inner.isJsonNull()) inner = entry.get("track");
                        if (inner == null || !inner.isJsonObject()) continue;
                        JsonObject tr = inner.getAsJsonObject();
                        String uri = str(tr, "uri");
                        if (uri.isEmpty()) continue;
                        out.add(new Item(str(tr, "name"), artistsOf(tr), lng(tr, "duration_ms"), uri));
                    }
                }
                items.put(playlistId, out);
                itemErrors.remove(playlistId);
                itemsTime.put(playlistId, System.currentTimeMillis());
            } catch (Exception e) {
                itemErrors.put(playlistId, "Треки: " + errText(e));
            } finally {
                itemsLoading.remove(playlistId);
            }
        });
    }

    // ------------------------------------------------------------- covers

    /** Мозаика обложки: grid*grid ячеек, по 2 цвета (две грани) на ячейку. null, пока обложка грузится. */
    public int[] mosaic(String url, int grid) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "|" + grid;
        int[] m = mosaics.get(key);
        if (m != null) return m;
        int[] raw = rawCache.get(url);
        if (raw == null) {
            if (!coverFailed.contains(url) && coverLoading.add(url)) {
                exec.submit(() -> loadRaw(url));
            }
            return null;
        }
        m = buildMosaic(raw, grid);
        mosaics.put(key, m);
        return m;
    }

    private void loadRaw(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            int[] raw = decodeNative(r.body());
            if (raw == null) raw = decodeAwt(r.body());
            if (raw != null) {
                rawCache.put(url, raw);
            } else {
                coverFailed.add(url);
            }
        } catch (Throwable e) {
            LOG.warn("cover load failed: {}", url, e);
            coverFailed.add(url);
        } finally {
            coverLoading.remove(url);
        }
    }

    private static int[] decodeNative(byte[] bytes) {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes))) {
            return downsample(img.getWidth(), img.getHeight(), img::getColorArgb);
        } catch (Throwable e) {
            LOG.warn("NativeImage decode failed", e);
            return null;
        }
    }

    private static int[] decodeAwt(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            return downsample(img.getWidth(), img.getHeight(), img::getRGB);
        } catch (Throwable e) {
            LOG.warn("ImageIO decode failed", e);
            return null;
        }
    }

    private static int[] downsample(int w, int h, IntBinaryOperator px) {
        int[] out = new int[RAW * RAW];
        for (int ry = 0; ry < RAW; ry++) {
            for (int rx = 0; rx < RAW; rx++) {
                int x0 = rx * w / RAW, x1 = Math.max(x0 + 1, (rx + 1) * w / RAW);
                int y0 = ry * h / RAW, y1 = Math.max(y0 + 1, (ry + 1) * h / RAW);
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = y0; y < Math.min(y1, h); y++) {
                    for (int x = x0; x < Math.min(x1, w); x++) {
                        int c = px.applyAsInt(x, y);
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                out[ry * RAW + rx] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return out;
    }

    private static int[] buildMosaic(int[] raw, int grid) {
        int k = RAW / grid;
        int[] out = new int[grid * grid * 2];
        for (int cy = 0; cy < grid; cy++) {
            for (int cx = 0; cx < grid; cx++) {
                long[] lo = new long[4], hi = new long[4];
                for (int y = 0; y < k; y++) {
                    for (int x = 0; x < k; x++) {
                        int c = raw[(cy * k + y) * RAW + (cx * k + x)];
                        long[] a = x <= y ? lo : hi;
                        a[0] += (c >> 16) & 0xFF;
                        a[1] += (c >> 8) & 0xFF;
                        a[2] += c & 0xFF;
                        a[3]++;
                    }
                }
                int i = (cy * grid + cx) * 2;
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

    public void play(Consumer<String> err) { control("PUT", "/me/player/play", null, err); }

    public void pause(Consumer<String> err) { control("PUT", "/me/player/pause", null, err); }

    public void next(Consumer<String> err) { control("POST", "/me/player/next", null, err); }

    public void previous(Consumer<String> err) { control("POST", "/me/player/previous", null, err); }

    public void volume(int percent, Consumer<String> err) {
        control("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)), null, err);
    }

    public void seek(long ms, Consumer<String> err) {
        control("PUT", "/me/player/seek?position_ms=" + Math.max(0, ms), null, err);
    }

    public void shuffle(boolean on, Consumer<String> err) {
        control("PUT", "/me/player/shuffle?state=" + on, null, err);
    }

    public void repeat(String mode, Consumer<String> err) {
        control("PUT", "/me/player/repeat?state=" + mode, null, err);
    }

    /** Играть плейлист целиком или с конкретного трека (trackUri может быть пустым). */
    public void playContext(String contextUri, String trackUri, Consumer<String> err) {
        JsonObject body = new JsonObject();
        body.addProperty("context_uri", contextUri);
        if (trackUri != null && !trackUri.isEmpty()) {
            JsonObject off = new JsonObject();
            off.addProperty("uri", trackUri);
            body.add("offset", off);
        }
        control("PUT", "/me/player/play", body.toString(), err);
    }

    private void control(String method, String path, String body, Consumer<String> err) {
        exec.submit(() -> {
            try {
                if (!auth.isLoggedIn()) {
                    err.accept("Сначала войди: /spotify login");
                    return;
                }
                HttpResponse<String> r = send(method, path, body);
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
                err.accept("Ошибка: " + errText(e));
            }
        });
    }
}
