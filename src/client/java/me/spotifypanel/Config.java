package me.spotifypanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String clientId = "";
    public String refreshToken = "";
    public String scope = "";
    public boolean visible = true;

    // положение панели
    public double angle = 60;     // градусов вправо от направления взгляда
    public double distance = 1.5; // блоков от игрока
    public double width = 1.4;    // ширина панели в блоках
    public double tilt = 20;      // доворот панели (даёт перспективу/объём)

    // клавиши (коды GLFW): H, J, K
    public int keyToggle = 72;
    public int keyLock = 74;
    public int keyCursor = 75;

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
        if (scope == null) scope = "";
        return this;
    }

    public synchronized void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
