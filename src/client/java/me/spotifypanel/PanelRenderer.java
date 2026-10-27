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
import org.lwjgl.glfw.GLFWScrollCallback;

import java.util.ArrayList;
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
    private static final int WARN = 0xFFFFAA00;
    private static final int ERR = 0xFFFF6B6B;

    // что сейчас тащит мышь
    private static final int D_NONE = 0, D_SIDE = 1, D_TRACK = 2, D_SEEK = 3, D_VOL = 4;

    // позиция панели в мире (с плавным догоном)
    private static boolean init;
    private static double px, py, pz;
    private static double bx, by, bz, syaw; // сглаженные положение и поворот тела
    private static long lastNs;

    // позиция панели на экране (держим на месте, пока открыт чат — иначе по ней не попасть)
    private static boolean screenInit;
    private static double sox, soy, ss, settle;

    // состояние интерфейса
    private static String selected = "";
    private static int sideScroll, trackScroll;
    private static boolean down, prevDown, clicked, released, chatOpen;
    private static double lmx, lmy;
    private static int drag = D_NONE;
    private static double dragOff;
    private static long seekTarget, lastVolSend;
    private static int lastSentVol = -1;

    // колесо мыши (перехватываем только когда курсор над панелью)
    private static volatile boolean overPanel;
    private static volatile double wheelAcc;
    private static int wheelSteps;
    private static boolean scrollHooked;
    private static GLFWScrollCallback prevScroll;

    private static TextRenderer tr;
    private static DrawContext g;
    private static SpotifyClient sp;

    private static String wrapKey = "";
    private static List<String> wrapVal = List.of();

    private PanelRenderer() {}

    // =================================================================== кадр

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        released = false;
        try {
            draw(ctx, tick);
        } finally {
            if (released) drag = D_NONE;
        }
    }

    private static void hookScroll(long handle) {
        if (scrollHooked) return;
        scrollHooked = true;
        prevScroll = GLFW.glfwSetScrollCallback(handle, (win, xo, yo) -> {
            if (overPanel) wheelAcc += yo;
            else if (prevScroll != null) prevScroll.invoke(win, xo, yo);
        });
    }

    private static void draw(DrawContext ctx, RenderTickCounter tick) {
        overPanel = false;
        if (!SpotifyPanelMod.config.visible) {
            drag = D_NONE;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        hookScroll(mc.getWindow().getHandle());

        float pt = tick.getTickProgress(false);
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        Vec3d cam = player.getCameraPosVec(pt);
        double yr = Math.toRadians(player.getYaw(pt)), pr = Math.toRadians(player.getPitch(pt));
        double sinY = Math.sin(yr), cosY = Math.cos(yr), sinP = Math.sin(pr), cosP = Math.cos(pr);

        // панель привязана к ТОРСУ: берём положение тела и поворот тела (не головы, не камеры),
        // поэтому вертеть головой можно сколько угодно — в мире панель стоит на месте
        Vec3d body = player.getLerpedPos(pt);
        double bodyYaw = player.getBodyYaw();

        long now = System.nanoTime();
        double dt = init ? Math.min(0.1, (now - lastNs) / 1e9) : 1.0;
        lastNs = now;

        // Сглаживаем отдельно угол и положение тела, а панель каждый кадр заново ставим
        // по этим двум числам. Поэтому при повороте она облетает игрока по дуге
        // и не режет путь напрямик через тело.
        double ddx = body.x - bx, ddy = body.y - by, ddz = body.z - bz;
        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        if (!init || dist > 16) {
            bx = body.x; by = body.y; bz = body.z;
            syaw = bodyYaw;
            init = true;
        } else {
            // чем дальше убежал игрок — тем быстрее панель догоняет
            double ka = 1.0 - Math.exp(-dt * (4.0 + 3.0 * dist));
            bx += ddx * ka; by += ddy * ka; bz += ddz * ka;
            // чем резче и больше поворот — тем быстрее догоняет угол (при 180° почти мгновенно)
            double err = ((bodyYaw - syaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
            double ky = 1.0 - Math.exp(-dt * (5.0 + 0.2 * Math.abs(err)));
            syaw += err * ky;
        }
        double br = Math.toRadians(syaw);
        double sinB = Math.sin(br), cosB = Math.cos(br);
        px = bx - sinB * 2.6 - cosB * 1.5;
        py = by + 1.3;
        pz = bz + cosB * 2.6 - sinB * 1.5;

        // мышь (курсор виден только при открытом чате) — читаем до проекции
        chatOpen = mc.currentScreen instanceof ChatScreen;
        down = false;
        double mxs = 0, mys = 0;
        if (chatOpen) {
            long handle = mc.getWindow().getHandle();
            down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            mxs = mc.mouse.getX() * sw / mc.getWindow().getWidth();
            mys = mc.mouse.getY() * sh / mc.getWindow().getHeight();
        }
        clicked = chatOpen && down && !prevDown;
        released = prevDown && !down;
        prevDown = down;

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
        double tsc = Math.max(0.5, Math.min(1.25, focal / zc * WORLD_W / W));
        double tox = scx - W * tsc / 2.0, toy = scy - H * tsc / 2.0;

        // на экране: пока чат открыт — панель стоит намертво (можно спокойно кликать),
        // иначе мягко догоняет цель
        if (!screenInit) {
            sox = tox; soy = toy; ss = tsc;
            screenInit = true;
        } else if (chatOpen) {
            settle = 0.4; // экран держим на месте
        } else if (settle > 0) {
            // сразу после закрытия чата плавно возвращаемся к живой позиции, без скачка
            settle -= dt;
            double a = 1.0 - Math.exp(-dt * 12.0);
            sox += (tox - sox) * a;
            soy += (toy - soy) * a;
            ss += (tsc - ss) * a;
        } else {
            sox = tox; soy = toy; ss = tsc; // обычный режим: никакого дополнительного «плавания»
        }
        double s = ss;
        double ox = chatOpen ? Math.round(sox) : sox;
        double oy = chatOpen ? Math.round(soy) : soy;
        if (ox > sw || oy > sh || ox + W * s < 0 || oy + H * s < 0) return;

        lmx = (mxs - ox) / s;
        lmy = (mys - oy) / s;
        overPanel = chatOpen && lmx >= 0 && lmx < W && lmy >= 0 && lmy < H;
        int steps = (int) wheelAcc;
        wheelAcc -= steps;
        wheelSteps = overPanel ? steps : 0;
        if (!overPanel) wheelAcc = 0;

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
        text("Медиатека", 10, 9, WHITE, 66);

        List<SpotifyClient.Playlist> pls = sp.playlists();
        if (pls.isEmpty()) {
            String err = sp.playlistsError();
            if (err.isEmpty()) {
                text("Загрузка…", 10, 26, GRAY, 66);
            } else {
                wrapped(err, 10, 26, 66, 10, ERR);
                text("Повтор… (" + sp.playlistFails() + ")", 10, 140, DIM, 66);
            }
            return;
        }

        final int vis = 6;
        int maxScroll = Math.max(0, pls.size() - vis);
        if (wheelSteps != 0 && hit(4, 4, 76, 146)) sideScroll -= wheelSteps * 2;
        sideScroll = Math.max(0, Math.min(sideScroll, maxScroll));
        sideScroll = scrollbar(77, 24, 120, pls.size(), vis, sideScroll, D_SIDE);

        for (int i = 0; i < vis; i++) {
            int idx = sideScroll + i;
            if (idx >= pls.size()) break;
            SpotifyClient.Playlist p = pls.get(idx);
            int y = 24 + i * 20;
            boolean sel = p.id().equals(selected);
            boolean hov = hit(6, y, 70, 19);
            if (sel) g.fill(6, y, 76, y + 19, ROW_SEL);
            else if (hov) g.fill(6, y, 76, y + 19, ROW_HOVER);

            int[] img = sp.pixels(p.coverUrl(), 9); // 64x64 -> 9x9 клеток по 2px = 18px
            if (img != null) drawImage(img, 9, 7, y + 1, 2);
            else g.fill(7, y + 1, 25, y + 19, 0xFF282828);

            text(p.name(), 28, y + 2, sel ? GREEN : WHITE, 48);
            text(p.total() + " тр.", 28, y + 11, GRAY, 48);

            if (click(6, y, 70, 19)) {
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
        int[] avg = sp.pixels(cur.coverUrl(), 4);
        int[] img = sp.pixels(cur.coverUrl(), 36);
        int base = avg != null ? darken(avgColor(avg), 0.55f) : 0xFF1B3B4A;
        for (int k = 0; k < 20; k++) {
            g.fill(84, 4 + k * 3, 296, 7 + k * 3, mix(base, PANEL, k / 19f));
        }
        if (img != null) drawImage(img, 36, 92, 12, 1);
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
        g.fill(88, 79, 290, 80, 0xFF2A2A2A);

        List<SpotifyClient.Item> its = sp.items(cur.id());
        if (its == null) {
            String err = sp.itemError(cur.id());
            if (err.isEmpty()) {
                text("Загрузка треков…", 94, 92, GRAY, 190);
                sp.requestItems(cur.id(), false);
            } else {
                wrapped(err, 94, 90, 190, 6, WARN);
            }
            return;
        }
        if (its.isEmpty()) {
            text("В плейлисте нет треков", 94, 92, GRAY, 196);
            return;
        }

        final int vis = 5;
        int maxScroll = Math.max(0, its.size() - vis);
        if (wheelSteps != 0 && hit(84, 4, 212, 146)) trackScroll -= wheelSteps * 2;
        trackScroll = Math.max(0, Math.min(trackScroll, maxScroll));
        trackScroll = scrollbar(291, 82, 60, its.size(), vis, trackScroll, D_TRACK);

        String playingUri = s.track() != null ? s.track().uri() : "";
        for (int r = 0; r < vis; r++) {
            int idx = trackScroll + r;
            if (idx >= its.size()) break;
            SpotifyClient.Item it = its.get(idx);
            int ry = 82 + r * 12;
            boolean hov = hit(88, ry, 201, 12);
            boolean isCur = !playingUri.isEmpty() && playingUri.equals(it.uri());
            if (hov) g.fill(88, ry, 289, ry + 12, ROW_HOVER);

            if (hov) tri(93, ry + 2, 93, ry + 9, 99, ry + 5.5f, WHITE);
            else text(String.valueOf(idx + 1), 94, ry + 2, isCur ? GREEN : GRAY, 12);

            text(it.title(), 106, ry + 2, isCur ? GREEN : WHITE, 92);
            text(it.artists(), 202, ry + 2, GRAY, 58);
            String d = fmt(it.durationMs());
            g.drawText(tr, d, 288 - tr.getWidth(d), ry + 2, GRAY, false);

            if (click(88, ry, 201, 12)) {
                sp.playContext(cur.uri(), it.uri(), SpotifyPanelMod::say);
            }
        }
    }

    // ================================================================ нижняя панель

    private static void drawBottom(SpotifyClient.Snapshot s) {
        g.fill(4, 152, 296, 153, 0xFF1A1A1A);
        SpotifyClient.Track t = s.track();

        if (s.status() == SpotifyClient.Status.ACTIVE && t != null) {
            int[] img = sp.pixels(t.coverUrl(), 12); // 12 клеток по 2px = 24px
            if (img != null) drawImage(img, 12, 8, 158, 2);
            else g.fill(8, 158, 32, 182, 0xFF282828);
            text(t.title(), 36, 159, WHITE, 60);
            text(t.artists(), 36, 170, GRAY, 60);
        } else if (s.status() == SpotifyClient.Status.ERROR) {
            wrapped(s.error(), 8, 158, 86, 3, ERR);
        } else {
            text("Ничего не играет", 8, 162, GRAY, 86);
            text("Включи трек в Spotify", 8, 173, DIM, 86);
        }

        // --- кнопки управления (зоны клика 16x16 встык, промахнуться сложно)
        int cy = 163;

        // shuffle
        boolean sh = s.shuffle();
        boolean hs = hit(125, cy - 8, 16, 16);
        int cs = sh ? GREEN : (hs ? WHITE : GRAY);
        for (int i = 0; i < 8; i++) {
            g.fill(129 + i, cy - 4 + i, 130 + i, cy - 3 + i, cs);
            g.fill(129 + i, cy + 3 - i, 130 + i, cy + 4 - i, cs);
        }
        if (click(125, cy - 8, 16, 16)) sp.shuffle(!sh, SpotifyPanelMod::say);

        // previous
        boolean hp = hit(141, cy - 8, 16, 16);
        int cp = hp ? WHITE : GRAY;
        g.fill(144, cy - 4, 146, cy + 4, cp);
        tri(153, cy - 4, 153, cy + 4, 146, cy, cp);
        if (click(141, cy - 8, 16, 16)) sp.previous(SpotifyPanelMod::say);

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
        boolean hn = hit(173, cy - 8, 16, 16);
        int cn = hn ? WHITE : GRAY;
        tri(177, cy - 4, 177, cy + 4, 184, cy, cn);
        g.fill(184, cy - 4, 186, cy + 4, cn);
        if (click(173, cy - 8, 16, 16)) sp.next(SpotifyPanelMod::say);

        // repeat
        String rep = s.repeat();
        boolean on = !"off".equals(rep);
        boolean hr = hit(189, cy - 8, 16, 16);
        int cr = on ? GREEN : (hr ? WHITE : GRAY);
        g.fill(192, cy - 3, 201, cy - 2, cr);
        g.fill(192, cy + 2, 201, cy + 3, cr);
        g.fill(192, cy - 3, 193, cy + 3, cr);
        g.fill(200, cy - 3, 201, cy + 3, cr);
        if ("track".equals(rep)) g.fill(195, cy - 1, 198, cy + 1, cr);
        if (click(189, cy - 8, 16, 16)) {
            String nextMode = "off".equals(rep) ? "context" : ("context".equals(rep) ? "track" : "off");
            sp.repeat(nextMode, SpotifyPanelMod::say);
        }

        // --- прогресс: клик перематывает, зажал и тянешь — перемотка применится, когда отпустишь
        long dur = t != null ? t.durationMs() : 0;
        int bx = 124, bw = 84, by = 176;
        boolean hb = hit(bx - 3, by - 5, bw + 6, 14);
        if (click(bx - 3, by - 5, bw + 6, 14) && dur > 0) drag = D_SEEK;
        long pos = s.currentProgress();
        boolean seeking = drag == D_SEEK && dur > 0;
        if (seeking) {
            double f = Math.max(0, Math.min(1, (lmx - bx) / bw));
            seekTarget = (long) (f * dur);
            pos = seekTarget;
            if (released) sp.seek(seekTarget, SpotifyPanelMod::say);
        }
        g.fill(bx, by, bx + bw, by + 4, BAR_BG);
        int fw = dur > 0 ? (int) (bw * pos / dur) : 0;
        g.fill(bx, by, bx + fw, by + 4, (hb || seeking) ? GREEN : WHITE);
        if (hb || seeking) circle(bx + fw, by + 2, 3, WHITE);
        text(fmt(pos), 98, 174, GRAY, 24);
        text(fmt(dur), 212, 174, GRAY, 24);

        // --- громкость: тянется мышью, колесом над блоком — шаг 5%
        int vx = 252, vw = 40, vy = 163;
        g.fill(238, 161, 241, 166, GRAY);
        tri(241, 163.5f, 246, 159, 246, 168, GRAY);
        boolean hv = hit(vx - 3, vy - 5, vw + 6, 14);
        if (click(vx - 3, vy - 5, vw + 6, 14)) {
            drag = D_VOL;
            lastSentVol = -1;
            lastVolSend = 0;
        }
        int vol = Math.max(0, Math.min(100, s.volume()));
        if (drag == D_VOL) {
            int nv = (int) Math.round(Math.max(0, Math.min(1, (lmx - vx) / vw)) * 100);
            long nowMs = System.currentTimeMillis();
            if (nv != lastSentVol && (released || nowMs - lastVolSend >= 100)) {
                sp.volume(nv, SpotifyPanelMod::say);
                lastSentVol = nv;
                lastVolSend = nowMs;
            }
            vol = nv;
        }
        if (wheelSteps != 0 && hit(232, 152, 64, 36)) {
            vol = Math.max(0, Math.min(100, vol + wheelSteps * 5));
            sp.volume(vol, SpotifyPanelMod::say);
        }
        g.fill(vx, vy, vx + vw, vy + 4, BAR_BG);
        g.fill(vx, vy, vx + vw * vol / 100, vy + 4, (hv || drag == D_VOL) ? GREEN : WHITE);
        if (hv || drag == D_VOL) circle(vx + vw * vol / 100, vy + 2, 3, WHITE);
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

    /** Вертикальный скроллбар: тащится мышью, клик по полосе — ползунок прыгает туда. Возвращает новую позицию. */
    private static int scrollbar(int x, int y, int h, int total, int vis, int scroll, int id) {
        if (total <= vis) return scroll;
        int max = total - vis;
        int th = Math.max(8, h * vis / total);
        if (click(x - 3, y, 8, h)) {
            drag = id;
            int ty0 = y + (h - th) * scroll / max;
            dragOff = (lmy >= ty0 && lmy < ty0 + th) ? lmy - ty0 : th / 2.0;
        }
        if (drag == id) {
            double f = (lmy - dragOff - y) / (double) (h - th);
            scroll = (int) Math.round(Math.max(0, Math.min(1, f)) * max);
        }
        int ty = y + (h - th) * scroll / max;
        boolean hov = hit(x - 3, y, 8, h);
        g.fill(x, y, x + 3, y + h, 0xFF1C1C1C);
        g.fill(x, ty, x + 3, ty + th, (drag == id || hov) ? GRAY : 0xFF5A5A5A);
        return scroll;
    }

    // =============================================================== рисование

    /** Картинка n x n, каждая клетка — cell x cell пикселей. Подряд идущие одинаковые цвета склеиваются в один fill. */
    private static void drawImage(int[] img, int n, int x, int y, int cell) {
        for (int j = 0; j < n; j++) {
            int row = j * n;
            int a = 0;
            while (a < n) {
                int c = img[row + a];
                int b = a + 1;
                while (b < n && img[row + b] == c) b++;
                g.fill(x + a * cell, y + j * cell, x + b * cell, y + (j + 1) * cell, c);
                a = b;
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

    /** Текст с переносом по словам (длинные слова режутся). Лишние строки отбрасываются с «…». */
    private static void wrapped(String s, int x, int y, int maxW, int maxLines, int color) {
        List<String> lines = wrap(s, maxW, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            g.drawText(tr, lines.get(i), x, y + i * 10, color, false);
        }
    }

    private static List<String> wrap(String s, int maxW, int maxLines) {
        if (s == null) s = "";
        String key = maxW + "|" + maxLines + "|" + s;
        if (key.equals(wrapKey)) return wrapVal;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (tr.getWidth(t) <= maxW) {
                cur.setLength(0);
                cur.append(t);
                continue;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            while (tr.getWidth(word) > maxW) {
                String part = tr.trimToWidth(word, maxW);
                if (part.isEmpty()) break;
                out.add(part);
                word = word.substring(part.length());
            }
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, fit(cut.get(maxLines - 1) + "…", maxW));
            out = cut;
        }
        wrapKey = key;
        wrapVal = out;
        return out;
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
