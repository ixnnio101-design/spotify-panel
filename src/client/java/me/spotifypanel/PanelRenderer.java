package me.spotifypanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Отрисовка панели и обработка её элементов (кнопки, ползунки, списки).
 * Положение в мире считает PanelSpace, клавиши — PanelInput, сеть — SpotifyClient.
 */
public final class PanelRenderer {
    private static final int W = 340, H = 204;
    private static final double WORLD_W = 2.0; // ширина панели в блоках

    private static final int BASE = 0xF20A0A0A;
    private static final int EDGE = 0xFF2C2C2C;
    private static final int CARD = 0xFF161616;
    private static final int ROW_HOVER = 0xFF262626;
    private static final int GREEN = 0xFF1ED760;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B3B3;
    private static final int DIM = 0xFF727272;
    private static final int OFF = 0xFF4A4A4A;
    private static final int BAR_BG = 0xFF4D4D4D;
    private static final int RED = 0xFFFF6B6B;
    private static final int BLACK = 0xFF000000;

    private static final PanelSpace space = new PanelSpace();

    // кадр
    private static long lastNs;
    private static double dt = 0.016;
    private static float alpha = 1f;
    private static TextRenderer tr;
    private static DrawContext g;
    private static SpotifyClient sp;

    // ввод
    private static boolean interactive, prevDown, downNow, pressedEdge, releasedEdge, cancelRelease;
    private static double lmx, lmy;
    private static String pressId, releasedPress, dragId, releasedDragId;
    private static double dragFrac;
    private static final Map<String, Float> hoverAnim = new HashMap<>();
    private static long lastVolSend;

    // интерфейс
    private static int view; // 0 - плейлисты, 1 - треки
    private static String selected = "";
    private static int listScroll, trackScroll;
    private static String lastUri = "";
    private static long changeAt;

    private PanelRenderer() {}

    // =================================================================== кадр

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        long nowNs = System.nanoTime();
        dt = lastNs == 0 ? 0.016 : Math.min(0.1, (nowNs - lastNs) / 1e9);
        lastNs = nowNs;

        PanelInput.update(mc);

        // анимация появления / исчезновения (~280 мс)
        PanelState.vis = approach(PanelState.vis, PanelState.visible ? 1f : 0f, (float) (dt / 0.28));
        if (PanelState.vis <= 0.002f) {
            prevDown = false;
            return;
        }
        float e = ease(PanelState.vis);

        // --- положение в мире (yaw без pitch) и проекция на экран
        float pt = tick.getTickProgress(false);
        Vec3d cam = player.getCameraPosVec(pt);
        double yaw = player.getYaw(pt), pitch = player.getPitch(pt);
        double ang = SpotifyPanelMod.config.angle;
        if (PanelState.interacting && !PanelState.fixed) ang = Math.min(ang, 28.0); // «подплывает» в зону видимости
        space.update(dt, cam, yaw, PanelState.fixed, ang);
        Vec3d p = space.world();

        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double sinY = Math.sin(yr), cosY = Math.cos(yr), sinP = Math.sin(pr), cosP = Math.cos(pr);
        double vx = p.x - cam.x, vy = p.y - cam.y, vz = p.z - cam.z;
        double fx = -sinY * cosP, fy = -sinP, fz = cosY * cosP;
        double rx = -cosY, rz = -sinY;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double zc = vx * fx + vy * fy + vz * fz;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (zc < 0.3 || dist > 24) { // панель за спиной или слишком далеко
            prevDown = false;
            return;
        }
        double xc = vx * rx + vz * rz;
        double yc = vx * ux + vy * uy + vz * uz;
        double fov = Math.toRadians(mc.options.getFov().getValue());
        double focal = (sh / 2.0) / Math.tan(fov / 2.0);
        double scx = sw / 2.0 + xc / zc * focal;
        double scy = sh / 2.0 - yc / zc * focal;
        // размер зависит от расстояния до панели, а не от направления камеры: стабилен при повороте
        double s = Math.max(0.4, Math.min(1.6, focal / dist * WORLD_W / W * (0.9 + 0.1 * e)));
        double ox = scx - W * s / 2.0;
        double oy = scy - H * s / 2.0 + (1 - e) * 14 * s;
        if (ox > sw || oy > sh || ox + W * s < 0 || oy + H * s < 0) {
            prevDown = false;
            return;
        }

