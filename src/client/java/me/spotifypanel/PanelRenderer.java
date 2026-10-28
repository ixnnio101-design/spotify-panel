package me.spotifypanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Плавающая панель в стиле приложения Spotify. Привязана к точке в мире рядом с игроком,
 * плавно догоняет его при движении, масштабируется с расстоянием. Кликается мышью, когда открыт чат.
 */
public final class PanelRenderer {
    private static final int W = 350, H = 250;
    private static final int SBR = 120;   // правый край сайдбара
    private static final int MX = 124;    // левый край центральной части
    private static final int BAR = 212;   // верх нижней панели плеера
    private static final double WORLD_W = 3.6; // ширина панели в блоках (больше = крупнее текст)
    private static final double SIDE = 2.2;    // насколько правее тела
    private static final double AHEAD = 2.4;   // насколько впереди тела
    private static final double PANEL_YAW = 15; // разворот панели вокруг вертикальной оси, градусов (даёт «объём»)

    private static final int BLACK = 0xFF000000;
    private static final int PANEL = 0xFF121212;
    private static final int ROW_HOVER = 0xFF1F1F1F;
    private static final int ROW_SEL = 0xFF2A2A2A;
    private static final int GREEN = 0xFF1ED760;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B3B3;
    private static final int DIM = 0xFF9A9A9A;
    private static final int BAR_BG = 0xFF4D4D4D;
    private static final int WARN = 0xFFFFAA00;
    private static final int ERR = 0xFFFF6B6B;

    /** Размер клетки обложки в пикселях панели: 1 = максимум чёткости, 2 = вчетверо меньше прямоугольников (если просядет FPS). */
    private static final int CELL_HEAD = 1;  // большая обложка 64x64: 1 = каждый пиксель отдельно (чётче, но тяжелее)
    private static final int CELL_SMALL = 1; // маленькие 32x32 (сайдбар и плеер)
    private static final boolean SHADOW = true; // тень у текста — читается намного лучше

    // что сейчас тащит мышь
    private static final int D_NONE = 0, D_SIDE = 1, D_TRACK = 2, D_SEEK = 3, D_VOL = 4;

    // позиция панели в мире (с плавным догоном)
    private static boolean init;
    private static double px, pz;
    private static double bx, bz, syaw; // сглаженные положение и поворот тела
    private static long lastNs;

