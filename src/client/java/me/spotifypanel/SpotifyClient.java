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
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.UnaryOperator;

/** Spotify Web API: состояние плеера, медиатека разных типов, треки, обложки и команды управления. */
public final class SpotifyClient {
    private static final Logger LOG = LoggerFactory.getLogger("spotifypanel");
    private static final int RAW = 256; // обложка хранится как 256x256, из неё считаются картинки любого размера

    public enum Status { NO_CLIENT_ID, LOGGED_OUT, IDLE, ACTIVE, ERROR }

    public record Track(String title, String artists, String coverUrl, long durationMs, String uri, String albumName) {
        public Track(String title, String artists, String coverUrl, long durationMs, String uri) {
            this(title, artists, coverUrl, durationMs, uri, "");
        }
    }

    public enum LyricsStatus { IDLE, LOADING, FOUND, NOT_FOUND, INSTRUMENTAL, ERROR }

    /** One active track's lyrics only. No per-track map or disk cache is kept. */
    public record LyricLine(long timeMs, String text) {}
    public record Lyrics(String trackUri, LyricsStatus status, boolean instrumental,
                         List<String> plainLines, List<LyricLine> syncedLines, String message) {
        public Lyrics {
            plainLines = plainLines == null ? List.of() : List.copyOf(plainLines);
            syncedLines = syncedLines == null ? List.of() : List.copyOf(syncedLines);
            message = message == null ? "" : message;
        }
        public static Lyrics empty() {
            return new Lyrics("", LyricsStatus.IDLE, false, List.of(), List.of(), "");
        }
    }

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

