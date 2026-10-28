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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.UnaryOperator;

/** Spotify Web API: состояние плеера, плейлисты, треки, обложки и команды управления. */
public final class SpotifyClient {
    private static final Logger LOG = LoggerFactory.getLogger("spotifypanel");
    private static final int RAW = 256; // обложка хранится как 256x256, из неё считаются картинки любого размера

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

        Snapshot withPlaying(boolean p) {
            return new Snapshot(status, p, track, currentProgress(), System.currentTimeMillis(), device, volume, shuffle, repeat, error);
        }

        Snapshot withProgress(long ms) {
            return new Snapshot(status, playing, track, ms, System.currentTimeMillis(), device, volume, shuffle, repeat, error);
        }

        Snapshot withVolume(int v) {
            return new Snapshot(status, playing, track, progressMs, fetchedAtMs, device, v, shuffle, repeat, error);
        }

        Snapshot withShuffle(boolean on) {
            return new Snapshot(status, playing, track, progressMs, fetchedAtMs, device, volume, on, repeat, error);
        }

        Snapshot withRepeat(String mode) {
            return new Snapshot(status, playing, track, progressMs, fetchedAtMs, device, volume, shuffle, mode, error);
        }
    }

    private final SpotifyAuth auth;
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "spotifypanel");
        t.setDaemon(true);
        return t;
    });

    /** Команды управления идут строго по очереди, чтобы громкость/перемотка не приходили в перепутанном порядке. */
    private final ExecutorService ctl = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotifypanel-ctl");
        t.setDaemon(true);
        return t;
    });
    private final Object pollLock = new Object();
    private final AtomicBoolean pollPending = new AtomicBoolean();
    private final AtomicInteger pendingVolume = new AtomicInteger(-1);
    /** До этого момента ответы опроса игнорируются: на экране то, что игрок только что нажал (без «отскока»). */
    private volatile long ignorePollUntil = 0;
    private volatile long lastLocalAt = 0;

    private volatile Snapshot snapshot = Snapshot.of(Status.LOGGED_OUT);

    // плейлисты и треки
    private volatile List<Playlist> playlists = List.of();
    private volatile String playlistsError = "";
    private volatile long nextPlaylistTry = 0;
    private volatile long retryAfterMs = 0;
    private volatile int playlistFails = 0;
    private final java.util.concurrent.atomic.AtomicBoolean playlistsLoading = new java.util.concurrent.atomic.AtomicBoolean();
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
        return Net.http().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String errText(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ------------------------------------------------------------ polling

    private void poll() {
        synchronized (pollLock) {
            pollInner();
        }
    }

    private void pollInner() {
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
            if (now >= nextPlaylistTry) tryLoadPlaylists();

            long started = System.currentTimeMillis();
            HttpResponse<String> r = send("GET", "/me/player", null);
            int sc = r.statusCode();
            if ((sc == 200 || sc == 204) && (started < lastLocalAt || System.currentTimeMillis() < ignorePollUntil)) {
                return; // ответ устарел относительно только что нажатой кнопки
            }
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
        if (imgHolder != null) cover = bestImage(imgHolder);

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

        // плавная полоска прогресса: не даём ей прыгать на каждом опросе
        long prog = lng(o, "progress_ms");
        Snapshot prev = snapshot;
        if (prev.status() == Status.ACTIVE && prev.playing() && playing && prev.track() != null
                && !uri.isEmpty() && uri.equals(prev.track().uri())) {
            long predicted = prev.currentProgress();
            long diff = prog - predicted;
            if (Math.abs(diff) < 2500) prog = predicted + diff / 3;
        }

        snapshot = new Snapshot(Status.ACTIVE, playing, new Track(title, artists, cover, duration, uri),
                prog, System.currentTimeMillis(), device, volume, shuffle, repeat, "");
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

    /** Самая маленькая картинка, у которой ширина >= 64 (иначе самая большая). Так 64x64 получается чёткой. */
    private static String bestImage(JsonObject holder) {
        if (!holder.has("images") || !holder.get("images").isJsonArray()) return "";
        String best = "", fallback = "";
        long bestW = Long.MAX_VALUE, fallbackW = -1;
        for (JsonElement e : holder.getAsJsonArray("images")) {
            if (!e.isJsonObject()) continue;
            JsonObject im = e.getAsJsonObject();
            String url = str(im, "url");
            if (url.isEmpty()) continue;
            long w = im.has("width") && !im.get("width").isJsonNull() ? lng(im, "width") : 640;
            if (w >= RAW && w < bestW) { bestW = w; best = url; }
            if (w > fallbackW) { fallbackW = w; fallback = url; }
        }
        return best.isEmpty() ? fallback : best;
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

    /** Сбросить таймер и сразу перезагрузить список (после add/remove плейлиста). */
    public void reloadPlaylists() {
        nextPlaylistTry = 0;
        tryLoadPlaylists();
    }

    public int playlistFails() { return playlistFails; }

    /** Один запуск загрузки за раз. Неудача — повтор через 3 с (или Retry-After при 429), успех — обновление раз в 2 минуты. */
    private void tryLoadPlaylists() {
        if (!playlistsLoading.compareAndSet(false, true)) return;
        nextPlaylistTry = Long.MAX_VALUE; // пока идёт запрос, новых не стартуем
        exec.submit(() -> {
            retryAfterMs = 0;
            try {
                loadPlaylists();
            } finally {
                long now = System.currentTimeMillis();
                if (playlistsError.isEmpty()) {
                    playlistFails = 0;
                    nextPlaylistTry = now + 120_000;
                } else {
                    playlistFails++;
                    nextPlaylistTry = now + Math.max(3_000, retryAfterMs);
                }
                playlistsLoading.set(false);
            }
        });
    }

    private static String apiMessage(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error") && o.get("error").isJsonObject()) return str(o.getAsJsonObject("error"), "message");
            if (o.has("error_description")) return str(o, "error_description");
        } catch (Exception ignored) {
        }
        return "";
    }

    private Playlist parsePlaylist(JsonObject p) {
        String id = str(p, "id");
        if (id.isEmpty()) return null;
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
        return new Playlist(id, str(p, "name"), owner, total, bestImage(p), "spotify:playlist:" + id);
    }

    private void playlistsFailed(HttpResponse<String> r) {
        int sc = r.statusCode();
        String msg = apiMessage(r.body());
        String full;
        if (sc == 429) {
            String ra = r.headers().firstValue("Retry-After").orElse("5");
            try { retryAfterMs = Long.parseLong(ra.trim()) * 1000L; } catch (NumberFormatException e) { retryAfterMs = 5_000; }
            full = "Spotify просит подождать (429), повтор автоматически…";
        } else if (sc == 401) {
            full = "Токен не принят, войди заново: /spotify login";
            auth.invalidate();
        } else if (sc == 403) {
            full = "403 от Spotify: " + (msg.isEmpty() ? "нет доступа" : msg)
                    + ". Скорее всего: твой аккаунт не добавлен в User Management приложения на developer.spotify.com, или нужен /spotify logout + /spotify login (обновить права).";
        } else {
            full = "Плейлисты: HTTP " + sc + (msg.isEmpty() ? "" : " " + msg);
        }
        if (!full.equals(playlistsError)) {
            LOG.warn("playlists failed: HTTP {} {}", sc, r.body());
            SpotifyPanelMod.say(full);
        }
        playlistsError = full;
    }

    /** Грузит ВСЕ плейлисты пользователя страницами по 50 (раньше брались только первые 50). */
    private void loadPlaylists() {
        try {
            List<Playlist> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int offset = 0;
            int apiTotal = -1;
            while (offset < 10_000) {
                HttpResponse<String> r = send("GET", "/me/playlists?limit=50&offset=" + offset, null);
                if (r.statusCode() != 200) {
                    playlistsFailed(r);
                    return;
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                int got = 0;
                if (apiTotal < 0) apiTotal = (int) lng(o, "total");
                if (o.has("items") && o.get("items").isJsonArray()) {
                    for (JsonElement el : o.getAsJsonArray("items")) {
                        got++;
                        if (!el.isJsonObject()) continue;
                        Playlist pl = parsePlaylist(el.getAsJsonObject());
                        if (pl != null && seen.add(pl.id())) out.add(pl);
                    }
                }
                boolean hasNext = o.has("next") && !o.get("next").isJsonNull();
                if (!hasNext || got == 0) break;
                offset += got;
            }

            // плейлисты, добавленные по ссылке
            for (String id : new ArrayList<>(SpotifyPanelMod.config.extraPlaylists)) {
                if (!seen.add(id)) continue;
                Playlist pl = null;
                try {
                    HttpResponse<String> pr = send("GET", "/playlists/" + id, null);
                    if (pr.statusCode() == 200) {
                        pl = parsePlaylist(JsonParser.parseString(pr.body()).getAsJsonObject());
                    }
                } catch (Exception ignored) {
                }
                // метаданные недоступны (403/404/сеть) — всё равно оставляем, играть по URI можно
                if (pl == null) pl = new Playlist(id, "Плейлист " + id.substring(0, Math.min(6, id.length())), "", 0, "", "spotify:playlist:" + id);
                out.add(pl);
            }
            int before = playlists.size();
            playlists = out;
            playlistsError = "";
            if (before != out.size()) {
                SpotifyPanelMod.say("Плейлистов в панели: " + out.size() + " (Spotify вернул " + apiTotal
                        + " в твоей медиатеке; Liked Songs, подкасты и сборники самого Spotify API не отдаёт)");
            }
        } catch (Exception e) {
            playlistsError = "Плейлисты: " + errText(e);
        }
    }

    /** Достать ID плейлиста из ссылки open.spotify.com, spotify:playlist:... или голого ID. null — не похоже. */
    public static String parsePlaylistId(String input) {
        if (input == null) return null;
        String v = input.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:playlist[/:])([A-Za-z0-9]{10,40})").matcher(v);
        if (m.find()) return m.group(1);
        if (v.matches("[A-Za-z0-9]{22}")) return v;
        return null;
    }

    public void requestItems(String playlistId, boolean force) {
        long now = System.currentTimeMillis();
        Long t = itemsTime.get(playlistId);
        if (!force && t != null && now - t < 60_000) return;
        if (!itemsLoading.add(playlistId)) return;
        exec.submit(() -> {
            try {
                List<Item> out = new ArrayList<>();
                int offset = 0;
                while (offset < 3000) {
                    HttpResponse<String> r = send("GET", "/playlists/" + playlistId + "/items?limit=50&offset=" + offset, null);
                    int sc = r.statusCode();
                    if (sc != 200) {
                        if (offset > 0) break; // часть треков уже есть — показываем что успели
                        if (sc == 403 || sc == 404) {
                            itemErrors.put(playlistId, "Spotify не отдаёт список треков (403/404): в Development mode чужие плейлисты не читаются. Кнопка ▶ всё равно играет плейлист.");
                            itemsTime.put(playlistId, System.currentTimeMillis());
                        } else {
                            itemErrors.put(playlistId, "Треки: HTTP " + sc);
                        }
                        return;
                    }
                    JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                    int got = 0;
                    if (o.has("items") && o.get("items").isJsonArray()) {
                        for (JsonElement el : o.getAsJsonArray("items")) {
                            got++;
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
                    items.put(playlistId, new ArrayList<>(out)); // список появляется постепенно
                    itemErrors.remove(playlistId);
                    boolean hasNext = o.has("next") && !o.get("next").isJsonNull();
                    if (!hasNext || got == 0) break;
                    offset += got;
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

    /** Обложка, ужатая с исходных 64x64 до size x size (усреднение по площади). null, пока обложка грузится. */
    public int[] pixels(String url, int size) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "|" + size;
        int[] m = mosaics.get(key);
        if (m != null) return m;
        int[] raw = rawCache.get(url);
        if (raw == null) {
            if (!coverFailed.contains(url) && coverLoading.add(url)) {
                exec.submit(() -> loadRaw(url));
            }
            return null;
        }
        m = downsample(RAW, RAW, (x, y) -> raw[y * RAW + x], size);
        mosaics.put(key, m);
        return m;
    }

    private void loadRaw(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> r = Net.http().send(req, HttpResponse.BodyHandlers.ofByteArray());
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
            return downsample(img.getWidth(), img.getHeight(), img::getColorArgb, RAW);
        } catch (Throwable e) {
            LOG.warn("NativeImage decode failed", e);
            return null;
        }
    }

    private static int[] decodeAwt(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            return downsample(img.getWidth(), img.getHeight(), img::getRGB, RAW);
        } catch (Throwable e) {
            LOG.warn("ImageIO decode failed", e);
            return null;
        }
    }

    /** Усреднение по площади до n x n. */
    private static int[] downsample(int w, int h, IntBinaryOperator px, int n) {
        int[] out = new int[n * n];
        for (int ry = 0; ry < n; ry++) {
            for (int rx = 0; rx < n; rx++) {
                int x0 = rx * w / n, x1 = Math.max(x0 + 1, (rx + 1) * w / n);
                int y0 = ry * h / n, y1 = Math.max(y0 + 1, (ry + 1) * h / n);
                long r = 0, g = 0, b = 0, cnt = 0;
                for (int y = y0; y < Math.min(y1, h); y++) {
                    for (int x = x0; x < Math.min(x1, w); x++) {
                        int c = px.applyAsInt(x, y);
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        cnt++;
                    }
                }
                if (cnt == 0) cnt = 1;
                out[ry * n + rx] = 0xFF000000 | ((int) (r / cnt) << 16) | ((int) (g / cnt) << 8) | (int) (b / cnt);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ controls

    /** Мгновенно меняет то, что видит игрок, не дожидаясь ответа Spotify. */
    private void local(UnaryOperator<Snapshot> f, long ignoreMs) {
        Snapshot s = snapshot;
        if (s.status() != Status.ACTIVE) return;
        long now = System.currentTimeMillis();
        snapshot = f.apply(s);
        lastLocalAt = now;
        ignorePollUntil = now + ignoreMs;
    }

    public void togglePlay(Consumer<String> err) {
        boolean was = snapshot.playing();
        local(s -> s.withPlaying(!was), 1500);
        if (was) pause(err); else play(err);
    }

    public void play(Consumer<String> err) { control("PUT", "/me/player/play", null, err); }

    public void pause(Consumer<String> err) { control("PUT", "/me/player/pause", null, err); }

    public void next(Consumer<String> err) { control("POST", "/me/player/next", null, err); }

    public void previous(Consumer<String> err) { control("POST", "/me/player/previous", null, err); }

    /** Можно вызывать хоть каждый кадр при перетаскивании: лишние значения склеиваются, уходит последнее. */
    public void volume(int percent, Consumer<String> err) {
        int v = Math.max(0, Math.min(100, percent));
        local(s -> s.withVolume(v), 1500);
        pendingVolume.set(v);
        ctl.submit(() -> {
            int p = pendingVolume.getAndSet(-1);
            if (p >= 0) doControl("PUT", "/me/player/volume?volume_percent=" + p, null, err);
        });
    }

    public void seek(long ms, Consumer<String> err) {
        long v = Math.max(0, ms);
        local(s -> s.withProgress(v), 2000);
        control("PUT", "/me/player/seek?position_ms=" + v, null, err);
    }

    public void shuffle(boolean on, Consumer<String> err) {
        local(s -> s.withShuffle(on), 1500);
        control("PUT", "/me/player/shuffle?state=" + on, null, err);
    }

    public void repeat(String mode, Consumer<String> err) {
        local(s -> s.withRepeat(mode), 1500);
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
        ctl.submit(() -> doControl(method, path, body, err));
    }

    private void pollOnce(long delayMs) {
        if (pollPending.compareAndSet(false, true)) {
            exec.schedule(() -> {
                pollPending.set(false);
                poll();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void revert() {
        ignorePollUntil = 0;
        lastLocalAt = 0;
        exec.submit(this::poll);
    }

    private void doControl(String method, String path, String body, Consumer<String> err) {
        try {
            if (!auth.isLoggedIn()) {
                err.accept("Сначала войди: /spotify login");
                revert();
                return;
            }
            HttpResponse<String> r = send(method, path, body);
            int sc = r.statusCode();
            if (sc == 404) {
                err.accept("Нет активного устройства: открой Spotify и запусти любой трек.");
                revert();
            } else if (sc == 403) {
                err.accept("Spotify отказал (нужен Premium или действие недоступно).");
                revert();
            } else if (sc >= 300) {
                err.accept("Spotify: HTTP " + sc);
                revert();
            } else {
                long wait = ignorePollUntil - System.currentTimeMillis();
                if (wait > 0) {
                    pollOnce(wait + 100); // подтянуть настоящее состояние, когда «окно игнора» кончится
                } else {
                    // смена трека у Spotify занимает время — спрашиваем два раза
                    exec.schedule(this::poll, 400, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 1300, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            err.accept("Ошибка: " + errText(e));
            revert();
        }
    }
}
