package me.spotifypanel.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.spotifypanel.SpotifyPanel;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Весь сетевой код Spotify. Главное отличие от старой версии: запросов МНОГО меньше
 * (иначе Spotify отвечает 429 QUOTA_EXCEEDED для приложений в Development Mode):
 *  - состояние плеера опрашивается раз в 7 с (3 с при открытом курсоре), только пока панель видна;
 *  - плейлисты кешируются на диск и обновляются раз в 30 минут;
 *  - треки плейлиста грузятся только при выборе и кешируются в памяти;
 *  - после 429 все запросы замораживаются на время Retry-After.
 */
public final class SpotifyClient {
	public enum Status { NO_CLIENT_ID, NOT_LOGGED_IN, LOADING, IDLE, ACTIVE }

	public static final class Track {
		public String uri = "";
		public String name = "";
		public String artists = "";
		public String coverUrl = "";
		public long durationMs;
	}

	public static final class Playlist {
		public String id = "";
		public String name = "";
		public String coverUrl = "";
		public int total = -1;
	}

	public static final class TrackList {
		public final List<Track> tracks = new CopyOnWriteArrayList<>();
		public volatile int total = -1;
		public volatile boolean loading;
		public volatile String error;
		public volatile long time;
	}

	public static final class State {
		public Status status = Status.LOADING;
		public boolean playing;
		public boolean shuffle;
		public String repeat = "off";
		public int volume = -1;
		public long progressMs;
		public long stamp = System.currentTimeMillis();
		public Track track;

		State copy() {
			State s = new State();
			s.status = status;
			s.playing = playing;
			s.shuffle = shuffle;
			s.repeat = repeat;
			s.volume = volume;
			s.progressMs = progressMs;
			s.stamp = stamp;
			s.track = track;
			return s;
		}

		public long progressNow() {
			long p = progressMs;
			if (playing) p += System.currentTimeMillis() - stamp;
			if (track != null && track.durationMs > 0) p = Math.min(p, track.durationMs);
			return Math.max(0, p);
		}
	}

	private static final class RateLimited extends Exception {
		RateLimited() {
			super("rate limited");
		}
	}

	private static final class Resp {
		int code;
		String body = "";
	}

	private static final class Cache {
		long time;
		List<Playlist> playlists = new ArrayList<>();
	}

	private static final String API = "https://api.spotify.com/v1";
	private static final long PLAYLIST_TTL_MS = 30 * 60_000L;
	private static final long TRACKS_TTL_MS = 5 * 60_000L;
	private static final Gson GSON = new Gson();

