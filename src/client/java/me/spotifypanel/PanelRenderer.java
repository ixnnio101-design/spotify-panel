package me.spotifypanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Плавающая панель в стиле приложения Spotify. Привязана к точке в мире рядом с игроком,
 * плавно догоняет его при движении, масштабируется с расстоянием. Кликается мышью, когда открыт чат.
 */
public final class PanelRenderer {
    private static final int W = 300, H = 190;
    private static final double WORLD_W = 2.1; // ширина панели в блоках

    private static final int BLACK = 0xFF000000;
    private static final int PANEL = 0xFF121212;
    private static final int ROW_HOVER = 0xFF1F1F1F;
    private static final int ROW_SEL = 0xFF2A2A2A;
    private static final int GREEN = 0xFF1ED760;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B3B3;
    private static final int DIM = 0xFF727272;
    private static final int BAR_BG = 0xFF4D4D4D;

    // позиция панели в мире (с плавным догоном)
    private static boolean init;
    private static double px, py, pz;
    private static long lastNs;

    // состояние интерфейса
    private static String selected = "";
    private static int sideScroll, trackScroll;
    private static boolean prevDown, clicked, chatOpen;
    private static double lmx, lmy;

    private static TextRenderer tr;
    private static DrawContext g;
    private static SpotifyClient sp;

    private PanelRenderer() {}

    // =================================================================== кадр

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        if (!SpotifyPanelMod.config.visible) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        float pt = tick.getTickProgress(false);
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        Vec3d cam = player.getCameraPosVec(pt);
        double yr = Math.toRadians(player.getYaw(pt)), pr = Math.toRadians(player.getPitch(pt));
        double sinY = Math.sin(yr), cosY = Math.cos(yr), sinP = Math.sin(pr), cosP = Math.cos(pr);

        // целевая точка: справа-спереди от игрока
        double tx = cam.x - sinY * 2.6 - cosY * 1.5;
        double ty = cam.y - 0.3;
        double tz = cam.z + cosY * 2.6 - sinY * 1.5;

        long now = System.nanoTime();
        double dt = init ? Math.min(0.1, (now - lastNs) / 1e9) : 1.0;
        lastNs = now;
        double dx = tx - px, dy = ty - py, dz = tz - pz;
        if (!init || dx * dx + dy * dy + dz * dz > 256) {
            px = tx; py = ty; pz = tz;
            init = true;
        } else {
            double a = 1.0 - Math.exp(-dt * 4.0);
            px += dx * a; py += dy * a; pz += dz * a;
        }

        // проекция на экран
        double vx = px - cam.x, vy = py - cam.y, vz = pz - cam.z;
        double fx = -sinY * cosP, fy = -sinP, fz = cosY * cosP;
        double rx = -cosY, rz = -sinY;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double zc = vx * fx + vy * fy + vz * fz;
        if (zc < 0.3) return; // панель за спиной
        double xc = vx * rx + vz * rz;
        double yc = vx * ux + vy * uy + vz * uz;
        double fov = Math.toRadians(mc.options.getFov().getValue());
        double focal = (sh / 2.0) / Math.tan(fov / 2.0);
        double scx = sw / 2.0 + xc / zc * focal;
        double scy = sh / 2.0 - yc / zc * focal;
        double s = Math.max(0.5, Math.min(1.25, focal / zc * WORLD_W / W));
        double ox = scx - W * s / 2.0, oy = scy - H * s / 2.0;
        if (ox > sw || oy > sh || ox + W * s < 0 || oy + H * s < 0) return;

        // мышь (курсор виден только при открытом чате)
        chatOpen = mc.currentScreen instanceof ChatScreen;
        boolean down = false;
        double mxs = 0, mys = 0;
        if (chatOpen) {
            long handle = mc.getWindow().getHandle();
            down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            mxs = mc.mouse.getX() * sw / mc.getWindow().getWidth();
            mys = mc.mouse.getY() * sh / mc.getWindow().getHeight();
        }
        clicked = chatOpen && down && !prevDown;
        prevDown = down;
        lmx = (mxs - ox) / s;
        lmy = (mys - oy) / s;

