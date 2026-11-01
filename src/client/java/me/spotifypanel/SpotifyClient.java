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

/** Spotify Web API: состояние плеера, медиатека разных типов, треки, обложки и команды управления. */
public final class SpotifyClient {
    private static final Logger LOG = LoggerFactory.getLogger("spotifypanel");
    private static final int RAW = 256; // обложка хранится как 256x256, из неё считаются картинки любого размера

    public enum Status { NO_CLIENT_ID, LOGGED_OUT, IDLE, ACTIVE, ERROR }

    public record Track(String title, String artists, String coverUrl, long durationMs, String uri) {}

    public record Playlist(String id, String name, String owner, int total, String coverUrl, String uri) {}

    /** Единый элемент медиатеки: плейлист, альбом, артист, коллекция треков, подкаст или аудиокнига. */
    public record LibraryEntry(String id, String name, String owner, int total, String coverUrl, String uri, String kind) {
        public String key() { return "playlist".equals(kind) ? id : kind + ":" + id; }
        public String kindLabel() {
            return switch (kind) {
                case "playlist" -> "ПЛЕЙЛИСТ";
                case "album" -> "АЛЬБОМ";
                case "artist" -> "ИСПОЛНИТЕЛЬ";
                case "saved_tracks" -> "ЛЮБИМЫЕ ТРЕКИ";
                case "show" -> "ПОДКАСТ";
                case "saved_episodes" -> "СОХРАНЁННЫЕ ЭПИЗОДЫ";
                case "audiobook" -> "АУДИОКНИГА";
                default -> "МЕДИАТЕКА";
            };
        }
        public String iconMark() {
            return switch (kind) {
                case "playlist" -> "≡";
                case "album" -> "▣";
                case "artist" -> "♪";
                case "saved_tracks" -> "♥";
                case "show", "saved_episodes" -> "◖";
                case "audiobook" -> "▤";
                default -> "•";
            };
        }
    }

    public record Item(String title, String artists, long durationMs, String uri, String albumUri) {
        public Item(String title, String artists, long durationMs, String uri) {
            this(title, artists, durationMs, uri, "");
        }
    }

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

    // All Spotify Web API calls share one request gate. This prevents the player poll,
    // library refresh and user controls from bursting the same app-wide rate limit.
    private final Object apiRequestLock = new Object();
    private long nextApiRequestAtMs;
    private long apiCooldownUntilMs;
    private static final long MIN_API_REQUEST_GAP_MS = 900;
    private static final long DEFAULT_RETRY_AFTER_MS = 15_000;
    private static final long MAX_RETRY_AFTER_MS = 5 * 60_000L;