    // позиция панели на экране (держим на месте, пока открыт чат — иначе по ней не попасть)
    private static boolean screenInit;
    private static final double[] cur = new double[6]; // экранная матрица панели: начало, ось X, ось Y
    private static double settle, fovMul = 1.0;
    private static double cfx, cfz, crx, crz, cux, cuz, cfocal, csw, csh;

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
        // настоящая камера (в виде от 3-го лица она позади игрока, а не в глазах)
        Camera camera = mc.gameRenderer.getCamera();
        double yr = Math.toRadians(camera.getYaw()), pr = Math.toRadians(camera.getPitch());
        double sinY = Math.sin(yr), cosY = Math.cos(yr), sinP = Math.sin(pr), cosP = Math.cos(pr);
        Vec3d eye = player.getCameraPosVec(pt);
        double back = mc.options.getPerspective().isFirstPerson() ? 0.0 : 4.0;
        camX = eye.x + sinY * cosP * back;
        camY = eye.y + sinP * back;
        camZ = eye.z - cosY * cosP * back;

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
            // положение — жёстко по телу (getLerpedPos уже интерполирован между тиками)
            bx = body.x; by = body.y; bz = body.z;
            // угол — тоже почти жёстко: поворот тела меняется только 20 раз в секунду,
            // лёгкое сглаживание (~30 мс) просто убирает ступеньки, но не даёт панели отставать
            double err = ((bodyYaw - syaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
            double ky = 1.0 - Math.exp(-dt * (35.0 + 0.5 * Math.abs(err)));
            syaw += err * ky;
        }
        double br = Math.toRadians(syaw);
        double sinB = Math.sin(br), cosB = Math.cos(br);
        // система координат тела: вперёд (fxw,fzw), вправо (rxw,rzw)
        double fxw = -sinB, fzw = cosB, rxw = -cosB, rzw = -sinB;
        px = bx + fxw * AHEAD + rxw * SIDE;
        py = by + 1.3;
        pz = bz + fzw * AHEAD + rzw * SIDE;
        // ось ширины панели, довёрнутая на PANEL_YAW к игроку
        double pa = Math.toRadians(PANEL_YAW);
        double pwx = rxw * Math.cos(pa) - fxw * Math.sin(pa);
        double pwz = rzw * Math.cos(pa) - fzw * Math.sin(pa);

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

        // реальный FOV: при беге игра его расширяет, без этого панель «плывёт» относительно мира
        double spd = player.getMovementSpeed();
        double fTarget = spd > 1e-4 ? Math.max(0.9, Math.min(1.4, (spd / 0.1 + 1.0) / 2.0)) : 1.0;
        fovMul += (fTarget - fovMul) * (1.0 - Math.exp(-dt * 20.0));
        double fov = Math.toRadians(mc.options.getFov().getValue() * fovMul);

        // базис камеры
        cfx = -sinY * cosP; cfy = -sinP; cfz = cosY * cosP;
        crx = -cosY; crz = -sinY;
        cux = -crz * cfy; cuy = crz * cfx - crx * cfz; cuz = crx * cfy;
        csw = sw; csh = sh;
        cfocal = (sh / 2.0) / Math.tan(fov / 2.0);

        // 4 угла панели в мире -> на экран. Из трёх углов получаем матрицу панели (сдвиг + оси X и Y),
        // так что панель честно стоит в мире, повёрнутая вокруг вертикальной оси
        double halfW = WORLD_W / 2.0, halfH = WORLD_W * H / W / 2.0;
        // Аффинное приближение перспективы (двумерная матрица GUI не умеет трапецию):
        // оси берём по серединам краёв панели, а не по трём углам. Ошибка делится поровну
        // между краями, поэтому панель не заваливается и не перекашивается в параллелограмм.
        double[] pc = new double[2], pl = new double[2], pr2 = new double[2], pt2 = new double[2], pb = new double[2];
        boolean ok = project(px, py, pz, pc);
        ok &= project(px - pwx * halfW, py, pz - pwz * halfW, pl);
        ok &= project(px + pwx * halfW, py, pz + pwz * halfW, pr2);
        ok &= project(px, py + halfH, pz, pt2);
        ok &= project(px, py - halfH, pz, pb);
        if (!ok) return; // панель за спиной

        double exx = (pr2[0] - pl[0]) / W, exy = (pr2[1] - pl[1]) / W;
        double eyx = (pb[0] - pt2[0]) / H, eyy = (pb[1] - pt2[1]) / H;
        double ox0 = pc[0] - exx * W / 2.0 - eyx * H / 2.0;
        double oy0 = pc[1] - exy * W / 2.0 - eyy * H / 2.0;

        double minX = Math.min(Math.min(ox0, ox0 + exx * W), Math.min(ox0 + eyx * H, ox0 + exx * W + eyx * H));
        double maxX = Math.max(Math.max(ox0, ox0 + exx * W), Math.max(ox0 + eyx * H, ox0 + exx * W + eyx * H));
        double minY = Math.min(Math.min(oy0, oy0 + exy * W), Math.min(oy0 + eyy * H, oy0 + exy * W + eyy * H));
        double maxY = Math.max(Math.max(oy0, oy0 + exy * W), Math.max(oy0 + eyy * H, oy0 + exy * W + eyy * H));
        if (maxX < 0 || minX > sw || maxY < 0 || minY > sh) return;

        double[] tg = {ox0, oy0, exx, exy, eyx, eyy};
        if (!screenInit) {
            System.arraycopy(tg, 0, cur, 0, 6);
            screenInit = true;
        } else if (chatOpen) {
            settle = 0.4; // пока чат открыт — панель стоит на экране намертво, можно кликать
        } else if (settle > 0) {
            // сразу после закрытия чата плавно возвращаемся к живой позиции, без скачка
            settle -= dt;
            double a = 1.0 - Math.exp(-dt * 12.0);
            for (int k = 0; k < 6; k++) cur[k] += (tg[k] - cur[k]) * a;
        } else {
            System.arraycopy(tg, 0, cur, 0, 6); // обычный режим: жёстко к миру
        }

        // курсор -> координаты внутри панели (обратная матрица)
        double det = cur[2] * cur[5] - cur[4] * cur[3];
        if (Math.abs(det) < 1e-9) return;
        double mdx = mxs - cur[0], mdy = mys - cur[1];
        lmx = (mdx * cur[5] - mdy * cur[4]) / det;
        lmy = (cur[2] * mdy - cur[3] * mdx) / det;
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
        m.mul(new Matrix3x2f((float) cur[2], (float) cur[3], (float) cur[4], (float) cur[5], (float) cur[0], (float) cur[1]));
        try {
            drawPanel();
        } finally {
            m.popMatrix();
        }
    }