	private final Config config;
	private final SpotifyAuth auth;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "spotifypanel-api");
		t.setDaemon(true);
		return t;
	});

	public volatile State state = new State();
	public volatile List<Playlist> playlists;
	public volatile String playlistsError;
	public final Map<String, TrackList> lists = new ConcurrentHashMap<>();
	public volatile boolean visible = true;
	public volatile boolean cursor = false;
	public volatile String toast = "";
	public volatile long toastUntil = 0;
	public volatile String error = "";

	private volatile long blockedUntil = 0;
	private volatile boolean playlistsLoading;
	private long playlistsTime = 0;
	private long nextPlaylistTry = 0;
	private long nextPoll = 0;
	private boolean wasVisible = true;

	public SpotifyClient(Config config, SpotifyAuth auth) {
		this.config = config;
		this.auth = auth;
		loadCache();
	}

	public void start() {
		exec.scheduleWithFixedDelay(() -> {
			try {
				tick();
			} catch (Throwable t) {
				SpotifyPanel.LOGGER.warn("tick failed", t);
			}
		}, 1, 500, TimeUnit.MILLISECONDS);
	}

	/** Сколько мс ещё действует блокировка из-за 429. */
	public long blockedMs() {
		return Math.max(0, blockedUntil - System.currentTimeMillis());
	}

	public void toast(String s) {
		toast = s;
		toastUntil = System.currentTimeMillis() + 4000;
	}

	/** Полный сброс состояния (после login/logout). */
	public void reset() {
		playlists = null;
		playlistsError = null;
		playlistsTime = 0;
		nextPlaylistTry = 0;
		lists.clear();
		state = new State();
		nextPoll = 0;
		blockedUntil = 0;
	}

	// ------------------------------------------------------------------ tick

	private void tick() throws Exception {
		long now = System.currentTimeMillis();
		if (!auth.hasClientId()) {
			setStatus(Status.NO_CLIENT_ID);
			return;
		}
		if (!auth.isLoggedIn()) {
			setStatus(Status.NOT_LOGGED_IN);
			return;
		}
		if (state.status == Status.NO_CLIENT_ID || state.status == Status.NOT_LOGGED_IN) setStatus(Status.LOADING);
		if (!visible) {
			wasVisible = false;
			return;
		}
		if (!wasVisible) {
			wasVisible = true;
			nextPoll = 0;
		}
		if (now < blockedUntil) return;

		if (!playlistsLoading && now >= nextPlaylistTry
			&& (playlists == null || now - playlistsTime > PLAYLIST_TTL_MS)) {
			loadPlaylists();
			if (System.currentTimeMillis() < blockedUntil) return;
		}
		if (System.currentTimeMillis() >= nextPoll) {
			poll();
			nextPoll = System.currentTimeMillis() + (cursor ? 3000 : 7000);
		}
	}

	private void setStatus(Status s) {
		if (state.status != s) {
			State n = state.copy();
			n.status = s;
			state = n;
		}
	}

	// ------------------------------------------------------------------ HTTP

	private Resp call(String method, String path, String json) throws Exception {
		if (System.currentTimeMillis() < blockedUntil) throw new RateLimited();
		for (int attempt = 0; attempt < 2; attempt++) {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + auth.accessToken());
			if (json != null) {
				b.header("Content-Type", "application/json");
				b.method(method, HttpRequest.BodyPublishers.ofString(json));
			} else if (method.equals("GET")) {
				b.GET();
			} else {
				b.method(method, HttpRequest.BodyPublishers.noBody());
			}
			HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
			int code = r.statusCode();
			if (code == 401 && attempt == 0) {
				auth.invalidate();
				continue;
			}
			if (code == 429) {
				long retry = r.headers().firstValue("Retry-After").map(v -> {
					try {
						return Long.parseLong(v.trim());
					} catch (Exception e) {
						return 30L;
					}
				}).orElse(30L);
				boolean quota = r.body() != null && r.body().contains("QUOTA_EXCEEDED");
				long ms = Math.min(Math.max(retry, quota ? 60 : 5), 24 * 3600) * 1000;
				blockedUntil = System.currentTimeMillis() + ms;
				throw new RateLimited();
			}
			Resp out = new Resp();
			out.code = code;
			out.body = r.body() == null ? "" : r.body();
			return out;
		}
		throw new java.io.IOException("auth failed");
	}

	private static String apiError(Resp r) {
		try {
			JsonObject o = JsonParser.parseString(r.body).getAsJsonObject();
			if (o.has("error") && o.get("error").isJsonObject()) {
				JsonObject e = o.getAsJsonObject("error");
				String m = str(e, "message");
				String reason = str(e, "reason");
				return "HTTP " + r.code + ": " + (m.isEmpty() ? reason : m) + (reason.isEmpty() || m.isEmpty() ? "" : " (" + reason + ")");
			}
		} catch (Exception ignored) {
		}
		return "HTTP " + r.code;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ playback state

	private void poll() {
		try {
			Resp r = call("GET", "/me/player", null);
			if (r.code == 204) {
				State n = new State();
				n.status = Status.IDLE;
				state = n;
				error = "";
				return;
			}
			if (r.code != 200) {
				error = apiError(r);
				return;
			}
			JsonObject o = JsonParser.parseString(r.body).getAsJsonObject();
			State n = new State();
			n.status = Status.ACTIVE;
			n.playing = bool(o, "is_playing");
			n.shuffle = bool(o, "shuffle_state");
			String rep = str(o, "repeat_state");
			n.repeat = rep.isEmpty() ? "off" : rep;
			n.progressMs = lng(o, "progress_ms");
			n.stamp = System.currentTimeMillis();
			JsonObject dev = obj(o, "device");
			if (dev != null && dev.has("volume_percent") && !dev.get("volume_percent").isJsonNull()) {
				n.volume = dev.get("volume_percent").getAsInt();
			}
			n.track = parseTrack(obj(o, "item"));
			state = n;
			error = "";
		} catch (RateLimited e) {
			// пауза уже выставлена
		} catch (Exception e) {
			error = "Spotify: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
		}
	}

	private void pollSoon(long delayMs) {
		exec.schedule(() -> {
			if (System.currentTimeMillis() >= blockedUntil) {
				poll();
				nextPoll = System.currentTimeMillis() + (cursor ? 3000 : 7000);
			}
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	// ------------------------------------------------------------------ playlists

	private void loadPlaylists() {
		playlistsLoading = true;
		try {
			List<Playlist> out = new ArrayList<>();
			int offset = 0;
			while (offset < 100) {
				Resp r = call("GET", "/me/playlists?limit=50&offset=" + offset, null);
				if (r.code != 200) {
					playlistsError = apiError(r);
					nextPlaylistTry = System.currentTimeMillis() + 60_000;
					return;
				}
				JsonObject o = JsonParser.parseString(r.body).getAsJsonObject();
				JsonArray items = arr(o, "items");
				if (items == null) break;
				for (JsonElement e : items) {
					if (!e.isJsonObject()) continue;
					JsonObject p = e.getAsJsonObject();
					Playlist pl = new Playlist();
					pl.id = str(p, "id");
					pl.name = str(p, "name");
					pl.coverUrl = bestImage(p);
					JsonObject cnt = obj(p, "items");
					if (cnt == null) cnt = obj(p, "tracks");
					pl.total = cnt != null && cnt.has("total") ? cnt.get("total").getAsInt() : -1;
					if (!pl.id.isEmpty()) out.add(pl);
				}
				if (o.has("next") && !o.get("next").isJsonNull() && items.size() == 50) offset += 50;
				else break;
			}
			playlists = out;
			playlistsError = null;
			playlistsTime = System.currentTimeMillis();
			saveCache();
		} catch (RateLimited e) {
			// пауза выставлена, повторим после неё
		} catch (Exception e) {
			playlistsError = "Не удалось загрузить плейлисты: " + e.getMessage();
			nextPlaylistTry = System.currentTimeMillis() + 60_000;
		} finally {
			playlistsLoading = false;
		}
	}

	private Path cacheFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("spotifypanel-cache.json");
	}

	private void loadCache() {
		try {
			Path p = cacheFile();
			if (!Files.exists(p)) return;
			Cache c = GSON.fromJson(Files.readString(p), Cache.class);
			if (c != null && c.playlists != null && !c.playlists.isEmpty()) {
				playlists = c.playlists;
				playlistsTime = c.time;
			}
		} catch (Exception ignored) {
		}
	}

	private void saveCache() {
		try {
			Cache c = new Cache();
			c.time = playlistsTime;
			c.playlists = playlists;
			Files.writeString(cacheFile(), GSON.toJson(c));
		} catch (Exception ignored) {
		}
	}

	// ------------------------------------------------------------------ tracks

	public void loadTracks(Playlist pl, boolean more) {
		TrackList tl = lists.computeIfAbsent(pl.id, k -> new TrackList());
		if (tl.loading) return;
		if (!more && !tl.tracks.isEmpty() && System.currentTimeMillis() - tl.time < TRACKS_TTL_MS) return;
		if (more && (tl.total < 0 || tl.tracks.size() >= tl.total || tl.tracks.size() >= 300)) return;
		tl.loading = true;
		exec.execute(() -> {
			try {
				if (!more) {
					tl.tracks.clear();
					tl.total = -1;
				}
				int offset = tl.tracks.size();
				String q = "?limit=50&offset=" + offset + "&market=from_token";
				Resp r = call("GET", "/playlists/" + pl.id + "/items" + q, null);
				if (r.code == 404 || r.code == 400) {
					// старый адрес для приложений, которых ещё не перевели на новый API
					r = call("GET", "/playlists/" + pl.id + "/tracks" + q, null);
				}
				if (r.code == 403 || r.code == 404) {
					tl.error = "Spotify не отдаёт треки этого плейлиста";
					return;
				}
				if (r.code != 200) {
					tl.error = apiError(r);
					return;
				}
				JsonObject o = JsonParser.parseString(r.body).getAsJsonObject();
				tl.total = o.has("total") && !o.get("total").isJsonNull() ? o.get("total").getAsInt() : tl.tracks.size();
				JsonArray items = arr(o, "items");
				if (items != null) {
					for (JsonElement e : items) {
						if (!e.isJsonObject()) continue;
						JsonObject it = e.getAsJsonObject();
						JsonObject t = obj(it, "item");
						if (t == null) t = obj(it, "track");
						Track tr = parseTrack(t);
						if (tr != null && !tr.uri.isEmpty()) tl.tracks.add(tr);
					}
				}
				tl.error = null;
				tl.time = System.currentTimeMillis();
			} catch (RateLimited e) {
				tl.error = null;
			} catch (Exception e) {
				tl.error = "Ошибка: " + e.getMessage();
			} finally {
				tl.loading = false;
			}
		});
	}

	// ------------------------------------------------------------------ commands

	private interface Task {
		void run() throws Exception;
	}

	private void run(String what, Task t) {
		exec.execute(() -> {
			try {
				t.run();
			} catch (RateLimited e) {
				toast("Лимит запросов Spotify. Подожди немного.");
			} catch (Exception e) {
				toast(what + ": " + e.getMessage());
			}
		});
	}

	private void mutate(Consumer<State> c) {
		State s = state.copy();
		c.accept(s);
		state = s;
	}

	private void check(Resp r) {
		if (r.code == 204 || r.code == 200 || r.code == 202) return;
		if (r.code == 403) toast("Нужен Spotify Premium (или действие недоступно)");
		else if (r.code == 404) toast("Нет активного устройства. Открой Spotify.");
		else toast(apiError(r));
	}

	private String pickDevice() throws Exception {
		Resp r = call("GET", "/me/player/devices", null);
		if (r.code != 200) return null;
		JsonArray ds = arr(JsonParser.parseString(r.body).getAsJsonObject(), "devices");
		if (ds == null) return null;
		String first = null;
		for (JsonElement e : ds) {
			JsonObject d = e.getAsJsonObject();
			if (bool(d, "is_restricted")) continue;
			if (first == null) first = str(d, "id");
			if (bool(d, "is_active")) return str(d, "id");
		}
		return first;
	}

	public void togglePlay() {
		State s = state;
		boolean play = !s.playing;
		boolean active = s.status == Status.ACTIVE;
		mutate(n -> {
			n.playing = play;
			n.progressMs = n.progressNow();
			n.stamp = System.currentTimeMillis();
		});
		run("Play", () -> {
			if (play && !active) {
				String dev = pickDevice();
				if (dev == null) {
					toast("Нет устройства. Открой Spotify на ПК/телефоне.");
					return;
				}
				check(call("PUT", "/me/player/play?device_id=" + enc(dev), null));
			} else {
				check(call("PUT", play ? "/me/player/play" : "/me/player/pause", null));
			}
			pollSoon(900);
		});
	}

	public void next() {
		run("Next", () -> {
			check(call("POST", "/me/player/next", null));
			pollSoon(900);
		});
	}

	public void previous() {
		run("Prev", () -> {
			check(call("POST", "/me/player/previous", null));
			pollSoon(900);
		});
	}

	public void toggleShuffle() {
		boolean v = !state.shuffle;
		mutate(n -> n.shuffle = v);
		run("Shuffle", () -> check(call("PUT", "/me/player/shuffle?state=" + v, null)));
	}

	public void cycleRepeat() {
		String cur = state.repeat;
		String nx = cur.equals("off") ? "context" : cur.equals("context") ? "track" : "off";
		mutate(n -> n.repeat = nx);
		run("Repeat", () -> check(call("PUT", "/me/player/repeat?state=" + nx, null)));
	}

	public void setVolume(int percent) {
		int p = Math.max(0, Math.min(100, percent));
		mutate(n -> n.volume = p);
		run("Volume", () -> check(call("PUT", "/me/player/volume?volume_percent=" + p, null)));
	}

	public void seek(long ms) {
		long p = Math.max(0, ms);
		mutate(n -> {
			n.progressMs = p;
			n.stamp = System.currentTimeMillis();
		});
		run("Seek", () -> check(call("PUT", "/me/player/seek?position_ms=" + p, null)));
	}

	public void playTrack(Playlist pl, Track t) {
		boolean active = state.status == Status.ACTIVE;
		mutate(n -> {
			n.track = t;
			n.playing = true;
			n.progressMs = 0;
			n.stamp = System.currentTimeMillis();
		});
		run("Play", () -> {
			String q = "";
			if (!active) {
				String dev = pickDevice();
				if (dev == null) {
					toast("Нет устройства. Открой Spotify на ПК/телефоне.");
					return;
				}
				q = "?device_id=" + enc(dev);
			}
			JsonObject body = new JsonObject();
			body.addProperty("context_uri", "spotify:playlist:" + pl.id);
			JsonObject off = new JsonObject();
			off.addProperty("uri", t.uri);
			body.add("offset", off);
			check(call("PUT", "/me/player/play" + q, body.toString()));
			pollSoon(1200);
		});
	}

	// ------------------------------------------------------------------ parsing helpers

	private static Track parseTrack(JsonObject o) {
		if (o == null) return null;
		Track t = new Track();
		t.uri = str(o, "uri");
		t.name = str(o, "name");
		t.durationMs = lng(o, "duration_ms");
		StringBuilder a = new StringBuilder();
		JsonArray artists = arr(o, "artists");
		if (artists != null) {
			for (JsonElement e : artists) {
				if (!e.isJsonObject()) continue;
				String n = str(e.getAsJsonObject(), "name");
				if (n.isEmpty()) continue;
				if (a.length() > 0) a.append(", ");
				a.append(n);
			}
		}
		JsonObject album = obj(o, "album");
		if (album == null) album = obj(o, "show");
		if (a.length() == 0 && album != null) a.append(str(album, "publisher"));
		t.artists = a.toString();
		t.coverUrl = album == null ? "" : bestImage(album);
		return t;
	}

	/** Самая маленькая картинка шириной не меньше 64px. */
	private static String bestImage(JsonObject o) {
		JsonArray imgs = arr(o, "images");
		if (imgs == null || imgs.isEmpty()) return "";
		String best = "";
		long bestW = Long.MAX_VALUE;
		String biggest = "";
		long biggestW = -1;
		for (JsonElement e : imgs) {
			if (!e.isJsonObject()) continue;
			JsonObject im = e.getAsJsonObject();
			String url = str(im, "url");
			long w = im.has("width") && !im.get("width").isJsonNull() ? im.get("width").getAsLong() : 300;
			if (w >= 64 && w < bestW) {
				bestW = w;
				best = url;
			}
			if (w > biggestW) {
				biggestW = w;
				biggest = url;
			}
		}
		return best.isEmpty() ? biggest : best;
	}

	private static String str(JsonObject o, String k) {
		return o != null && o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	private static long lng(JsonObject o, String k) {
		return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
	}

	private static boolean bool(JsonObject o, String k) {
		return o != null && o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
	}

	private static JsonObject obj(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
	}

	private static JsonArray arr(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
	}
}