    /** Результаты поиска Spotify: список найденных треков и сообщение об ошибке, если запрос не прошёл. */
    public record SearchResults(List<Item> items, String error) {}

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
    /** One coordinator plus three independent provider workers; the first valid result wins. */
    private final ExecutorService lyricsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotifypanel-lyrics-coordinator");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService lyricsProviderExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "spotifypanel-lyrics-provider");
        t.setDaemon(true);
        return t;
    });
    private volatile Future<?> lyricsFuture;
    private final AtomicLong lyricsRequestSequence = new AtomicLong();
    private volatile String lyricsRequestedUri = "";
    private volatile Lyrics lyrics = Lyrics.empty();
    // Provider cooldowns are shared across track changes; honor Retry-After per service.
    private volatile long liriqoCooldownUntilMs;
    private volatile long lyricaCooldownUntilMs;
    private volatile long betterLyricsCooldownUntilMs;

    // Artwork network I/O and decoding must not occupy the player/library scheduler.
    private final ScheduledExecutorService coverExec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "spotifypanel-covers");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService ctl = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spotifypanel-ctl");
        t.setDaemon(true);
        return t;
    });
    private final Object pollLock = new Object();
    // Serializes playback-intent changes with commits from /me/player responses.
    private final Object playbackStateLock = new Object();
    private final AtomicBoolean pollPending = new AtomicBoolean();
    // Track/collection clicks are debounced: rapid clicks collapse into the latest intent.
    private static final long PLAYBACK_DEBOUNCE_MS = 60;
    // Track selection is reconciled by URI confirmation, not by a fixed time delay.
    private final AtomicLong playbackIntentSequence = new AtomicLong();
    private final Object playbackScheduleLock = new Object();
    private ScheduledFuture<?> pendingPlaybackCommand;
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
    private final AtomicBoolean coreLibraryLoading = new AtomicBoolean();
    private final Map<String, List<Item>> items = new ConcurrentHashMap<>();
    private final Map<String, String> itemErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> itemsTime = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<Item>>> itemLoads = new ConcurrentHashMap<>();
    // Spotify does not push library-change events to third-party Web API clients.
    // Revalidate library GETs with ETags/Cache-Control instead of downloading unchanged pages.
    private record CachedGet(HttpResponse<String> response, String etag, long expiresAtMs) {}
    private final Map<String, CachedGet> libraryHttpCache = new ConcurrentHashMap<>();
    private final Map<String, String> playlistSnapshotIds = new ConcurrentHashMap<>();
    private final Map<String, String> loadedPlaylistSnapshotIds = new ConcurrentHashMap<>();
    private volatile long lastCoreLibraryRefreshAtMs;
    private volatile LibraryEntry visibleSelection;
    private static final long CORE_LIBRARY_REFRESH_INTERVAL_MS = 120_000L;
    private static final long PLAYLIST_ITEMS_REFRESH_INTERVAL_MS = 120_000L;
    private static final long FULL_LIBRARY_REFRESH_INTERVAL_MS = 30 * 60_000L;
    private record LikedPlayback(List<Item> items, int index, long sessionId) {}
    private volatile LikedPlayback likedPlayback;
    private final AtomicLong likedSessionSequence = new AtomicLong();
    // Liked Songs uses Spotify album-context playback for compatibility. Advance the
    // favorites queue before the album can continue, but never override a manual track
    // selection made directly in Spotify: verify the live player immediately first.
    private final Object likedAdvanceLock = new Object();
    private ScheduledFuture<?> likedAdvanceTask;
    private LikedPlayback scheduledLikedQueue;
    private String scheduledLikedUri = "";
    private long scheduledLikedAtMs;
    private static final long LIKED_ADVANCE_LEAD_MS = 450;
    private static final long LIKED_ADVANCE_RECHECK_MS = 1_200;
    // Optimistic UI target: highlight the last user-selected row immediately,
    // while waiting for Spotify to confirm the actual player state.
    private volatile String optimisticTrackUri = "";
    private volatile long optimisticTrackUntilMs = 0;
    private volatile long optimisticTrackSetAtMs = 0;

    public String optimisticTrackUri() {
        return System.currentTimeMillis() < optimisticTrackUntilMs ? optimisticTrackUri : "";
    }

    private boolean isAwaitingOptimisticTrack() {
        return !optimisticTrackUri.isEmpty()
                && System.currentTimeMillis() < optimisticTrackUntilMs;
    }

    private void setOptimisticTrack(Item item) {
        optimisticTrackUri = item == null ? "" : item.uri();
        optimisticTrackSetAtMs = System.currentTimeMillis();
        optimisticTrackUntilMs = optimisticTrackUri.isEmpty() ? 0 : optimisticTrackSetAtMs + 5000;
        if (item != null) {
            // Warm the shared cover cache before the now-playing card needs the image.
            // The API snapshot supplies the canonical cover URL; this just primes known cached URLs.
        }
    }

    private void cancelLikedAdvanceTask() {
        synchronized (likedAdvanceLock) {
            cancelLikedAdvanceTaskLocked();
        }
    }

    private void cancelLikedAdvanceTaskLocked() {
        if (likedAdvanceTask != null) likedAdvanceTask.cancel(false);
        likedAdvanceTask = null;
        scheduledLikedQueue = null;
        scheduledLikedUri = "";
        scheduledLikedAtMs = 0;
    }

    private long endLikedSession() {
        long sessionId = likedSessionSequence.incrementAndGet();
        synchronized (likedAdvanceLock) {
            likedPlayback = null;
            cancelLikedAdvanceTaskLocked();
        }
        return sessionId;
    }

    private void endLikedSession(LikedPlayback expected) {
        synchronized (likedAdvanceLock) {
            if (expected == null || likedPlayback != expected) return;
            likedSessionSequence.incrementAndGet();
            likedPlayback = null;
            cancelLikedAdvanceTaskLocked();
        }
    }

    private boolean setLikedPlayback(LikedPlayback queue, long sessionId, long intentId) {
        synchronized (likedAdvanceLock) {
            if (sessionId != likedSessionSequence.get() || !isPlaybackIntentCurrent(intentId)) return false;
            likedPlayback = queue;
            cancelLikedAdvanceTaskLocked();
            return true;
        }
    }

    private boolean endLikedSession(long intentId) {
        synchronized (likedAdvanceLock) {
            if (!isPlaybackIntentCurrent(intentId)) return false;
            likedSessionSequence.incrementAndGet();
            likedPlayback = null;
            cancelLikedAdvanceTaskLocked();
            return true;
        }
    }

    /**
     * Schedule the next favorite slightly before the current album-context track ends.
     * The callback validates the live player state before issuing a new album-context
     * play command, so paused tracks and seeks do not advance the queue incorrectly.
     */
    private void scheduleLikedAdvance(Snapshot state) {
        LikedPlayback queue = likedPlayback;
        if (queue == null || queue.sessionId() != likedSessionSequence.get()
                || queue.items() == null || queue.items().isEmpty()
                || queue.index() < 0 || queue.index() >= queue.items().size()
                || state == null || state.status() != Status.ACTIVE || !state.playing()
                || state.track() == null || state.track().durationMs() <= 0
                || "track".equals(state.repeat())) {
            cancelLikedAdvanceTask();
            return;
        }

        String expectedUri = queue.items().get(queue.index()).uri();
        if (expectedUri == null || expectedUri.isEmpty() || !expectedUri.equals(state.track().uri())) {
            cancelLikedAdvanceTask();
            return;
        }

        long remainingMs = Math.max(0L, state.track().durationMs() - state.currentProgress());
        long delayMs = Math.max(75L, remainingMs - LIKED_ADVANCE_LEAD_MS);
        long fireAtMs = System.currentTimeMillis() + delayMs;

        synchronized (likedAdvanceLock) {
            if (likedPlayback != queue) return;
            if (scheduledLikedQueue == queue && expectedUri.equals(scheduledLikedUri)
                    && likedAdvanceTask != null && !likedAdvanceTask.isDone()
                    && Math.abs(fireAtMs - scheduledLikedAtMs) < 2_500L) {
                return;
            }
            cancelLikedAdvanceTaskLocked();
            scheduledLikedQueue = queue;
            scheduledLikedUri = expectedUri;
            scheduledLikedAtMs = fireAtMs;
            likedAdvanceTask = exec.schedule(() -> runLikedAdvance(queue, expectedUri),
                    delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void runLikedAdvance(LikedPlayback queue, String expectedUri) {
        synchronized (likedAdvanceLock) {
            if (scheduledLikedQueue != queue || !expectedUri.equals(scheduledLikedUri)) return;
            cancelLikedAdvanceTaskLocked();
        }
        if (likedPlayback != queue || queue.sessionId() != likedSessionSequence.get()) return;

        // The cached snapshot can be up to two seconds old. Query Spotify directly
        // before issuing an automatic command so a song manually started in Spotify
        // Desktop is never replaced by the old Liked Songs queue.
        try {
            HttpResponse<String> response = send("GET", "/me/player", null);
            if (response.statusCode() == 204) {
                endLikedSession(queue);
                return;
            }
            if (response.statusCode() != 200) {
                // A rate limit or transient API failure must not trigger a blind play.
                endLikedSession(queue);
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement liveItem = root.get("item");
            if (liveItem == null || !liveItem.isJsonObject()) {
                endLikedSession(queue);
                return;
            }
            JsonObject track = liveItem.getAsJsonObject();
            String liveUri = str(track, "uri");
            String repeat = str(root, "repeat_state");
            if (repeat.isEmpty()) repeat = "off";
            if (!expectedUri.equals(liveUri)) {
                // A natural album-context transition can win the race against our
                // pre-end timer. Only recover when the previous snapshot proves that
                // the expected favorite had reached its end; otherwise this is an
                // external Spotify selection and the favorites queue must stand down.
                Snapshot previous = snapshot;
                if (wasAtNaturalTrackEnd(previous, expectedUri)) {
                    handleNaturalLikedTrackChange(queue, liveUri, repeat);
                } else {
                    endLikedSession(queue);
                }
                return;
            }
            if (!bool(root, "is_playing")) return;

            long duration = lng(track, "duration_ms");
            long progress = lng(root, "progress_ms");
            long remainingMs = Math.max(0L, duration - progress);
            if ("track".equals(repeat)) return;

            if (remainingMs > LIKED_ADVANCE_RECHECK_MS) {
                // For example, the user sought backwards after the timer was scheduled.
                String device = root.has("device") && root.get("device").isJsonObject()
                        ? str(root.getAsJsonObject("device"), "name") : "";
                int volume = root.has("device") && root.get("device").isJsonObject()
                        ? (int) lng(root.getAsJsonObject("device"), "volume_percent") : 0;
                Snapshot verified = new Snapshot(Status.ACTIVE, true,
                        new Track(str(track, "name"), artistsOf(track), "", duration, liveUri,
                                track.has("album") && track.get("album").isJsonObject()
                                        ? str(track.getAsJsonObject("album"), "name") : ""),
                        progress, System.currentTimeMillis(), device, volume,
                        bool(root, "shuffle_state"), repeat, "");
                scheduleLikedAdvance(verified);
                return;
            }
            advanceLikedQueue(queue, repeat);
        } catch (Exception e) {
            // On network failure prefer ending this queue over risking an override of
            // a manual Spotify selection that the client could not verify.
            LOG.debug("Liked Songs auto-advance verification failed", e);
            endLikedSession(queue);
        }
    }

    /** True only when the previous favorite was effectively at its natural end. */
    private boolean wasAtNaturalTrackEnd(Snapshot previous, String expectedUri) {
        if (previous == null || !previous.playing() || previous.track() == null
                || previous.track().durationMs() <= 0 || expectedUri == null
                || !expectedUri.equals(previous.track().uri())) return false;
        // Polling runs every two seconds. A 2.5s window covers the last poll before
        // Spotify's own album-context transition without treating normal track picks
        // as end-of-track events in the usual case.
        return previous.currentProgress() >= Math.max(0L, previous.track().durationMs() - 2_500L);
    }

    /** Keep the favorites queue when Spotify naturally advances its album context. */
    private void handleNaturalLikedTrackChange(LikedPlayback queue, String liveUri, String repeat) {
        if (queue == null || likedPlayback != queue || queue.sessionId() != likedSessionSequence.get()) return;
        int nextIndex = queue.index() + 1;
        if (nextIndex < queue.items().size()) {
            String nextUri = queue.items().get(nextIndex).uri();
            if (nextUri != null && nextUri.equals(liveUri)) {
                // Spotify happened to choose exactly the next favorite. Adopt that
                // position instead of sending a redundant play command.
                synchronized (likedAdvanceLock) {
                    if (likedPlayback == queue && queue.sessionId() == likedSessionSequence.get()) {
                        likedPlayback = new LikedPlayback(queue.items(), nextIndex, queue.sessionId());
                        cancelLikedAdvanceTaskLocked();
                    }
                }
            } else {
                // Spotify continued to another album track; correct it to the next
                // favorite, but only because the previous favorite naturally ended.
                playLikedItem(queue, nextIndex, ignored -> {});
            }
        } else if ("context".equals(repeat)) {
            playLikedItem(queue, 0, ignored -> {});
        } else {
            pauseLikedQueue(queue, ignored -> {});
        }
    }

    /** Manual Spotify track changes end the temporary queue; natural endings do not. */
    private void releaseLikedQueueOnExternalTrackChange(Snapshot previous, Snapshot current) {
        LikedPlayback queue = likedPlayback;
        if (queue == null || queue.sessionId() != likedSessionSequence.get()
                || previous == null || current == null || previous.track() == null || current.track() == null
                || queue.index() < 0 || queue.index() >= queue.items().size()) return;

        String expectedUri = queue.items().get(queue.index()).uri();
        if (expectedUri.equals(current.track().uri())) return; // the panel's requested next favorite started
        if (System.currentTimeMillis() < optimisticTrackUntilMs
                && expectedUri.equals(optimisticTrackUri)) return; // our play command is still pending
        if (wasAtNaturalTrackEnd(previous, expectedUri)) {
            handleNaturalLikedTrackChange(queue, current.track().uri(), current.repeat());
            return;
        }
        endLikedSession(queue); // manual/external Spotify selection: relinquish control
    }

    private void pauseLikedQueue(LikedPlayback queue, Consumer<String> err) {
        long intentId;
        synchronized (likedAdvanceLock) {
            if (queue == null || likedPlayback != queue || queue.sessionId() != likedSessionSequence.get()) return;
            intentId = beginPlaybackIntent();
            cancelLikedAdvanceTaskLocked();
        }
        schedulePlaybackControl(intentId, "PUT", "/me/player/pause", null, err);
    }

    private void advanceLikedQueue(LikedPlayback queue, String repeat) {
        if (queue == null || likedPlayback != queue || queue.sessionId() != likedSessionSequence.get()
                || queue.items().isEmpty()) return;
        if ("track".equals(repeat)) return;
        int nextIndex = queue.index() + 1;
        if (nextIndex < queue.items().size()) {
            playLikedItem(queue, nextIndex, ignored -> {});
        } else if ("context".equals(repeat)) {
            playLikedItem(queue, 0, ignored -> {});
        } else {
            // Do not let Spotify continue into the remainder of the current album
            // after the final saved track. Guard the pause like a track selection so
            // a newer user choice cannot be paused by a stale end-of-queue callback.
            pauseLikedQueue(queue, ignored -> {});
        }
    }

    // обложки
    private final Map<String, int[]> rawCache = new ConcurrentHashMap<>();
    private final Map<String, int[]> mosaics = new ConcurrentHashMap<>();
    private final Map<String, Integer> coverAccents = new ConcurrentHashMap<>();
    private final Set<String> coverLoading = ConcurrentHashMap.newKeySet();
    // Failed artwork is retried with exponential backoff instead of being marked
    // permanently unavailable. This handles temporary CDN/network/decode failures.
    private final Map<String, Long> coverRetryAtMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> coverAttempts = new ConcurrentHashMap<>();
    // Render thread drains these URLs and repaints only the visible artwork regions.
    private final ConcurrentLinkedQueue<String> completedCoverUrls = new ConcurrentLinkedQueue<>();

    public SpotifyClient(SpotifyAuth auth) {
        this.auth = auth;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::poll, 1, 2, TimeUnit.SECONDS);
        // Spotify does not expose library-change push events to third-party apps.
        // While the panel is visible, revalidate the small core library snapshot every
        // two minutes. Cache-Control and ETag avoid downloading unchanged responses.
        exec.scheduleWithFixedDelay(this::refreshVisibleCoreLibrary, 120, 120, TimeUnit.SECONDS);
    }

    /** Called once when the in-world panel becomes visible again. */
    public void onPanelOpened() {
        if (!auth.isLoggedIn()) return;
        exec.execute(() -> {
            long now = System.currentTimeMillis();
            if (library.isEmpty()) {
                if (!playlistsLoading.get()) {
                    nextPlaylistTry = 0;
                    tryLoadPlaylists();
                }
            } else if (now >= nextPlaylistTry && !playlistsLoading.get()) {
                tryLoadPlaylists();
            } else {
                refreshVisibleCoreLibrary(true);
            }
        });
    }

    /** Revalidate metadata when the user switches to another library section. */
    public void onLibrarySectionOpened(LibraryEntry entry) {
        visibleSelection = entry;
        if (!auth.isLoggedIn()) return;
        exec.execute(() -> refreshVisibleCoreLibrary(true));
    }

    /** Current selection is used to refresh its contents after a playlist snapshot changes. */
    public void setVisibleSelection(LibraryEntry entry) {
        visibleSelection = entry;
    }

    public Snapshot snapshot() { return snapshot; }

    /** Lyrics for the currently playing track only. Previous tracks are never cached. */
    public Lyrics lyrics() { return lyrics; }

    public List<Playlist> playlists() { return playlists; }

    public List<LibraryEntry> library() { return library; }

    public String playlistsError() { return playlistsError; }

    public String libraryError() { return playlistsError; }

    public List<Item> items(String key) { return items.get(key); }

    public List<Item> items(LibraryEntry entry) { return entry == null ? null : items.get(entry.key()); }

    public String itemError(String key) { return itemErrors.getOrDefault(key, ""); }

    public String itemError(LibraryEntry entry) { return entry == null ? "" : itemErrors.getOrDefault(entry.key(), ""); }

    /**
     * Асинхронно ищет треки в каталоге Spotify. Запрос выполняется в общем пуле API,
     * поэтому поток отрисовки Minecraft не ждёт ответа сети.
     */
    public CompletableFuture<SearchResults> searchTracks(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) return CompletableFuture.completedFuture(new SearchResults(List.of(), ""));
        if (auth.clientId().isBlank()) {
            return CompletableFuture.completedFuture(new SearchResults(List.of(), "Сначала настрой Spotify: /spotify setup <client_id>."));
        }
        if (!auth.isLoggedIn()) {
            return CompletableFuture.completedFuture(new SearchResults(List.of(), "Сначала подключи Spotify: /spotify login."));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encoded = URLEncoder.encode(normalized, java.nio.charset.StandardCharsets.UTF_8);
                HttpResponse<String> response = send("GET", "/search?q=" + encoded + "&type=track&limit=10", null);
                if (response.statusCode() != 200) {
                    String detail = apiMessage(response.body());
                    String message;
                    if (response.statusCode() == 403) {
                        message = "Spotify отклонил поиск (HTTP 403). Проверь доступность Web API для приложения и аккаунта.";
                    } else {
                        message = "Поиск Spotify: HTTP " + response.statusCode()
                                + (detail.isEmpty() ? "" : " — " + detail);
                    }
                    return new SearchResults(List.of(), message);
                }

                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject tracks = root.has("tracks") && root.get("tracks").isJsonObject()
                        ? root.getAsJsonObject("tracks") : new JsonObject();
                JsonArray results = tracks.has("items") && tracks.get("items").isJsonArray()
                        ? tracks.getAsJsonArray("items") : new JsonArray();
                List<Item> found = new ArrayList<>();
                for (JsonElement element : results) {
                    if (!element.isJsonObject()) continue;
                    Item item = parseTrackItem(element.getAsJsonObject());
                    if (item != null) found.add(item);
                }
                return new SearchResults(List.copyOf(found), "");
            } catch (Exception e) {
                return new SearchResults(List.of(), "Поиск Spotify: " + errText(e));
            }
        }, exec);
    }

    // ------------------------------------------------------------- HTTP

    private HttpResponse<String> send(String method, String path, String jsonBody) throws Exception {
        long remainingCooldown = apiCooldownUntilMs - System.currentTimeMillis();
        if (remainingCooldown > 0) {
            throw new IOException("Spotify временно ограничил запросы. Повтори через "
                    + Math.max(1, (remainingCooldown + 999) / 1000) + " сек.");
        }

        String cacheKey = method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        boolean cacheableGet = "GET".equalsIgnoreCase(method) && isLibraryCacheablePath(path);
        long now = System.currentTimeMillis();
        CachedGet cached = cacheableGet ? libraryHttpCache.get(cacheKey) : null;
        if (cached != null && now < cached.expiresAtMs()) {
            return cached.response();
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                .header("Authorization", "Bearer " + auth.accessToken())
                .timeout(Duration.ofSeconds(10));
        if (cacheableGet && cached != null && !cached.etag().isEmpty()) {
            b.header("If-None-Match", cached.etag());
        }
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        HttpResponse<String> response = Net.http().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            long delayMs = Net.retryAfterDelayMillis(
                    response.headers().firstValue("Retry-After").orElse(""), 5_000L, 5 * 60_000L);
            extendApiCooldown(System.currentTimeMillis() + Math.max(1_000L, delayMs));
        }

        if (cacheableGet && response.statusCode() == 304 && cached != null) {
            long expiresAt = cacheExpiry(response.headers(), System.currentTimeMillis());
            if (isNoStore(response.headers())) {
                libraryHttpCache.remove(cacheKey);
            } else {
                libraryHttpCache.put(cacheKey, new CachedGet(cached.response(), cached.etag(), expiresAt));
            }
            // Callers receive the original successful response/body transparently.
            return cached.response();
        }
        if (cacheableGet && response.statusCode() == 200) {
            String etag = response.headers().firstValue("ETag").orElse("");
            long expiresAt = cacheExpiry(response.headers(), System.currentTimeMillis());
            if (!isNoStore(response.headers()) && (!etag.isEmpty() || expiresAt > System.currentTimeMillis())) {
                libraryHttpCache.put(cacheKey, new CachedGet(response, etag, expiresAt));
            } else {
                libraryHttpCache.remove(cacheKey);
            }
        }
        return response;
    }

    private static boolean isLibraryCacheablePath(String path) {
        return path.startsWith("/me/playlists") || path.startsWith("/me/albums")
                || path.startsWith("/me/tracks") || path.startsWith("/playlists/")
                || path.startsWith("/albums/");
    }

    private static boolean isNoStore(HttpHeaders headers) {
        return headers.firstValue("Cache-Control").orElse("")
                .toLowerCase(java.util.Locale.ROOT).contains("no-store");
    }

    /** Uses Spotify's Cache-Control max-age/Age headers when supplied. */
    private static long cacheExpiry(HttpHeaders headers, long nowMs) {
        String cacheControl = headers.firstValue("Cache-Control").orElse("");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:s-maxage|max-age)\\s*=\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(cacheControl);
        if (!matcher.find()) return nowMs;
        try {
            long maxAgeSeconds = Long.parseLong(matcher.group(1));
            long ageSeconds = Long.parseLong(headers.firstValue("Age").orElse("0"));
            return nowMs + Math.max(0L, maxAgeSeconds - ageSeconds) * 1_000L;
        } catch (NumberFormatException ignored) {
            return nowMs;
        }
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
        // A poll started before/during a track-selection burst must not overwrite newer state.
        long playbackIntentAtStart = playbackIntentSequence.get();
        try {
            if (auth.clientId().isEmpty()) {
                snapshot = Snapshot.of(Status.NO_CLIENT_ID);
                clearCurrentLyrics();
                return;
            }
            if (!auth.isLoggedIn()) {
                snapshot = Snapshot.of(Status.LOGGED_OUT);
                clearCurrentLyrics();
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
            if (now >= nextPlaylistTry && (library.isEmpty()
                    || (SpotifyPanelMod.config != null && SpotifyPanelMod.config.visible))) {
                tryLoadPlaylists();
            }

            long started = System.currentTimeMillis();
            HttpResponse<String> r = send("GET", "/me/player", null);
            int sc = r.statusCode();
            boolean awaitingSelectedTrack = isAwaitingOptimisticTrack();
            if ((sc == 200 || sc == 204)
                    && (started < lastLocalAt
                    || (!awaitingSelectedTrack && System.currentTimeMillis() < ignorePollUntil))) {
                return; // ответ начался до локального действия или относится к его старому состоянию
            }
            if (sc == 204) {
                // Spotify can briefly report no active item while switching context.
                // Do not replace the selected track card until Spotify confirms a URI.
                if (awaitingSelectedTrack) return;
                synchronized (playbackStateLock) {
                    if (playbackIntentAtStart != playbackIntentSequence.get()) return;
                    snapshot = Snapshot.of(Status.IDLE);
                }
                clearCurrentLyrics();
                endLikedSession();
            } else if (sc == 401) {
                synchronized (playbackStateLock) {
                    if (playbackIntentAtStart != playbackIntentSequence.get()) return;
                    auth.invalidate();
                    snapshot = Snapshot.of(Status.LOGGED_OUT);
                }
                clearCurrentLyrics();
            } else if (sc == 429) {
                synchronized (playbackStateLock) {
                    if (playbackIntentAtStart != playbackIntentSequence.get()) return;
                    Snapshot current = snapshot;
                    if (current.status() == Status.ACTIVE) {
                        snapshot = new Snapshot(current.status(), current.playing(), current.track(),
                                current.progressMs(), current.fetchedAtMs(), current.device(), current.volume(),
                                current.shuffle(), current.repeat(), "Слишком много запросов (429)");
                    } else {
                        snapshot = Snapshot.error("Слишком много запросов (429)");
                    }
                }
            } else if (sc != 200) {
                synchronized (playbackStateLock) {
                    if (playbackIntentAtStart != playbackIntentSequence.get()) return;
                    snapshot = Snapshot.error("HTTP " + sc);
                }
            } else {
                parse(r.body(), playbackIntentAtStart);
            }
        } catch (Exception e) {
            synchronized (playbackStateLock) {
                // A failed old poll must not replace the state produced by a newer click.
                if (playbackIntentAtStart != playbackIntentSequence.get()) return;
                if (!auth.isLoggedIn()) {
                    snapshot = Snapshot.of(Status.LOGGED_OUT);
                } else {
                    snapshot = Snapshot.error(errText(e));
                }
            }
            if (!auth.isLoggedIn()) clearCurrentLyrics();
        }
    }

    private void parse(String body, long expectedPlaybackIntent) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonElement itemEl = o.get("item");
        if (itemEl == null || itemEl.isJsonNull()) {
            synchronized (playbackStateLock) {
                if (expectedPlaybackIntent != playbackIntentSequence.get()) return;
                snapshot = Snapshot.of(Status.IDLE);
            }
            clearCurrentLyrics();
            endLikedSession();
            return;
        }
        JsonObject item = itemEl.getAsJsonObject();

        String title = str(item, "name");
        long duration = lng(item, "duration_ms");
        String uri = str(item, "uri");
        String artists = artistsOf(item);
        String albumName = item.has("album") && item.get("album").isJsonObject()
                ? str(item.getAsJsonObject("album"), "name") : "";

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

        Track currentTrack = new Track(title, artists, cover, duration, uri, albumName);
        Snapshot current;
        Snapshot prev;
        synchronized (playbackStateLock) {
            // A newer click may arrive while this JSON is being decoded. Never let this
            // older response commit after that newer playback intent.
            if (expectedPlaybackIntent != playbackIntentSequence.get()) return;
            // No fixed transition delay: accept the new track immediately when its URI
            // matches the latest selection, but reject stale Spotify snapshots until then.
            String expectedUri = optimisticTrackUri;
            if (System.currentTimeMillis() < optimisticTrackUntilMs
                    && !expectedUri.isEmpty() && !expectedUri.equals(uri)) {
                return;
            }
            // Smooth the playhead only when the confirmed URI is unchanged.
            long prog = lng(o, "progress_ms");
            prev = snapshot;
            if (prev.status() == Status.ACTIVE && prev.playing() && playing && prev.track() != null
                    && !uri.isEmpty() && uri.equals(prev.track().uri())) {
                long predicted = prev.currentProgress();
                long diff = prog - predicted;
                if (Math.abs(diff) < 2500) prog = predicted + diff / 3;
            }
            current = new Snapshot(Status.ACTIVE, playing, currentTrack,
                    prog, System.currentTimeMillis(), device, volume, shuffle, repeat, "");
            snapshot = current;
            String previousUri = prev.track() == null ? "" : prev.track().uri();
            if (!uri.equals(previousUri)) requestLyricsForTrack(currentTrack);
        }
        releaseLikedQueueOnExternalTrackChange(prev, current);
        scheduleLikedAdvance(current);
        String optimisticUri = optimisticTrackUri;
        if (!optimisticUri.isEmpty()) {
            if (optimisticUri.equals(uri)
                    || System.currentTimeMillis() - optimisticTrackSetAtMs >= 5_000L) {
                optimisticTrackUntilMs = 0;
                optimisticTrackUri = "";
            }
        }
    }

    // --------------------------------------------------------------- lyrics (LiriQo)

    private void clearCurrentLyrics() {
        lyricsRequestSequence.incrementAndGet();
        Future<?> previous = lyricsFuture;
        if (previous != null) previous.cancel(true);
        lyricsFuture = null;
        lyricsRequestedUri = "";
        if (lyrics.status() != LyricsStatus.IDLE || !lyrics.trackUri().isEmpty()) {
            lyrics = Lyrics.empty();
        }
    }

    /** Start one on-demand lookup when Spotify reports a new track. There is no lyrics cache. */
    private void requestLyricsForTrack(Track track) {
        if (track == null || track.uri() == null || track.uri().isBlank()) {
            clearCurrentLyrics();
            return;
        }
        String uri = track.uri();
        if (uri.equals(lyricsRequestedUri)) return;
        lyricsRequestedUri = uri;
        long requestId = lyricsRequestSequence.incrementAndGet();
        Future<?> previous = lyricsFuture;
        if (previous != null) previous.cancel(true);
        lyrics = new Lyrics(uri, LyricsStatus.LOADING, false, List.of(), List.of(), "Ищем текст...");
        try {
            lyricsFuture = lyricsExecutor.submit(() -> {
                if (requestId != lyricsRequestSequence.get()) return;
                Lyrics result = fetchLyrics(track);
                if (requestId == lyricsRequestSequence.get()
                        && snapshot.track() != null && uri.equals(snapshot.track().uri())) {
                    lyrics = result;
                }
            });
        } catch (RuntimeException rejected) {
            if (requestId == lyricsRequestSequence.get()) {
                lyrics = new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                        "Не удалось запустить поиск текста");
            }
            LOG.debug("Lyrics lookup could not be queued", rejected);
        }
    }

    private Lyrics fetchLyrics(Track track) {
        String uri = track.uri();
        String title = track.title() == null ? "" : track.title().trim();
        String artists = track.artists() == null ? "" : track.artists().trim();
        String artist = artists.contains(",") ? artists.substring(0, artists.indexOf(',')).trim() : artists;
        String album = track.albumName() == null ? "" : track.albumName().trim();
        long durationSeconds = Math.max(0L, Math.round(track.durationMs() / 1000.0));
        if (title.isEmpty() || artist.isEmpty() || durationSeconds <= 0 || durationSeconds > 3600) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "Недостаточно данных для поиска текста");
        }

        CompletionService<Lyrics> completion = new ExecutorCompletionService<>(lyricsProviderExecutor);
        List<Future<Lyrics>> requests = new ArrayList<>(3);
        requests.add(completion.submit(() -> fetchProviderSafely("LiriQo", () ->
                fetchLyricsFromLiriQo(uri, title, artist, album, durationSeconds))));
        requests.add(completion.submit(() -> fetchProviderSafely("Lyrica", () ->
                fetchLyricsFromLyrica(uri, title, artist, album, durationSeconds))));
        requests.add(completion.submit(() -> fetchProviderSafely("Better Lyrics", () ->
                fetchLyricsFromBetterLyrics(uri, title, artist, album, durationSeconds))));

        Lyrics lastError = null;
        Lyrics instrumentalCandidate = null;
        try {
            for (int completed = 0; completed < requests.size(); completed++) {
                Future<Lyrics> finished = completion.take();
                try {
                    Lyrics candidate = finished.get();
                    if (candidate != null && candidate.status() == LyricsStatus.FOUND) {
                        LOG.debug("Lyrics found by {} for {} ({} timed lines)",
                                candidate.message().isBlank() ? "provider" : candidate.message(),
                                title, candidate.syncedLines().size());
                        return candidate;
                    }
                    if (candidate != null && candidate.status() == LyricsStatus.INSTRUMENTAL) {
                        instrumentalCandidate = candidate;
                    }
                    if (candidate != null && candidate.status() == LyricsStatus.ERROR) lastError = candidate;
                } catch (ExecutionException failedProvider) {
                    LOG.debug("A lyrics provider failed for {}", title, failedProvider.getCause());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(), "Поиск текста прерван");
        } finally {
            // Once a winner is found (or the track changes), do not leave other providers running.
            for (Future<Lyrics> request : requests) request.cancel(true);
        }

        if (instrumentalCandidate != null) return instrumentalCandidate;
        if (lastError != null) return lastError;
        return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                "Ни один источник не нашёл текст для этого трека");
    }

    @FunctionalInterface
    private interface LyricsLookup { Lyrics run() throws Exception; }

    private Lyrics fetchProviderSafely(String provider, LyricsLookup lookup) {
        try {
            return lookup.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Lyrics(lyricsRequestedUri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    provider + ": запрос отменён");
        } catch (Exception failure) {
            LOG.debug("{} lyrics lookup failed", provider, failure);
            return new Lyrics(lyricsRequestedUri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    provider + " временно недоступен");
        }
    }

    private Lyrics fetchLyricsFromLyrica(String uri, String title, String artist,
                                         String album, long durationSeconds) throws Exception {
        long now = System.currentTimeMillis();
        if (lyricaCooldownUntilMs > now) {
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "Lyrica временно ограничил запросы");
        }
        String query = "artist=" + encodeQuery(artist)
                + "&song=" + encodeQuery(title)
                + "&timestamps=true&word=true&fast=true";
        if (!album.isBlank()) query += "&album=" + encodeQuery(album);
        if (durationSeconds > 0) query += "&duration=" + durationSeconds;
        URI endpoint = URI.create("https://wilooper-lyrica.hf.space/lyrics/?" + query);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(28))
                .header("Accept", "application/json")
                .header("User-Agent", "SpotifyPanelMod/1.0 (Lyrica lyrics integration)")
                .GET().build();
        HttpResponse<String> response = Net.http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            long delay = Net.retryAfterDelayMillis(response.headers().firstValue("Retry-After").orElse(""),
                    15_000L, 300_000L);
            lyricaCooldownUntilMs = Math.max(lyricaCooldownUntilMs, System.currentTimeMillis() + delay);
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "Lyrica временно ограничил запросы");
        }
        if (response.statusCode() == 404) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(), "Lyrica не нашёл текст");
        }
        if (response.statusCode() != 200) {
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "Lyrica: HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) return new Lyrics(uri, LyricsStatus.NOT_FOUND, false,
                List.of(), List.of(), "Lyrica вернул пустой ответ");
        JsonObject root = parsed.getAsJsonObject();
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : root;
        String status = str(root, "status");
        if (!status.isBlank() && !"success".equalsIgnoreCase(status)) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(), "Lyrica не нашёл текст");
        }
        List<LyricLine> timed = parseLyricaTimedLines(data);
        String plain = str(data, "lyrics");
        List<String> plainLines = splitLyrics(plain);
        if (plainLines.isEmpty() && !timed.isEmpty()) {
            for (LyricLine line : timed) plainLines.add(line.text());
        }
        if (plainLines.isEmpty() && timed.isEmpty()) {
            if (bool(data, "instrumental")) return new Lyrics(uri, LyricsStatus.INSTRUMENTAL,
                    true, List.of(), List.of(), "Инструментальная композиция");
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(), "Lyrica не нашёл текст");
        }
        return new Lyrics(uri, LyricsStatus.FOUND, false, plainLines, timed, "Lyrica");
    }

    private Lyrics fetchLyricsFromBetterLyrics(String uri, String title, String artist,
                                               String album, long durationSeconds) throws Exception {
        long now = System.currentTimeMillis();
        if (betterLyricsCooldownUntilMs > now) {
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "Better Lyrics временно ограничил запросы");
        }
        String query = "s=" + encodeQuery(title)
                + "&a=" + encodeQuery(artist)
                + "&al=" + encodeQuery(album)
                + "&d=" + durationSeconds;
        URI endpoint = URI.create("https://api.betterlyrics.org/kugou/getLyrics?" + query);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(22))
                .header("Accept", "application/json")
                .header("User-Agent", "SpotifyPanelMod/1.0 (Better Lyrics integration)")
                .GET().build();
        HttpResponse<String> response = Net.http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            long delay = Net.retryAfterDelayMillis(response.headers().firstValue("Retry-After").orElse(""),
                    15_000L, 300_000L);
            betterLyricsCooldownUntilMs = Math.max(betterLyricsCooldownUntilMs,
                    System.currentTimeMillis() + delay);
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "Better Lyrics временно ограничил запросы");
        }
        if (response.statusCode() == 404) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "Better Lyrics не нашёл текст");
        }
        if (response.statusCode() != 200) {
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "Better Lyrics: HTTP " + response.statusCode());
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) return new Lyrics(uri, LyricsStatus.NOT_FOUND, false,
                List.of(), List.of(), "Better Lyrics вернул пустой ответ");
        JsonObject root = parsed.getAsJsonObject();
        String lrc = str(root, "lyrics");
        if (lrc.isBlank()) lrc = str(root, "lrc");
        LrcResult result = parseLrc(lrc);
        if (result.plainLines().isEmpty() && result.syncedLines().isEmpty()) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "Better Lyrics не нашёл текст");
        }
        return new Lyrics(uri, LyricsStatus.FOUND, false,
                result.plainLines(), result.syncedLines(), "Better Lyrics / Kugou");
    }

    private record LrcResult(List<String> plainLines, List<LyricLine> syncedLines) {}

    private static LrcResult parseLrc(String lrc) {
        if (lrc == null || lrc.isBlank()) return new LrcResult(List.of(), List.of());
        Pattern timestamp = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        java.util.LinkedHashSet<String> plain = new java.util.LinkedHashSet<>();
        List<LyricLine> timed = new ArrayList<>();
        for (String raw : lrc.replace("\r", "").split("\n")) {
            Matcher matcher = timestamp.matcher(raw);
            List<Long> times = new ArrayList<>();
            int lastEnd = -1;
            while (matcher.find()) {
                try {
                    long minutes = Long.parseLong(matcher.group(1));
                    long seconds = Long.parseLong(matcher.group(2));
                    String fraction = matcher.group(3);
                    long fractionMs = 0;
                    if (fraction != null) {
                        fractionMs = Long.parseLong(fraction);
                        if (fraction.length() == 1) fractionMs *= 100;
                        else if (fraction.length() == 2) fractionMs *= 10;
                    }
                    times.add(minutes * 60_000L + seconds * 1_000L + fractionMs);
                    lastEnd = matcher.end();
                } catch (NumberFormatException ignored) {}
            }
            String text = (lastEnd >= 0 ? raw.substring(lastEnd) : raw).strip();
            if (text.isEmpty() || text.startsWith("[ar:") || text.startsWith("[ti:")
                    || text.startsWith("[al:") || text.startsWith("[by:")
                    || text.startsWith("[offset:") || text.startsWith("[length:")) continue;
            plain.add(text);
            for (long time : times) timed.add(new LyricLine(time, text));
        }
        timed.sort(java.util.Comparator.comparingLong(LyricLine::timeMs));
        return new LrcResult(new ArrayList<>(plain), timed);
    }

    private static List<LyricLine> parseLyricaTimedLines(JsonObject data) {
        List<LyricLine> lines = new ArrayList<>();
        JsonElement array = data.get("timed_lyrics");
        if (array == null || !array.isJsonArray()) return lines;
        for (JsonElement element : array.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject line = element.getAsJsonObject();
            String text = str(line, "text").strip();
            if (text.isEmpty()) continue;
            long start = firstLong(line, "start_time", "startTimeMs", "start");
            if (start < 0) continue;
            lines.add(new LyricLine(start, text));
        }
        lines.sort(java.util.Comparator.comparingLong(LyricLine::timeMs));
        return lines;
    }

    private static long firstLong(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) continue;
            try { return Math.max(0L, Math.round(value.getAsDouble())); }
            catch (RuntimeException ignored) {}
        }
        return -1L;
    }

    private static List<String> splitLyrics(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        for (String line : text.replace("\r", "").split("\n")) {
            String clean = line.strip();
            if (!clean.isEmpty()) lines.add(clean);
        }
        return lines;
    }

    /** Fetch and normalize LiriQo's multi-provider response. */
    private Lyrics fetchLyricsFromLiriQo(String uri, String title, String artist,
                                         String album, long durationSeconds) throws Exception {
        // Full mode waits for every eligible provider within LiriQo's global deadline.
        // This gives us a chance to find synced lyrics even when the first result is plain text.
        String query = "title=" + encodeQuery(title)
                + "&artist=" + encodeQuery(artist)
                + "&duration=" + durationSeconds
                + "&full=1";
        if (!album.isEmpty()) query += "&album=" + encodeQuery(album);
        URI endpoint = URI.create("https://api.liriqo-alfarrizi.my.id/v1/lyrics?" + query);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(35))
                .header("Accept", "application/json")
                .header("User-Agent", "SpotifyPanelMod/1.0 (LiriQo lyrics integration)")
                .GET().build();

        long now = System.currentTimeMillis();
        long waitUntil = liriqoCooldownUntilMs;
        if (waitUntil > now) Thread.sleep(waitUntil - now);

        HttpResponse<String> response = Net.http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            long retryDelayMs = Net.retryAfterDelayMillis(
                    response.headers().firstValue("Retry-After").orElse(""), 5_000L, 300_000L);
            liriqoCooldownUntilMs = Math.max(liriqoCooldownUntilMs,
                    System.currentTimeMillis() + Math.max(1_000L, retryDelayMs));
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "LiriQo временно ограничил запросы. Повтори позже");
        }
        if (response.statusCode() == 404) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "Текст для этого трека не найден в LiriQo");
        }
        if (response.statusCode() == 403) {
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "LiriQo отклонил запрос");
        }
        if (response.statusCode() != 200) {
            return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                    "LiriQo: HTTP " + response.statusCode());
        }

        JsonElement rootElement = JsonParser.parseString(response.body());
        if (!rootElement.isJsonObject()) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "LiriQo вернул пустой ответ");
        }
        JsonObject root = rootElement.getAsJsonObject();
        if (root.has("error") && !root.get("error").isJsonNull()) {
            JsonElement error = root.get("error");
            String message = error.isJsonObject()
                    ? str(error.getAsJsonObject(), "message")
                    : (error.isJsonPrimitive() ? error.getAsString() : "");
            if (!message.isBlank()) {
                return new Lyrics(uri, LyricsStatus.ERROR, false, List.of(), List.of(),
                        "LiriQo: " + message);
            }
        }

        JsonObject primary = root.has("primary") && root.get("primary").isJsonObject()
                ? root.getAsJsonObject("primary") : null;
        List<JsonObject> candidates = new ArrayList<>();
        if (primary != null) candidates.add(primary);
        // LiriQo returns provider candidates in ranked order. The selected primary can
        // be plain-only while another provider in tracks[] has usable timestamps.
        if (root.has("tracks") && root.get("tracks").isJsonArray()) {
            for (JsonElement candidate : root.getAsJsonArray("tracks")) {
                if (candidate != null && candidate.isJsonObject()) {
                    JsonObject object = candidate.getAsJsonObject();
                    if (object != primary) candidates.add(object);
                }
            }
        }
        if (candidates.isEmpty()) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "Текст для этого трека не найден в LiriQo");
        }

        // Prefer the first candidate with real timestamps; candidates are already
        // quality-ranked by LiriQo. Only fall back to plain text when none are synced.
        JsonObject selected = null;
        List<LyricLine> selectedTimed = List.of();
        for (JsonObject candidate : candidates) {
            List<LyricLine> timed = parseLiriQoTimedLines(candidate);
            if (!timed.isEmpty()) {
                selected = candidate;
                selectedTimed = timed;
                break;
            }
        }
        if (selected == null) {
            for (JsonObject candidate : candidates) {
                String plain = str(candidate, "plain");
                if (!plain.isBlank() || bool(candidate, "instrumental")
                        || bool(candidate, "isInstrumental")) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected == null) selected = candidates.get(0);

        boolean instrumental = bool(selected, "instrumental") || bool(selected, "isInstrumental");
        if (instrumental && selectedTimed.isEmpty()) {
            return new Lyrics(uri, LyricsStatus.INSTRUMENTAL, true, List.of(), List.of(),
                    "Инструментальная композиция");
        }

        List<String> plainLines = new ArrayList<>();
        String plain = str(selected, "plain");
        if (!plain.isBlank()) {
            for (String line : plain.replace("\r", "").split("\n")) {
                String clean = line.strip();
                if (!clean.isEmpty()) plainLines.add(clean);
            }
        }
        // Some providers return only timed lines. Use their text as display content too.
        if (plainLines.isEmpty() && !selectedTimed.isEmpty()) {
            for (LyricLine line : selectedTimed) plainLines.add(line.text());
        }
        if (plainLines.isEmpty() && selectedTimed.isEmpty()) {
            return new Lyrics(uri, LyricsStatus.NOT_FOUND, false, List.of(), List.of(),
                    "LiriQo не нашёл текст для этого трека");
        }
        return new Lyrics(uri, LyricsStatus.FOUND, false, plainLines, selectedTimed, "LiriQo");
    }

    private static List<LyricLine> parseLiriQoTimedLines(JsonObject candidate) {
        List<LyricLine> lines = new ArrayList<>();
        if (candidate == null || !candidate.has("timed") || !candidate.get("timed").isJsonArray()) {
            return lines;
        }
        for (JsonElement lineElement : candidate.getAsJsonArray("timed")) {
            if (lineElement == null || !lineElement.isJsonObject()) continue;
            JsonObject line = lineElement.getAsJsonObject();
            if (bool(line, "isInstrumental")) continue;
            String text = str(line, "text").strip();
            if (text.isEmpty() || !line.has("start") || line.get("start").isJsonNull()) continue;
            try {
                // LiriQo normalized timed[].start values are absolute milliseconds.
                long startMs = Math.max(0L, Math.round(line.get("start").getAsDouble()));
                lines.add(new LyricLine(startMs, text));
            } catch (RuntimeException malformedTime) {
                // Ignore malformed individual lines without discarding valid neighbors.
            }
        }
        lines.sort(java.util.Comparator.comparingLong(LyricLine::timeMs));
        return lines;
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
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
        // Re-authentication or explicit library commands should observe the latest server state.
        libraryHttpCache.clear();
        playlistSnapshotIds.clear();
        loadedPlaylistSnapshotIds.clear();
        itemsTime.keySet().removeIf(key -> key.startsWith("playlist:"));
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
                    nextPlaylistTry = now + FULL_LIBRARY_REFRESH_INTERVAL_MS;
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
                    long delay = Net.retryAfterDelayMillis(
                            r.headers().firstValue("Retry-After").orElse(""), 5_000L, 5 * 60_000L);
                    retryAfterMs = Math.max(retryAfterMs, delay);
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

    /** Fetch only the newest 50 saved tracks for lightweight near-live synchronization. */
    private PageData fetchFirstPage(String endpoint) throws Exception {
        String separator = endpoint.contains("?") ? "&" : "?";
        HttpResponse<String> response = send("GET", endpoint + separator + "limit=50&offset=0", null);
        if (response.statusCode() != 200) {
            if (response.statusCode() == 401) auth.invalidate();
            throw new IOException("HTTP " + response.statusCode()
                    + (apiMessage(response.body()).isEmpty() ? "" : ": " + apiMessage(response.body())));
        }
        JsonObject page = JsonParser.parseString(response.body()).getAsJsonObject();
        List<JsonObject> objects = new ArrayList<>();
        JsonArray array = page.has("items") && page.get("items").isJsonArray()
                ? page.getAsJsonArray("items") : new JsonArray();
        for (JsonElement element : array) if (element.isJsonObject()) objects.add(element.getAsJsonObject());
        return new PageData(objects, (int) lng(page, "total"));
    }

    private void refreshVisibleCoreLibrary() {
        refreshVisibleCoreLibrary(false);
    }

    private void refreshVisibleCoreLibrary(boolean immediate) {
        long refreshStartedAt = System.currentTimeMillis();
        if (!auth.isLoggedIn() || SpotifyPanelMod.config == null || !SpotifyPanelMod.config.visible
                || visibleSelection == null || playlistsLoading.get() || refreshStartedAt < apiCooldownUntilMs
                || (immediate && refreshStartedAt - lastCoreLibraryRefreshAtMs < 5_000L)
                || (!immediate && refreshStartedAt - lastCoreLibraryRefreshAtMs < CORE_LIBRARY_REFRESH_INTERVAL_MS)
                || !coreLibraryLoading.compareAndSet(false, true)) return;
        try {
            // A full first-time load is already in progress; let it populate all categories.
            if (library.isEmpty()) return;

            PageData playlistPage = fetchOffsetPages("/me/playlists");
            PageData albumPage = fetchOffsetPages("/me/albums");
            PageData likedPage = fetchFirstPage("/me/tracks");

            List<LibraryEntry> freshPlaylists = new ArrayList<>();
            List<Playlist> freshPlaylistOnly = new ArrayList<>();
            Set<String> playlistIds = new HashSet<>();
            for (JsonObject object : playlistPage.objects()) {
                Playlist playlist = parsePlaylist(object);
                if (playlist != null && playlistIds.add(playlist.id())) {
                    updatePlaylistSnapshot(playlist.id(), str(object, "snapshot_id"));
                    freshPlaylistOnly.add(playlist);
                    freshPlaylists.add(asEntry(playlist));
                }
            }
            // Keep manually configured playlist URLs that are not returned by /me/playlists.
            Set<String> configuredExtras = new HashSet<>(SpotifyPanelMod.config.extraPlaylists);
            for (LibraryEntry previous : library) {
                if ("playlist".equals(previous.kind()) && configuredExtras.contains(previous.id())
                        && playlistIds.add(previous.id())) {
                    freshPlaylists.add(previous);
                    freshPlaylistOnly.add(new Playlist(previous.id(), previous.name(), previous.owner(),
                            previous.total(), previous.coverUrl(), previous.uri()));
                }
            }
            playlistSnapshotIds.keySet().retainAll(playlistIds);
            loadedPlaylistSnapshotIds.keySet().retainAll(playlistIds.stream()
                    .map(id -> "playlist:" + id).collect(java.util.stream.Collectors.toSet()));

            List<LibraryEntry> freshAlbums = new ArrayList<>();
            Set<String> albumIds = new HashSet<>();
            for (JsonObject wrapper : albumPage.objects()) {
                LibraryEntry album = albumEntry(nested(wrapper, "album"));
                if (album != null && albumIds.add(album.id())) freshAlbums.add(album);
            }

            List<Item> freshLikedHead = new ArrayList<>();
            String likedCover = "";
            for (JsonObject wrapper : likedPage.objects()) {
                JsonObject track = nested(wrapper, "track");
                Item parsed = parseTrackItem(track);
                if (parsed != null) freshLikedHead.add(parsed);
                if (likedCover.isEmpty() && track.has("album") && track.get("album").isJsonObject()) {
                    likedCover = bestImage(track.getAsJsonObject("album"));
                }
            }

            // Replace the cached first page while preserving deeper cached pages. This
            // immediately reflects recent likes/unlikes without re-downloading hundreds
            // of older tracks on every lightweight two-minute check; the full sync refreshes all pages.
            String likedKey = "saved_tracks:liked";
            List<Item> oldLiked = items.getOrDefault(likedKey, List.of());
            int oldHeadSize = Math.min(50, oldLiked.size());
            Set<String> newLikedUris = new HashSet<>();
            List<Item> mergedLiked = new ArrayList<>(freshLikedHead);
            for (Item item : freshLikedHead) newLikedUris.add(item.uri());
            if (likedPage.total() > freshLikedHead.size()) {
                for (int i = oldHeadSize; i < oldLiked.size(); i++) {
                    Item old = oldLiked.get(i);
                    if (old != null && old.uri() != null && newLikedUris.add(old.uri())) mergedLiked.add(old);
                }
            }
            List<Item> immutableLiked = List.copyOf(mergedLiked);
            items.put(likedKey, immutableLiked);
            itemsTime.put(likedKey, System.currentTimeMillis());
            refreshActiveLikedQueue(immutableLiked);

            List<LibraryEntry> updatedLibrary = new ArrayList<>();
            updatedLibrary.add(new LibraryEntry("liked", "Любимые треки", "Твоя медиатека",
                    likedPage.total(), likedCover, "", "saved_tracks"));
            updatedLibrary.addAll(freshPlaylists);
            updatedLibrary.addAll(freshAlbums);
            for (LibraryEntry old : library) {
                if (!"playlist".equals(old.kind()) && !"album".equals(old.kind())
                        && !"saved_tracks".equals(old.kind())) updatedLibrary.add(old);
            }
            updatedLibrary.sort((a, b) -> Integer.compare(libraryOrder(a.kind()), libraryOrder(b.kind())));
            library = List.copyOf(updatedLibrary);
            playlists = List.copyOf(freshPlaylistOnly);
            lastCoreLibraryRefreshAtMs = System.currentTimeMillis();

            // If the currently open playlist changed, refresh its visible rows immediately.
            LibraryEntry selected = visibleSelection;
            if (selected != null && "playlist".equals(selected.kind())) {
                LibraryEntry currentEntry = updatedLibrary.stream()
                        .filter(entry -> entry.key().equals(selected.key())).findFirst().orElse(null);
                if (currentEntry != null) requestItemsAsync(currentEntry, false);
            }
        } catch (Exception e) {
            // Keep the last good snapshot. The normal full refresh and API cooldown handle recovery.
            LOG.debug("Core Spotify library refresh failed", e);
        } finally {
            coreLibraryLoading.set(false);
        }
    }

    private void updatePlaylistSnapshot(String playlistId, String snapshotId) {
        if (playlistId == null || playlistId.isEmpty() || snapshotId == null || snapshotId.isEmpty()) return;
        String previous = playlistSnapshotIds.put(playlistId, snapshotId);
        if (previous == null || !previous.equals(snapshotId)) {
            itemsTime.remove("playlist:" + playlistId);
            String itemPathPrefix = "GET /playlists/" + playlistId + "/items";
            libraryHttpCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(itemPathPrefix));
        }
    }

    private void refreshActiveLikedQueue(List<Item> refreshedItems) {
        LikedPlayback previous = likedPlayback;
        if (previous == null || previous.sessionId() != likedSessionSequence.get()
                || previous.index() < 0 || previous.index() >= previous.items().size()) return;
        Item active = previous.items().get(previous.index());
        String activeUri = active == null ? "" : active.uri();
        if (activeUri.isEmpty()) return;

        int newIndex = -1;
        for (int i = 0; i < refreshedItems.size(); i++) {
            if (activeUri.equals(refreshedItems.get(i).uri())) { newIndex = i; break; }
        }
        List<Item> queueItems = new ArrayList<>(refreshedItems);
        if (newIndex < 0) {
            // Keep a song that is already playing until it ends even if it was just unliked.
            newIndex = Math.max(0, Math.min(previous.index(), queueItems.size()));
            queueItems.add(newIndex, active);
        }
        LikedPlayback replacement = new LikedPlayback(List.copyOf(queueItems), newIndex, previous.sessionId());
        synchronized (likedAdvanceLock) {
            if (likedPlayback == previous && previous.sessionId() == likedSessionSequence.get()) {
                likedPlayback = replacement;
                cancelLikedAdvanceTaskLocked(); // the next poll reschedules against the new list
            }
        }
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
                    long delay = Net.retryAfterDelayMillis(
                            r.headers().firstValue("Retry-After").orElse(""), 5_000L, 5 * 60_000L);
                    retryAfterMs = Math.max(retryAfterMs, delay);
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
                    updatePlaylistSnapshot(p.id(), str(obj, "snapshot_id"));
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
                if (r.statusCode() == 200) {
                    JsonObject detail = JsonParser.parseString(r.body()).getAsJsonObject();
                    p = parsePlaylist(detail);
                    updatePlaylistSnapshot(id, str(detail, "snapshot_id"));
                }
            } catch (Exception ignored) {}
            if (p == null) p = new Playlist(id, "Плейлист " + id.substring(0, Math.min(6, id.length())), "", 0, "", "spotify:playlist:" + id);
            playlistOnly.add(p);
            all.add(asEntry(p));
        }

        playlists = List.copyOf(playlistOnly);
        if (errors.stream().noneMatch(error -> error.startsWith("плейлисты:"))) {
            Set<String> livePlaylistIds = new HashSet<>();
            for (Playlist playlist : playlistOnly) livePlaylistIds.add(playlist.id());
            playlistSnapshotIds.keySet().retainAll(livePlaylistIds);
            loadedPlaylistSnapshotIds.keySet().retainAll(livePlaylistIds.stream()
                    .map(id -> "playlist:" + id).collect(java.util.stream.Collectors.toSet()));
        }
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
        lastCoreLibraryRefreshAtMs = System.currentTimeMillis();
        LibraryEntry activeSelection = visibleSelection;
        if (activeSelection != null && "playlist".equals(activeSelection.kind())) {
            LibraryEntry currentEntry = all.stream()
                    .filter(entry -> entry.key().equals(activeSelection.key())).findFirst().orElse(null);
            if (currentEntry != null) requestItemsAsync(currentEntry, false);
        }
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
        if (cached != null) {
            if ("album".equals(entry.kind())) {
                // Album tracklists are immutable; keep them cached for the entire session.
                return CompletableFuture.completedFuture(cached);
            }
            if ("playlist".equals(entry.kind())) {
                String currentSnapshot = playlistSnapshotIds.getOrDefault(entry.id(), "");
                String loadedSnapshot = loadedPlaylistSnapshotIds.getOrDefault(key, "");
                if (!currentSnapshot.isEmpty() && currentSnapshot.equals(loadedSnapshot)) {
                    return CompletableFuture.completedFuture(cached);
                }
            }
            if (!force && cachedAt != null && now - cachedAt < PLAYLIST_ITEMS_REFRESH_INTERVAL_MS) {
                return CompletableFuture.completedFuture(cached);
            }
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
        String playlistSnapshotAtStart = "playlist".equals(entry.kind())
                ? playlistSnapshotIds.getOrDefault(entry.id(), "") : "";
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
            if ("playlist".equals(entry.kind())) {
                String snapshotNow = playlistSnapshotIds.getOrDefault(entry.id(), "");
                if (playlistSnapshotAtStart.equals(snapshotNow) && !playlistSnapshotAtStart.isEmpty()) {
                    loadedPlaylistSnapshotIds.put(key, playlistSnapshotAtStart);
                } else if (!playlistSnapshotAtStart.equals(snapshotNow)) {
                    // The playlist changed while its pages were loading. Do not bless
                    // this potentially mixed-version result as current; retry once the
                    // in-flight load has been removed from itemLoads.
                    loadedPlaylistSnapshotIds.remove(key);
                    itemsTime.remove(key);
                    exec.schedule(() -> requestItemsAsync(entry, true), 250, TimeUnit.MILLISECONDS);
                }
            }
        } else if (!out.isEmpty()) {
            // Keep successfully fetched pages visible, but retain the API error and do not
            // mark the incomplete result as a fresh cache entry.
            items.put(key, List.copyOf(out));
        }
    }

    /**
     * Запускает выбранный раздел медиатеки с его первого доступного элемента.
     * Повторные клики во время загрузки списка заменяют предыдущее намерение.
     */
    public void playLibraryEntry(LibraryEntry entry, Consumer<String> err) {
        if (entry == null) return;
        // End the old queue before allocating a new playback intent, so an auto-advance
        // callback cannot win a race and invalidate this user's library selection.
        endLikedSession();
        long intentId = beginPlaybackIntent();

        if ("artist".equals(entry.kind())) {
            requestItems(entry, false); // список альбомов нужен для отображения раздела
            if (entry.uri() != null && !entry.uri().isEmpty()) {
                schedulePlaybackControl(intentId, "PUT", "/me/player/play",
                        contextBody(entry.uri(), ""), err);
            } else if (isPlaybackIntentCurrent(intentId)) {
                err.accept("Не удалось определить исполнителя для воспроизведения.");
            }
            return;
        }

        switch (entry.kind()) {
            case "playlist", "album" -> requestItemsAndThen(entry, false, loaded -> {
                if (!isPlaybackIntentCurrent(intentId)) return;
                if (loaded != null && !loaded.isEmpty()) {
                    playLibraryItem(entry, loaded.get(0), err, intentId);
                } else if (entry.uri() != null && !entry.uri().isEmpty()) {
                    // The collection context can still play if reading its visible list failed.
                    schedulePlaybackControl(intentId, "PUT", "/me/player/play",
                            contextBody(entry.uri(), ""), err);
                } else {
                    err.accept("Не удалось загрузить содержимое этого раздела Spotify.");
                }
            });
            case "saved_tracks", "saved_episodes", "show", "audiobook" ->
                    requestItemsAndThen(entry, false, loaded -> {
                        if (!isPlaybackIntentCurrent(intentId)) return;
                        if (loaded == null) {
                            String detail = itemError(entry);
                            err.accept(detail.isEmpty()
                                    ? "Не удалось загрузить содержимое этого раздела Spotify." : detail);
                        } else if (loaded.isEmpty()) {
                            err.accept("В этом разделе пока нет доступных для воспроизведения элементов.");
                        } else {
                            playLibraryItem(entry, loaded.get(0), err, intentId);
                        }
                    });
            default -> {
                if (entry.uri() != null && !entry.uri().isEmpty()) {
                    schedulePlaybackControl(intentId, "PUT", "/me/player/play",
                            contextBody(entry.uri(), ""), err);
                } else if (isPlaybackIntentCurrent(intentId)) {
                    err.accept("У этого раздела нет адреса воспроизведения Spotify.");
                }
            }
        }
    }

    public void playLibraryItem(LibraryEntry entry, Item item, Consumer<String> err) {
        if (entry == null || item == null || item.uri().isEmpty()) return;
        endLikedSession();
        playLibraryItem(entry, item, err, beginPlaybackIntent());
    }

    /** Запускает трек, найденный поиском, через контекст его альбома, когда URI альбома доступен. */
    public void playSearchResult(Item item, Consumer<String> err) {
        if (item == null || item.uri() == null || item.uri().isEmpty()) return;
        endLikedSession();
        long intentId = beginPlaybackIntent();
        setOptimisticTrack(item);
        playTrackFromAlbum(item, err, intentId);
    }

    private void playLibraryItem(LibraryEntry entry, Item item, Consumer<String> err, long intentId) {
        if (!isPlaybackIntentCurrent(intentId) || entry == null || item == null || item.uri().isEmpty()) return;
        setOptimisticTrack(item);

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
            long sessionId = likedSessionSequence.get();
            if (!setLikedPlayback(saved != null && selectedIndex >= 0
                    ? new LikedPlayback(List.copyOf(saved), selectedIndex, sessionId) : null, sessionId, intentId)) return;
            playTrackFromAlbum(item, err, intentId);
            return;
        }

        if (!endLikedSession(intentId)) return;
        switch (entry.kind()) {
            case "playlist", "album" -> playContextInternal(entry.uri(), item.uri(), err, intentId);
            case "artist" -> schedulePlaybackControl(intentId, "PUT", "/me/player/play",
                    contextBody(item.uri(), ""), err); // artist row is one of that artist's albums
            default -> playUris(List.of(item.uri()), err, intentId);
        }
    }

    private void playLikedItem(LikedPlayback queue, int index, Consumer<String> err) {
        if (queue == null || queue.items() == null || index < 0 || index >= queue.items().size()) return;
        Item item = queue.items().get(index);
        long intentId;
        synchronized (likedAdvanceLock) {
            // Claim this transition only while the same Liked Songs session is still active.
            // A newer library/search selection ends the session first, so a stale timer cannot
            // allocate a newer playback intent and override that user's selection.
            if (likedPlayback != queue || queue.sessionId() != likedSessionSequence.get()) return;
            intentId = beginPlaybackIntent();
            likedPlayback = new LikedPlayback(queue.items(), index, queue.sessionId());
            cancelLikedAdvanceTaskLocked();
        }
        setOptimisticTrack(item);
        playTrackFromAlbum(item, err, intentId);
    }

    private void playTrackFromAlbum(Item item, Consumer<String> err, long intentId) {
        if (!isPlaybackIntentCurrent(intentId) || item == null || item.uri().isEmpty()) return;
        if (item.albumUri() != null && !item.albumUri().isEmpty()) {
            playContextInternal(item.albumUri(), item.uri(), err, intentId);
        } else {
            // Fallback for an incomplete API response. Most track objects include album.uri.
            playUris(List.of(item.uri()), err, intentId);
        }
    }

    // ------------------------------------------------------------- covers

    /** Artwork pixels, asynchronously loaded and downsampled. Returns null while the first attempt/retry is pending. */
    public int[] pixels(String url, int size) {
        if (url == null || url.isBlank() || size <= 0) return null;
        // Spotify artwork is served over HTTPS. Ignore malformed/non-web URLs instead of
        // creating a permanent retry loop for an invalid value from incomplete metadata.
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null;
        String key = url + "|" + size;
        int[] m = mosaics.get(key);
        if (m != null) return m;
        int[] raw = rawCache.get(url);
        if (raw == null) {
            long now = System.currentTimeMillis();
            long retryAt = coverRetryAtMs.getOrDefault(url, 0L);
            if (now >= retryAt && coverLoading.add(url)) {
                try {
                    coverExec.execute(() -> loadRaw(url));
                } catch (RuntimeException rejected) {
                    coverLoading.remove(url);
                    LOG.debug("Artwork load could not be queued: {}", url, rejected);
                }
            }
            return null;
        }
        m = downsample(RAW, RAW, (x, y) -> raw[y * RAW + x], size);
        int[] existing = mosaics.putIfAbsent(key, m);
        return existing != null ? existing : m;
    }

    /** URLs whose artwork has just become available; consumed by the world-panel renderer. */
    public List<String> drainCompletedCoverUrls() {
        List<String> completed = new ArrayList<>();
        String url;
        while ((url = completedCoverUrls.poll()) != null) completed.add(url);
        return completed;
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
        long serverRetryDelayMs = 0L;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build();
            HttpResponse<byte[]> r = Net.http().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                serverRetryDelayMs = Net.retryAfterDelayMillis(
                        r.headers().firstValue("Retry-After").orElse(""), 0L, 300_000L);
                throw new IOException("HTTP " + r.statusCode());
            }
            int[] raw = decodeAwt(r.body());
            if (raw == null) raw = decodeNative(r.body());
            if (raw == null) throw new IOException("Unsupported or invalid artwork image");

            rawCache.put(url, raw);
            coverAccents.put(url, extractAccentColor(raw));
            coverRetryAtMs.remove(url);
            coverAttempts.remove(url);
            // Publish completion only after the pixel cache is visible to readers.
            completedCoverUrls.add(url);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            scheduleCoverRetry(url, e, serverRetryDelayMs);
        } finally {
            coverLoading.remove(url);
        }
    }

    /** Retry forever, but back off to at most once per minute for a persistently bad URL. */
    private void scheduleCoverRetry(String url, Exception failure, long serverDelayMs) {
        int attempt = coverAttempts.computeIfAbsent(url, ignored -> new AtomicInteger()).incrementAndGet();
        long exponentialDelay = Math.min(60_000L, 1_500L << Math.min(6, Math.max(0, attempt - 1)));
        long delay = Math.min(5 * 60_000L, Math.max(exponentialDelay, serverDelayMs));
        coverRetryAtMs.put(url, System.currentTimeMillis() + delay);

        // Avoid flooding the log while a CDN URL is unavailable for a long period.
        if (attempt == 1 || attempt % 5 == 0) {
            LOG.warn("Artwork load failed (attempt {}, retry in {} ms): {} ({})",
                    attempt, delay, url, failure.toString());
        } else {
            LOG.debug("Artwork load failed (attempt {}, retry in {} ms): {}",
                    attempt, delay, url, failure.toString());
        }

        try {
            coverExec.schedule(() -> {
                if (rawCache.containsKey(url) || !coverLoading.add(url)) return;
                try {
                    coverExec.execute(() -> loadRaw(url));
                } catch (RuntimeException rejected) {
                    coverLoading.remove(url);
                    LOG.debug("Artwork retry could not be queued: {}", url, rejected);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            LOG.debug("Artwork retry could not be scheduled: {}", url, rejected);
        }
    }

    private static int[] decodeNative(byte[] bytes) {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes))) {
            return downsample(img.getWidth(), img.getHeight(), img::getColorArgb, RAW);
        } catch (Exception e) {
            LOG.warn("NativeImage decode failed", e);
            return null;
        }
    }

    private static int[] decodeAwt(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return null;
            return downsample(img.getWidth(), img.getHeight(), img::getRGB, RAW);
        } catch (Exception e) {
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
            pauseLikedQueue(queue, err);
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
        endLikedSession();
        long intentId = beginPlaybackIntent();
        playContextInternal(contextUri, trackUri, err, intentId);
    }

    /** Внутренний запрос контекста, который не сбрасывает навигацию по «Любимым трекам». */
    private void playContextInternal(String contextUri, String trackUri, Consumer<String> err, long intentId) {
        schedulePlaybackControl(intentId, "PUT", "/me/player/play", contextBody(contextUri, trackUri), err);
    }

    private String contextBody(String contextUri, String trackUri) {
        JsonObject body = new JsonObject();
        body.addProperty("context_uri", contextUri);
        if (trackUri != null && !trackUri.isEmpty()) {
            JsonObject off = new JsonObject();
            off.addProperty("uri", trackUri);
            body.add("offset", off);
        }
        return body.toString();
    }

    /** Запускает отдельные сохранённые треки/эпизоды через список URI (Spotify принимает до 100 URI). */
    public void playUris(List<String> uris, Consumer<String> err) {
        playUris(uris, err, beginPlaybackIntent());
    }

    private void playUris(List<String> uris, Consumer<String> err, long intentId) {
        JsonArray array = new JsonArray();
        if (uris != null) for (String uri : uris) if (uri != null && !uri.isEmpty() && array.size() < 100) array.add(uri);
        if (array.isEmpty()) {
            if (isPlaybackIntentCurrent(intentId)) err.accept("Нет доступных элементов для запуска.");
            return;
        }
        JsonObject body = new JsonObject();
        body.add("uris", array);
        schedulePlaybackControl(intentId, "PUT", "/me/player/play", body.toString(), err);
    }

    private boolean isPlaybackIntentCurrent(long intentId) {
        return playbackIntentSequence.get() == intentId;
    }

    /** Begin a new user playback choice and cancel any not-yet-sent older choice. */
    private long beginPlaybackIntent() {
        synchronized (playbackStateLock) {
            long intentId = playbackIntentSequence.incrementAndGet();
            synchronized (playbackScheduleLock) {
                if (pendingPlaybackCommand != null) {
                    pendingPlaybackCommand.cancel(false);
                    pendingPlaybackCommand = null;
                }
            }
            lastLocalAt = System.currentTimeMillis();
            // Track switching has no fixed cooldown. Poll responses are reconciled against
            // the selected URI, so an old track cannot overwrite the new selection.
            return intentId;
        }
    }

    private void invalidatePendingPlayback() {
        synchronized (playbackStateLock) {
            playbackIntentSequence.incrementAndGet();
            synchronized (playbackScheduleLock) {
                if (pendingPlaybackCommand != null) {
                    pendingPlaybackCommand.cancel(false);
                    pendingPlaybackCommand = null;
                }
            }
        }
    }

    /** Trailing-edge debounce: only the last rapid track click is sent to Spotify. */
    private void schedulePlaybackControl(long intentId, String method, String path, String body,
                                         Consumer<String> err) {
        synchronized (playbackScheduleLock) {
            if (!isPlaybackIntentCurrent(intentId)) return;
            if (pendingPlaybackCommand != null) pendingPlaybackCommand.cancel(false);
            pendingPlaybackCommand = exec.schedule(() -> {
                synchronized (playbackScheduleLock) {
                    if (!isPlaybackIntentCurrent(intentId)) return;
                    pendingPlaybackCommand = null;
                }
                ctl.submit(() -> {
                    if (isPlaybackIntentCurrent(intentId)) {
                        doControl(method, path, body, err, intentId);
                    }
                });
            }, PLAYBACK_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void control(String method, String path, String body, Consumer<String> err) {
        // An explicit transport/control action supersedes a pending not-yet-sent track click.
        invalidatePendingPlayback();
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
        return doControl(method, path, body, err, -1L);
    }

    private boolean doControl(String method, String path, String body, Consumer<String> err, long guardedIntentId) {
        boolean guarded = guardedIntentId >= 0;
        try {
            if (guarded && !isPlaybackIntentCurrent(guardedIntentId)) return false;
            if (!auth.isLoggedIn()) {
                if (!guarded || isPlaybackIntentCurrent(guardedIntentId)) {
                    err.accept("Сначала войди: /spotify login");
                    revert();
                }
                return false;
            }
            if (guarded) {
                synchronized (playbackStateLock) {
                    if (!isPlaybackIntentCurrent(guardedIntentId)) return false;
                    lastLocalAt = System.currentTimeMillis();
                }
            }
            HttpResponse<String> r = send(method, path, body);
            // If another track was clicked while this HTTP request was in flight, its response
            // must not show an obsolete error or schedule polls for the previous choice.
            if (guarded && !isPlaybackIntentCurrent(guardedIntentId)) return false;
            int sc = r.statusCode();
            if (sc >= 300 && guarded && isPlaybackIntentCurrent(guardedIntentId)) {
                optimisticTrackUntilMs = 0;
                optimisticTrackUri = "";
            }
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
                if (guarded) {
                    exec.schedule(() -> {
                        if (isPlaybackIntentCurrent(guardedIntentId)) poll();
                    }, 140, TimeUnit.MILLISECONDS);
                    exec.schedule(() -> {
                        if (isPlaybackIntentCurrent(guardedIntentId)) poll();
                    }, 420, TimeUnit.MILLISECONDS);
                    exec.schedule(() -> {
                        if (isPlaybackIntentCurrent(guardedIntentId)) poll();
                    }, 850, TimeUnit.MILLISECONDS);
                } else {
                    // Pull player state promptly after transport/playback commands.
                    exec.schedule(this::poll, 140, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 420, TimeUnit.MILLISECONDS);
                    exec.schedule(this::poll, 850, TimeUnit.MILLISECONDS);
                }
                return true;
            }
        } catch (Exception e) {
            if (!guarded || isPlaybackIntentCurrent(guardedIntentId)) {
                err.accept("Ошибка: " + errText(e));
                revert();
            }
            return false;
        }
    }
}