    /** Точка мира -> экран (в масштабированных пикселях GUI). false, если за камерой. */
    private static boolean project(double wx, double wy, double wz, double[] out) {
        double vx = wx - camX, vy = wy - camY, vz = wz - camZ;
        double zc = vx * cfx + vy * cfy + vz * cfz;
        if (zc < 0.2) return false;
        double xc = vx * crx + vz * crz;
        double yc = vx * cux + vy * cuy + vz * cuz;
        out[0] = csw / 2.0 + xc / zc * cfocal;
        out[1] = csh / 2.0 - yc / zc * cfocal;
        return true;
    }

    private static void drawPanel() {
        g.fill(-1, -1, W + 1, H + 1, 0xFF2B2B2B);
        g.fill(0, 0, W, H, BLACK);

        SpotifyClient.Snapshot s = sp.snapshot();
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID || s.status() == SpotifyClient.Status.LOGGED_OUT) {
            g.fill(4, 4, W - 4, H - 4, PANEL);
            tri(150, 85, 150, 115, 176, 100, GREEN);
            if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) {
                text("Spotify не настроен", 105, 125, WHITE, 240);
                text("/spotify setup <client_id>", 105, 139, GRAY, 240);
            } else {
                text("Spotify не подключён", 105, 125, WHITE, 240);
                text("/spotify login", 105, 139, GREEN, 240);
            }
            return;
        }
        drawSidebar();
        drawMain(s);
        drawBottom(s);
    }

    // ================================================================ сайдбар

    private static void drawSidebar() {
        g.fill(4, 4, SBR, BAR - 4, PANEL);
        text("Медиатека", 10, 9, WHITE, SBR - 16);

        List<SpotifyClient.Playlist> pls = sp.playlists();
        if (pls.isEmpty()) {
            String err = sp.playlistsError();
            if (err.isEmpty()) {
                text("Загрузка…", 10, 26, GRAY, SBR - 16);
            } else {
                wrapped(err, 10, 26, SBR - 18, 16, ERR);
                text("Повтор… (" + sp.playlistFails() + ")", 10, BAR - 16, DIM, SBR - 16);
            }
            return;
        }

        final int vis = 5, rowH = 36;
        int maxScroll = Math.max(0, pls.size() - vis);
        if (wheelSteps != 0 && hit(4, 4, SBR - 4, BAR - 8)) sideScroll -= wheelSteps * 2;
        sideScroll = Math.max(0, Math.min(sideScroll, maxScroll));
        sideScroll = scrollbar(SBR - 6, 24, vis * rowH, pls.size(), vis, sideScroll, D_SIDE);

        for (int i = 0; i < vis; i++) {
            int idx = sideScroll + i;
            if (idx >= pls.size()) break;
            SpotifyClient.Playlist p = pls.get(idx);
            int y = 24 + i * rowH;
            boolean sel = p.id().equals(selected);
            boolean hov = hit(6, y, SBR - 16, rowH - 1);
            if (sel) g.fill(6, y, SBR - 10, y + rowH - 1, ROW_SEL);
            else if (hov) g.fill(6, y, SBR - 10, y + rowH - 1, ROW_HOVER);

            // маленькая иконка плейлиста 32x32
            int n = 32 / CELL_SMALL;
            int[] img = sp.pixels(p.coverUrl(), n);
            if (img != null) drawImage(img, n, 8, y + 2, CELL_SMALL);
            else g.fill(8, y + 2, 40, y + 34, 0xFF282828);

            text(p.name(), 44, y + 8, sel ? GREEN : WHITE, SBR - 54);
            text(p.total() + " тр.", 44, y + 20, GRAY, SBR - 54);

            if (click(6, y, SBR - 16, rowH - 1)) {
                selected = p.id();
                trackScroll = 0;
                sp.requestItems(p.id(), true);
            }
        }
    }

    // ============================================================== центр

    private static void drawMain(SpotifyClient.Snapshot s) {
        g.fill(MX, 4, W - 4, BAR - 4, PANEL);
        List<SpotifyClient.Playlist> pls = sp.playlists();
        if (pls.isEmpty()) {
            text("Здесь появятся твои плейлисты", MX + 10, 14, GRAY, W - MX - 20);
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
        int base = avg != null ? darken(avgColor(avg), 0.55f) : 0xFF1B3B4A;
        for (int k = 0; k < 20; k++) {
            g.fill(MX, 4 + k * 4, W - 4, 8 + k * 4, mix(base, PANEL, k / 19f));
        }
        // большая иконка плейлиста 64x64
        int hn = 64 / CELL_HEAD;
        int[] img = sp.pixels(cur.coverUrl(), hn);
        int cx0 = MX + 8, cy0 = 10;
        if (img != null) drawImage(img, hn, cx0, cy0, CELL_HEAD);
        else g.fill(cx0, cy0, cx0 + 64, cy0 + 64, 0xFF282828);

        int tx = cx0 + 64 + 8;
        int tw = W - 8 - tx;
        text("Плейлист", tx, 14, 0xFFE0E0E0, tw);
        g.drawText(tr, Text.literal(fit(cur.name(), tw)).formatted(Formatting.BOLD), tx, 26, WHITE, SHADOW);
        String info = (cur.owner().isEmpty() ? "" : cur.owner() + " • ") + cur.total() + " треков";
        text(info, tx, 40, GRAY, tw);

        // большая зелёная кнопка «играть»
        int pcx = tx + 8, pcy = 62;
        boolean ph = hit(pcx - 8, pcy - 8, 16, 16);
        circle(pcx, pcy, 8, ph ? 0xFF3BE477 : GREEN);
        tri(pcx - 2.5f, pcy - 4, pcx + 4, pcy, pcx - 2.5f, pcy + 4, BLACK);
        if (click(pcx - 8, pcy - 8, 16, 16)) {
            sp.playContext(cur.uri(), "", SpotifyPanelMod::say);
        }

        // таблица треков
        final int hdrY = 90, lineY = 100, rowsY = 102, rowH = 12, vis = 8;
        text("#", MX + 8, hdrY, DIM, 12);
        text("Название", MX + 22, hdrY, DIM, 90);
        text("Артист", 240, hdrY, DIM, 62);
        g.fill(MX + 4, lineY, W - 8, lineY + 1, 0xFF2A2A2A);

        List<SpotifyClient.Item> its = sp.items(cur.id());
        if (its == null) {
            String err = sp.itemError(cur.id());
            if (err.isEmpty()) {
                text("Загрузка треков…", MX + 10, rowsY + 6, GRAY, W - MX - 24);
                sp.requestItems(cur.id(), false);
            } else {
                wrapped(err, MX + 10, rowsY + 4, W - MX - 28, 8, WARN);
            }
            return;
        }
        if (its.isEmpty()) {
            text("В плейлисте нет треков", MX + 10, rowsY + 6, GRAY, W - MX - 24);
            return;
        }

        int maxScroll = Math.max(0, its.size() - vis);
        if (wheelSteps != 0 && hit(MX, 4, W - MX - 4, BAR - 8)) trackScroll -= wheelSteps * 2;
        trackScroll = Math.max(0, Math.min(trackScroll, maxScroll));
        trackScroll = scrollbar(W - 9, rowsY, vis * rowH, its.size(), vis, trackScroll, D_TRACK);

        int rowX = MX + 4, rowW = W - 14 - rowX;
        String playingUri = s.track() != null ? s.track().uri() : "";
        for (int r = 0; r < vis; r++) {
            int idx = trackScroll + r;
            if (idx >= its.size()) break;
            SpotifyClient.Item it = its.get(idx);
            int ry = rowsY + r * rowH;
            boolean hov = hit(rowX, ry, rowW, rowH);
            boolean isCur = !playingUri.isEmpty() && playingUri.equals(it.uri());
            if (hov) g.fill(rowX, ry, rowX + rowW, ry + rowH, ROW_HOVER);

            if (hov) tri(MX + 9, ry + 2, MX + 9, ry + 9, MX + 15, ry + 5.5f, WHITE);
            else text(String.valueOf(idx + 1), MX + 8, ry + 2, isCur ? GREEN : GRAY, 14);

            text(it.title(), MX + 22, ry + 2, isCur ? GREEN : WHITE, 92);
            text(it.artists(), 240, ry + 2, GRAY, 62);
            String d = fmt(it.durationMs());
            g.drawText(tr, d, rowX + rowW - 2 - tr.getWidth(d), ry + 2, GRAY, SHADOW);

            if (click(rowX, ry, rowW, rowH)) {
                sp.playContext(cur.uri(), it.uri(), SpotifyPanelMod::say);
            }
        }
    }

    // ================================================================ нижняя панель

    private static void drawBottom(SpotifyClient.Snapshot s) {
        g.fill(4, BAR, W - 4, BAR + 1, 0xFF1A1A1A);
        SpotifyClient.Track t = s.track();

        if (s.status() == SpotifyClient.Status.ACTIVE && t != null) {
            int n = 32 / CELL_SMALL;
            int[] img = sp.pixels(t.coverUrl(), n); // обложка играющего трека 32x32
            if (img != null) drawImage(img, n, 8, BAR + 3, CELL_SMALL);
            else g.fill(8, BAR + 3, 40, BAR + 35, 0xFF282828);
            text(t.title(), 44, BAR + 7, WHITE, 74);
            text(t.artists(), 44, BAR + 19, GRAY, 74);
        } else if (s.status() == SpotifyClient.Status.ERROR) {
            wrapped(s.error(), 8, BAR + 5, 106, 3, ERR);
        } else {
            text("Не играет", 8, BAR + 10, GRAY, 106);
            text("Открой Spotify", 8, BAR + 22, DIM, 106);
        }

        // --- кнопки управления (зоны клика 16x16 встык, промахнуться сложно)
        final int ox = 20;
        int cy = BAR + 12;

        // shuffle
        boolean sh = s.shuffle();
        boolean hs = hit(ox + 125, cy - 8, 16, 16);
        int cs = sh ? GREEN : (hs ? WHITE : GRAY);
        for (int i = 0; i < 8; i++) {
            g.fill(ox + 129 + i, cy - 4 + i, ox + 130 + i, cy - 3 + i, cs);
            g.fill(ox + 129 + i, cy + 3 - i, ox + 130 + i, cy + 4 - i, cs);
        }
        if (click(ox + 125, cy - 8, 16, 16)) sp.shuffle(!sh, SpotifyPanelMod::say);

        // previous
        boolean hp = hit(ox + 141, cy - 8, 16, 16);
        int cp = hp ? WHITE : GRAY;
        g.fill(ox + 144, cy - 4, ox + 146, cy + 4, cp);
        tri(ox + 153, cy - 4, ox + 153, cy + 4, ox + 146, cy, cp);
        if (click(ox + 141, cy - 8, 16, 16)) sp.previous(SpotifyPanelMod::say);

        // play / pause
        boolean hpl = hit(ox + 157, cy - 8, 16, 16);
        circle(ox + 165, cy, 7, hpl ? WHITE : 0xFFEAEAEA);
        if (s.playing()) {
            g.fill(ox + 162, cy - 3, ox + 164, cy + 3, BLACK);
            g.fill(ox + 166, cy - 3, ox + 168, cy + 3, BLACK);
        } else {
            tri(ox + 163, cy - 4, ox + 163, cy + 4, ox + 169, cy, BLACK);
        }
        if (click(ox + 157, cy - 8, 16, 16)) sp.togglePlay(SpotifyPanelMod::say);

        // next
        boolean hn = hit(ox + 173, cy - 8, 16, 16);
        int cn = hn ? WHITE : GRAY;
        tri(ox + 177, cy - 4, ox + 177, cy + 4, ox + 184, cy, cn);
        g.fill(ox + 184, cy - 4, ox + 186, cy + 4, cn);
        if (click(ox + 173, cy - 8, 16, 16)) sp.next(SpotifyPanelMod::say);

        // repeat
        String rep = s.repeat();
        boolean on = !"off".equals(rep);
        boolean hr = hit(ox + 189, cy - 8, 16, 16);
        int cr = on ? GREEN : (hr ? WHITE : GRAY);
        g.fill(ox + 192, cy - 3, ox + 201, cy - 2, cr);
        g.fill(ox + 192, cy + 2, ox + 201, cy + 3, cr);
        g.fill(ox + 192, cy - 3, ox + 193, cy + 3, cr);
        g.fill(ox + 200, cy - 3, ox + 201, cy + 3, cr);
        if ("track".equals(rep)) g.fill(ox + 195, cy - 1, ox + 198, cy + 1, cr);
        if (click(ox + 189, cy - 8, 16, 16)) {
            String nextMode = "off".equals(rep) ? "context" : ("context".equals(rep) ? "track" : "off");
            sp.repeat(nextMode, SpotifyPanelMod::say);
        }

        // --- прогресс: клик перематывает, зажал и тянешь — перемотка применится, когда отпустишь
        long dur = t != null ? t.durationMs() : 0;
        int bx = ox + 124, bw = 84, by = cy + 13;
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
        text(fmt(pos), bx - 28, by - 2, GRAY, 26);
        text(fmt(dur), bx + bw + 4, by - 2, GRAY, 26);

        // --- громкость: тянется мышью, колесом над блоком — шаг 5%
        int vx = W - 52, vw = 40, vy = cy;
        g.fill(vx - 14, vy - 2, vx - 11, vy + 3, GRAY);
        tri(vx - 11, vy + 0.5f, vx - 6, vy - 4, vx - 6, vy + 5, GRAY);
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
        if (wheelSteps != 0 && hit(vx - 20, BAR, W - vx + 16, H - BAR)) {
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
        g.drawText(tr, fit(s, maxW), x, y, color, SHADOW);
    }

    /** Текст с переносом по словам (длинные слова режутся). Лишние строки отбрасываются с «…». */
    private static void wrapped(String s, int x, int y, int maxW, int maxLines, int color) {
        List<String> lines = wrap(s, maxW, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            g.drawText(tr, lines.get(i), x, y + i * 10, color, SHADOW);
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
