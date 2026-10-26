package me.spotifypanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Голографическая панель Spotify. Изогнута дугой вокруг ТУЛОВИЩА игрока (привязка к повороту корпуса,
 * а не к камере и голове), стоит на одной высоте, видна и от первого лица, и в F5.
 * Рисуется вертикальными полосами с перспективой. Управление: курсор (чат / режим курсора) или прицел.
 */
public final class PanelRenderer {
    // ---------------------------------------------------------------- размеры
    private static final int W = 400, H = 240;

    // слайдеры (используются и для рисования, и для перетаскивания)
    private static final int PB_X = 190, PB_W = 120, PB_Y = 226;
    private static final int VOL_X = 356, VOL_W = 36, VOL_Y = 210;

    // ---------------------------------------------------------------- цвета
    private static final int BG = 0xD0060B10;
    private static final int PANEL = 0xC80F1820;
    private static final int ROW_HOVER = 0xD01E2E3A;
    private static final int ROW_SEL = 0xD02A3C4A;
    private static final int GREEN = 0xFF1ED760;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB7C2CC;
    private static final int DIM = 0xFF73828E;
    private static final int BAR_BG = 0xFF3A4652;
    private static final int CYAN = 0xFF5CE1E6;

    private static final String[] HEX = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F"};

    // ---------------------------------------------------------------- состояние
    public static volatile boolean aimHover;

    private static boolean smInit;
    private static double smBody;
    private static long lastNs;
    private static double anim;

    private static boolean frozen, hasLast;
    private static double frozenX, frozenY, frozenZ, lastX, lastY, lastZ;

    private static boolean pKt, pKl, pKc;

    private static String selected = "";
    private static int sideScroll, trackScroll;
    private static boolean prevDown, down, clicked, inputOn;
    private static double lmx, lmy;
    private static int dragId;
    private static double dragFrac;
    private static long lastVolSend;

    // камера и геометрия панели (на кадр)
    private static double camX, camY, camZ, fX, fY, fZ, rX, rZ, uX, uY, uZ, focal, sw2, sh2;
    private static double cylX, cylZ, cylR, cylAc, pcy, kS;

    private static TextRenderer tr;
    private static DrawContext g;
    private static SpotifyClient sp;
    private static SpotifyClient.Snapshot snap;
    private static final Map<String, String> fitCache = new HashMap<>();

    private PanelRenderer() {}

    // ================================================================ публичные действия

    public static void setVisible(boolean v) {
        Config c = SpotifyPanelMod.config;
        c.visible = v;
        c.save();
    }

    public static void toggleFrozen() {
        if (!frozen) {
            if (!hasLast) {
                SpotifyPanelMod.say("Панель ещё не показана.");
                return;
            }
            frozen = true;
            frozenX = lastX;
            frozenY = lastY;
            frozenZ = lastZ;
            SpotifyPanelMod.say("Панель закреплена в мире и всегда повёрнута к тебе.");
        } else {
            frozen = false;
            SpotifyPanelMod.say("Панель снова следует за корпусом.");
        }
    }