        // --- мышь: курсор свободен только в режиме взаимодействия (экран SpotifyScreen)
        interactive = PanelState.interacting && mc.currentScreen instanceof SpotifyScreen && PanelState.vis > 0.95f;
        downNow = false;
        double mxs = 0, mys = 0;
        if (interactive) {
            long handle = mc.getWindow().getHandle();
            downNow = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            mxs = mc.mouse.getX() * sw / mc.getWindow().getWidth();
            mys = mc.mouse.getY() * sh / mc.getWindow().getHeight();
        }
        pressedEdge = interactive && downNow && !prevDown;
        releasedEdge = prevDown && !downNow;
        cancelRelease = releasedEdge && !interactive; // режим выключили/панель скрыли посреди перетаскивания
        releasedPress = releasedEdge ? pressId : null;
        releasedDragId = releasedEdge ? dragId : null;
        if (releasedEdge) {
            pressId = null;
            dragId = null;
        }
        prevDown = downNow;
        lmx = (mxs - ox) / s;
        lmy = (mys - oy) / s;

        g = ctx;
        tr = mc.textRenderer;
        sp = SpotifyPanelMod.spotify;
        alpha = e;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) ox, (float) oy);
        m.scale((float) s, (float) s);
        try {
            double shadowDx = -(scx - sw / 2.0) / (sw / 2.0) * 7.0; // тень смещается от центра экрана: ощущение глубины
            drawPanel((int) Math.round(shadowDx));
        } finally {
            m.popMatrix();
        }
    }

    private static void drawPanel(int shadowDx) {
        // мягкая тень (несколько слоёв)
        for (int i = 0; i < 4; i++) {
            int grow = 8 - i * 2;
            rrect(shadowDx - grow / 2 + 2, 6 - grow / 2 + 4, W + grow - 4, H + grow - 4, 12, 0x14000000);
        }
        rrect(-1, -1, W + 2, H + 2, 10, PanelState.interacting ? 0xFF2F6F46 : EDGE);
        rrect(0, 0, W, H, 9, BASE);
        // верхний блик
        rrect(6, 1, W - 12, 1, 0, 0x22FFFFFF);

        SpotifyClient.Snapshot s = sp.snapshot();
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID || s.status() == SpotifyClient.Status.LOGGED_OUT) {
            tri(150, 60, 150, 96, 182, 78, GREEN);
            boolean noId = s.status() == SpotifyClient.Status.NO_CLIENT_ID;
            textS(noId ? "Spotify не настроен" : "Spotify не подключён", 90, 112, WHITE, 170, 1.4f, true);
            textS(noId ? "/spotify setup <client_id>" : "/spotify login", 90, 132, noId ? GRAY : GREEN, 170, 1.1f, false);
            return;
        }

        rrect(8, 8, 160, 188, 7, CARD);
        rrect(176, 8, 156, 188, 7, CARD);
        drawNowPlaying(s);
        drawLibrary(s);
        drawHint();
    }

    // ============================================================ левая карточка

    private static void drawNowPlaying(SpotifyClient.Snapshot s) {
        SpotifyClient.Track t = s.track();
        boolean has = s.status() == SpotifyClient.Status.ACTIVE && t != null;

        // анимация смены трека
        String uri = has ? t.uri() : "";
        if (!uri.equals(lastUri)) {
            lastUri = uri;
            changeAt = System.currentTimeMillis();
        }
        float ta = ease(Math.min(1f, (System.currentTimeMillis() - changeAt) / 380f));
        float keep = alpha;

        // обложка
        rrect(38, 22, 100, 100, 6, 0x44000000);
        int[] cm = has ? sp.mosaic(t.coverUrl(), 24) : null;
        alpha = keep * ta;
        if (cm != null) {
            drawGrid(cm, 24, 40, 18, 4);
        } else {
            rrect(40, 18, 96, 96, 5, 0xFF282828);
            circle(88, 66, 12, 0xFF3A3A3A);
            circle(88, 66, 4, 0xFF282828);
        }

        // название и исполнитель
        String title, artist;
        int artistColor = GRAY;
        if (has) {
            title = t.title();
            artist = t.artists();
        } else if (s.status() == SpotifyClient.Status.ERROR) {
            title = "Ошибка Spotify";
            artist = s.error();
            artistColor = RED;
        } else {
            title = "Ничего не играет";
            artist = "Запусти Spotify на любом устройстве";
        }
        float slideX = (1 - ta) * 10;
        textS(marquee(title, 100), 16 + slideX, 120, WHITE, 144, 1.35f, true);
        textS(marquee(artist, 130), 16 + slideX, 136, artistColor, 144, 1.05f, false);
        alpha = keep;

        // --- прогресс (перетаскиваемый)
        long dur = has ? t.durationMs() : 0;
        long pos = s.currentProgress();
        int bx = 20, by = 152, bw = 136, bh = 5;
        Slider sl = slider("seek", bx, by, bw, bh, has && dur > 0);
        double frac = dur > 0 ? Math.max(0, Math.min(1, (double) pos / dur)) : 0;
        if (sl.active) frac = sl.frac;
        int fw = (int) Math.round(bw * frac);
        rrect(bx, by, bw, bh, 2, BAR_BG);
        if (fw > 0) rrect(bx, by, Math.max(fw, 4), bh, 2, sl.hover || sl.active ? GREEN : WHITE);
        if ((sl.hover || sl.active) && has) circle(bx + fw, by + 2, sl.active ? 5 : 4, WHITE);
        long shown = sl.active ? (long) (frac * dur) : pos;
        text(fmt(shown), 20, 162, sl.active ? WHITE : GRAY, 30);
        String total = fmt(dur);
        text(total, 156 - tr.getWidth(total), 162, GRAY, 30);
        if (sl.released && !sl.cancel && dur > 0) {
            sp.seek((long) (sl.frac * dur), SpotifyPanelMod::say);
        }

        // --- кнопки
        int cy = 183;
        boolean en = has;

        Btn bShuffle = btn("shuffle", 18, cy - 11, 24, 22, en);
        int c1 = iconColor(bShuffle, en, s.shuffle());
        int po = bShuffle.pressed ? 1 : 0;
        line(23, cy - 4 + po, 35, cy + 4 + po, c1);
        line(23, cy + 4 + po, 35, cy - 4 + po, c1);
        tri(33, cy - 8 + po, 38, cy - 5 + po, 33, cy - 1 + po, c1);
        tri(33, cy + 1 + po, 38, cy + 5 + po, 33, cy + 8 + po, c1);
        if (bShuffle.clicked) sp.shuffle(!s.shuffle(), SpotifyPanelMod::say);

        Btn bPrev = btn("prev", 50, cy - 12, 24, 24, en);
        int c2 = iconColor(bPrev, en, false);
        po = bPrev.pressed ? 1 : 0;
        fill(55, cy - 6 + po, 58, cy + 6 + po, c2);
        tri(70, cy - 6 + po, 70, cy + 6 + po, 59, cy + po, c2);
        if (bPrev.clicked) sp.previous(SpotifyPanelMod::say);

        Btn bPlay = btn("play", 74, cy - 15, 30, 30, true);
        int pr = Math.round(13 + bPlay.h * 1.5f) - (bPlay.pressed ? 2 : 0);
        circle(89, cy, pr, mix(0xFFDADADA, WHITE, bPlay.h));
        if (s.playing()) {
            fill(84, cy - 5, 88, cy + 6, BLACK);
            fill(91, cy - 5, 95, cy + 6, BLACK);
        } else {
            tri(85, cy - 7, 85, cy + 7, 96, cy, BLACK);
        }
        if (bPlay.clicked) sp.togglePlay(SpotifyPanelMod::say);

        Btn bNext = btn("next", 104, cy - 12, 24, 24, en);
        int c3 = iconColor(bNext, en, false);
        po = bNext.pressed ? 1 : 0;
        tri(108, cy - 6 + po, 108, cy + 6 + po, 119, cy + po, c3);
        fill(120, cy - 6 + po, 123, cy + 6 + po, c3);
        if (bNext.clicked) sp.next(SpotifyPanelMod::say);

        String rep = s.repeat();
        boolean repOn = !"off".equals(rep);
        Btn bRep = btn("repeat", 136, cy - 11, 24, 22, en);
        int c4 = iconColor(bRep, en, repOn);
        po = bRep.pressed ? 1 : 0;
        line(141, cy - 5 + po, 155, cy - 5 + po, c4);
        line(141, cy + 5 + po, 155, cy + 5 + po, c4);
        line(140, cy - 4 + po, 140, cy + 4 + po, c4);
        line(156, cy - 4 + po, 156, cy + 4 + po, c4);
        tri(152, cy - 9 + po, 158, cy - 5 + po, 152, cy - 1 + po, c4);
        if ("track".equals(rep)) g.drawText(tr, "1", 146, cy - 4 + po, a(c4), false);
        if (bRep.clicked) {
            String next = "off".equals(rep) ? "context" : ("context".equals(rep) ? "track" : "off");
            sp.repeat(next, SpotifyPanelMod::say);
        }
    }

    // ============================================================ правая карточка

    private static void drawLibrary(SpotifyClient.Snapshot s) {
        List<SpotifyClient.Playlist> pls = sp.playlists();
        SpotifyClient.Playlist cur = null;
        for (SpotifyClient.Playlist p : pls) {
            if (p.id().equals(selected)) {
                cur = p;
                break;
            }
        }
        if (view == 1 && cur == null) view = 0;

        // --- шапка
        if (view == 0) {
            textS("Плейлисты", 188, 15, WHITE, 110, 1.2f, true);
        } else {
            Btn back = btn("back", 180, 10, 22, 20, true);
            int bc = mix(GRAY, WHITE, back.h);
            line(193, 14, 187, 20, bc);
            line(187, 20, 193, 26, bc);
            if (back.clicked) view = 0;
            textS(cur.name(), 204, 15, WHITE, 76, 1.1f, true);

            Btn playAll = btn("playall", 288, 11, 20, 20, true);
            circle(298, 21, Math.round(8 + playAll.h * 1.5f), mix(GREEN, 0xFF3BE477, playAll.h));
            tri(295, 17, 295, 25, 303, 21, BLACK);
            if (playAll.clicked) sp.playContext(cur.uri(), "", SpotifyPanelMod::say);
        }
        drawPin();

        // --- содержимое
        int rows = 6;
        if (view == 0) {
            if (pls.isEmpty()) {
                String err = sp.playlistsError();
                text(err.isEmpty() ? "Загрузка…" : err, 188, 40, GRAY, 124);
            } else {
                int max = Math.max(0, pls.size() - rows);
                listScroll = Math.max(0, Math.min(listScroll, max));
                drawScroll(max > 0, () -> listScroll = Math.max(0, listScroll - 1),
                        () -> listScroll = Math.min(max, listScroll + 1));
                for (int i = 0; i < rows; i++) {
                    int idx = listScroll + i;
                    if (idx >= pls.size()) break;
                    SpotifyClient.Playlist p = pls.get(idx);
                    int y = 34 + i * 24;
                    Btn b = btn("pl" + idx, 180, y, 138, 24, true);
                    if (b.hover) rrect(180, y, 138, 24, 5, ROW_HOVER);
                    int[] cm = sp.mosaic(p.coverUrl(), 6);
                    if (cm != null) drawGrid(cm, 6, 184, y + 3, 3);
                    else rrect(184, y + 3, 18, 18, 3, 0xFF282828);
                    text(p.name(), 208, y + 4, WHITE, 106);
                    text(p.total() + " треков", 208, y + 13, GRAY, 106);
                    if (b.clicked) {
                        selected = p.id();
                        view = 1;
                        trackScroll = 0;
                        sp.requestItems(p.id(), true);
                    }
                }
            }
        } else {
            List<SpotifyClient.Item> its = sp.items(cur.id());
            if (its == null) {
                String err = sp.itemError(cur.id());
                text(err.isEmpty() ? "Загрузка треков…" : err, 188, 40, GRAY, 124);
                if (err.isEmpty()) sp.requestItems(cur.id(), false);
            } else if (its.isEmpty()) {
                text("В плейлисте нет треков", 188, 40, GRAY, 124);
            } else {
                int max = Math.max(0, its.size() - rows);
                trackScroll = Math.max(0, Math.min(trackScroll, max));
                drawScroll(max > 0, () -> trackScroll = Math.max(0, trackScroll - 1),
                        () -> trackScroll = Math.min(max, trackScroll + 1));
                String playingUri = s.track() != null ? s.track().uri() : "";
                for (int i = 0; i < rows; i++) {
                    int idx = trackScroll + i;
                    if (idx >= its.size()) break;
                    SpotifyClient.Item it = its.get(idx);
                    int y = 34 + i * 24;
                    boolean isCur = !playingUri.isEmpty() && playingUri.equals(it.uri());
                    Btn b = btn("tr" + idx, 180, y, 138, 24, true);
                    if (b.hover) rrect(180, y, 138, 24, 5, ROW_HOVER);
                    if (b.hover) tri(186, y + 7, 186, y + 17, 194, y + 12, WHITE);
                    else text(String.valueOf(idx + 1), 186, y + 8, isCur ? GREEN : DIM, 14);
                    text(it.title(), 200, y + 4, isCur ? GREEN : WHITE, 82);
                    text(it.artists(), 200, y + 13, GRAY, 82);
                    String d = fmt(it.durationMs());
                    g.drawText(tr, d, 316 - tr.getWidth(d), y + 8, a(DIM), false);
                    if (b.clicked) sp.playContext(cur.uri(), it.uri(), SpotifyPanelMod::say);
                }
            }
        }

        // --- громкость (перетаскиваемая)
        int vx = 206, vy = 187, vw = 92;
        boolean hasPlayer = s.status() == SpotifyClient.Status.ACTIVE;
        Slider vs = slider("vol", vx, vy, vw, 4, hasPlayer);
        int vol = Math.max(0, Math.min(100, s.volume()));
        if (vs.active) {
            vol = (int) Math.round(vs.frac * 100);
            long now = System.currentTimeMillis();
            if (now - lastVolSend > 150) { // не чаще ~7 запросов в секунду, итог отправляется при отпускании
                lastVolSend = now;
                sp.volume(vol, SpotifyPanelMod::say);
            }
        }
        if (vs.released) {
            vol = (int) Math.round(vs.frac * 100);
            sp.volume(vol, SpotifyPanelMod::say);
        }
        int vc = !hasPlayer ? OFF : mix(GRAY, WHITE, vs.hover || vs.active ? 1f : 0f);
        fill(185, 186, 188, 191, vc);
        tri(188, 186, 194, 182, 194, 195, vc);
        if (vol == 0) {
            line(197, 185, 203, 191, vc);
        }
        rrect(vx, vy, vw, 4, 2, BAR_BG);
        int vf = vw * vol / 100;
        if (vf > 0) rrect(vx, vy, Math.max(vf, 4), 4, 2, vs.hover || vs.active ? GREEN : WHITE);
        if ((vs.hover || vs.active) && hasPlayer) circle(vx + vf, vy + 2, vs.active ? 5 : 4, WHITE);
        String vt = vol + "%";
        g.drawText(tr, vt, 326 - tr.getWidth(vt), 186, a(vs.active ? WHITE : GRAY), false);
    }

    private static void drawScroll(boolean enabled, Runnable up, Runnable down) {
        Btn bu = btn("sup", 318, 34, 14, 12, enabled);
        int cu = !enabled ? OFF : mix(DIM, WHITE, bu.h);
        tri(325, 36, 321, 42, 329, 42, cu);
        if (bu.clicked) up.run();
        Btn bd = btn("sdown", 318, 166, 14, 12, enabled);
        int cd = !enabled ? OFF : mix(DIM, WHITE, bd.h);
        tri(321, 168, 329, 168, 325, 174, cd);
        if (bd.clicked) down.run();
    }

    private static void drawPin() {
        Btn b = btn("pin", 310, 11, 20, 20, true);
        boolean fixed = PanelState.fixed;
        int c = fixed ? GREEN : mix(DIM, WHITE, b.h);
        int po = b.pressed ? 1 : 0;
        circle(320, 18 + po, 4, c);
        fill(319, 21 + po, 321, 27 + po, c);
        if (fixed) rrect(316, 29 + po, 8, 1, 0, c);
        if (b.clicked) PanelState.toggleFixed();
    }

    private static void drawHint() {
        if (PanelState.interacting) {
            String t = "Esc / " + PanelInput.keyName(PanelInput.interact) + " — выйти из режима управления";
            g.drawText(tr, t, (W - tr.getWidth(t)) / 2, H + 8, a(GRAY), false);
        } else {
            String t = "[" + PanelInput.keyName(PanelInput.interact) + "] управление   ["
                    + PanelInput.keyName(PanelInput.mode) + "] " + (PanelState.fixed ? "фиксация" : "следование")
                    + "   [" + PanelInput.keyName(PanelInput.hide) + "] скрыть";
            g.drawText(tr, t, (W - tr.getWidth(t)) / 2, H + 8, a(DIM), false);
        }
    }

    // ================================================================ ввод

    private static final class Btn {
        boolean hover, pressed, clicked;
        float h;
    }

    private static final class Slider {
        boolean hover, active, released, cancel;
        double frac;
    }

    private static boolean hit(double x, double y, double w, double h) {
        return interactive && lmx >= x && lmx < x + w && lmy >= y && lmy < y + h;
    }

    /** Кнопка: срабатывает при отпускании над ней (после нажатия на ней же), показывает состояния hover/pressed. */
    private static Btn btn(String id, double x, double y, double w, double h, boolean enabled) {
        Btn b = new Btn();
        b.hover = enabled && hit(x, y, w, h);
        if (b.hover && pressedEdge) {
            pressId = id;
            pressedEdge = false; // нажатие «съедено», соседние элементы не сработают
        }
        b.pressed = b.hover && id.equals(pressId) && downNow;
        b.clicked = b.hover && id.equals(releasedPress);
        b.h = hoverAmt(id, b.hover);
        return b;
    }

    /** Горизонтальный ползунок: значение меняется при удержании, итог доступен при отпускании. */
    private static Slider slider(String id, int x, int y, int w, int h, boolean enabled) {
        Slider s = new Slider();
        s.hover = enabled && hit(x - 3, y - 7, w + 6, h + 14);
        if (s.hover && pressedEdge) {
            dragId = id;
            pressedEdge = false;
        }
        s.active = id.equals(dragId);
        if (s.active) {
            dragFrac = clamp01((lmx - x) / w);
            s.frac = dragFrac;
        }
        if (id.equals(releasedDragId)) {
            s.released = true;
            s.cancel = cancelRelease;
            s.frac = cancelRelease ? dragFrac : clamp01((lmx - x) / w);
        }
        return s;
    }

    private static float hoverAmt(String id, boolean hovered) {
        float v = hoverAnim.getOrDefault(id, 0f);
        v = approach(v, hovered ? 1f : 0f, (float) (dt / 0.12));
        hoverAnim.put(id, v);
        return v;
    }

    private static int iconColor(Btn b, boolean enabled, boolean green) {
        if (!enabled) return OFF;
        if (green) return mix(GREEN, WHITE, b.h * 0.4f);
        return mix(GRAY, WHITE, b.h);
    }

    // =============================================================== рисование

    private static int a(int c) {
        int al = (int) ((c >>> 24) * alpha);
        return (al << 24) | (c & 0xFFFFFF);
    }

    private static void fill(int x1, int y1, int x2, int y2, int c) {
        g.fill(x1, y1, x2, y2, a(c));
    }

    private static void rrect(int x, int y, int w, int h, int r, int c) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            fill(x + inset, y + i, x + w - inset, y + i + 1, c);
            fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, c);
        }
        fill(x, y + r, x + w, y + h - r, c);
    }

    private static void circle(int cx, int cy, int r, int c) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
            fill(cx - half, cy + dy, cx + half, cy + dy + 1, c);
        }
    }

    private static void line(int x0, int y0, int x1, int y1, int c) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) {
            fill(x0, y0, x0 + 2, y0 + 2, c);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + Math.round((float) (x1 - x0) * i / steps);
            int y = y0 + Math.round((float) (y1 - y0) * i / steps);
            fill(x, y, x + 2, y + 2, c);
        }
    }

    private static void drawGrid(int[] cm, int grid, int x, int y, int cs) {
        for (int cy = 0; cy < grid; cy++) {
            for (int cx = 0; cx < grid; cx++) {
                fill(x + cx * cs, y + cy * cs, x + cx * cs + cs, y + cy * cs + cs, cm[cy * grid + cx]);
            }
        }
    }

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
                if (r > l) fill(l, y, r, y + 1, color);
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

    // ================================================================= текст

    private static void text(String s, int x, int y, int color, int maxW) {
        g.drawText(tr, fit(s, maxW, false), x, y, a(color), false);
    }

    /** Текст с масштабом (чтобы название и исполнитель были крупнее основного шрифта). */
    private static void textS(String s, float x, float y, int color, int maxW, float scale, boolean bold) {
        var m = g.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        String f = fit(s, (int) (maxW / scale), bold);
        Text t = bold ? Text.literal(f).formatted(Formatting.BOLD) : Text.literal(f);
        g.drawText(tr, t, 0, 0, a(color), false);
        m.popMatrix();
    }

    private static int width(String s, boolean bold) {
        return bold ? tr.getWidth(Text.literal(s).formatted(Formatting.BOLD)) : tr.getWidth(s);
    }

    private static String fit(String s, int maxW, boolean bold) {
        if (s == null) return "";
        if (width(s, bold) <= maxW) return s;
        int n = s.length();
        while (n > 0 && width(s.substring(0, n) + "…", bold) > maxW) n--;
        return s.substring(0, n) + "…";
    }

    /** Бегущая строка для длинных названий: пауза в начале, прокрутка, пауза в конце. */
    private static String marquee(String s, int maxW) {
        if (s == null) return "";
        if (tr.getWidth(s) <= maxW) return s;
        int n = s.length();
        int maxOff = 0;
        while (maxOff < n && tr.getWidth(s.substring(maxOff)) > maxW) maxOff++;
        long step = System.currentTimeMillis() / 140;
        int pause = 10;
        long period = pause * 2L + maxOff;
        int pos = (int) (step % period);
        int off = pos < pause ? 0 : (pos < pause + maxOff ? pos - pause : maxOff);
        return tr.trimToWidth(s.substring(off), maxW);
    }

    private static String fmt(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    // ============================================================ утилиты

    private static float approach(float v, float target, float step) {
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int gg = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }
}