    /** Команды управления идут строго по очереди, чтобы громкость/перемотка не приходили в перепутанном порядке. */
    private final ExecutorService ctl = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotifypanel-ctl");
        t.setDaemon(true);
        return t;
    });
    private final Object pollLock = new Object();
    private final AtomicBoolean pollPending = new AtomicBoolean();
    private final AtomicInteger pendingVolume = new AtomicInteger(-1);
    private final AtomicBoolean volumeFlushScheduled = new AtomicBoolean();
    private volatile long lastVolumeRequestAtMs;
    private static final long MIN_VOLUME_REQUEST_INTERVAL_MS = 120;
    private static final int MAX_VOLUME_RAMP_STEP = 8;
    private final AtomicInteger volumeRampValue = new AtomicInteger(-1);
    /** До этого момента ответы опроса игнорируются: на экране то, что игрок только что нажал (без «отскока»). */
    private volatile long ignorePollUntil = 0;
    private volatile long lastLocalAt = 0;

    private volatile Snapshot snapshot = Snapshot.of(Status.LOGGED_OUT);

    // плейлисты и треки
    // playlists() оставлен для старого HUD-кода; WorldPanel использует общую library().
    private volatile List<Playlist> playlists = List.of();
    private volatile List<LibraryEntry> library = List.of();
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
    private final Map<String, Integer> coverAccents = new ConcurrentHashMap<>();
    private final Set<String> coverLoading = ConcurrentHashMap.newKeySet();
    private final Set<String> coverFailed = ConcurrentHashMap.newKeySet();

    public SpotifyClient(SpotifyAuth auth) {
        this.auth = auth;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::poll, 1, 4, TimeUnit.SECONDS);
    }

    public Snapshot snapshot() { return snapshot; }

    public List<Playlist> playlists() { return playlists; }

    public List<LibraryEntry> library() { return library; }

    public String playlistsError() { return playlistsError; }

    public String libraryError() { return playlistsError; }

    public List<Item> items(String key) { return items.get(key); }

    public List<Item> items(LibraryEntry entry) { return entry == null ? null : items.get(entry.key()); }

    public String itemError(String key) { return itemErrors.getOrDefault(key, ""); }

    public String itemError(LibraryEntry entry) { return entry == null ? "" : itemErrors.getOrDefault(entry.key(), ""); }

    // ------------------------------------------------------------- HTTP

    private HttpResponse<String> send(String method, String path, String jsonBody) throws Exception {
        synchronized (apiRequestLock) {
            long now = System.currentTimeMillis();
            long waitUntil = Math.max(nextApiRequestAtMs, apiCooldownUntilMs);
            long waitMs = waitUntil - now;
            if (waitMs > 0) Thread.sleep(waitMs);

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                    .header("Authorization", "Bearer " + auth.accessToken())
                    .timeout(Duration.ofSeconds(15));
            if (jsonBody == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            }

            HttpResponse<String> response;
            try {
                response = Net.http().send(b.build(), HttpResponse.BodyHandlers.ofString());
            } finally {
                // Even failed requests leave a small gap before the next API call.
                nextApiRequestAtMs = System.currentTimeMillis() + MIN_API_REQUEST_GAP_MS;
            }

            if (response.statusCode() == 429) {
                long retryMs = parseRetryAfterMillis(response);
                long retryUntil = System.currentTimeMillis() + retryMs;
                apiCooldownUntilMs = Math.max(apiCooldownUntilMs, retryUntil);
                retryAfterMs = Math.max(retryAfterMs, retryMs);
                LOG.warn("Spotify API rate limit (429) on {}. Pausing all Web API requests for {} seconds (Retry-After).",
                        path, Math.max(1, retryMs / 1000));
            }
            return response;
        }
    }

    private static long parseRetryAfterMillis(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (!value.isEmpty()) {
            try {
                double seconds = Double.parseDouble(value);
                long millis = (long) Math.ceil(seconds * 1000.0);
                return Math.max(1_000L, Math.min(MAX_RETRY_AFTER_MS, millis));
            } catch (NumberFormatException ignored) {
                // Spotify normally sends a number of seconds. Fall back safely if
                // a proxy or future API version returns an unexpected value.
            }
        }
        return DEFAULT_RETRY_AFTER_MS;
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

            // The library refresh makes a sequence of requests. Pause the recurring
            // player poll until it finishes so both jobs do not compete for the quota.
            if (playlistsLoading.get()) return;

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
        if (pendingVolume.get() < 0 && !volumeFlushScheduled.get()) {
            volumeRampValue.set(Math.max(0, Math.min(100, volume)));
        }
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

    // ---------------------------------------------------------- Spotify library

    /** Перезагрузить все разделы медиатеки после авторизации или изменения списка. */
    public void reloadPlaylists() {
        nextPlaylistTry = 0;
        tryLoadPlaylists();
    }

    public int playlistFails() { return playlistFails; }

    private void tryLoadPlaylists() {
        if (!playlistsLoading.compareAndSet(false, true)) return;
        nextPlaylistTry = Long.MAX_VALUE;
        exec.submit(() -> {
            retryAfterMs = 0;
            try {
                loadLibrary();
            } finally {
                long now = System.currentTimeMillis();
                if (playlistsError.isEmpty()) {
                    playlistFails = 0;
                    nextPlaylistTry = now + 120_000;
                } else {
                    playlistFails++;
                    nextPlaylistTry = now + Math.max(60_000, retryAfterMs);
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
        } catch (Exception ignored) {}
        return "";
    }

    private record PageData(List<JsonObject> objects, int total) {}

    /**
     * Offset pagination for Spotify library endpoints.
     *
     * Do not use the `next` URL as the stop condition: since Spotify's February 2026
     * API migration, /me/playlists can return a broken next URL (and in some responses
     * it can be null even though `total` says more results exist). Build every page
     * from /me/playlists + offset ourselves and use `total` as the authoritative bound.
     */
    private PageData fetchOffsetPages(String endpoint) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        Set<String> seenPages = new HashSet<>();
        int offset = 0;
        int total = -1;
        while (offset < 1000) {
            String separator = endpoint.contains("?") ? "&" : "?";
            HttpResponse<String> r = send("GET", endpoint + separator + "limit=50&offset=" + offset, null);
            if (r.statusCode() != 200) {
                if (r.statusCode() == 429) {
                    String ra = r.headers().firstValue("Retry-After").orElse("5");
                    try { retryAfterMs = Math.max(retryAfterMs, Long.parseLong(ra.trim()) * 1000L); }
                    catch (NumberFormatException ignored) { retryAfterMs = Math.max(retryAfterMs, 5_000); }
                }
                if (r.statusCode() == 401) auth.invalidate();
                throw new IOException("HTTP " + r.statusCode() + (apiMessage(r.body()).isEmpty() ? "" : ": " + apiMessage(r.body())));
            }
            JsonObject page = JsonParser.parseString(r.body()).getAsJsonObject();
            if (total < 0 && page.has("total") && !page.get("total").isJsonNull()) {
                total = Math.max(0, (int) lng(page, "total"));
            }
            JsonArray arr = page.has("items") && page.get("items").isJsonArray()
                    ? page.getAsJsonArray("items") : new JsonArray();
            int got = arr.size();
            if (got == 0) {
                if (total > offset) LOG.warn("Spotify pagination returned an empty page for {} at offset {} (reported total {}).", endpoint, offset, total);
                break;
            }

            // Guard against an endpoint/proxy that ignores the offset and repeats page 1.
            String signature = arr.toString();
            if (!seenPages.add(signature)) {
                LOG.warn("Spotify pagination repeated a page for {} at offset {}; stopping to avoid duplicates.", endpoint, offset);
                break;
            }
            for (JsonElement el : arr) {
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
            }

            offset += got;
            if (total >= 0) {
                if (offset >= total) break;
                // Continue even when `next` is null: total is more reliable during
                // the current /me/playlists migration than the returned next URL.
            } else {
                boolean hasNext = page.has("next") && !page.get("next").isJsonNull();
                if (!hasNext) break;
            }
        }
        int reportedTotal = total < 0 ? out.size() : total;
        if (reportedTotal > out.size()) {
            LOG.warn("Spotify returned {} of {} items for {}.", out.size(), reportedTotal, endpoint);
        }
        return new PageData(out, reportedTotal);
    }

    private PageData fetchFollowedArtists() throws Exception {
        List<JsonObject> out = new ArrayList<>();
        String after = "";
        int total = 0;
        boolean first = true;
        while (out.size() < 1000) {
            String path = "/me/following?type=artist&limit=50" + (after.isEmpty() ? "" : "&after=" + after);
            HttpResponse<String> r = send("GET", path, null);
            if (r.statusCode() != 200) {
                if (r.statusCode() == 429) {
                    String ra = r.headers().firstValue("Retry-After").orElse("5");
                    try { retryAfterMs = Math.max(retryAfterMs, Long.parseLong(ra.trim()) * 1000L); }
                    catch (NumberFormatException ignored) { retryAfterMs = Math.max(retryAfterMs, 5_000); }
                }
                if (r.statusCode() == 401) auth.invalidate();
                throw new IOException("HTTP " + r.statusCode() + (apiMessage(r.body()).isEmpty() ? "" : ": " + apiMessage(r.body())));
            }
            JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonObject artists = root.has("artists") && root.get("artists").isJsonObject() ? root.getAsJsonObject("artists") : new JsonObject();
            if (first) { total = (int) lng(artists, "total"); first = false; }
            JsonArray arr = artists.has("items") && artists.get("items").isJsonArray() ? artists.getAsJsonArray("items") : new JsonArray();
            int got = 0;
            for (JsonElement el : arr) { got++; if (el.isJsonObject()) out.add(el.getAsJsonObject()); }
            JsonObject cursors = artists.has("cursors") && artists.get("cursors").isJsonObject() ? artists.getAsJsonObject("cursors") : new JsonObject();
            String nextAfter = str(cursors, "after");
            if (got == 0 || nextAfter.isEmpty() || nextAfter.equals(after)) break;
            after = nextAfter;
        }
        return new PageData(out, total);
    }

    private Playlist parsePlaylist(JsonObject p) {
        String id = str(p, "id");
        if (id.isEmpty()) return null;
        String owner = "";
        if (p.has("owner") && p.get("owner").isJsonObject()) owner = str(p.getAsJsonObject("owner"), "display_name");
        int total = 0;
        if (p.has("items") && p.get("items").isJsonObject()) total = (int) lng(p.getAsJsonObject("items"), "total");
        else if (p.has("tracks") && p.get("tracks").isJsonObject()) total = (int) lng(p.getAsJsonObject("tracks"), "total");
        return new Playlist(id, str(p, "name"), owner, total, bestImage(p), "spotify:playlist:" + id);
    }

    private static LibraryEntry asEntry(Playlist p) {
        return new LibraryEntry(p.id(), p.name(), p.owner(), p.total(), p.coverUrl(), p.uri(), "playlist");
    }

    private static JsonObject nested(JsonObject wrapper, String key) {
        return wrapper.has(key) && wrapper.get(key).isJsonObject() ? wrapper.getAsJsonObject(key) : wrapper;
    }

    private static LibraryEntry albumEntry(JsonObject album) {
        String id = str(album, "id");
        if (id.isEmpty()) return null;
        String uri = str(album, "uri");
        if (uri.isEmpty()) uri = "spotify:album:" + id;
        return new LibraryEntry(id, str(album, "name"), artistsOf(album),
                (int) lng(album, "total_tracks"), bestImage(album), uri, "album");
    }

    private static LibraryEntry artistEntry(JsonObject artist) {
        String id = str(artist, "id");
        if (id.isEmpty()) return null;
        String uri = str(artist, "uri");
        if (uri.isEmpty()) uri = "spotify:artist:" + id;
        return new LibraryEntry(id, str(artist, "name"), "Исполнитель", 0, bestImage(artist), uri, "artist");
    }

    private static LibraryEntry showEntry(JsonObject show) {
        String id = str(show, "id");
        if (id.isEmpty()) return null;
        String uri = str(show, "uri");
        if (uri.isEmpty()) uri = "spotify:show:" + id;
        return new LibraryEntry(id, str(show, "name"), "Подкаст",
                (int) lng(show, "total_episodes"), bestImage(show), uri, "show");
    }

    private static LibraryEntry audiobookEntry(JsonObject book) {
        String id = str(book, "id");
        if (id.isEmpty()) return null;
        String uri = str(book, "uri");
        if (uri.isEmpty()) uri = "spotify:audiobook:" + id;
        String author = "";
        if (book.has("authors") && book.get("authors").isJsonArray()) {
            for (JsonElement el : book.getAsJsonArray("authors")) {
                if (el.isJsonObject()) {
                    if (!author.isEmpty()) author += ", ";
                    author += str(el.getAsJsonObject(), "name");
                }
            }
        }
        return new LibraryEntry(id, str(book, "name"), author,
                (int) lng(book, "total_chapters"), bestImage(book), uri, "audiobook");
    }

    private static Item parseTrackItem(JsonObject item) {
        String uri = str(item, "uri");
        if (uri.isEmpty()) return null;
        String albumUri = "";
        if (item.has("album") && item.get("album").isJsonObject()) {
            JsonObject album = item.getAsJsonObject("album");
            albumUri = str(album, "uri");
            if (albumUri.isEmpty()) {
                String albumId = str(album, "id");
                if (!albumId.isEmpty()) albumUri = "spotify:album:" + albumId;
            }
        }
        return new Item(str(item, "name"), artistsOf(item), lng(item, "duration_ms"), uri, albumUri);
    }

    private void loadLibrary() {
        List<LibraryEntry> previousLibrary = library;
        List<LibraryEntry> all = new ArrayList<>();
        List<Playlist> playlistOnly = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Regular and private/collaborative playlists.
        try {
            PageData page = fetchOffsetPages("/me/playlists");
            LOG.info("Spotify playlists loaded: {} of {} reported by /me/playlists.", page.objects().size(), page.total());
            for (JsonObject obj : page.objects()) {
                Playlist p = parsePlaylist(obj);
                if (p != null && seen.add("playlist:" + p.id())) {
                    playlistOnly.add(p);
                    all.add(asEntry(p));
                }
            }
            if (page.total() > page.objects().size()) {
                errors.add("Spotify вернул " + page.objects().size() + " из " + page.total()
                        + " плейлистов; смотри latest.log для причины неполной пагинации");
            }
        } catch (Exception e) { errors.add("плейлисты: " + errText(e)); }

        // Saved albums.
        try {
            PageData page = fetchOffsetPages("/me/albums");
            for (JsonObject wrapper : page.objects()) {
                LibraryEntry entry = albumEntry(nested(wrapper, "album"));
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) { errors.add("альбомы: " + errText(e)); }

        // Followed artists (cursor pagination rather than offset pagination).
        try {
            PageData page = fetchFollowedArtists();
            for (JsonObject obj : page.objects()) {
                LibraryEntry entry = artistEntry(obj);
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) { errors.add("исполнители: " + errText(e)); }

        // Liked songs are a synthetic collection backed by /me/tracks.
        try {
            PageData page = fetchOffsetPages("/me/tracks");
            List<Item> liked = new ArrayList<>();
            String cover = "";
            for (JsonObject wrapper : page.objects()) {
                JsonObject track = nested(wrapper, "track");
                Item item = parseTrackItem(track);
                if (item != null) liked.add(item);
                if (cover.isEmpty() && track.has("album") && track.get("album").isJsonObject()) cover = bestImage(track.getAsJsonObject("album"));
            }
            String key = "saved_tracks:liked";
            items.put(key, liked);
            itemsTime.put(key, System.currentTimeMillis());
            all.add(new LibraryEntry("liked", "Любимые треки", "Твоя медиатека", page.total(), cover, "", "saved_tracks"));
        } catch (Exception e) { errors.add("любимые треки: " + errText(e)); }

        // Saved podcast shows.
        try {
            PageData page = fetchOffsetPages("/me/shows");
            for (JsonObject wrapper : page.objects()) {
                LibraryEntry entry = showEntry(nested(wrapper, "show"));
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) { errors.add("подкасты: " + errText(e)); }

        // Saved episodes as a single collection.
        try {
            PageData page = fetchOffsetPages("/me/episodes");
            List<Item> savedEpisodes = new ArrayList<>();
            String cover = "";
            for (JsonObject wrapper : page.objects()) {
                JsonObject episode = nested(wrapper, "episode");
                String uri = str(episode, "uri");
                if (uri.isEmpty()) continue;
                String showName = episode.has("show") && episode.get("show").isJsonObject()
                        ? str(episode.getAsJsonObject("show"), "name") : "Подкаст";
                savedEpisodes.add(new Item(str(episode, "name"), showName, lng(episode, "duration_ms"), uri));
                if (cover.isEmpty() && episode.has("images")) cover = bestImage(episode);
                if (cover.isEmpty() && episode.has("show") && episode.get("show").isJsonObject()) cover = bestImage(episode.getAsJsonObject("show"));
            }
            String key = "saved_episodes:episodes";
            items.put(key, savedEpisodes);
            itemsTime.put(key, System.currentTimeMillis());
            all.add(new LibraryEntry("episodes", "Сохранённые эпизоды", "Твоя медиатека", page.total(), cover, "", "saved_episodes"));
        } catch (Exception e) { errors.add("эпизоды: " + errText(e)); }

        // Audiobooks.
        try {
            PageData page = fetchOffsetPages("/me/audiobooks");
            for (JsonObject wrapper : page.objects()) {
                LibraryEntry entry = audiobookEntry(nested(wrapper, "audiobook"));
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) { errors.add("аудиокниги: " + errText(e)); }

        // Extra playlists manually added by Spotify URL.
        for (String id : new ArrayList<>(SpotifyPanelMod.config.extraPlaylists)) {
            if (!seen.add("playlist:" + id)) continue;
            Playlist p = null;
            try {
                HttpResponse<String> r = send("GET", "/playlists/" + id, null);
                if (r.statusCode() == 200) p = parsePlaylist(JsonParser.parseString(r.body()).getAsJsonObject());
            } catch (Exception ignored) {}
            if (p == null) p = new Playlist(id, "Плейлист " + id.substring(0, Math.min(6, id.length())), "", 0, "", "spotify:playlist:" + id);
            playlistOnly.add(p);
            all.add(asEntry(p));
        }

        playlists = List.copyOf(playlistOnly);
        String newError = errors.isEmpty() ? "" : "Часть медиатеки недоступна: " + String.join("; ", errors);
        if (!newError.equals(playlistsError)) {
            if (!newError.isEmpty()) LOG.warn("Spotify library partially unavailable: {}", newError);
            playlistsError = newError;
            if (!newError.isEmpty()) SpotifyPanelMod.say(newError + ". Проверь права /spotify login; доступные разделы всё равно показаны.");
        }
        // Keep the special "Liked Songs" collection at the top, then show the user's
        // playlists, albums, followed artists, podcasts, saved episodes and audiobooks.
        all.sort((a, b) -> Integer.compare(libraryOrder(a.kind()), libraryOrder(b.kind())));
        library = List.copyOf(all);
        if (!all.isEmpty() || errors.isEmpty()) {
            if (previousLibrary.isEmpty() || previousLibrary.size() != all.size()
                    || !previousLibrary.stream().map(LibraryEntry::key).toList().equals(all.stream().map(LibraryEntry::key).toList())) {
                SpotifyPanelMod.say("Медиатека обновлена: " + all.size() + " элементов — любимые треки, плейлисты, альбомы, исполнители, подкасты и аудиокниги.");
            }
        }
    }

    private static int libraryOrder(String kind) {
        return switch (kind) {
            case "saved_tracks" -> 0;
            case "playlist" -> 1;
            case "album" -> 2;
            case "artist" -> 3;
            case "show" -> 4;
            case "saved_episodes" -> 5;
            case "audiobook" -> 6;
            default -> 7;
        };
    }

    /** Прочитать ID из Spotify URL/URI с указанным типом. */
    public static String parseSpotifyId(String input, String type) {
        if (input == null || type == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:" + java.util.regex.Pattern.quote(type) + "[/:])([A-Za-z0-9]{10,40})").matcher(input.trim());
        return m.find() ? m.group(1) : null;
    }

    /** Сохраняем старый helper для существующей команды добавления плейлистов. */
    public static String parsePlaylistId(String input) { return parseSpotifyId(input, "playlist"); }

    public void requestItems(String playlistId, boolean force) {
        requestItems(new LibraryEntry(playlistId, "", "", 0, "", "spotify:playlist:" + playlistId, "playlist"), force);
    }

    public void requestItems(LibraryEntry entry, boolean force) {
        if (entry == null || "saved_tracks".equals(entry.kind()) || "saved_episodes".equals(entry.kind())) return;
        String key = entry.key();
        long now = System.currentTimeMillis();
        Long t = itemsTime.get(key);
        if (!force && t != null && now - t < 60_000) return;
        if (!itemsLoading.add(key)) return;
        exec.submit(() -> {
            try {
                List<Item> out = new ArrayList<>();
                int offset = 0;
                while (offset < 1000) {
                    String endpoint;
                    switch (entry.kind()) {
                        case "playlist" -> endpoint = "/playlists/" + entry.id() + "/items";
                        case "album" -> endpoint = "/albums/" + entry.id() + "/tracks";
                        case "artist" -> endpoint = "/artists/" + entry.id() + "/albums?include_groups=album,single,compilation";
                        case "show" -> endpoint = "/shows/" + entry.id() + "/episodes";
                        case "audiobook" -> endpoint = "/audiobooks/" + entry.id() + "/chapters";
                        default -> { itemErrors.put(key, "Для этого раздела список элементов не поддерживается."); return; }
                    }
                    String separator = endpoint.contains("?") ? "&" : "?";
                    HttpResponse<String> r = send("GET", endpoint + separator + "limit=50&offset=" + offset, null);
                    if (r.statusCode() != 200) {
                        if (offset == 0) {
                            String detail = apiMessage(r.body());
                            itemErrors.put(key, "Не удалось загрузить содержимое: HTTP " + r.statusCode() + (detail.isEmpty() ? "" : " — " + detail));
                        }
                        break;
                    }
                    JsonObject page = JsonParser.parseString(r.body()).getAsJsonObject();
                    JsonArray arr = page.has("items") && page.get("items").isJsonArray() ? page.getAsJsonArray("items") : new JsonArray();
                    int got = 0;
                    for (JsonElement el : arr) {
                        got++;
                        if (!el.isJsonObject()) continue;
                        JsonObject wrapper = el.getAsJsonObject();
                        JsonObject obj = switch (entry.kind()) {
                            case "playlist" -> nested(wrapper, "item").equals(wrapper) ? nested(wrapper, "track") : nested(wrapper, "item");
                            case "show" -> nested(wrapper, "episode");
                            default -> wrapper;
                        };
                        String uri = str(obj, "uri");
                        if (uri.isEmpty()) continue;
                        if ("artist".equals(entry.kind())) {
                            out.add(new Item(str(obj, "name"), artistsOf(obj), 0, uri));
                        } else if ("show".equals(entry.kind())) {
                            String showName = entry.name();
                            out.add(new Item(str(obj, "name"), showName, lng(obj, "duration_ms"), uri));
                        } else if ("audiobook".equals(entry.kind())) {
                            out.add(new Item(str(obj, "name"), entry.owner(), lng(obj, "duration_ms"), uri));
                        } else {
                            Item item = parseTrackItem(obj);
                            if (item != null) out.add(item);
                        }
                    }
                    items.put(key, new ArrayList<>(out));
                    itemErrors.remove(key);
                    boolean hasNext = page.has("next") && !page.get("next").isJsonNull();
                    if (!hasNext || got == 0) break;
                    offset += got;
                }
                items.put(key, out);
                itemErrors.remove(key);
                itemsTime.put(key, System.currentTimeMillis());
            } catch (Exception e) {
                itemErrors.put(key, "Содержимое: " + errText(e));
            } finally {
                itemsLoading.remove(key);
            }
        });
    }

    /** Запустить контекст медиатеки или конкретную строку. */
    public void playLibraryEntry(LibraryEntry entry, Consumer<String> err) {
        if (entry == null) return;
        if ("saved_tracks".equals(entry.kind()) || "saved_episodes".equals(entry.kind())) {
            List<Item> loaded = items(entry);
            if (loaded == null) {
                err.accept("Сначала загружаю содержимое коллекции — нажми ещё раз через секунду.");
                return;
            }
            if (loaded.isEmpty()) { err.accept("В этой коллекции пока нет доступных элементов."); return; }
            // Liked Songs is not a Spotify playback context. On some desktop clients,
            // a bare `uris` playback request may clear the current player. Start the
            // chosen saved track through its album context and exact track offset instead.
            playTrackFromAlbum(loaded.get(0), err);
            return;
        }
        // Spotify's playback endpoint accepts contexts for albums, artists and playlists,
        // but not for show/audiobook objects. For those, play the first loaded episode/chapter.
        if ("show".equals(entry.kind()) || "audiobook".equals(entry.kind())) {
            List<Item> loaded = items(entry);
            if (loaded == null) {
                err.accept("Загружаю эпизоды/главы. Нажми на обложку ещё раз через секунду.");
                return;
            }
            if (loaded.isEmpty()) { err.accept("В этом разделе пока нет доступных эпизодов или глав."); return; }
            playLibraryItem(entry, loaded.get(0), err);
            return;
        }
        if (!entry.uri().isEmpty()) playContext(entry.uri(), "", err);
    }

    public void playLibraryItem(LibraryEntry entry, Item item, Consumer<String> err) {
        if (entry == null || item == null || item.uri().isEmpty()) return;
        switch (entry.kind()) {
            case "playlist", "album" -> playContext(entry.uri(), item.uri(), err);
            case "artist" -> playContext(item.uri(), "", err); // artist row is one of that artist's albums
            case "saved_tracks" -> playTrackFromAlbum(item, err);
            default -> playUris(List.of(item.uri()), err);
        }
    }

    private void playTrackFromAlbum(Item item, Consumer<String> err) {
        if (item == null || item.uri().isEmpty()) return;
        if (item.albumUri() != null && !item.albumUri().isEmpty()) {
            playContext(item.albumUri(), item.uri(), err);
        } else {
            // Fallback for an incomplete API response. Most track objects include album.uri.
            playUris(List.of(item.uri()), err);
        }
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

    /** Returns an artwork-derived accent color for the selected library header. */
    public int coverAccentColor(String url) {
        if (url == null || url.isBlank()) return 0xFF52635B;
        Integer cached = coverAccents.get(url);
        if (cached != null) return cached;
        // Also starts the asynchronous artwork download if the cover is not cached yet.
        pixels(url, 8);
        int[] raw = rawCache.get(url);
        if (raw == null) return 0xFF52635B;
        int accent = extractAccentColor(raw);
        coverAccents.put(url, accent);
        return accent;
    }

    private static int extractAccentColor(int[] pixels) {
        long allR = 0, allG = 0, allB = 0, allCount = 0;
        double r = 0, g = 0, b = 0, weightSum = 0;
        // Sample every fourth pixel. Saturated mid-range pixels get more weight than
        // white borders, black shadows and nearly-neutral artwork.
        for (int y = 0; y < RAW; y += 4) {
            for (int x = 0; x < RAW; x += 4) {
                int c = pixels[y * RAW + x];
                int cr = (c >>> 16) & 255;
                int cg = (c >>> 8) & 255;
                int cb = c & 255;
                allR += cr; allG += cg; allB += cb; allCount++;

                int max = Math.max(cr, Math.max(cg, cb));
                int min = Math.min(cr, Math.min(cg, cb));
                double saturation = max == 0 ? 0 : (max - min) / (double) max;
                double luminance = (0.2126 * cr + 0.7152 * cg + 0.0722 * cb) / 255.0;
                double visibility = 0.30 + 0.70 * (1.0 - Math.abs(luminance - 0.52) / 0.52);
                double weight = (0.18 + saturation * 2.2) * Math.max(0.20, visibility);
                if (luminance < 0.035 || luminance > 0.97) weight *= 0.22;
                r += cr * weight; g += cg * weight; b += cb * weight; weightSum += weight;
            }
        }
        if (weightSum < 1 || allCount == 0) return 0xFF52635B;
        int rr = (int) Math.round(r / weightSum);
        int gg = (int) Math.round(g / weightSum);
        int bb = (int) Math.round(b / weightSum);
        // If the image is almost entirely monochrome, keep its neutral character.
        double saturation = Math.max(rr, Math.max(gg, bb)) == 0 ? 0
                : (Math.max(rr, Math.max(gg, bb)) - Math.min(rr, Math.min(gg, bb)))
                / (double) Math.max(rr, Math.max(gg, bb));
        if (saturation < 0.10) {
            rr = (int) (allR / allCount);
            gg = (int) (allG / allCount);
            bb = (int) (allB / allCount);
        }
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private void loadRaw(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> r = Net.http().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            int[] raw = decodeAwt(r.body());
            if (raw == null) raw = decodeNative(r.body());
            if (raw != null) {
                rawCache.put(url, raw);
                coverAccents.put(url, extractAccentColor(raw));
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
        // NativeImage.read expects PNG. ImageIO above handles common formats such as
        // JPEG; do not feed HTML/error bodies or non-PNG formats into the PNG decoder.
        if (bytes == null || bytes.length < 8
                || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4E
                || bytes[3] != 0x47 || bytes[4] != 0x0D || bytes[5] != 0x0A
                || bytes[6] != 0x1A || bytes[7] != 0x0A) {
            return null;
        }
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes))) {
            return downsample(img.getWidth(), img.getHeight(), img::getColorArgb, RAW);
        } catch (Throwable e) {
            LOG.debug("PNG artwork decode failed: {}", e.toString());
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

    /**
     * Update the visible value immediately, but coalesce rapid slider movements into
     * at most one in-flight volume command plus one latest pending value. This prevents
     * a long queue of stale Spotify requests while the player drags the slider.
     */
    public void volume(int percent, Consumer<String> err) {
        int v = Math.max(0, Math.min(100, percent));
        Snapshot before = snapshot;
        if (volumeRampValue.get() < 0 && before.status() == Status.ACTIVE) {
            volumeRampValue.compareAndSet(-1, Math.max(0, Math.min(100, before.volume())));
        }
        // The slider responds instantly; the real Spotify device is eased toward the
        // requested value in small steps so the audible volume does not jump abruptly.
        local(s -> s.withVolume(v), 1800);
        pendingVolume.set(v);
        scheduleVolumeFlush(err);
    }

    private void scheduleVolumeFlush(Consumer<String> err) {
        if (!volumeFlushScheduled.compareAndSet(false, true)) return;
        long elapsed = System.currentTimeMillis() - lastVolumeRequestAtMs;
        long delay = Math.max(0, MIN_VOLUME_REQUEST_INTERVAL_MS - elapsed);
        exec.schedule(() -> ctl.submit(() -> flushPendingVolume(err)), delay, TimeUnit.MILLISECONDS);
    }

    private void flushPendingVolume(Consumer<String> err) {
        try {
            int requested = pendingVolume.getAndSet(-1);
            if (requested >= 0) {
                int current = volumeRampValue.get();
                if (current < 0) current = requested;
                int delta = requested - current;
                int step = Math.max(-MAX_VOLUME_RAMP_STEP, Math.min(MAX_VOLUME_RAMP_STEP, delta));
                int next = Math.max(0, Math.min(100, current + step));
                lastVolumeRequestAtMs = System.currentTimeMillis();
                boolean success = doControl("PUT", "/me/player/volume?volume_percent=" + next, null, err);
                if (success) {
                    volumeRampValue.set(next);
                    // Continue the ramp, unless a newer mouse value arrived while the
                    // previous network request was in flight.
                    if (next != requested) pendingVolume.compareAndSet(-1, requested);
                } else {
                    volumeRampValue.set(-1);
                    pendingVolume.set(-1);
                }
            }
        } finally {
            volumeFlushScheduled.set(false);
            if (pendingVolume.get() >= 0) scheduleVolumeFlush(err);
        }
    }

    public void seek(long ms, Consumer<String> err) {
        Snapshot current = snapshot;
        long max = current.track() == null ? Long.MAX_VALUE : Math.max(0L, current.track().durationMs());
        long v = Math.max(0L, Math.min(max, ms));
        local(s -> s.withProgress(v), 1800);
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

    /** Запускает отдельные сохранённые треки/эпизоды через список URI (Spotify принимает до 100 URI). */
    public void playUris(List<String> uris, Consumer<String> err) {
        JsonArray array = new JsonArray();
        if (uris != null) for (String uri : uris) if (uri != null && !uri.isEmpty() && array.size() < 100) array.add(uri);
        if (array.isEmpty()) { err.accept("Нет доступных элементов для запуска."); return; }
        JsonObject body = new JsonObject();
        body.add("uris", array);
        control("PUT", "/me/player/play", body.toString(), err);
    }

    private void control(String method, String path, String body, Consumer<String> err) {
        ctl.submit(() -> { doControl(method, path, body, err); });
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

    private boolean doControl(String method, String path, String body, Consumer<String> err) {
        try {
            if (!auth.isLoggedIn()) {
                err.accept("Сначала войди: /spotify login");
                revert();
                return false;
            }
            HttpResponse<String> r = send(method, path, body);
            int sc = r.statusCode();
            if (sc == 404) {
                err.accept("Нет активного устройства: открой Spotify и запусти любой трек.");
                revert();
                return false;
            } else if (sc == 403) {
                err.accept("Spotify отказал (нужен Premium или действие недоступно).");
                revert();
                return false;
            } else if (sc >= 300) {
                err.accept("Spotify: HTTP " + sc);
                revert();
                return false;
            } else {
                long wait = ignorePollUntil - System.currentTimeMillis();
                if (wait > 0) {
                    pollOnce(wait + 100); // подтянуть настоящее состояние, когда «окно игнора» кончится
                } else {
                    // смена трека у Spotify занимает время — спрашиваем два раза
                    exec.schedule(this::poll, 400, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 1300, TimeUnit.MILLISECONDS);
                }
                return true;
            }
        } catch (Exception e) {
            err.accept("Ошибка: " + errText(e));
            revert();
            return false;
        }
    }
}
