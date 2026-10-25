package me.spotifypanel.client;

import me.spotifypanel.SpotifyPanel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Панель в стиле Spotify. Висит в мире рядом с телом игрока (как окна в Apple Vision Pro):
 * якорь считается от поворота ТЕЛА, поэтому при повороте головы панель остаётся на месте
 * сбоку, а при ходьбе/повороте тела едет вместе с игроком. Сама панель - ровный прямоугольник
 * (никакого искажения и нарезки на полоски), всегда лицом к камере.
 */
public final class PanelRenderer {
	// размер панели в "дизайн-единицах"; потом масштабируется по расстоянию
	static final int W = 400, H = 230;

	// палитра Spotify
	static final int BLACK = 0xF2000000;
	static final int SURFACE = 0xFF121212;
	static final int HOVER = 0xFF1F1F1F;
	static final int SELECT = 0xFF2A2A2A;
	static final int WHITE = 0xFFFFFFFF;
	static final int GRAY = 0xFFB3B3B3;
	static final int DIM = 0xFF727272;
	static final int GREEN = 0xFF1DB954;
	static final int BAR_BG = 0xFF4D4D4D;

	// геометрия
	static final int SIDE_X = 6, SIDE_Y = 6, SIDE_W = 124, SIDE_H = 164;
	static final int MAIN_X = 136, MAIN_Y = 6, MAIN_W = 258, MAIN_H = 164;
	static final int SIDE_ROW_Y = 30, SIDE_ROW_H = 22, SIDE_ROWS = 6;
	static final int TRACK_Y = 56, TRACK_H = 22, TRACK_ROWS = 5;
	static final int CX = 243;                        // центр блока управления
	static final int PLAY_Y = 190;
	static final int PB_X = 186, PB_W = 114, PB_Y = 209;
	static final int VOL_X = 354, VOL_W = 38, VOL_Y = 202;

	private final Config cfg;
	private final SpotifyClient sp;
	private final CoverCache covers;
	private final Map<String, Identifier> icons = new HashMap<>();

	private boolean frozen;
	private Vec3 frozenPos;
	private Vec3 lastAnchor;

	private SpotifyClient.Playlist selected;
	private int sideScroll, trackScroll;

	private final boolean[] keyPrev = new boolean[3];
	private boolean prevLeft;
	private int drag; // 0 нет, 1 прогресс, 2 громкость
	private double dragFrac;
	private double lx = -1000, ly = -1000;

	public PanelRenderer(Config cfg, SpotifyClient sp, CoverCache covers) {
		this.cfg = cfg;
		this.sp = sp;
		this.covers = covers;
	}

	// ------------------------------------------------------------------ управление

	public void setVisible(boolean v) {
		cfg.visible = v;
		cfg.save();
	}

	public void toggleFrozen() {
		if (!cfg.visible) {
			SpotifyPanelClient.say("Панель сейчас скрыта (H).");
			return;
		}
		if (frozen) {
			frozen = false;
			SpotifyPanelClient.say("Панель снова следует за тобой.");
		} else if (lastAnchor != null) {
			frozen = true;
			frozenPos = lastAnchor;
			SpotifyPanelClient.say("Панель закреплена в мире.");
		}
	}

