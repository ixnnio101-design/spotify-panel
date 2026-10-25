package me.spotifypanel.client;

import com.mojang.blaze3d.platform.NativeImage;
import me.spotifypanel.SpotifyPanel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Обложки: качаются по одной в фоне (CDN Spotify, на квоту API не влияет),
 * кешируются на диск и превращаются в текстуры в основном потоке рендера.
 */
public final class CoverCache {
	public static final int SMALL = 20;
	public static final int LARGE = 40;

	private final Map<String, Identifier> textures = new ConcurrentHashMap<>();
	private final Map<String, byte[]> ready = new ConcurrentHashMap<>();
	private final Set<String> loading = ConcurrentHashMap.newKeySet();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "spotifypanel-covers");
		t.setDaemon(true);
		return t;
	});
	private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("spotifypanel-covers");
	private int budget = 2;

	public void beginFrame() {
		budget = 2;
	}

	/** Возвращает текстуру размера size x size или null, пока она не готова. */
	public Identifier get(String url, int size) {
		if (url == null || url.isEmpty()) return null;
		String key = url + "@" + size;
		Identifier id = textures.get(key);
		if (id != null) return id;
		byte[] png = ready.get(key);
		if (png != null) {
			if (budget <= 0) return null;
			budget--;
			ready.remove(key);
			try {
				NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
				Identifier tid = SpotifyPanel.id("cover/" + hash(key));
				AbstractTexture tex = makeTexture(img, tid.toString());
				Minecraft.getInstance().getTextureManager().register(tid, tex);
				textures.put(key, tid);
				return tid;
			} catch (Throwable t) {
				failed.add(key);
				SpotifyPanel.LOGGER.warn("cover texture failed", t);
				return null;
			}
		}
		if (!failed.contains(key) && loading.add(key)) {
			exec.execute(() -> load(url, size, key));
		}
		return null;
	}

	private void load(String url, int size, String key) {
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve(hash(key) + ".png");
			byte[] png;
			if (Files.exists(file)) {
				png = Files.readAllBytes(file);
			} else {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build();
				HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
				if (r.statusCode() != 200) throw new java.io.IOException("HTTP " + r.statusCode());
				BufferedImage src = ImageIO.read(new ByteArrayInputStream(r.body()));
				if (src == null) throw new java.io.IOException("decode failed");
				png = encode(scale(src, size));
				Files.write(file, png);
			}
			ready.put(key, png);
		} catch (Throwable t) {
			failed.add(key);
		} finally {
			loading.remove(key);
		}
	}

	private static BufferedImage scale(BufferedImage src, int size) {
		int side = Math.min(src.getWidth(), src.getHeight());
		BufferedImage sq = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
		// ступенчатое уменьшение даёт гораздо чище картинку, чем один прыжок 300 -> 20
		BufferedImage cur = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g0 = cur.createGraphics();
		g0.drawImage(sq, 0, 0, null);
		g0.dispose();
		int w = side;
		while (w / 2 >= size) {
			w /= 2;
			cur = resize(cur, w);
		}
		return resize(cur, size);
	}

	private static BufferedImage resize(BufferedImage src, int size) {
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, size, size, null);
		g.dispose();
		return out;
	}

	private static byte[] encode(BufferedImage img) throws java.io.IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bo);
		return bo.toByteArray();
	}

	private static String hash(String s) {
		return UUID.nameUUIDFromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
	}

	/** Конструктор DynamicTexture менялся между версиями, поэтому подбираем его по сигнатуре. */
	private static AbstractTexture makeTexture(NativeImage img, String name) throws Exception {
		for (Constructor<?> c : DynamicTexture.class.getConstructors()) {
			Class<?>[] p = c.getParameterTypes();
			if (p.length == 2 && p[1] == NativeImage.class) {
				if (Supplier.class.isAssignableFrom(p[0])) {
					Supplier<String> label = () -> name;
					return (AbstractTexture) c.newInstance(label, img);
				}
				if (p[0] == String.class) return (AbstractTexture) c.newInstance(name, img);
			}
		}
		for (Constructor<?> c : DynamicTexture.class.getConstructors()) {
			Class<?>[] p = c.getParameterTypes();
			if (p.length == 1 && p[0] == NativeImage.class) return (AbstractTexture) c.newInstance(img);
		}
		throw new IllegalStateException("no DynamicTexture constructor");
	}
}
