package me.spotifypanel.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** OAuth (Authorization Code + PKCE). Redirect URI: http://127.0.0.1:8888/callback */
public final class SpotifyAuth {
	public static final int PORT = 8888;
	public static final String REDIRECT = "http://127.0.0.1:8888/callback";
	public static final String SCOPES =
		"user-read-playback-state user-modify-playback-state playlist-read-private playlist-read-collaborative";

	private final Config config;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final SecureRandom random = new SecureRandom();
	private volatile String accessToken = "";
	private volatile long expiresAtMs = 0;

	public SpotifyAuth(Config config) {
		this.config = config;
	}

	public boolean hasClientId() {
		return !config.clientId.isEmpty();
	}

	public boolean isLoggedIn() {
		return !config.refreshToken.isEmpty();
	}

	public void logout() {
		config.refreshToken = "";
		config.scope = "";
		accessToken = "";
		expiresAtMs = 0;
		config.save();
	}

	public void invalidate() {
		expiresAtMs = 0;
	}

	public boolean hasScope(String s) {
		return config.scope.contains(s);
	}

	public synchronized String accessToken() throws IOException, InterruptedException {
		if (!isLoggedIn()) throw new IOException("not logged in");
		if (System.currentTimeMillis() > expiresAtMs - 30_000) refresh();
		return accessToken;
	}

	private void refresh() throws IOException, InterruptedException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", config.refreshToken);
		form.put("client_id", config.clientId);
		try {
			applyTokens(tokenRequest(form));
		} catch (IOException e) {
			String m = e.getMessage() == null ? "" : e.getMessage();
			if (m.contains("invalid_grant")) {
				config.refreshToken = "";
				config.save();
			}
			throw e;
		}
	}

	private void applyTokens(JsonObject j) {
		accessToken = j.get("access_token").getAsString();
		long exp = j.has("expires_in") ? j.get("expires_in").getAsLong() : 3600;
		expiresAtMs = System.currentTimeMillis() + exp * 1000;
		if (j.has("refresh_token") && !j.get("refresh_token").isJsonNull()) {
			config.refreshToken = j.get("refresh_token").getAsString();
		}
		if (j.has("scope") && !j.get("scope").isJsonNull()) {
			config.scope = j.get("scope").getAsString();
		}
		config.save();
	}

	private JsonObject tokenRequest(Map<String, String> form) throws IOException, InterruptedException {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : form.entrySet()) {
			if (sb.length() > 0) sb.append('&');
			sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
			.build();
		HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}

	/** Запускает вход в отдельном потоке. say вызывается из любого потока. */
	public void login(Consumer<String> say) {
		Thread t = new Thread(() -> {
			try {
				doLogin(say);
			} catch (Exception e) {
				say.accept("Ошибка входа: " + e.getMessage());
			}
		}, "spotifypanel-login");
		t.setDaemon(true);
		t.start();
	}

	private void doLogin(Consumer<String> say) throws Exception {
		String verifier = randomUrlSafe(64);
		String challenge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		String state = randomUrlSafe(16);

		Map<String, String> q = new LinkedHashMap<>();
		q.put("client_id", config.clientId);
		q.put("response_type", "code");
		q.put("redirect_uri", REDIRECT);
		q.put("scope", SCOPES);
		q.put("state", state);
		q.put("code_challenge_method", "S256");
		q.put("code_challenge", challenge);
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : q.entrySet()) {
			if (sb.length() > 0) sb.append('&');
			sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		String url = "https://accounts.spotify.com/authorize?" + sb;

		try (ServerSocket ss = new ServerSocket()) {
			ss.setReuseAddress(true);
			ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
			ss.setSoTimeout(1000);

			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				mc.keyboardHandler.setClipboard(url);
				try {
					Util.getPlatform().openUri(URI.create(url));
				} catch (Throwable ignored) {
				}
			});
			say.accept("Открываю браузер для входа в Spotify. Если не открылся - ссылка скопирована в буфер обмена (Ctrl+V в браузер).");

			long deadline = System.currentTimeMillis() + 180_000;
			String code = null;
			while (System.currentTimeMillis() < deadline && code == null) {
				try (Socket s = ss.accept()) {
					BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					String line = in.readLine();
					if (line == null) continue;
					String[] parts = line.split(" ");
					if (parts.length < 2) continue;
					URI target = URI.create("http://127.0.0.1" + parts[1]);
					if (!"/callback".equals(target.getPath())) {
						respond(s, "Not found");
						continue;
					}
					Map<String, String> params = parseQuery(target.getRawQuery());
					if (params.containsKey("error")) {
						respond(s, "Вход отменён.");
						throw new IOException("Вход отменён: " + params.get("error"));
					}
					if (!state.equals(params.get("state")) || !params.containsKey("code")) {
						respond(s, "Неверный ответ.");
						continue;
					}
					code = params.get("code");
					respond(s, "Готово! Можно закрыть вкладку и вернуться в Minecraft.");
				} catch (SocketTimeoutException ignored) {
				}
			}
			if (code == null) throw new IOException("время ожидания входа истекло");

			Map<String, String> form = new LinkedHashMap<>();
			form.put("grant_type", "authorization_code");
			form.put("code", code);
			form.put("redirect_uri", REDIRECT);
			form.put("client_id", config.clientId);
			form.put("code_verifier", verifier);
			synchronized (this) {
				applyTokens(tokenRequest(form));
			}
			say.accept("Spotify подключён!");
		}
	}

	private static void respond(Socket s, String text) throws IOException {
		byte[] body = ("<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding-top:80px\"><h2>"
			+ text + "</h2></body></html>").getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
		OutputStream o = s.getOutputStream();
		o.write(head.getBytes(StandardCharsets.US_ASCII));
		o.write(body);
		o.flush();
	}

	private static Map<String, String> parseQuery(String q) {
		Map<String, String> m = new LinkedHashMap<>();
		if (q == null) return m;
		for (String kv : q.split("&")) {
			int i = kv.indexOf('=');
			if (i < 0) continue;
			m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
				URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
		}
		return m;
	}

	private String randomUrlSafe(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		String s = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
		return s.length() > n ? s.substring(0, n) : s;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
