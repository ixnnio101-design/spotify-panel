package me.spotifypanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String clientId = "";
    public String refreshToken = "";
    public boolean visible = true;
    /** HTTP-прокси "host:port" для запросов к Spotify (пусто = напрямую). */
    public String proxy = "";
    /** ID плейлистов, добавленных по ссылке (/spotify playlist add). */
    public List<String> extraPlaylists = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("spotifypanel.json");
    }

    public static Config load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) return c.fix();
            }
        } catch (Exception ignored) {
        }
        return new Config();
    }

    private Config fix() {
        if (clientId == null) clientId = "";
        if (refreshToken == null) refreshToken = "";
        if (proxy == null) proxy = "";
        if (extraPlaylists == null) extraPlaylists = new ArrayList<>();
        return this;
    }

    public synchronized void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