        g = ctx;
        tr = mc.textRenderer;
        sp = SpotifyPanelMod.spotify;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) ox, (float) oy);
        m.scale((float) s, (float) s);
        try {
            drawPanel();
        } finally {
            m.popMatrix();
        }
    }

    private static void drawPanel() {
        g.fill(-1, -1, W + 1, H + 1, 0xFF2B2B2B);
        g.fill(0, 0, W, H, BLACK);

        SpotifyClient.Snapshot s = sp.snapshot();
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID || s.status() == SpotifyClient.Status.LOGGED_OUT) {
            g.fill(4, 4, W - 4, H - 4, PANEL);
            tri(130, 70, 130, 100, 156, 85, GREEN);
            if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) {
                text("Spotify не настроен", 90, 110, WHITE, 200);
                text("/spotify setup <client_id>", 90, 124, GRAY, 200);
            } else {
                text("Spotify не подключён", 90, 110, WHITE, 200);
                text("/spotify login", 90, 124, GREEN, 200);
            }
            return;
        }
        drawSidebar();
        drawMain(s);
        drawBottom(s);
    }

    // ================================================================ сайдбар

    private static void drawSidebar() {
        g.fill(4, 4, 80, 150, PANEL);
        text("Медиатека", 10, 9, WHITE, 52);

        List<SpotifyClient.Playlist> pls = sp.playlists();
        int maxScroll = Math.max(0, pls.size() - 6);
        sideScroll = Math.max(0, Math.min(sideScroll, maxScroll));
        if (arrow(64, 11, true)) sideScroll = Math.max(0, sideScroll - 1);
        if (arrow(72, 11, false)) sideScroll = Math.min(maxScroll, sideScroll + 1);

        if (pls.isEmpty()) {
            String err = sp.playlistsError();
            text(err.isEmpty() ? "Загрузка…" : err, 10, 26, GRAY, 66);
            if (!err.isEmpty()) text("Повтор… (" + sp.playlistFails() + ")", 10, 140, DIM, 66);
            return;
        }
        for (int i = 0; i < 6; i++) {
            int idx = sideScroll + i;
            if (idx >= pls.size()) break;
            SpotifyClient.Playlist p = pls.get(idx);
            int y = 24 + i * 20;
            boolean sel = p.id().equals(selected);
            boolean hov = hit(6, y, 72, 19);
            if (sel) g.fill(6, y, 78, y + 19, ROW_SEL);
            else if (hov) g.fill(6, y, 78, y + 19, ROW_HOVER);

            int[] cm = sp.mosaic(p.coverUrl(), 6);
            if (cm != null) drawMosaic(cm, 6, 7, y + 1, true);
            else g.fill(7, y + 1, 25, y + 19, 0xFF282828);

            text(p.name(), 28, y + 2, sel ? GREEN : WHITE, 48);
            text(p.total() + " тр.", 28, y + 11, GRAY, 48);

            if (click(6, y, 72, 19)) {
                selected = p.id();
                trackScroll = 0;
                sp.requestItems(p.id(), true);
            }
        }
    }

    // ============================================================== центр

    private static void drawMain(SpotifyClient.Snapshot s) {
        g.fill(84, 4, 296, 150, PANEL);
        List<SpotifyClient.Playlist> pls = sp.playlists();
        if (pls.isEmpty()) {
            text("Здесь появятся твои плейлисты", 94, 14, GRAY, 196);
            return;
        }

        SpotifyClient.Playlist cur = null;
        for (SpotifyClient.Playlist p : pls) {
            if (p.id().equals(selected)) { cur = p; break; }
        }
        if (cur == null) {
            cur = pls.get(0);
            selected = cur.id();
            trackScroll = 0;
            sp.requestItems(cur.id(), false);
        }

        // шапка с градиентом из цвета обложки
        int[] cm = sp.mosaic(cur.coverUrl(), 12);
        int base = cm != null ? darken(avgColor(cm), 0.55f) : 0xFF1B3B4A;
        for (int k = 0; k < 20; k++) {
            g.fill(84, 4 + k * 3, 296, 7 + k * 3, mix(base, PANEL, k / 19f));
        }
        if (cm != null) drawMosaic(cm, 12, 92, 12, false);
        else g.fill(92, 12, 128, 48, 0xFF282828);

        text("Плейлист", 134, 14, 0xFFE0E0E0, 158);
        g.drawText(tr, Text.literal(fit(cur.name(), 150)).formatted(Formatting.BOLD), 134, 24, WHITE, false);
        String info = (cur.owner().isEmpty() ? "" : cur.owner() + " • ") + cur.total() + " треков";
        text(info, 134, 36, GRAY, 158);

        // большая зелёная кнопка «играть»
        int pcx = 100, pcy = 58;
        boolean ph = hit(pcx - 8, pcy - 8, 16, 16);
        circle(pcx, pcy, 8, ph ? 0xFF3BE477 : GREEN);
        tri(pcx - 2.5f, pcy - 4, pcx + 4, pcy, pcx - 2.5f, pcy + 4, BLACK);
        if (click(pcx - 8, pcy - 8, 16, 16)) {
            sp.playContext(cur.uri(), "", SpotifyPanelMod::say);
        }

        // таблица треков
        text("#", 94, 69, DIM, 10);
        text("Название", 106, 69, DIM, 90);
        text("Исполнитель", 202, 69, DIM, 60);
        g.fill(88, 79, 292, 80, 0xFF2A2A2A);

        List<SpotifyClient.Item> its = sp.items(cur.id());
        if (its == null) {
            String err = sp.itemError(cur.id());
            text(err.isEmpty() ? "Загрузка треков…" : err, 94, 92, GRAY, 196);
            if (err.isEmpty()) sp.requestItems(cur.id(), false);
            return;
        }
        if (its.isEmpty()) {
            text("В плейлисте нет треков", 94, 92, GRAY, 196);
            return;
        }

        int maxScroll = Math.max(0, its.size() - 5);
        trackScroll = Math.max(0, Math.min(trackScroll, maxScroll));
        if (arrow(272, 70, true)) trackScroll = Math.max(0, trackScroll - 1);
        if (arrow(282, 70, false)) trackScroll = Math.min(maxScroll, trackScroll + 1);

        String playingUri = s.track() != null ? s.track().uri() : "";
        for (int r = 0; r < 5; r++) {
            int idx = trackScroll + r;
            if (idx >= its.size()) break;
            SpotifyClient.Item it = its.get(idx);
            int ry = 82 + r * 12;
            boolean hov = hit(88, ry, 204, 12);
            boolean isCur = !playingUri.isEmpty() && playingUri.equals(it.uri());
            if (hov) g.fill(88, ry, 292, ry + 12, ROW_HOVER);

            if (hov) tri(93, ry + 2, 93, ry + 9, 99, ry + 5.5f, WHITE);
            else text(String.valueOf(idx + 1), 94, ry + 2, isCur ? GREEN : GRAY, 12);

            text(it.title(), 106, ry + 2, isCur ? GREEN : WHITE, 92);
            text(it.artists(), 202, ry + 2, GRAY, 62);
            String d = fmt(it.durationMs());
            g.drawText(tr, d, 291 - tr.getWidth(d), ry + 2, GRAY, false);

            if (click(88, ry, 204, 12)) {
                sp.playContext(cur.uri(), it.uri(), SpotifyPanelMod::say);
            }
        }
    }

    // ================================================================ нижняя панель

    private static void drawBottom(SpotifyClient.Snapshot s) {
        g.fill(4, 152, 296, 153, 0xFF1A1A1A);
        SpotifyClient.Track t = s.track();

        if (s.status() == SpotifyClient.Status.ACTIVE && t != null) {
            int[] cm = sp.mosaic(t.coverUrl(), 8);
            if (cm != null) drawMosaic(cm, 8, 8, 158, false);
            else g.fill(8, 158, 32, 182, 0xFF282828);
            text(t.title(), 36, 159, WHITE, 60);
            text(t.artists(), 36, 170, GRAY, 60);
        } else if (s.status() == SpotifyClient.Status.ERROR) {
            text(s.error(), 8, 162, 0xFFFF6B6B, 86);
        } else {
            text("Ничего не играет", 8, 162, GRAY, 86);
            text("Включи трек в Spotify", 8, 173, DIM, 86);
        }

        // --- кнопки управления
        int cy = 163;

        // shuffle
        boolean sh = s.shuffle();
        boolean hs = hit(127, cy - 6, 12, 12);
        int cs = sh ? GREEN : (hs ? WHITE : GRAY);
        for (int i = 0; i < 8; i++) {
            g.fill(129 + i, cy - 4 + i, 130 + i, cy - 3 + i, cs);
            g.fill(129 + i, cy + 3 - i, 130 + i, cy + 4 - i, cs);
        }
        if (click(127, cy - 6, 12, 12)) sp.shuffle(!sh, SpotifyPanelMod::say);

        // previous
        boolean hp = hit(143, cy - 6, 12, 12);
        int cp = hp ? WHITE : GRAY;
        g.fill(144, cy - 4, 146, cy + 4, cp);
        tri(153, cy - 4, 153, cy + 4, 146, cy, cp);
        if (click(143, cy - 6, 12, 12)) sp.previous(SpotifyPanelMod::say);

        // play / pause
        boolean hpl = hit(157, cy - 8, 16, 16);
        circle(165, cy, 7, hpl ? WHITE : 0xFFEAEAEA);
        if (s.playing()) {
            g.fill(162, cy - 3, 164, cy + 3, BLACK);
            g.fill(166, cy - 3, 168, cy + 3, BLACK);
        } else {
            tri(163, cy - 4, 163, cy + 4, 169, cy, BLACK);
        }
        if (click(157, cy - 8, 16, 16)) sp.togglePlay(SpotifyPanelMod::say);

        // next
        boolean hn = hit(175, cy - 6, 12, 12);
        int cn = hn ? WHITE : GRAY;
        tri(177, cy - 4, 177, cy + 4, 184, cy, cn);
        g.fill(184, cy - 4, 186, cy + 4, cn);
        if (click(175, cy - 6, 12, 12)) sp.next(SpotifyPanelMod::say);

        // repeat
        String rep = s.repeat();
        boolean on = !"off".equals(rep);
        boolean hr = hit(191, cy - 6, 12, 12);
        int cr = on ? GREEN : (hr ? WHITE : GRAY);
        g.fill(192, cy - 3, 201, cy - 2, cr);
        g.fill(192, cy + 2, 201, cy + 3, cr);
        g.fill(192, cy - 3, 193, cy + 3, cr);
        g.fill(200, cy - 3, 201, cy + 3, cr);
        if ("track".equals(rep)) g.fill(195, cy - 1, 198, cy + 1, cr);
        if (click(191, cy - 6, 12, 12)) {
            String nextMode = "off".equals(rep) ? "context" : ("context".equals(rep) ? "track" : "off");
            sp.repeat(nextMode, SpotifyPanelMod::say);
        }

        // --- прогресс
        long dur = t != null ? t.durationMs() : 0;
        long pos = s.currentProgress();
        int bx = 124, bw = 84, by = 176;
        boolean hb = hit(bx - 2, by - 4, bw + 4, 12);
        g.fill(bx, by, bx + bw, by + 4, BAR_BG);
        int fw = dur > 0 ? (int) (bw * pos / dur) : 0;
        g.fill(bx, by, bx + fw, by + 4, hb ? GREEN : WHITE);
        if (hb) circle(bx + fw, by + 2, 3, WHITE);
        text(fmt(pos), 98, 174, GRAY, 24);
        text(fmt(dur), 212, 174, GRAY, 24);
        if (click(bx - 2, by - 4, bw + 4, 12) && dur > 0) {
            double f = Math.max(0, Math.min(1, (lmx - bx) / bw));
            sp.seek((long) (f * dur), SpotifyPanelMod::say);
        }

        // --- громкость
        int vx = 252, vw = 40, vy = 163;
        g.fill(238, 161, 241, 166, GRAY);
        tri(241, 163.5f, 246, 159, 246, 168, GRAY);
        boolean hv = hit(vx - 2, vy - 4, vw + 4, 12);
        g.fill(vx, vy, vx + vw, vy + 4, BAR_BG);
        g.fill(vx, vy, vx + vw * Math.max(0, Math.min(100, s.volume())) / 100, vy + 4, hv ? GREEN : WHITE);
        if (click(vx - 2, vy - 4, vw + 4, 12)) {
            double f = Math.max(0, Math.min(1, (lmx - vx) / vw));
            sp.volume((int) Math.round(f * 100), SpotifyPanelMod::say);
        }
    }

    // ================================================================ ввод

    private static boolean hit(double x, double y, double w, double h) {
        return chatOpen && lmx >= x && lmx < x + w && lmy >= y && lmy < y + h;
    }

    private static boolean click(double x, double y, double w, double h) {
        if (clicked && hit(x, y, w, h)) {
            clicked = false;
            return true;
        }
        return false;
    }

    private static boolean arrow(int x, int y, boolean up) {
        boolean hov = hit(x - 2, y - 2, 10, 8);
        int c = hov ? WHITE : DIM;
        if (up) tri(x + 3, y, x, y + 4, x + 6, y + 4, c);
        else tri(x, y, x + 6, y, x + 3, y + 4, c);
        return click(x - 2, y - 2, 10, 8);
    }

    // =============================================================== рисование

    private static void drawMosaic(int[] m, int grid, int x, int y, boolean flat) {
        for (int cy = 0; cy < grid; cy++) {
            for (int cx = 0; cx < grid; cx++) {
                int i = (cy * grid + cx) * 2;
                int lo = m[i], hi = m[i + 1];
                int px0 = x + cx * 3, py0 = y + cy * 3;
                if (flat) {
                    g.fill(px0, py0, px0 + 3, py0 + 3, mix(lo, hi, 0.5f));
                } else {
                    for (int dy = 0; dy < 3; dy++) {
                        g.fill(px0, py0 + dy, px0 + dy + 1, py0 + dy + 1, lo);
                        if (dy + 1 < 3) g.fill(px0 + dy + 1, py0 + dy, px0 + 3, py0 + dy + 1, hi);
                    }
                }
            }
        }
    }

    private static void circle(int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    /** Закрашенный треугольник построчной заливкой. */
    private static void tri(float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        int minY = (int) Math.floor(Math.min(y1, Math.min(y2, y3)));
        int maxY = (int) Math.ceil(Math.max(y1, Math.max(y2, y3)));
        float[] acc = new float[2];
        for (int y = minY; y < maxY; y++) {
            float sy = y + 0.5f;
            acc[0] = Float.MAX_VALUE;
            acc[1] = -Float.MAX_VALUE;
            edge(acc, sy, x1, y1, x2, y2);
            edge(acc, sy, x2, y2, x3, y3);
            edge(acc, sy, x3, y3, x1, y1);
            if (acc[1] > acc[0]) {
                int l = Math.round(acc[0]), r = Math.round(acc[1]);
                if (r > l) g.fill(l, y, r, y + 1, color);
            }
        }
    }

    private static void edge(float[] acc, float sy, float xa, float ya, float xb, float yb) {
        if ((sy >= ya && sy < yb) || (sy >= yb && sy < ya)) {
            float x = xa + (sy - ya) / (yb - ya) * (xb - xa);
            if (x < acc[0]) acc[0] = x;
            if (x > acc[1]) acc[1] = x;
        }
    }

    // ================================================================= текст/цвет

    private static void text(String s, int x, int y, int color, int maxW) {
        g.drawText(tr, fit(s, maxW), x, y, color, false);
    }

    private static String fit(String s, int maxW) {
        if (s == null) return "";
        if (tr.getWidth(s) <= maxW) return s;
        int ell = tr.getWidth("…");
        return tr.trimToWidth(s, Math.max(0, maxW - ell)) + "…";
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int gg = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    private static int darken(int c, float f) {
        return mix(BLACK, c, f);
    }

    private static int avgColor(int[] m) {
        long r = 0, gg = 0, b = 0;
        for (int c : m) {
            r += (c >> 16) & 0xFF;
            gg += (c >> 8) & 0xFF;
            b += c & 0xFF;
        }
        int n = Math.max(1, m.length);
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (gg / n) << 8) | (int) (b / n);
    }
}