	public void toggleCursor() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof CursorScreen) {
			mc.setScreen(null);
		} else if (mc.screen == null) {
			if (!cfg.visible) setVisible(true);
			mc.setScreen(new CursorScreen());
		}
	}

	public void onScroll(double dy) {
		int dir = dy > 0 ? -1 : 1;
		if (lx >= SIDE_X && lx <= SIDE_X + SIDE_W) {
			scrollSide(dir);
		} else {
			scrollTracks(dir);
		}
	}

	private void scrollSide(int dir) {
		List<SpotifyClient.Playlist> pl = sp.playlists;
		int n = pl == null ? 0 : pl.size();
		sideScroll = Math.max(0, Math.min(Math.max(0, n - SIDE_ROWS), sideScroll + dir));
	}

	private void scrollTracks(int dir) {
		SpotifyClient.TrackList tl = selected == null ? null : sp.lists.get(selected.id);
		int n = tl == null ? 0 : tl.tracks.size();
		trackScroll = Math.max(0, Math.min(Math.max(0, n - TRACK_ROWS), trackScroll + dir));
		if (tl != null && trackScroll + TRACK_ROWS >= n - 1 && n < tl.total) sp.loadTracks(selected, true);
	}

	private void handleKeys(Minecraft mc, long win) {
		boolean ok = mc.screen == null || mc.screen instanceof CursorScreen;
		int[] keys = {cfg.keyToggle, cfg.keyLock, cfg.keyCursor};
		for (int i = 0; i < 3; i++) {
			boolean down = ok && GLFW.glfwGetKey(win, keys[i]) == GLFW.GLFW_PRESS;
			if (down && !keyPrev[i]) {
				if (i == 0) {
					setVisible(!cfg.visible);
					SpotifyPanelClient.say(cfg.visible ? "Панель показана." : "Панель скрыта.");
				} else if (i == 1) {
					toggleFrozen();
				} else {
					toggleCursor();
				}
			}
			keyPrev[i] = down;
		}
	}

	// ------------------------------------------------------------------ рендер

	public void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		long win = GLFW.glfwGetCurrentContext();
		handleKeys(mc, win);

		sp.visible = cfg.visible;
		boolean cursorMode = mc.screen instanceof CursorScreen;
		sp.cursor = cursorMode;
		if (!cfg.visible) {
			drag = 0;
			return;
		}
		covers.beginFrame();

		float pt = dt.getGameTimeDeltaPartialTick(true);
		int gw = g.guiWidth(), gh = g.guiHeight();

		// --- где панель в мире
		Vec3 eye = p.getEyePosition(pt);
		Vec3 pos = p.getPosition(pt);
		Vec3 anchor;
		if (frozen && frozenPos != null) {
			anchor = frozenPos;
		} else {
			double bodyYaw = lerpAngle(p.yBodyRotO, p.yBodyRot, pt);
			double a = Math.toRadians(bodyYaw + cfg.angle);
			double dx = -Math.sin(a) * cfg.distance;
			double dz = Math.cos(a) * cfg.distance;
			double y = eye.y - Math.tan(Math.toRadians(cfg.tilt)) * cfg.distance;
			anchor = new Vec3(pos.x + dx, y, pos.z + dz);
		}
		lastAnchor = anchor;

		// --- проекция центра панели на экран
		double yaw = Math.toRadians(p.getViewYRot(pt));
		double pitch = Math.toRadians(p.getViewXRot(pt));
		double sy = Math.sin(yaw), cy = Math.cos(yaw), sp_ = Math.sin(pitch), cp = Math.cos(pitch);
		double fx = -sy * cp, fy = -sp_, fz = cy * cp;      // вперёд
		double rx = -cy, rz = -sy;                           // вправо
		double ux = -sy * sp_, uy = cp, uz = cy * sp_;       // вверх
		double dx = anchor.x - eye.x, dy = anchor.y - eye.y, dz = anchor.z - eye.z;
		double zc = dx * fx + dy * fy + dz * fz;
		if (zc < 0.2) return; // панель за спиной
		double xc = dx * rx + dz * rz;
		double yc = dx * ux + dy * uy + dz * uz;
		double fov = Math.toRadians(mc.options.fov().get());
		double focal = (gh / 2.0) / Math.tan(fov / 2.0);
		double k = Math.max(0.3, Math.min(2.5, focal / zc * cfg.width / W));
		double scrX = gw / 2.0 + xc / zc * focal;
		double scrY = gh / 2.0 - yc / zc * focal;
		double ox = scrX - W * k / 2.0;
		double oy = scrY - H * k / 2.0;
		if (ox > gw || oy > gh || ox + W * k < 0 || oy + H * k < 0) return;

		// --- мышь (только в режиме курсора)
		if (cursorMode) {
			double[] mx = new double[1], my = new double[1];
			int[] ww = new int[1], wh = new int[1];
			GLFW.glfwGetCursorPos(win, mx, my);
			GLFW.glfwGetWindowSize(win, ww, wh);
			double gx = ww[0] > 0 ? mx[0] * gw / ww[0] : 0;
			double gy = wh[0] > 0 ? my[0] * gh / wh[0] : 0;
			lx = (gx - ox) / k;
			ly = (gy - oy) / k;
			boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
			if (left && !prevLeft) onPress();
			if (left && drag != 0) onDrag();
			if (!left && prevLeft) onRelease();
			prevLeft = left;
		} else {
			lx = ly = -1000;
			prevLeft = false;
			drag = 0;
		}

		g.pose().pushMatrix();
		g.pose().translate((float) ox, (float) oy);
		g.pose().scale((float) k, (float) k);
		try {
			draw(g, mc, cursorMode);
		} finally {
			g.pose().popMatrix();
		}
	}

	private static double lerpAngle(double a, double b, double t) {
		double d = b - a;
		while (d > 180) d -= 360;
		while (d < -180) d += 360;
		return a + d * t;
	}

	// ------------------------------------------------------------------ ввод

	private boolean in(double x, double y, double w, double h) {
		return lx >= x && lx < x + w && ly >= y && ly < y + h;
	}

	private void onPress() {
		SpotifyClient.State st = sp.state;
		// сайдбар
		if (in(SIDE_X + 88, 10, 14, 14)) {
			scrollSide(-1);
			return;
		}
		if (in(SIDE_X + 104, 10, 14, 14)) {
			scrollSide(1);
			return;
		}
		List<SpotifyClient.Playlist> pl = sp.playlists;
		if (pl != null && in(SIDE_X, SIDE_ROW_Y, SIDE_W, SIDE_ROW_H * SIDE_ROWS)) {
			int idx = sideScroll + (int) ((ly - SIDE_ROW_Y) / SIDE_ROW_H);
			if (idx >= 0 && idx < pl.size()) {
				selected = pl.get(idx);
				trackScroll = 0;
				sp.loadTracks(selected, false);
			}
			return;
		}
		// список треков
		if (in(MAIN_X + 224, 12, 14, 14)) {
			scrollTracks(-1);
			return;
		}
		if (in(MAIN_X + 240, 12, 14, 14)) {
			scrollTracks(1);
			return;
		}
		SpotifyClient.TrackList tl = selected == null ? null : sp.lists.get(selected.id);
		if (tl != null && in(MAIN_X, TRACK_Y, MAIN_W, TRACK_H * TRACK_ROWS)) {
			int idx = trackScroll + (int) ((ly - TRACK_Y) / TRACK_H);
			if (idx >= 0 && idx < tl.tracks.size()) sp.playTrack(selected, tl.tracks.get(idx));
			return;
		}
		// кнопки плеера
		double ddx = lx - CX, ddy = ly - PLAY_Y;
		if (ddx * ddx + ddy * ddy <= 13 * 13) {
			sp.togglePlay();
		} else if (in(CX - 70, 181, 22, 18)) {
			sp.toggleShuffle();
		} else if (in(CX - 42, 181, 22, 18)) {
			sp.previous();
		} else if (in(CX + 20, 181, 22, 18)) {
			sp.next();
		} else if (in(CX + 48, 181, 22, 18)) {
			sp.cycleRepeat();
		} else if (in(PB_X - 4, PB_Y - 6, PB_W + 8, 14) && st.track != null) {
			drag = 1;
			onDrag();
		} else if (in(VOL_X - 4, VOL_Y - 6, VOL_W + 8, 14)) {
			drag = 2;
			onDrag();
		} else if (in(VOL_X - 22, VOL_Y - 6, 16, 14)) {
			sp.setVolume(st.volume > 0 ? 0 : 50);
		}
	}

	private void onDrag() {
		if (drag == 1) dragFrac = clamp01((lx - PB_X) / PB_W);
		else if (drag == 2) dragFrac = clamp01((lx - VOL_X) / VOL_W);
	}

	private void onRelease() {
		if (drag == 1) {
			SpotifyClient.Track t = sp.state.track;
			if (t != null && t.durationMs > 0) sp.seek((long) (dragFrac * t.durationMs));
		} else if (drag == 2) {
			sp.setVolume((int) Math.round(dragFrac * 100));
		}
		drag = 0;
	}

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}

	// ------------------------------------------------------------------ рисование

	private void draw(GuiGraphics g, Minecraft mc, boolean cursorMode) {
		Font f = mc.font;
		SpotifyClient.State st = sp.state;

		rr(g, 0, 0, W, H, 8, BLACK);
		rr(g, SIDE_X, SIDE_Y, SIDE_W, SIDE_H, 6, SURFACE);
		rr(g, MAIN_X, MAIN_Y, MAIN_W, MAIN_H, 6, SURFACE);
		if (cursorMode) {
			// тонкая зелёная рамка: курсор активен
			g.fill(0, 0, W, 1, GREEN);
			g.fill(0, H - 1, W, H, GREEN);
			g.fill(0, 0, 1, H, GREEN);
			g.fill(W - 1, 0, W, H, GREEN);
		}

		drawSidebar(g, f);
		drawMain(g, f, st);
		drawPlayer(g, f, st);
		drawToast(g, f, st);
	}

	private void drawSidebar(GuiGraphics g, Font f) {
		icon(g, "library_g", SIDE_X + 8, 11, 14);
		text(g, f, "Медиатека", SIDE_X + 26, 14, GRAY, true);
		icon(g, "up_g", SIDE_X + 90, 12, 10);
		icon(g, "down_g", SIDE_X + 106, 12, 10);

		List<SpotifyClient.Playlist> pl = sp.playlists;
		if (pl == null) {
			String msg = sp.playlistsError != null ? sp.playlistsError : "Загрузка...";
			wrap(g, f, msg, SIDE_X + 8, SIDE_ROW_Y + 4, SIDE_W - 16, DIM, 6);
			return;
		}
		if (pl.isEmpty()) {
			wrap(g, f, "Здесь появятся твои плейлисты", SIDE_X + 8, SIDE_ROW_Y + 4, SIDE_W - 16, DIM, 4);
			return;
		}
		for (int i = 0; i < SIDE_ROWS; i++) {
			int idx = sideScroll + i;
			if (idx >= pl.size()) break;
			SpotifyClient.Playlist p = pl.get(idx);
			int y = SIDE_ROW_Y + i * SIDE_ROW_H;
			boolean sel = selected != null && selected.id.equals(p.id);
			boolean hov = in(SIDE_X, y, SIDE_W, SIDE_ROW_H);
			if (sel || hov) rr(g, SIDE_X + 3, y, SIDE_W - 6, SIDE_ROW_H, 4, sel ? SELECT : HOVER);
			cover(g, p.coverUrl, SIDE_X + 8, y + 1, CoverCache.SMALL);
			text(g, f, trunc(f, p.name, SIDE_W - 42), SIDE_X + 33, y + 7, sel ? GREEN : WHITE, false);
		}
	}

	private void drawMain(GuiGraphics g, Font f, SpotifyClient.State st) {
		if (st.status == SpotifyClient.Status.NO_CLIENT_ID) {
			icon(g, "logo_gr", MAIN_X + 12, 18, 28);
			text(g, f, "Нужен Client ID Spotify", MAIN_X + 48, 22, WHITE, true);
			wrap(g, f, "1) developer.spotify.com -> Create app", MAIN_X + 12, 56, MAIN_W - 24, GRAY, 3);
			wrap(g, f, "2) Redirect URI: " + SpotifyAuth.REDIRECT, MAIN_X + 12, 80, MAIN_W - 24, GRAY, 3);
			wrap(g, f, "3) В игре: /spotify setup <client_id>", MAIN_X + 12, 112, MAIN_W - 24, GRAY, 3);
			wrap(g, f, "4) Затем: /spotify login", MAIN_X + 12, 126, MAIN_W - 24, GRAY, 3);
			return;
		}
		if (st.status == SpotifyClient.Status.NOT_LOGGED_IN) {
			icon(g, "logo_gr", MAIN_X + 12, 18, 28);
			text(g, f, "Войди в Spotify", MAIN_X + 48, 22, WHITE, true);
			wrap(g, f, "Введи в чате: /spotify login", MAIN_X + 12, 60, MAIN_W - 24, GRAY, 3);
			return;
		}
		if (selected == null) {
			icon(g, "logo_gr", MAIN_X + 12, 18, 28);
			text(g, f, "Spotify", MAIN_X + 48, 22, WHITE, true);
			wrap(g, f, sp.playlists == null ? "Загружаю плейлисты..." : "Выбери плейлист слева",
				MAIN_X + 12, 60, MAIN_W - 24, GRAY, 3);
			if (!sp.cursor) wrap(g, f, "Нажми K, чтобы управлять мышью", MAIN_X + 12, 80, MAIN_W - 24, DIM, 3);
			return;
		}
		// шапка плейлиста
		cover(g, selected.coverUrl, MAIN_X + 8, 12, CoverCache.LARGE);
		text(g, f, trunc(f, selected.name, MAIN_W - 110), MAIN_X + 54, 18, WHITE, true);
		SpotifyClient.TrackList tl = sp.lists.get(selected.id);
		int total = tl != null && tl.total >= 0 ? tl.total : selected.total;
		text(g, f, total >= 0 ? total + " " + plural(total) : "Плейлист", MAIN_X + 54, 32, GRAY, false);
		icon(g, "up_g", MAIN_X + 226, 14, 10);
		icon(g, "down_g", MAIN_X + 242, 14, 10);
		g.fill(MAIN_X + 8, TRACK_Y - 4, MAIN_X + MAIN_W - 8, TRACK_Y - 3, 0xFF2A2A2A);

		if (tl == null || (tl.tracks.isEmpty() && tl.loading)) {
			text(g, f, "Загрузка...", MAIN_X + 12, TRACK_Y + 6, DIM, false);
			return;
		}
		if (tl.tracks.isEmpty()) {
			wrap(g, f, tl.error != null ? tl.error : "В плейлисте нет треков", MAIN_X + 12, TRACK_Y + 6, MAIN_W - 24, DIM, 5);
			return;
		}
		String curUri = sp.state.track == null ? "" : sp.state.track.uri;
		for (int i = 0; i < TRACK_ROWS; i++) {
			int idx = trackScroll + i;
			if (idx >= tl.tracks.size()) break;
			SpotifyClient.Track t = tl.tracks.get(idx);
			int y = TRACK_Y + i * TRACK_H;
			boolean cur = t.uri.equals(curUri);
			if (in(MAIN_X, y, MAIN_W, TRACK_H)) rr(g, MAIN_X + 3, y, MAIN_W - 6, TRACK_H, 4, HOVER);
			text(g, f, trunc(f, t.name, MAIN_W - 70), MAIN_X + 12, y + 3, cur ? GREEN : WHITE, false);
			text(g, f, trunc(f, t.artists, MAIN_W - 70), MAIN_X + 12, y + 12, DIM, false);
			String d = time(t.durationMs);
			text(g, f, d, MAIN_X + MAIN_W - 10 - f.width(d), y + 7, GRAY, false);
		}
	}

	private void drawPlayer(GuiGraphics g, Font f, SpotifyClient.State st) {
		SpotifyClient.Track t = st.track;
		// слева: обложка + название
		if (t != null && !t.coverUrl.isEmpty() && cover(g, t.coverUrl, 8, 180, CoverCache.LARGE)) {
			// нарисовано
		} else {
			rr(g, 8, 180, 40, 40, 4, SELECT);
			icon(g, "note_g", 18, 190, 20);
		}
		if (t != null) {
			text(g, f, trunc(f, t.name, 98), 54, 188, WHITE, true);
			text(g, f, trunc(f, t.artists, 98), 54, 200, GRAY, false);
		} else {
			String m = st.status == SpotifyClient.Status.IDLE ? "Открой Spotify" : "Ничего не играет";
			text(g, f, trunc(f, m, 98), 54, 188, GRAY, false);
			if (st.status == SpotifyClient.Status.IDLE) text(g, f, "на любом устройстве", 54, 200, DIM, false);
		}

		// центр: кнопки
		icon(g, st.shuffle ? "shuffle_gr" : "shuffle_g", CX - 66, 183, 14);
		icon(g, "prev_w", CX - 38, 183, 14);
		disc(g, CX, PLAY_Y, 12, WHITE);
		icon(g, st.playing ? "pause_b" : "play_b", CX - 7, PLAY_Y - 7, 14);
		icon(g, "next_w", CX + 24, 183, 14);
		String rp = st.repeat;
		icon(g, rp.equals("track") ? "repeat1_gr" : rp.equals("context") ? "repeat_gr" : "repeat_g", CX + 52, 183, 14);

		// прогресс
		long dur = t == null ? 0 : t.durationMs;
		long pos = st.progressNow();
		double frac = dur > 0 ? clamp01((double) pos / dur) : 0;
		if (drag == 1) {
			frac = dragFrac;
			pos = (long) (dragFrac * dur);
		}
		String left = time(pos);
		text(g, f, left, PB_X - 6 - f.width(left), PB_Y - 4, DIM, false);
		text(g, f, time(dur), PB_X + PB_W + 6, PB_Y - 4, DIM, false);
		slider(g, PB_X, PB_Y, PB_W, frac, in(PB_X - 4, PB_Y - 6, PB_W + 8, 14) || drag == 1);

		// громкость
		double vf = st.volume < 0 ? 0 : st.volume / 100.0;
		if (drag == 2) vf = dragFrac;
		icon(g, vf <= 0.001 ? "mute_g" : "volume_g", VOL_X - 18, VOL_Y - 6, 12);
		slider(g, VOL_X, VOL_Y, VOL_W, vf, in(VOL_X - 4, VOL_Y - 6, VOL_W + 8, 14) || drag == 2);
	}

	private void drawToast(GuiGraphics g, Font f, SpotifyClient.State st) {
		String msg = null;
		int color = GRAY;
		long blocked = sp.blockedMs();
		if (blocked > 0) {
			msg = "Лимит запросов Spotify. Повтор через " + waitText(blocked);
			color = 0xFFFFB347;
		} else if (System.currentTimeMillis() < sp.toastUntil && !sp.toast.isEmpty()) {
			msg = sp.toast;
		} else if (sp.error != null && !sp.error.isEmpty()) {
			msg = sp.error;
			color = 0xFFFF6B6B;
		}
		if (msg == null) return;
		String s = trunc(f, msg, W - 12);
		int w = f.width(s) + 10;
		rr(g, 0, -14, w, 12, 4, 0xE0000000);
		text(g, f, s, 5, -12, color, false);
	}

	// ------------------------------------------------------------------ примитивы

	private boolean cover(GuiGraphics g, String url, int x, int y, int size) {
		Identifier id = covers.get(url, size);
		if (id == null) {
			rr(g, x, y, size, size, 3, SELECT);
			icon(g, "note_g", x + size / 4, y + size / 4, size / 2);
			return false;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, size, size, size, size);
		return true;
	}

	private void icon(GuiGraphics g, String name, int x, int y, int size) {
		Identifier id = icons.computeIfAbsent(name, n -> SpotifyPanel.id("textures/gui/" + n + ".png"));
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, 64, 64, 64, 64);
	}

	private void slider(GuiGraphics g, int x, int y, int w, double frac, boolean active) {
		rr(g, x, y, w, 3, 1, BAR_BG);
		int fw = (int) Math.round(w * clamp01(frac));
		if (fw > 0) rr(g, x, y, fw, 3, 1, active ? GREEN : WHITE);
		if (active) disc(g, x + fw, y + 1, 4, WHITE);
	}

	private static void text(GuiGraphics g, Font f, String s, int x, int y, int color, boolean bold) {
		if (s == null || s.isEmpty()) return;
		if (bold) g.drawString(f, Component.literal(s).withStyle(ChatFormatting.BOLD), x, y, color, false);
		else g.drawString(f, s, x, y, color, false);
	}

	private static void wrap(GuiGraphics g, Font f, String s, int x, int y, int w, int color, int maxLines) {
		String rest = s;
		for (int i = 0; i < maxLines && !rest.isEmpty(); i++) {
			String line = f.plainSubstrByWidth(rest, w);
			if (line.isEmpty()) break;
			int cut = line.length();
			if (cut < rest.length()) {
				int sp = line.lastIndexOf(' ');
				if (sp > 0) cut = sp;
			}
			g.drawString(f, rest.substring(0, cut), x, y + i * 10, color, false);
			rest = rest.substring(cut).trim();
		}
	}

	private static String trunc(Font f, String s, int w) {
		if (s == null) return "";
		if (f.width(s) <= w) return s;
		int e = f.width("...");
		return f.plainSubstrByWidth(s, Math.max(0, w - e)) + "...";
	}

	static void rr(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, c);
			return;
		}
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, c);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, c);
		}
		g.fill(x, y + r, x + w, y + h - r, c);
	}

	static void disc(GuiGraphics g, int cx, int cy, int r, int c) {
		for (int dy = -r; dy < r; dy++) {
			double d = dy + 0.5;
			int half = (int) Math.round(Math.sqrt(r * r - d * d));
			g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, c);
		}
	}

	private static String time(long ms) {
		long s = Math.max(0, ms) / 1000;
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}

	private static String plural(int n) {
		int a = n % 100, b = n % 10;
		if (a >= 11 && a <= 14) return "треков";
		if (b == 1) return "трек";
		if (b >= 2 && b <= 4) return "трека";
		return "треков";
	}

	private static String waitText(long ms) {
		long s = (ms + 999) / 1000;
		if (s < 90) return s + " с";
		long m = s / 60;
		if (m < 90) return m + " мин";
		return (m / 60) + " ч " + (m % 60) + " мин";
	}
}
