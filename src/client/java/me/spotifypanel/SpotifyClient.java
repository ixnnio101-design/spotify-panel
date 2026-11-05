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
import java.util.concurrent.CompletableFuture;
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
    // Shared Spotify API cooldown after HTTP 429; all background requests honor Retry-After.
    private volatile long apiCooldownUntilMs = 0;
    private volatile int playlistFails = 0;

    private synchronized void extendApiCooldown(long untilMs) {
        apiCooldownUntilMs = Math.max(apiCooldownUntilMs, untilMs);
    }
    private final java.util.concurrent.atomic.AtomicBoolean playlistsLoading = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String, List<Item>> items = new ConcurrentHashMap<>();
    private final Map<String, String> itemErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> itemsTime = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<Item>>> itemLoads = new ConcurrentHashMap<>();
    private record LikedPlayback(List<Item> items, int index) {}
    private volatile LikedPlayback likedPlayback;

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
        exec.scheduleWithFixedDelay(this::poll, 1, 2, TimeUnit.SECONDS);
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
        long remainingCooldown = apiCooldownUntilMs - System.currentTimeMillis();
        if (remainingCooldown > 0) {
            throw new IOException("Spotify временно ограничил запросы. Повтори через "
                    + Math.max(1, (remainingCooldown + 999) / 1000) + " сек.");
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                .header("Authorization", "Bearer " + auth.accessToken())
                .timeout(Duration.ofSeconds(10));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        HttpResponse<String> response = Net.http().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse("5");
            long delayMs;
            try {
                delayMs = Math.max(1_000L, Long.parseLong(retryAfter.trim()) * 1_000L);
            } catch (NumberFormatException ignored) {
                delayMs = 5_000L;
            }
            extendApiCooldown(System.currentTimeMillis() + delayMs);
        }
        return response;
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
            if (now < apiCooldownUntilMs) {
                // Keep the last known playback card visible during the cooldown.
                // Only show an unavailable state when there is no active snapshot to preserve.
                if (snapshot.status() != Status.ACTIVE) {
                    snapshot = Snapshot.error("Spotify временно ограничил запросы. Повторная проверка позже.");
                }
                return;
            }
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
                Snapshot current = snapshot;
                if (current.status() == Status.ACTIVE) {
                    snapshot = new Snapshot(current.status(), current.playing(), current.track(),
                            current.progressMs(), current.fetchedAtMs(), current.device(), current.volume(),
                            current.shuffle(), current.repeat(), "Слишком много запросов (429)");
                } else {
                    snapshot = Snapshot.error("Слишком много запросов (429)");
                }
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

    /** Offset pagination used by Spotify's saved albums/tracks/shows/episodes/audiobooks endpoints. */
    private PageData fetchOffsetPages(String endpoint) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        int offset = 0;
        int total = 0;
        boolean first = true;
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
            if (first) { total = (int) lng(page, "total"); first = false; }
            JsonArray arr = page.has("items") && page.get("items").isJsonArray() ? page.getAsJsonArray("items") : new JsonArray();
            int got = 0;
            for (JsonElement el : arr) {
                got++;
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
            }
            boolean hasNext = page.has("next") && !page.get("next").isJsonNull();
            if (!hasNext || got == 0) break;
            offset += got;
        }
        return new PageData(out, total);
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
            for (JsonObject obj : page.objects()) {
                Playlist p = parsePlaylist(obj);
                if (p != null && seen.add("playlist:" + p.id())) {
                    playlistOnly.add(p);
                    all.add(asEntry(p));
                }
            }
        } catch (Exception e) {
            errors.add("плейлисты: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

        // Saved albums.
        try {
            PageData page = fetchOffsetPages("/me/albums");
            for (JsonObject wrapper : page.objects()) {
                LibraryEntry entry = albumEntry(nested(wrapper, "album"));
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) {
            errors.add("альбомы: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

        // Followed artists (cursor pagination rather than offset pagination).
        try {
            PageData page = fetchFollowedArtists();
            for (JsonObject obj : page.objects()) {
                LibraryEntry entry = artistEntry(obj);
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) {
            errors.add("исполнители: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

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
        } catch (Exception e) {
            errors.add("любимые треки: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

        // Saved podcast shows.
        try {
            PageData page = fetchOffsetPages("/me/shows");
            for (JsonObject wrapper : page.objects()) {
                LibraryEntry entry = showEntry(nested(wrapper, "show"));
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) {
            errors.add("подкасты: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

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
        } catch (Exception e) {
            errors.add("эпизоды: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

        // Audiobooks.
        try {
            PageData page = fetchOffsetPages("/me/audiobooks");
            for (JsonObject wrapper : page.objects()) {
                LibraryEntry entry = audiobookEntry(nested(wrapper, "audiobook"));
                if (entry != null && seen.add(entry.key())) all.add(entry);
            }
        } catch (Exception e) {
            errors.add("аудиокниги: " + errText(e));
            if (retryAfterMs > 0) {
                // Spotify applies a shared API rate limit. Stop this refresh immediately
                // instead of hammering every remaining library endpoint with HTTP 429.
                playlistsError = "Spotify ограничил запросы (HTTP 429). Медиатека сохранена; повторная загрузка будет позже.";
                SpotifyPanelMod.say(playlistsError + " Подожди и не запускай /spotify login повторно.");
                return;
            }
        }

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
        requestItemsAsync(entry, force);
    }

    /** Выполняет callback после загрузки содержимого, повторно используя уже идущий запрос. */
    public void requestItemsAndThen(LibraryEntry entry, boolean force, Consumer<List<Item>> callback) {
        if (callback == null) {
            requestItems(entry, force);
            return;
        }
        requestItemsAsync(entry, force).thenAccept(callback);
    }

    private CompletableFuture<List<Item>> requestItemsAsync(LibraryEntry entry, boolean force) {
        if (entry == null) return CompletableFuture.completedFuture(null);
        if ("saved_tracks".equals(entry.kind()) || "saved_episodes".equals(entry.kind())) {
            return CompletableFuture.completedFuture(items(entry));
        }

        String key = entry.key();
        long now = System.currentTimeMillis();
        Long cachedAt = itemsTime.get(key);
        List<Item> cached = items.get(key);
        if (!force && cached != null && cachedAt != null && now - cachedAt < 60_000) {
            return CompletableFuture.completedFuture(cached);
        }

        AtomicBoolean shouldStart = new AtomicBoolean(false);
        CompletableFuture<List<Item>> future = itemLoads.compute(key, (ignored, current) -> {
            if (current != null && !current.isDone()) return current;
            shouldStart.set(true);
            return new CompletableFuture<>();
        });

        if (shouldStart.get()) {
            exec.submit(() -> {
                try {
                    loadItems(entry, key);
                } catch (Exception e) {
                    itemErrors.put(key, "Содержимое: " + errText(e));
                } finally {
                    future.complete(items.get(key));
                    itemLoads.remove(key, future);
                }
            });
        }
        return future;
    }

    private void loadItems(LibraryEntry entry, String key) throws Exception {
        List<Item> out = new ArrayList<>();
        int offset = 0;
        boolean failed = false;
        while (offset < 1000) {
            String endpoint;
            switch (entry.kind()) {
                case "playlist" -> endpoint = "/playlists/" + entry.id() + "/items";
                case "album" -> endpoint = "/albums/" + entry.id() + "/tracks";
                case "artist" -> endpoint = "/artists/" + entry.id() + "/albums?include_groups=album,single,compilation";
                case "show" -> endpoint = "/shows/" + entry.id() + "/episodes";
                case "audiobook" -> endpoint = "/audiobooks/" + entry.id() + "/chapters";
                default -> {
                    itemErrors.put(key, "Для этого раздела список элементов не поддерживается.");
                    return;
                }
            }
            String separator = endpoint.contains("?") ? "&" : "?";
            HttpResponse<String> r = send("GET", endpoint + separator + "limit=50&offset=" + offset, null);
            if (r.statusCode() != 200) {
                String detail = apiMessage(r.body());
                String prefix = offset == 0
                        ? "Не удалось загрузить содержимое: HTTP "
                        : "Не удалось загрузить оставшиеся элементы: HTTP ";
                itemErrors.put(key, prefix + r.statusCode()
                        + (detail.isEmpty() ? "" : " — " + detail));
                failed = true;
                break;
            }
            JsonObject page = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonArray arr = page.has("items") && page.get("items").isJsonArray()
                    ? page.getAsJsonArray("items") : new JsonArray();
            int got = 0;
            for (JsonElement el : arr) {
                got++;
                if (!el.isJsonObject()) continue;
                JsonObject wrapper = el.getAsJsonObject();
                JsonObject obj = switch (entry.kind()) {
                    case "playlist" -> nested(wrapper, "item").equals(wrapper)
                            ? nested(wrapper, "track") : nested(wrapper, "item");
                    case "show" -> nested(wrapper, "episode");
                    default -> wrapper;
                };
                String uri = str(obj, "uri");
                if (uri.isEmpty()) continue;
                if ("artist".equals(entry.kind())) {
                    out.add(new Item(str(obj, "name"), artistsOf(obj), 0, uri));
                } else if ("show".equals(entry.kind())) {
                    out.add(new Item(str(obj, "name"), entry.name(), lng(obj, "duration_ms"), uri));
                } else if ("audiobook".equals(entry.kind())) {
                    out.add(new Item(str(obj, "name"), entry.owner(), lng(obj, "duration_ms"), uri));
                } else {
                    Item item = parseTrackItem(obj);
                    if (item != null) out.add(item);
                }
            }
            items.put(key, new ArrayList<>(out));
            boolean hasNext = page.has("next") && !page.get("next").isJsonNull();
            if (!hasNext || got == 0) break;
            offset += got;
        }
        if (!failed) {
            items.put(key, List.copyOf(out));
            itemErrors.remove(key);
            itemsTime.put(key, System.currentTimeMillis());
        } else if (!out.isEmpty()) {
            // Keep successfully fetched pages visible, but retain the API error and do not
            // mark the incomplete result as a fresh cache entry.
            items.put(key, List.copyOf(out));
        }
    }

    /**
     * Запускает выбранный раздел медиатеки с его первого доступного элемента.
     * Для плейлистов и альбомов передаём Spotify URI первого трека, когда список
     * доступен; если чтение списка не удалось, запускаем сам контекст как запасной вариант.
     */
    public void playLibraryEntry(LibraryEntry entry, Consumer<String> err) {
        if (entry == null) return;

        if ("artist".equals(entry.kind())) {
            likedPlayback = null;
            requestItems(entry, false); // список альбомов нужен для отображения раздела
            if (entry.uri() != null && !entry.uri().isEmpty()) {
                playContext(entry.uri(), "", err);
            } else {
                err.accept("Не удалось определить исполнителя для воспроизведения.");
            }
            return;
        }

        switch (entry.kind()) {
            case "playlist", "album" -> requestItemsAndThen(entry, false, loaded -> {
                if (loaded != null && !loaded.isEmpty()) {
                    playLibraryItem(entry, loaded.get(0), err);
                } else if (entry.uri() != null && !entry.uri().isEmpty()) {
                    // The collection context can still play even if loading its visible
                    // track list failed (for example because Spotify throttled that request).
                    playContext(entry.uri(), "", err);
                } else {
                    err.accept("Не удалось загрузить содержимое этого раздела Spotify.");
                }
            });
            case "saved_tracks", "saved_episodes", "show", "audiobook" ->
                    requestItemsAndThen(entry, false, loaded -> {
                        if (loaded == null) {
                            String detail = itemError(entry);
                            err.accept(detail.isEmpty()
                                    ? "Не удалось загрузить содержимое этого раздела Spotify." : detail);
                        } else if (loaded.isEmpty()) {
                            err.accept("В этом разделе пока нет доступных для воспроизведения элементов.");
                        } else {
                            playLibraryItem(entry, loaded.get(0), err);
                        }
                    });
            default -> {
                likedPlayback = null;
                if (entry.uri() != null && !entry.uri().isEmpty()) {
                    playContext(entry.uri(), "", err);
                } else {
                    err.accept("У этого раздела нет адреса воспроизведения Spotify.");
                }
            }
        }
    }

    public void playLibraryItem(LibraryEntry entry, Item item, Consumer<String> err) {
        if (entry == null || item == null || item.uri().isEmpty()) return;
        optimisticTrack(item);
        if ("saved_tracks".equals(entry.kind())) {
            List<Item> saved = items(entry);
            int selectedIndex = -1;
            if (saved != null) {
                for (int i = 0; i < saved.size(); i++) {
                    if (item.uri().equals(saved.get(i).uri())) {
                        selectedIndex = i;
                        break;
                    }
                }
            }
            likedPlayback = saved != null && selectedIndex >= 0
                    ? new LikedPlayback(List.copyOf(saved), selectedIndex) : null;
            playTrackFromAlbum(item, err);
            return;
        }

        likedPlayback = null;
        switch (entry.kind()) {
            case "playlist", "album" -> playContext(entry.uri(), item.uri(), err);
            case "artist" -> playContext(item.uri(), "", err); // artist row is one of that artist's albums
            default -> playUris(List.of(item.uri()), err);
        }
    }

    private void playLikedItem(LikedPlayback queue, int index, Consumer<String> err) {
        if (queue == null || queue.items() == null || index < 0 || index >= queue.items().size()) return;
        Item item = queue.items().get(index);
        likedPlayback = new LikedPlayback(queue.items(), index);
        optimisticTrack(item);
        // Liked Songs itself is not a playable Spotify context. The album context keeps
        // playback working on desktop; our Next/Previous controls preserve the saved-list order.
        playTrackFromAlbum(item, err);
    }

    private void optimisticTrack(Item item) {
        Snapshot current = snapshot;
        String cover = current.track() == null ? "" : current.track().coverUrl();
        Track selected = new Track(item.title(), item.artists(), cover, item.durationMs(), item.uri());
        Snapshot next = new Snapshot(Status.ACTIVE, true, selected, 0, System.currentTimeMillis(),
                current.device(), current.volume(), current.shuffle(), current.repeat(), "");
        local(ignored -> next, 250);
    }

    private void playTrackFromAlbum(Item item, Consumer<String> err) {
        if (item == null || item.uri().isEmpty()) return;
        if (item.albumUri() != null && !item.albumUri().isEmpty()) {
            playContextInternal(item.albumUri(), item.uri(), err);
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

    public void next(Consumer<String> err) {
        LikedPlayback queue = likedPlayback;
        if (queue == null || queue.items().isEmpty()) {
            control("POST", "/me/player/next", null, err);
            return;
        }
        if ("track".equals(snapshot.repeat())) {
            playLikedItem(queue, queue.index(), err);
        } else if (queue.index() + 1 < queue.items().size()) {
            playLikedItem(queue, queue.index() + 1, err);
        } else if ("context".equals(snapshot.repeat())) {
            playLikedItem(queue, 0, err);
        } else {
            // Keep the Liked Songs session active, so repeated presses cannot fall
            // through to the current album and unexpectedly leave the collection.
            pause(err);
            err.accept("Это последний трек в «Любимых треках».");
        }
    }

    public void previous(Consumer<String> err) {
        LikedPlayback queue = likedPlayback;
        if (queue == null || queue.items().isEmpty()) {
            control("POST", "/me/player/previous", null, err);
            return;
        }
        if ("track".equals(snapshot.repeat()) || snapshot.currentProgress() > 3_000) {
            playLikedItem(queue, queue.index(), err);
        } else if (queue.index() > 0) {
            playLikedItem(queue, queue.index() - 1, err);
        } else if ("context".equals(snapshot.repeat())) {
            playLikedItem(queue, queue.items().size() - 1, err);
        } else {
            playLikedItem(queue, 0, err);
        }
    }

    /** Update the slider immediately and send only the latest requested volume to Spotify. */
    public void volume(int percent, Consumer<String> err) {
        int v = Math.max(0, Math.min(100, percent));
        local(s -> s.withVolume(v), 650);
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
                lastVolumeRequestAtMs = System.currentTimeMillis();
                // Direct target volume: no artificial multi-request fade/ramp.
                doControl("PUT", "/me/player/volume?volume_percent=" + requested, null, err);
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

    /** Играть плейлист/альбом/исполнителя; внешний запуск завершает навигацию по избранному. */
    public void playContext(String contextUri, String trackUri, Consumer<String> err) {
        likedPlayback = null;
        playContextInternal(contextUri, trackUri, err);
    }

    /** Внутренний запрос контекста, который не сбрасывает навигацию по «Любимым трекам». */
    private void playContextInternal(String contextUri, String trackUri, Consumer<String> err) {
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
            } else if (sc == 429) {
                err.accept("Spotify временно ограничил запросы. Подожди перед повторной попыткой.");
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
                    // Pull player state promptly after transport/playback commands.
                    exec.schedule(this::poll, 140, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 420, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 850, TimeUnit.MILLISECONDS);
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
