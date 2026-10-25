package me.spotifypanel;

import net.minecraft.util.math.Vec3d;

/**
 * Положение панели в мире. Горизонтальный угол, расстояние и высота считаются раздельно
 * и сглаживаются независимо; ориентация (лицом к игроку) вычисляется в рендере из направления
 * «панель → камера» и не влияет на координаты панели.
 */
public final class PanelSpace {
    private static final double RADIUS = 2.9;   // расстояние от игрока по горизонтали, блоков
    private static final double HEIGHT = -0.2;  // высота относительно уровня глаз, не зависит от pitch
    private static final double FOLLOW_SPEED = 5.5;

    private double az, radius = RADIUS, height = HEIGHT;
    private boolean init, wasFixed;
    private Vec3d fixedWorld = Vec3d.ZERO;
    private Vec3d world = Vec3d.ZERO;

    public Vec3d world() {
        return world;
    }

    /**
     * @param yaw   горизонтальный угол взгляда (pitch сюда намеренно не передаётся)
     * @param angle угол справа от направления взгляда, градусы
     */
    public void update(double dt, Vec3d cam, double yaw, boolean fixed, double angle) {
        double targetAz = yaw + angle;
        if (!init) {
            az = targetAz;
            radius = RADIUS;
            height = HEIGHT;
            init = true;
        }

        if (fixed) {
            if (!wasFixed) fixedWorld = worldFrom(cam); // фиксируем ровно там, где панель была
            world = fixedWorld;
            // держим относительное состояние в актуальном виде: возврат в Follow начнётся без скачка
            double dx = world.x - cam.x, dz = world.z - cam.z;
            az = Math.toDegrees(Math.atan2(-dx, dz));
            radius = Math.hypot(dx, dz);
            height = world.y - cam.y;
        } else {
            double a = 1.0 - Math.exp(-dt * FOLLOW_SPEED);
            az += wrap(targetAz - az) * a;
            radius += (RADIUS - radius) * a;
            height += (HEIGHT - height) * a;
            world = worldFrom(cam);
        }
        wasFixed = fixed;
    }

    private Vec3d worldFrom(Vec3d cam) {
        double r = Math.toRadians(az);
        return new Vec3d(cam.x - Math.sin(r) * radius, cam.y + height, cam.z + Math.cos(r) * radius);
    }

    private static double wrap(double d) {
        return ((d + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }
}
