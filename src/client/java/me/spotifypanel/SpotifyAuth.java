package me.spotifypanel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** OAuth 2.0 Authorization Code + PKCE (без client secret). */
public final class SpotifyAuth {
    public static final int PORT = 8888;
    public static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state playlist-read-private playlist-read-collaborative user-library-read";

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    private final SecureRandom random = new SecureRandom();

    private volatile String accessToken;
    private volatile long expiresAtMs;

    public SpotifyAuth(Config config) {
        this.config = config;
    }

    public String clientId() {
        return config.clientId;
    }

    public boolean isLoggedIn() {
        return !config.refreshToken.isEmpty();
    }

    public void invalidate() {
        accessToken = null;
    }

    public void logout() {
        config.refreshToken = "";
        config.scope = "";
        config.save();
        accessToken = null;
    }

    public boolean hasScope(String scope) {
        return (" " + config.scope + " ").contains(" " + scope + " ");
    }

    public synchronized String accessToken() throws IOException, InterruptedException {
        if (!isLoggedIn()) throw new IOException("not logged in");
        if (accessToken == null || System.currentTimeMillis() > expiresAtMs - 30_000) {
            refresh();
        }
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
            if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
                logout();
            }
            throw e;
        }
    }

    private void applyTokens(JsonObject j) {
        accessToken = j.get("access_token").getAsString();
        expiresAtMs = System.currentTimeMillis() + j.get("expires_in").getAsLong() * 1000L;
        if (j.has("refresh_token") && !j.get("refresh_token").isJsonNull()) {
            config.refreshToken = j.get("refresh_token").getAsString();
        }
        if (j.has("scope") && !j.get("scope").isJsonNull()) {
            config.scope = j.get("scope").getAsString();
        }
        config.save();
    }

    private JsonObject tokenRequest(Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").timeout(java.time.Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    // ---------------------------------------------------------------- login

    public void login(Consumer<String> say) {
        Thread t = new Thread(() -> {
            try {
                doLogin(say);
            } catch (Exception e) {
                say.accept("Ошибка входа: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }, "spotifypanel-login");
        t.setDaemon(true);
        t.start();
    }

    private void doLogin(Consumer<String> say) throws Exception {
        String verifier = randomUrlSafe(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlSafe(16);

        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", config.clientId);
        q.put("response_type", "code");
        q.put("redirect_uri", REDIRECT);
        q.put("scope", SCOPES);
        q.put("state", state);
        q.put("code_challenge_method", "S256");
        q.put("code_challenge", challenge);
        String url = "https://accounts.spotify.com/authorize?" + encode(q);

        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            ss.setSoTimeout(1000);

            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                try {
                    mc.keyboard.setClipboard(url);
                } catch (Throwable ignored) {
                }
                try {
                    Util.getOperatingSystem().open(URI.create(url));
                } catch (Throwable ignored) {
                }
            });
            say.accept("Открываю браузер для входа в Spotify. Если не открылся — ссылка скопирована в буфер обмена (Ctrl+V в браузер).");

            String code = null;
            long deadline = System.currentTimeMillis() + 3 * 60_000L;
            while (code == null && System.currentTimeMillis() < deadline) {
                Socket s;
                try {
                    s = ss.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try (s) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    String line = in.readLine();
                    if (line == null) continue;
                    String[] parts = line.split(" ");
                    if (parts.length < 2) continue;
                    URI target = URI.create("http://127.0.0.1" + parts[1]);
                    Map<String, String> params = parseQuery(target.getRawQuery());
                    boolean isCallback = "/callback".equals(target.getPath());
                    if (isCallback && params.containsKey("error")) {
                        respond(s, "Вход отменён: " + params.get("error"));
                        throw new IOException(params.get("error"));
                    }
                    if (isCallback && params.containsKey("code") && state.equals(params.get("state"))) {
                        respond(s, "Готово! Можно закрыть вкладку и вернуться в Minecraft.");
                        code = params.get("code");
                    } else {
                        respond(s, "Ожидаю вход...");
                    }
                }
            }
            if (code == null) throw new IOException("время ожидания входа истекло");

            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", REDIRECT);
            form.put("client_id", config.clientId);
            form.put("code_verifier", verifier);
            applyTokens(tokenRequest(form));
            say.accept("Spotify подключён!");
        }
    }

    private static void respond(Socket s, String text) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Spotify Panel</title>"
                + "<body style=\"font-family:sans-serif;background:#101418;color:#fff;text-align:center;padding-top:20vh\">"
                + "<h2>" + text + "</h2></body>").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: "
                + body.length + "\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String encode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8).replace("+", "%20"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
