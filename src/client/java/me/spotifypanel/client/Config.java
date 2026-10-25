package me.spotifypanel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Хранится в config/spotifypanel.json (тот же файл, что у старой версии: логин сохраняется). */
public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int LAYOUT_VERSION = 2;

	public String clientId = "";
	public String refreshToken = "";
	public String scope = "";
	public boolean visible = true;

	/** Угол от направления тела вправо, градусы. */
	public double angle = 34;
	/** Расстояние от игрока, блоки. */
	public double distance = 1.4;
	/** Ширина панели в мире, блоки. */
	public double width = 1.2;
	/** Насколько ниже уровня глаз находится центр панели (угол, градусы). */
	public double tilt = 14;

	public int keyToggle = 72; // H
	public int keyLock = 74;   // J
	public int keyCursor = 75; // K

	public int layoutVersion = 0;

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("spotifypanel.json");
	}

	public static Config load() {
		Config c = null;
		boolean fresh = true;
		try {
			Path p = file();
			if (Files.exists(p)) {
				c = GSON.fromJson(Files.readString(p), Config.class);
				fresh = false;
			}
		} catch (Exception ignored) {
		}
		if (c == null) c = new Config();
		c.fix();
		if (fresh) {
			c.layoutVersion = LAYOUT_VERSION;
		} else if (c.layoutVersion < LAYOUT_VERSION) {
			// старый конфиг: сбрасываем только положение панели на новые значения по умолчанию
			Config d = new Config();
			c.angle = d.angle;
			c.distance = d.distance;
			c.width = d.width;
			c.tilt = d.tilt;
			c.layoutVersion = LAYOUT_VERSION;
		}
		c.save();
		return c;
	}

	private void fix() {
		if (clientId == null) clientId = "";
		if (refreshToken == null) refreshToken = "";
		if (scope == null) scope = "";
	}

	public synchronized void save() {
		try {
			Files.writeString(file(), GSON.toJson(this));
		} catch (IOException ignored) {
		}
	}
}
