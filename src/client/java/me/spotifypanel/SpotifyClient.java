package me.spotifypanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

/** Spotify Web API: состояние плеера, плейлисты, треки, обложки и команды управления. */
public final class SpotifyClient {
    private static final Logger LOG = LoggerFactory.getLogger("spotifypanel");
    public static final int RAW = 64; // сторона текстуры обложки (64x64)
    public static final String LIKED = "liked";

    private static final int O_PLAY = 1, O_SHUF = 2, O_REP = 4, O_VOL = 8, O_POS = 16;

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
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "spotifypanel");
        t.setDaemon(true);
        return t;
    });
    // отдельный поток для команд — они не должны стоять в очереди за загрузкой обложек
    private final ExecutorService ctl = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotifypanel-ctl");
        t.setDaemon(true);
        return t;
    });

    private volatile Snapshot snapshot = Snapshot.of(Status.LOGGED_OUT);
    private volatile long optUntil = 0;
    private volatile int optMask = 0;
    private volatile boolean scopeWarned = false;

    // плейлисты и треки
    private static final Playlist LIKED_ENTRY = new Playlist(LIKED, "Любимые треки", "", 0, "", "");
    private volatile List<Playlist> playlists = List.of(LIKED_ENTRY);
    private volatile String playlistsError = "";
    private volatile long lastPlaylistTry = 0;
    private final Map<String, List<Item>> items = new ConcurrentHashMap<>();
    private final Map<String, String> itemErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> itemsTime = new ConcurrentHashMap<>();
    private final Set<String> itemsLoading = ConcurrentHashMap.newKeySet();

    // обложки
    private final Map<String, int[]> rawCache = new ConcurrentHashMap<>();
    private final Map<String, Identifier> textures = new ConcurrentHashMap<>();
    private final Set<String> coverLoading = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> coverTries = new ConcurrentHashMap<>();
    private final Map<String, Long> coverRetryAt = new ConcurrentHashMap<>();
    private final Set<String> texFailed = ConcurrentHashMap.newKeySet();
    private volatile boolean coverErrorShown = false;

    public SpotifyClient(SpotifyAuth auth) {
        this.auth = auth;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::poll, 1, 1500, TimeUnit.MILLISECONDS);
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

    /** Достаёт текст ошибки из ответа Spotify: {"error":{"status":403,"message":"..."}} */
    private static String apiError(int sc, String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error") && o.get("error").isJsonObject()) {
                String m = str(o.getAsJsonObject("error"), "message");
                if (!m.isEmpty()) return "HTTP " + sc + ": " + m;
            }
        } catch (Exception ignored) {
        }
        return "HTTP " + sc;
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
            if ((!auth.hasScope("playlist-read-private") || !auth.hasScope("user-library-read")) && !scopeWarned) {
                scopeWarned = true;
                SpotifyPanelMod.say("Для плейлистов нужен повторный вход: /spotify login");
            }

            long now = System.currentTimeMillis();
            long every = playlists.size() <= 1 ? 8_000 : 120_000;
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
                snapshot = Snapshot.error(apiError(sc, r.body()));
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
        long progress = lng(o, "progress_ms");

        // «оптимистичные» значения после наших команд живут ~1.8 с, чтобы ответ Spotify их не откатил
        long now = System.currentTimeMillis();
        Snapshot cur = snapshot;
        if (now < optUntil && cur.status() == Status.ACTIVE && cur.track() != null && uri.equals(cur.track().uri())) {
            if ((optMask & O_PLAY) != 0) playing = cur.playing();
            if ((optMask & O_SHUF) != 0) shuffle = cur.shuffle();
            if ((optMask & O_REP) != 0) repeat = cur.repeat();
            if ((optMask & O_VOL) != 0) volume = cur.volume();
            if ((optMask & O_POS) != 0) progress = cur.currentProgress();
        }

        snapshot = new Snapshot(Status.ACTIVE, playing, new Track(title, artists, cover, duration, uri),
                progress, now, device, volume, shuffle, repeat, "");
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

    /** Берём самую маленькую картинку шириной от 150 px (иначе самую большую). */
    private static String bestImage(JsonObject holder) {
        if (!holder.has("images") || !holder.get("images").isJsonArray()) return "";
        String best = "";
        long bestW = Long.MAX_VALUE;
        String biggest = "";
        long biggestW = -1;
        for (JsonElement el : holder.getAsJsonArray("images")) {
            if (!el.isJsonObject()) continue;
            JsonObject im = el.getAsJsonObject();
            String url = str(im, "url");
            if (url.isEmpty()) continue;
            long w = im.has("width") && !im.get("width").isJsonNull() ? im.get("width").getAsLong() : 640;
            if (w >= 150 && w < bestW) { bestW = w; best = url; }
            if (w > biggestW) { biggestW = w; biggest = url; }
        }
        return best.isEmpty() ? biggest : best;
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
            if (!auth.hasScope("playlist-read-private")) {
                playlistsError = "Войди заново: /spotify login";
                return;
            }
            HttpResponse<String> r = send("GET", "/me/playlists?limit=50", null);
            int sc = r.statusCode();
            if (sc != 200) {
                playlistsError = apiError(sc, r.body());
                LOG.warn("playlists failed: {} {}", sc, r.body());
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
                    out.add(new Playlist(id, str(p, "name"), owner, total, bestImage(p), str(p, "uri")));
                }
            }
            out.add(0, LIKED_ENTRY);
            playlists = out;
            playlistsError = out.size() <= 1 ? "Плейлистов пока нет" : "";
        } catch (Exception e) {
            playlistsError = errText(e);
            LOG.warn("playlists exception", e);
        }
    }

    public void requestItems(String playlistId, boolean force) {
        long now = System.currentTimeMillis();
        Long t = itemsTime.get(playlistId);
        if (!force && t != null && now - t < 60_000) return;
        if (!itemsLoading.add(playlistId)) return;
        exec.submit(() -> {
            try {
                boolean liked = LIKED.equals(playlistId);
                if (liked && !auth.hasScope("user-library-read")) {
                    itemErrors.put(playlistId, "Нужен повторный вход: /spotify login");
                    itemsTime.put(playlistId, System.currentTimeMillis());
                    return;
                }
                String path = liked ? "/me/tracks?limit=50" : "/playlists/" + playlistId + "/items?limit=100";
                HttpResponse<String> r = send("GET", path, null);
                int sc = r.statusCode();
                if (sc != 200) {
                    LOG.warn("items failed: {} {}", sc, r.body());
                    itemErrors.put(playlistId, liked
                            ? (sc == 403 ? "Нужен повторный вход: /spotify login" : apiError(sc, r.body()))
                            : (sc == 403 || sc == 404 ? "Spotify не отдаёт треки этого плейлиста" : apiError(sc, r.body())));
                    itemsTime.put(playlistId, System.currentTimeMillis());
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
                itemErrors.put(playlistId, errText(e));
                LOG.warn("items exception", e);
            } finally {
                itemsLoading.remove(playlistId);
            }
        });
    }

    // ------------------------------------------------------------- covers

    /** Текстура обложки (вызывать из потока рендера). null, пока обложка грузится или не удалась. */
    public Identifier coverTexture(String url) {
        if (url == null || url.isEmpty()) return null;
        Identifier id = textures.get(url);
        if (id != null) return id;
        int[] raw = rawCache.get(url);
        if (raw == null) {
            long now = System.currentTimeMillis();
            Long next = coverRetryAt.get(url);
            if ((next == null || now >= next) && coverLoading.add(url)) {
                exec.submit(() -> loadRaw(url));
            }
            return null;
        }
        if (texFailed.contains(url)) return null;
        try {
            NativeImage img = new NativeImage(RAW, RAW, false);
            for (int y = 0; y < RAW; y++) {
                for (int x = 0; x < RAW; x++) {
                    img.setColorArgb(x, y, raw[y * RAW + x]);
                }
            }
            NativeImageBackedTexture tex = makeTexture(img);
            Identifier nid = Identifier.of("spotify-panel", "cover/" + Integer.toHexString(url.hashCode()) + "x" + url.length());
            MinecraftClient.getInstance().getTextureManager().registerTexture(nid, tex);
            textures.put(url, nid);
            return nid;
        } catch (Throwable e) {
            LOG.warn("texture create failed", e);
            texFailed.add(url);
            reportCoverError("текстура: " + errText(e));
            return null;
        }
    }

    /** Средний цвет обложки (для градиента и запасного варианта). 0, если ещё нет. */
    public int coverColor(String url) {
        int[] raw = url == null ? null : rawCache.get(url);
        if (raw == null) return 0;
        long r = 0, g = 0, b = 0;
        for (int c : raw) {
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
        }
        int n = raw.length;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    /** Конструктор NativeImageBackedTexture менялся между версиями — подбираем подходящий. */
    private static NativeImageBackedTexture makeTexture(NativeImage img) throws Exception {
        for (Constructor<?> c : NativeImageBackedTexture.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[1] == NativeImage.class) {
                if (p[0] == String.class) {
                    return (NativeImageBackedTexture) c.newInstance("spotifypanel", img);
                }
                if (p[0] == Supplier.class) {
                    Supplier<String> name = () -> "spotifypanel";
                    return (NativeImageBackedTexture) c.newInstance(name, img);
                }
            }
            if (p.length == 1 && p[0] == NativeImage.class) {
                return (NativeImageBackedTexture) c.newInstance(img);
            }
        }
        throw new IllegalStateException("нет подходящего конструктора NativeImageBackedTexture");
    }

    private void loadRaw(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(12)).GET().build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            int[] raw = decodeNative(r.body());
            if (raw == null) raw = decodeAwt(r.body());
            if (raw != null) {
                rawCache.put(url, raw);
                coverRetryAt.remove(url);
            } else {
                scheduleCoverRetry(url);
                reportCoverError("не удалось декодировать картинку");
            }
        } catch (Throwable e) {
            LOG.warn("cover load failed: {}", url, e);
            scheduleCoverRetry(url);
            reportCoverError("загрузка: " + errText(e));
        } finally {
            coverLoading.remove(url);
        }
    }

    private void scheduleCoverRetry(String url) {
        int n = coverTries.merge(url, 1, Integer::sum);
        coverRetryAt.put(url, System.currentTimeMillis() + Math.min(30_000L, 1500L * n));
    }

    private void reportCoverError(String msg) {
        if (!coverErrorShown) {
            coverErrorShown = true;
            SpotifyPanelMod.say("Обложки не грузятся (" + msg + "). Подробности в logs/latest.log");
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

    // ------------------------------------------------------------ controls

    private void optimistic(int mask, java.util.function.UnaryOperator<Snapshot> f) {
        Snapshot s = snapshot;
        if (s.status() != Status.ACTIVE || s.track() == null) return;
        long now = System.currentTimeMillis();
        optMask = (now < optUntil ? optMask : 0) | mask;
        optUntil = now + 1800;
        snapshot = f.apply(s);
    }

    public void togglePlay(Consumer<String> err) {
        boolean wasPlaying = snapshot.playing();
        optimistic(O_PLAY | O_POS, s -> new Snapshot(s.status(), !s.playing(), s.track(), s.currentProgress(),
                System.currentTimeMillis(), s.device(), s.volume(), s.shuffle(), s.repeat(), s.error()));
        if (wasPlaying) control("PUT", "/me/player/pause", null, err);
        else control("PUT", "/me/player/play", null, err);
    }

    public void play(Consumer<String> err) {
        optimistic(O_PLAY | O_POS, s -> new Snapshot(s.status(), true, s.track(), s.currentProgress(),
                System.currentTimeMillis(), s.device(), s.volume(), s.shuffle(), s.repeat(), s.error()));
        control("PUT", "/me/player/play", null, err);
    }

    public void pause(Consumer<String> err) {
        optimistic(O_PLAY | O_POS, s -> new Snapshot(s.status(), false, s.track(), s.currentProgress(),
                System.currentTimeMillis(), s.device(), s.volume(), s.shuffle(), s.repeat(), s.error()));
        control("PUT", "/me/player/pause", null, err);
    }

    public void next(Consumer<String> err) { control("POST", "/me/player/next", null, err); }

    public void previous(Consumer<String> err) { control("POST", "/me/player/previous", null, err); }

    public void volume(int percent, Consumer<String> err) {
        int v = Math.max(0, Math.min(100, percent));
        optimistic(O_VOL, s -> new Snapshot(s.status(), s.playing(), s.track(), s.progressMs(), s.fetchedAtMs(),
                s.device(), v, s.shuffle(), s.repeat(), s.error()));
        control("PUT", "/me/player/volume?volume_percent=" + v, null, err);
    }

    public void seek(long ms, Consumer<String> err) {
        long p = Math.max(0, ms);
        optimistic(O_POS, s -> new Snapshot(s.status(), s.playing(), s.track(), p, System.currentTimeMillis(),
                s.device(), s.volume(), s.shuffle(), s.repeat(), s.error()));
        control("PUT", "/me/player/seek?position_ms=" + p, null, err);
    }

    public void shuffle(boolean on, Consumer<String> err) {
        optimistic(O_SHUF, s -> new Snapshot(s.status(), s.playing(), s.track(), s.progressMs(), s.fetchedAtMs(),
                s.device(), s.volume(), on, s.repeat(), s.error()));
        control("PUT", "/me/player/shuffle?state=" + on, null, err);
    }

    public void repeat(String mode, Consumer<String> err) {
        optimistic(O_REP, s -> new Snapshot(s.status(), s.playing(), s.track(), s.progressMs(), s.fetchedAtMs(),
                s.device(), s.volume(), s.shuffle(), mode, s.error()));
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

    /** Играть набор треков (для «Любимых», у которых нет контекста), начиная с trackUri. */
    public void playUris(List<String> uris, String trackUri, Consumer<String> err) {
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String u : uris) arr.add(u);
        body.add("uris", arr);
        if (trackUri != null && !trackUri.isEmpty()) {
            JsonObject off = new JsonObject();
            off.addProperty("uri", trackUri);
            body.add("offset", off);
        }
        control("PUT", "/me/player/play", body.toString(), err);
    }

    private void control(String method, String path, String body, Consumer<String> err) {
        ctl.submit(() -> {
            try {
                if (!auth.isLoggedIn()) {
                    err.accept("Сначала войди: /spotify login");
                    return;
                }
                HttpResponse<String> r = send(method, path, body);
                int sc = r.statusCode();
                if (sc >= 300) {
                    optUntil = 0; // откатываем «оптимистичные» значения
                    exec.submit(this::poll);
                    if (sc == 404) err.accept("Нет активного устройства: открой Spotify и запусти любой трек.");
                    else if (sc == 403) err.accept("Spotify отказал (нужен Premium или действие недоступно).");
                    else err.accept("Spotify: " + apiError(sc, r.body()));
                } else {
                    exec.schedule(this::poll, 350, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 1300, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                err.accept("Ошибка: " + errText(e));
            }
        });
    }
}
