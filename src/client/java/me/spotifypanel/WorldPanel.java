package me.spotifypanel;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * ЭТАП 1: настоящая 3D-панель в мире. Пока это пустая плита, жёстко прикреплённая к телу игрока
 * (положение и поворот торса, голова и камера не влияют). Интерфейс на неё переедет на следующих этапах.
 */
public final class WorldPanel {
    /** true = рисуем 3D-плиту (а плоский HUD отключаем), false = старый плоский HUD. */
    public static final boolean ENABLED = true;

    // размеры и положение относительно тела, в блоках
    private static final double WIDTH = 3.0;
    private static final double HEIGHT = WIDTH * 250.0 / 350.0; // те же пропорции, что у интерфейса 350x250
    private static final double SIDE = 2.2;    // правее тела
    private static final double AHEAD = 2.0;   // впереди тела
    private static final double LIFT = 1.2;    // высота центра над ногами
    private static final double TURN_DEG = 15; // разворот плиты к игроку вокруг вертикальной оси

    private static boolean init;
    private static double bx, by, bz, syaw;
    private static long lastNs;

    private WorldPanel() {}

    public static void register() {
        if (!ENABLED) return;
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(WorldPanel::draw);
    }

    private static void draw(WorldRenderContext context) {
        if (!SpotifyPanelMod.config.visible) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        MatrixStack matrices = context.matrices();
        if (matrices == null) return;
        Vec3d cam = context.worldState().cameraRenderState.pos;

        float pt = mc.getRenderTickCounter().getTickProgress(false);
        Vec3d body = player.getLerpedPos(pt);
        double bodyYaw = player.getBodyYaw();

        long now = System.nanoTime();
        double dt = init ? Math.min(0.1, (now - lastNs) / 1e9) : 1.0;
        lastNs = now;
        if (!init) {
            syaw = bodyYaw;
            init = true;
        }
        bx = body.x;
        by = body.y;
        bz = body.z;
        // поворот торса меняется 20 раз в секунду: лёгкое сглаживание убирает ступеньки, но не даёт отставать
        double err = ((bodyYaw - syaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        syaw += err * (1.0 - Math.exp(-dt * (35.0 + 0.5 * Math.abs(err))));

        // система координат тела: вперёд (fx,fz), вправо (rx,rz)
        double br = Math.toRadians(syaw);
        double fx = -Math.sin(br), fz = Math.cos(br);
        double rx = -Math.cos(br), rz = -Math.sin(br);

        double cx = bx + fx * AHEAD + rx * SIDE;
        double cy = by + LIFT;
        double cz = bz + fz * AHEAD + rz * SIDE;

        // ось ширины плиты, довёрнутая к игроку
        double a = Math.toRadians(TURN_DEG);
        double wx = rx * Math.cos(a) - fx * Math.sin(a);
        double wz = rz * Math.cos(a) - fz * Math.sin(a);
        // нормаль плиты (смотрит на игрока)
        double nx = -fx * Math.cos(a) - rx * Math.sin(a);
        double nz = -fz * Math.cos(a) - rz * Math.sin(a);

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumer vc = context.consumers().getBuffer(RenderLayer.getDebugQuads());

        // зелёная рамка и тёмная плита чуть перед ней (чтобы не мерцали друг в друге)
        quad(vc, m, cx, cy, cz, wx, wz, nx, nz, WIDTH + 0.08, HEIGHT + 0.08, 0.0, 0xFF1ED760);
        quad(vc, m, cx, cy, cz, wx, wz, nx, nz, WIDTH, HEIGHT, 0.004, 0xFF121212);

        matrices.pop();
    }

    /** Прямоугольник в мире, центр (cx,cy,cz), ось ширины (wx,wz), сдвиг по нормали off. Рисуется с обеих сторон. */
    private static void quad(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz,
                             double wx, double wz, double nx, double nz,
                             double w, double h, double off, int argb) {
        double hw = w / 2.0, hh = h / 2.0;
        double ox = cx + nx * off, oz = cz + nz * off;
        float x0 = (float) (ox - wx * hw), z0 = (float) (oz - wz * hw);
        float x1 = (float) (ox + wx * hw), z1 = (float) (oz + wz * hw);
        float yb = (float) (cy - hh), yt = (float) (cy + hh);
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, al = (argb >>> 24) & 0xFF;

        // лицевая сторона
        vc.vertex(m, x0, yb, z0).color(r, g, b, al);
        vc.vertex(m, x1, yb, z1).color(r, g, b, al);
        vc.vertex(m, x1, yt, z1).color(r, g, b, al);
        vc.vertex(m, x0, yt, z0).color(r, g, b, al);
        // обратная сторона (на случай, если слой отсекает заднюю грань)
        vc.vertex(m, x0, yt, z0).color(r, g, b, al);
        vc.vertex(m, x1, yt, z1).color(r, g, b, al);
        vc.vertex(m, x1, yb, z1).color(r, g, b, al);
        vc.vertex(m, x0, yb, z0).color(r, g, b, al);
    }
}