    public static void toggleCursor() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!SpotifyPanelMod.config.visible) setVisible(true);
        mc.execute(() -> {
            if (mc.currentScreen instanceof CursorScreen) {
                mc.setScreen(null);
            } else if (mc.currentScreen == null) {
                mc.setScreen(new CursorScreen());
            }
        });
    }

    // ================================================================ кадр

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;
        Config cfg = SpotifyPanelMod.config;

        long nowNs = System.nanoTime();
        double dt = lastNs == 0 ? 0.016 : Math.min(0.1, (nowNs - lastNs) / 1e9);
        lastNs = nowNs;

        boolean mouseMode = mc.currentScreen instanceof ChatScreen || mc.currentScreen instanceof CursorScreen;
        boolean crosshair = mc.currentScreen == null;
        handleKeys(mc, cfg);

        double target = cfg.visible ? 1.0 : 0.0;
        if (anim < target) anim = Math.min(target, anim + dt / 0.9);
        else if (anim > target) anim = Math.max(target, anim - dt / 0.9);
        if (anim <= 0.001) {
            aimHover = false;
            prevDown = false;
            return;
        }

        float pt = tick.getTickProgress(false);
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        Vec3d eye = player.getCameraPosVec(pt);
        double yawDeg = player.getYaw(pt), pitchDeg = player.getPitch(pt);

        // ---- камера: от первого лица или F5 (3-е лицо на расстоянии 4 блоков)
        Perspective persp = mc.options.getPerspective();
        boolean front = persp.isFrontView();
        double cYaw = yawDeg + (front ? 180.0 : 0.0);
        double cPitch = front ? -pitchDeg : pitchDeg;
        double yr = Math.toRadians(cYaw), pr = Math.toRadians(cPitch);
        double sinY = Math.sin(yr), cosY = Math.cos(yr), sinP = Math.sin(pr), cosP = Math.cos(pr);
        fX = -sinY * cosP; fY = -sinP; fZ = cosY * cosP;
        rX = -cosY; rZ = -sinY;
        uX = -rZ * fY; uY = rZ * fX - rX * fZ; uZ = rX * fY;
        if (persp.isFirstPerson()) {
            camX = eye.x; camY = eye.y; camZ = eye.z;
        } else {
            camX = eye.x - fX * 4.0; camY = eye.y - fY * 4.0; camZ = eye.z - fZ * 4.0;
        }
        double fov = Math.toRadians(mc.options.getFov().getValue());
        focal = (sh / 2.0) / Math.tan(fov / 2.0);
        sw2 = sw / 2.0;
        sh2 = sh / 2.0;

        // ---- панель привязана к повороту КОРПУСА (не головы) и к одной высоте
        double bodyYaw = player.getBodyYaw();
        double dby = wrapDeg(bodyYaw - smBody);
        smBody = smInit ? smBody + dby * (1 - Math.exp(-dt * 8)) : bodyYaw;
        smInit = true;

        cylX = eye.x;
        cylZ = eye.z;
        double ac;
        if (frozen) {
            double dxp = frozenX - cylX, dzp = frozenZ - cylZ;
            cylR = Math.max(0.6, Math.hypot(dxp, dzp));
            ac = Math.atan2(-dxp, dzp);
            pcy = frozenY;
        } else {
            cylR = cfg.distance;
            ac = Math.toRadians(smBody + cfg.angle);
            pcy = eye.y - 0.3;
        }
        cylAc = ac;
        kS = cfg.width / W;
        lastX = cylX - Math.sin(ac) * cylR;
        lastZ = cylZ + Math.cos(ac) * cylR;
        lastY = pcy;
        hasLast = true;

        // ---- ввод: курсор или прицел -> точка на цилиндре
        long handle = mc.getWindow().getHandle();
        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        inputOn = (mouseMode || crosshair) && anim >= 0.99;
        double dx, dy, dz;
        if (mouseMode) {
            double mxs = mc.mouse.getX() * sw / mc.getWindow().getWidth();
            double mys = mc.mouse.getY() * sh / mc.getWindow().getHeight();
            double a = (mxs - sw2) / focal, b = (sh2 - mys) / focal;
            dx = fX + a * rX + b * uX;
            dy = fY + b * uY;
            dz = fZ + a * rZ + b * uZ;
        } else {
            dx = fX; dy = fY; dz = fZ;
        }
        boolean valid = false;
        lmx = -9999;
        lmy = -9999;
        double qa = dx * dx + dz * dz;
        if (qa > 1e-9) {
            double ox = camX - cylX, oz = camZ - cylZ;
            double qb = 2 * (ox * dx + oz * dz);
            double qc = ox * ox + oz * oz - cylR * cylR;
            double disc = qb * qb - 4 * qa * qc;
            if (disc >= 0) {
                double t = (-qb + Math.sqrt(disc)) / (2 * qa); // дальняя стенка = лицевая сторона панели
                if (t > 0) {
                    double hx = camX + dx * t, hy = camY + dy * t, hz = camZ + dz * t;
                    double th = Math.atan2(-(hx - cylX), hz - cylZ);
                    lmx = W / 2.0 + wrapRad(th - ac) * cylR / kS;
                    lmy = H / 2.0 - (hy - pcy) / kS;
                    valid = true;
                }
            }
        }
        boolean inside = valid && lmx >= 0 && lmx < W && lmy >= 0 && lmy < H;
        aimHover = crosshair && inputOn && inside;
        down = inputOn && valid && leftDown;
        clicked = down && !prevDown;
        prevDown = down;

        g = ctx;
        tr = mc.textRenderer;
        sp = SpotifyPanelMod.spotify;
        snap = sp.snapshot();
        if (fitCache.size() > 700) fitCache.clear();

        if (inputOn) handleSliders();
        else if (dragId != 0) finishDrag();

        drawStrips(sw, sh);
    }

    // ================================================================ клавиши

    private static boolean key(long handle, int code) {
        return GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
    }

    private static void handleKeys(MinecraftClient mc, Config cfg) {
        long h = mc.getWindow().getHandle();
        boolean kt = key(h, cfg.keyToggle), kl = key(h, cfg.keyLock), kc = key(h, cfg.keyCursor);
        boolean noScreen = mc.currentScreen == null;
        boolean inCursor = mc.currentScreen instanceof CursorScreen;
        if (kt && !pKt && noScreen) setVisible(!cfg.visible);
        if (kl && !pKl && noScreen) toggleFrozen();
        if (kc && !pKc && (noScreen || inCursor)) toggleCursor();
        pKt = kt;
        pKl = kl;
        pKc = kc;
    }

    // ================================================================ перетаскивание слайдеров

    private static void handleSliders() {
        long dur = snap.track() != null ? snap.track().durationMs() : 0;
        if (clicked) {
            if (dur > 0 && hit(PB_X - 4, PB_Y - 6, PB_W + 8, 16)) {
                dragId = 1;
                clicked = false;
            } else if (hit(VOL_X - 4, VOL_Y - 6, VOL_W + 8, 16)) {
                dragId = 2;
                clicked = false;
            }
        }
        if (dragId == 0) return;
        int bx = dragId == 1 ? PB_X : VOL_X;
        int bw = dragId == 1 ? PB_W : VOL_W;
        if (down) {
            dragFrac = Math.max(0, Math.min(1, (lmx - bx) / bw));
            if (dragId == 2) {
                long now = System.currentTimeMillis();
                if (now - lastVolSend > 100) {
                    lastVolSend = now;
                    sp.volume((int) Math.round(dragFrac * 100), SpotifyPanelMod::say);
                }
            }
        } else {
            finishDrag();
        }
    }

    private static void finishDrag() {
        if (dragId == 1) {
            long dur = snap.track() != null ? snap.track().durationMs() : 0;
            sp.seek((long) (dragFrac * dur), SpotifyPanelMod::say);
        } else if (dragId == 2) {
            sp.volume((int) Math.round(dragFrac * 100), SpotifyPanelMod::say);
        }
        dragId = 0;
    }

    // ================================================================ 3D: дуга вокруг корпуса, рисуется полосами

    /** Точка панели в мире: дуга вокруг игрока радиусом cylR, центр дуги — направление cylAc. */
    private static double[] world(double u, double v) {
        double th = cylAc + (u - W / 2.0) * kS / cylR;
        return new double[]{cylX - Math.sin(th) * cylR, pcy + (H / 2.0 - v) * kS, cylZ + Math.cos(th) * cylR};
    }

    private static double[] proj(double[] p) {
        double vx = p[0] - camX, vy = p[1] - camY, vz = p[2] - camZ;
        double zc = vx * fX + vy * fY + vz * fZ;
        if (zc < 0.12) return null;
        double xc = vx * rX + vz * rZ;
        double yc = vx * uX + vy * uY + vz * uZ;
        return new double[]{sw2 + xc / zc * focal, sh2 - yc / zc * focal, zc};
    }

    private static void drawStrips(int sw, int sh) {
        int n = anim < 0.999 ? 28 : 16;

        double[][] top = new double[n + 1][];
        double[][] bot = new double[n + 1][];
        int[] bx = new int[n + 1];
        boolean any = false;
        for (int j = 0; j <= n; j++) {
            double u = W * (double) j / n;
            top[j] = proj(world(u, 0));
            bot[j] = proj(world(u, H));
            if (top[j] != null && bot[j] != null) {
                bx[j] = (int) Math.round((top[j][0] + bot[j][0]) / 2.0);
                any = true;
            }
        }
        if (!any) return;

        double eased = anim * anim * (3 - 2 * anim);
        double uBound = eased * (W + 16);

        for (int j = 0; j < n; j++) {
            double[] t0 = top[j], b0 = bot[j], t1 = top[j + 1], b1 = bot[j + 1];
            if (t0 == null || b0 == null || t1 == null || b1 == null) continue;
            double u0 = W * (double) j / n, u1 = W * (double) (j + 1) / n;

            // обратная сторона дуги не рисуется
            double[] pm = world((u0 + u1) / 2.0, H / 2.0);
            double facing = (cylX - pm[0]) * (camX - pm[0]) + (cylZ - pm[2]) * (camZ - pm[2]);
            if (facing <= 0) continue;

            double xl = (t0[0] + b0[0]) / 2.0, xr = (t1[0] + b1[0]) / 2.0;
            if (xr - xl < 0.01) continue;
            double sx = (xr - xl) / (u1 - u0);
            double sy = (((b0[1] - t0[1]) + (b1[1] - t1[1])) / 2.0) / H;
            double ml = (t0[1] + b0[1]) / 2.0, mr = (t1[1] + b1[1]) / 2.0;
            double kk = (mr - ml) / (u1 - u0);

            int s0 = Math.max(0, bx[j]), s1 = Math.min(sw, bx[j + 1]);
            int y0 = (int) Math.max(0, Math.floor(Math.min(t0[1], t1[1])) - 3);
            int y1 = (int) Math.min(sh, Math.ceil(Math.max(b0[1], b1[1])) + 3);
            if (s1 <= s0 || y1 <= y0) continue;

            Matrix3x2f mat = new Matrix3x2f(
                    (float) sx, (float) kk,
                    0f, (float) sy,
                    (float) (xl - sx * u0), (float) (ml - kk * u0 - sy * H / 2.0));

            g.enableScissor(s0, y0, s1, y1);
            var m = g.getMatrices();
            m.pushMatrix();
            m.mul(mat);
            try {
                boolean ui = anim >= 0.999 || (u0 + u1) / 2.0 < uBound;
                if (ui) drawUI();
                else drawCode(u0, u1);
                if (anim < 0.999) fill(uBound - 1, 0, uBound + 1, H, 0xFFB8FFD8);
            } finally {
                m.popMatrix();
                g.disableScissor();
            }
        }
    }

    /** «Код»: падающие символы на месте ещё не проявившейся (или уже распавшейся) части. */
    private static void drawCode(double u0, double u1) {
        fill(0, 0, W, H, 0x40021008);
        double a = Math.sin(Math.PI * anim);
        long t = System.currentTimeMillis();
        int rows = H / 9;
        int c0 = (int) Math.max(0, Math.floor(u0 / 8.0)), c1 = (int) Math.min(W / 8, Math.ceil(u1 / 8.0));
        for (int c = c0; c < c1; c++) {
            int seed = c * 7919 + 13;
            double speed = 6 + (seed % 5);
            int head = (int) (((t / 1000.0) * speed + (seed % rows)) % rows);
            for (int k = 0; k < 9; k++) {
                int row = ((head - k) % rows + rows) % rows;
                int alpha = (int) (255 * a * (1 - k / 9.0));
                if (alpha < 12) continue;
                int idx = (c * 31 + row * 17 + (int) (t / 90)) & 15;
                int color = k == 0 ? (((int) (255 * a) << 24) | 0xD8FFE8) : ((alpha << 24) | 0x1ED760);
                g.drawText(tr, HEX[idx], c * 8, row * 9, color, false);
            }
        }
    }

    // ================================================================ интерфейс

    private static void drawUI() {
        fill(0, 0, W, H, BG);

        if (snap.status() == SpotifyClient.Status.NO_CLIENT_ID || snap.status() == SpotifyClient.Status.LOGGED_OUT) {
            fill(6, 6, W - 6, H - 6, PANEL);
            tri(180, 90, 180, 130, 214, 110, GREEN);
            if (snap.status() == SpotifyClient.Status.NO_CLIENT_ID) {
                text("Spotify не настроен", 140, 142, WHITE, 240);
                text("/spotify setup <client_id>", 140, 156, GRAY, 240);
            } else {
                text("Spotify не подключён", 140, 142, WHITE, 240);
                text("/spotify login", 140, 156, GREEN, 240);
            }
        } else {
            drawSidebar();
            drawMain();
            drawBottom();
        }
        drawFrame();
        if (aimHover) {
            circle((int) lmx, (int) lmy, 3, WHITE);
            circle((int) lmx, (int) lmy, 2, GREEN);
        }
    }

    private static void drawFrame() {
        int edge = 0x805CE1E6;
        fill(0, 0, W, 1, edge);
        fill(0, H - 1, W, H, edge);
        fill(0, 0, 1, H, edge);
        fill(W - 1, 0, W, H, edge);
        int L = 16;
        fill(0, 0, L, 2, CYAN);
        fill(0, 0, 2, L, CYAN);
        fill(W - L, 0, W, 2, CYAN);
        fill(W - 2, 0, W, L, CYAN);
        fill(0, H - 2, L, H, CYAN);
        fill(0, H - L, 2, H, CYAN);
        fill(W - L, H - 2, W, H, CYAN);
        fill(W - 2, H - L, W, H, CYAN);
        int sy = (int) ((System.currentTimeMillis() / 16) % (H + 80)) - 40;
        int y0 = Math.max(0, sy), y1 = Math.min(H, sy + 3);
        if (y1 > y0) fill(2, y0, W - 2, y1, 0x1CFFFFFF);
    }

    // ---------------------------------------------------------------- сайдбар

    private static void drawSidebar() {
        fill(6, 6, 118, 192, PANEL);
        text("Медиатека", 12, 12, WHITE, 76);

        List<SpotifyClient.Playlist> pls = sp.playlists();
        int maxScroll = Math.max(0, pls.size() - 8);
        sideScroll = Math.max(0, Math.min(sideScroll, maxScroll));
        if (arrow(96, 13, true)) sideScroll = Math.max(0, sideScroll - 1);
        if (arrow(106, 13, false)) sideScroll = Math.min(maxScroll, sideScroll + 1);

        int shown = 0;
        for (int i = 0; i < 8; i++) {
            int idx = sideScroll + i;
            if (idx >= pls.size()) break;
            shown++;
            SpotifyClient.Playlist p = pls.get(idx);
            int y = 26 + i * 20;
            boolean liked = SpotifyClient.LIKED.equals(p.id());
            boolean sel = p.id().equals(selected);
            boolean hov = hit(8, y, 108, 19);
            if (sel) fill(8, y, 116, y + 19, ROW_SEL);
            else if (hov) fill(8, y, 116, y + 19, ROW_HOVER);

            if (liked) heart(10, y + 1, 17);
            else cover(p.coverUrl(), 10, y + 1, 17);
            text(p.name(), 32, y + 2, sel ? GREEN : WHITE, 82);
            text(liked ? "Избранное" : p.total() + " тр.", 32, y + 11, GRAY, 82);

            if (click(8, y, 108, 19)) {
                selected = p.id();
                trackScroll = 0;
                sp.requestItems(p.id(), true);
            }
        }
        String err = sp.playlistsError();
        int ey = 26 + shown * 20 + 4;
        if (!err.isEmpty() && ey < 160) wrap(err, 12, ey, 0xFFFF9B8A, 100);
    }

    // ---------------------------------------------------------------- центр

    private static void drawMain() {
        fill(124, 6, 394, 192, PANEL);
        List<SpotifyClient.Playlist> pls = sp.playlists();

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
        boolean liked = SpotifyClient.LIKED.equals(cur.id());

        // шапка с градиентом из цвета обложки
        int cc = liked ? 0xFF5B3FA0 : sp.coverColor(cur.coverUrl());
        int base = cc != 0 ? mix(0xFF000000, cc, 0.55f) : 0xFF1B3B4A;
        for (int k = 0; k < 24; k++) {
            fill(124, 6 + k * 3, 394, 9 + k * 3, mix(base, PANEL, k / 23f));
        }
        if (liked) heart(134, 14, 58);
        else cover(cur.coverUrl(), 134, 14, 58);

        text("Плейлист", 200, 18, 0xFFE0E8EE, 190);
        g.drawText(tr, Text.literal(fit(cur.name(), 176)).formatted(Formatting.BOLD), 200, 30, WHITE, false);

        List<SpotifyClient.Item> its = sp.items(cur.id());
        String info;
        if (liked) info = its != null ? its.size() + " треков (последние)" : "Твои сохранённые треки";
        else info = (cur.owner().isEmpty() ? "" : cur.owner() + " • ") + cur.total() + " треков";
        text(info, 200, 44, GRAY, 190);

        int pcx2 = 210, pcy2 = 63;
        boolean ph = hit(pcx2 - 10, pcy2 - 10, 20, 20);
        circle(pcx2, pcy2, 9, ph ? 0xFF3BE477 : GREEN);
        tri(pcx2 - 3, pcy2 - 5, pcx2 + 5, pcy2, pcx2 - 3, pcy2 + 5, 0xFF000000);
        if (click(pcx2 - 10, pcy2 - 10, 20, 20)) playFrom(cur, its, "");

        // таблица треков
        text("#", 134, 84, DIM, 14);
        text("Название", 150, 84, DIM, 110);
        text("Исполнитель", 272, 84, DIM, 80);
        fill(130, 95, 388, 96, 0xFF2A3A46);

        if (its == null) {
            String err = sp.itemError(cur.id());
            wrap(err.isEmpty() ? "Загрузка треков…" : err, 136, 108, err.isEmpty() ? GRAY : 0xFFFF9B8A, 240);
            if (err.isEmpty()) sp.requestItems(cur.id(), false);
            return;
        }
        if (its.isEmpty()) {
            text("В плейлисте нет треков", 136, 108, GRAY, 240);
            return;
        }

        int maxScroll = Math.max(0, its.size() - 6);
        trackScroll = Math.max(0, Math.min(trackScroll, maxScroll));
        if (arrow(362, 85, true)) trackScroll = Math.max(0, trackScroll - 1);
        if (arrow(374, 85, false)) trackScroll = Math.min(maxScroll, trackScroll + 1);

        String playingUri = snap.track() != null ? snap.track().uri() : "";
        for (int r = 0; r < 6; r++) {
            int idx = trackScroll + r;
            if (idx >= its.size()) break;
            SpotifyClient.Item it = its.get(idx);
            int ry = 98 + r * 14;
            boolean hov = hit(128, ry, 262, 14);
            boolean isCur = !playingUri.isEmpty() && playingUri.equals(it.uri());
            if (hov) fill(128, ry, 390, ry + 14, ROW_HOVER);

            if (hov) tri(134, ry + 3, 134, ry + 11, 141, ry + 7, WHITE);
            else text(String.valueOf(idx + 1), 134, ry + 3, isCur ? GREEN : GRAY, 14);

            text(it.title(), 150, ry + 3, isCur ? GREEN : WHITE, 116);
            text(it.artists(), 272, ry + 3, GRAY, 84);
            String d = fmt(it.durationMs());
            g.drawText(tr, d, 388 - tr.getWidth(d), ry + 3, GRAY, false);

            if (click(128, ry, 262, 14)) playFrom(cur, its, it.uri());
        }
    }

    private static void playFrom(SpotifyClient.Playlist cur, List<SpotifyClient.Item> its, String trackUri) {
        if (SpotifyClient.LIKED.equals(cur.id())) {
            if (its == null || its.isEmpty()) return;
            List<String> uris = new ArrayList<>();
            for (SpotifyClient.Item it : its) uris.add(it.uri());
            sp.playUris(uris, trackUri, SpotifyPanelMod::say);
        } else {
            sp.playContext(cur.uri(), trackUri, SpotifyPanelMod::say);
        }
    }

    // ---------------------------------------------------------------- нижняя панель

    private static void drawBottom() {
        fill(6, 197, 394, 198, 0xFF1C2A34);
        SpotifyClient.Snapshot s = snap;
        SpotifyClient.Track t = s.track();

        if (s.status() == SpotifyClient.Status.ACTIVE && t != null) {
            cover(t.coverUrl(), 12, 203, 32);
            text(t.title(), 50, 205, WHITE, 104);
            text(t.artists(), 50, 217, GRAY, 104);
        } else if (s.status() == SpotifyClient.Status.ERROR) {
            wrap(s.error(), 12, 204, 0xFFFF6B6B, 150);
        } else {
            text("Ничего не играет", 12, 207, GRAY, 150);
            text("Включи трек в Spotify", 12, 219, DIM, 150);
        }

        int cy = 210;

        boolean sh = s.shuffle();
        boolean hs = hit(207, cy - 7, 14, 14);
        int cs = sh ? GREEN : (hs ? WHITE : GRAY);
        for (int i = 0; i < 9; i++) {
            fill(209 + i, cy - 4 + i, 210 + i, cy - 3 + i, cs);
            fill(209 + i, cy + 4 - i, 210 + i, cy + 5 - i, cs);
        }
        if (click(207, cy - 7, 14, 14)) sp.shuffle(!sh, SpotifyPanelMod::say);

        boolean hp = hit(225, cy - 7, 14, 14);
        int cp = hp ? WHITE : GRAY;
        fill(226, cy - 5, 228, cy + 5, cp);
        tri(237, cy - 5, 237, cy + 5, 228, cy, cp);
        if (click(225, cy - 7, 14, 14)) sp.previous(SpotifyPanelMod::say);

        boolean hpl = hit(240, cy - 10, 20, 20);
        circle(250, cy, 9, hpl ? WHITE : 0xFFEAEAEA);
        if (s.playing()) {
            fill(246, cy - 4, 249, cy + 4, 0xFF000000);
            fill(252, cy - 4, 255, cy + 4, 0xFF000000);
        } else {
            tri(247, cy - 5, 247, cy + 5, 255, cy, 0xFF000000);
        }
        if (click(240, cy - 10, 20, 20)) sp.togglePlay(SpotifyPanelMod::say);

        boolean hn = hit(261, cy - 7, 14, 14);
        int cn = hn ? WHITE : GRAY;
        tri(263, cy - 5, 263, cy + 5, 272, cy, cn);
        fill(272, cy - 5, 274, cy + 5, cn);
        if (click(261, cy - 7, 14, 14)) sp.next(SpotifyPanelMod::say);

        String rep = s.repeat();
        boolean on = !"off".equals(rep);
        boolean hr = hit(279, cy - 7, 14, 14);
        int cr = on ? GREEN : (hr ? WHITE : GRAY);
        fill(280, cy - 4, 292, cy - 3, cr);
        fill(280, cy + 3, 292, cy + 4, cr);
        fill(280, cy - 4, 281, cy + 4, cr);
        fill(291, cy - 4, 292, cy + 4, cr);
        if ("track".equals(rep)) fill(284, cy - 1, 288, cy + 1, cr);
        if (click(279, cy - 7, 14, 14)) {
            String nextMode = "off".equals(rep) ? "context" : ("context".equals(rep) ? "track" : "off");
            sp.repeat(nextMode, SpotifyPanelMod::say);
        }

        long dur = t != null ? t.durationMs() : 0;
        long pos = s.currentProgress();
        double frac = dragId == 1 ? dragFrac : (dur > 0 ? Math.min(1.0, (double) pos / dur) : 0);
        boolean hb = hit(PB_X - 4, PB_Y - 6, PB_W + 8, 16) || dragId == 1;
        fill(PB_X, PB_Y, PB_X + PB_W, PB_Y + 4, BAR_BG);
        int fw = (int) (PB_W * frac);
        fill(PB_X, PB_Y, PB_X + fw, PB_Y + 4, hb ? GREEN : WHITE);
        if (hb) circle(PB_X + fw, PB_Y + 2, 4, WHITE);
        long shown = dragId == 1 ? (long) (dragFrac * dur) : pos;
        String lt = fmt(shown);
        g.drawText(tr, lt, 186 - tr.getWidth(lt), 224, GRAY, false);
        g.drawText(tr, fmt(dur), 316, 224, GRAY, false);

        double vfrac = dragId == 2 ? dragFrac : Math.max(0, Math.min(100, s.volume())) / 100.0;
        boolean hv = hit(VOL_X - 4, VOL_Y - 6, VOL_W + 8, 16) || dragId == 2;
        fill(336, VOL_Y - 2, 340, VOL_Y + 3, GRAY);
        tri(340, VOL_Y + 0.5f, 346, VOL_Y - 5, 346, VOL_Y + 6, GRAY);
        fill(VOL_X, VOL_Y - 2, VOL_X + VOL_W, VOL_Y + 2, BAR_BG);
        int vw = (int) (VOL_W * vfrac);
        fill(VOL_X, VOL_Y - 2, VOL_X + vw, VOL_Y + 2, hv ? GREEN : WHITE);
        if (hv) circle(VOL_X + vw, VOL_Y, 4, WHITE);
    }

    // ================================================================ ввод

    private static boolean hit(double x, double y, double w, double h) {
        return inputOn && lmx >= x && lmx < x + w && lmy >= y && lmy < y + h;
    }

    private static boolean click(double x, double y, double w, double h) {
        if (clicked && hit(x, y, w, h)) {
            clicked = false;
            return true;
        }
        return false;
    }

    private static boolean arrow(int x, int y, boolean up) {
        boolean hov = hit(x - 3, y - 3, 12, 10);
        int c = hov ? WHITE : DIM;
        if (up) tri(x + 3, y, x, y + 5, x + 6, y + 5, c);
        else tri(x, y, x + 6, y, x + 3, y + 5, c);
        return click(x - 3, y - 3, 12, 10);
    }

    // ================================================================ примитивы

    private static void fill(double x1, double y1, double x2, double y2, int color) {
        g.fill((int) Math.round(x1), (int) Math.round(y1), (int) Math.round(x2), (int) Math.round(y2), color);
    }

    private static void cover(String url, int x, int y, int size) {
        Identifier id = sp.coverTexture(url);
        if (id != null) {
            g.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size,
                    SpotifyClient.RAW, SpotifyClient.RAW, SpotifyClient.RAW, SpotifyClient.RAW);
        } else {
            int c = sp.coverColor(url);
            fill(x, y, x + size, y + size, c != 0 ? c : 0xFF22303A);
        }
    }

    /** Плитка «Любимые треки»: фиолетовый квадрат с сердцем. */
    private static void heart(int x, int y, int size) {
        fill(x, y, x + size, y + size, 0xFF5B3FA0);
        int r = Math.max(2, size / 6);
        int cx = x + size / 2, cy = y + size / 2;
        circle(cx - r, cy - r / 2, r, WHITE);
        circle(cx + r, cy - r / 2, r, WHITE);
        tri(cx - 2 * r, cy - r / 2 + 1, cx + 2 * r, cy - r / 2 + 1, cx, cy + 2 * r, WHITE);
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

    // ================================================================ текст / цвет

    private static void text(String s, int x, int y, int color, int maxW) {
        g.drawText(tr, fit(s, maxW), x, y, color, false);
    }

    /** Многострочный текст с переносом по словам (до 3 строк). */
    private static void wrap(String s, int x, int y, int color, int maxW) {
        if (s == null) return;
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        int row = 0;
        for (String w : words) {
            String test = line.length() == 0 ? w : line + " " + w;
            if (tr.getWidth(test) > maxW && line.length() > 0) {
                g.drawText(tr, fit(line.toString(), maxW), x, y + row * 10, color, false);
                row++;
                if (row >= 3) return;
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) g.drawText(tr, fit(line.toString(), maxW), x, y + row * 10, color, false);
    }

    private static String fit(String s, int maxW) {
        if (s == null) return "";
        String key = s + '\u0000' + maxW;
        String cached = fitCache.get(key);
        if (cached != null) return cached;
        String out = s;
        if (tr.getWidth(s) > maxW) {
            int ell = tr.getWidth("…");
            out = tr.trimToWidth(s, Math.max(0, maxW - ell)) + "…";
        }
        fitCache.put(key, out);
        return out;
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    private static int mix(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = Math.round(aa + (ba - aa) * t);
        int or = Math.round(ar + (br - ar) * t);
        int og = Math.round(ag + (bg - ag) * t);
        int ob = Math.round(ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    private static double wrapDeg(double a) {
        return ((a % 360.0) + 540.0) % 360.0 - 180.0;
    }

    private static double wrapRad(double a) {
        return ((a % (2 * Math.PI)) + 3 * Math.PI) % (2 * Math.PI) - Math.PI;
    }
}
