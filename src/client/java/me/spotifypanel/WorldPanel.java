package me.spotifypanel;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/**
 * 3D Spotify hologram attached to the player's body, not a screen-space GUI.
 * The chassis is made from real world-space geometry; only the live information
 * on the front faces is painted to dynamic textures.
 */
public final class WorldPanel {
    public static final boolean ENABLED = true;

    private static final double MAIN_BODY_W = 1.82;
    private static final double MAIN_BODY_H = 1.20;
    private static final double MAIN_BODY_D = 0.09;
    private static final double MAIN_FACE_W = 1.70;
    private static final double MAIN_FACE_H = 1.0625; // same 512:320 ratio as the texture
    private static final int MAIN_TEX_W = 512;
    private static final int MAIN_TEX_H = 320;

    private static final int MINI_TEX_W = 256;
    private static final int MINI_TEX_H = 96;
    private static final double MINI_BODY_W = 0.56;
    private static final double MINI_BODY_H = 0.23;
    private static final double MINI_BODY_D = 0.055;
    private static final double MINI_FACE_W = 0.53;
    private static final double MINI_FACE_H = MINI_FACE_W * MINI_TEX_H / MINI_TEX_W;
    private static final double MINI_GAP = 0.03;
    private static final int MINI_COLUMNS = 3;

    private static final double SIDE = 1.10;
    private static final double AHEAD = 1.60;
    private static final double TURN_DEG = 15.0;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    private static final Identifier MAIN_TEXTURE_ID = Identifier.of("spotify-panel", "dynamic/main_panel");
    private static final Identifier SOLID_TEXTURE_ID = Identifier.of("spotify-panel", "dynamic/solid_white");
    private static NativeImageBackedTexture mainTexture;
    private static NativeImageBackedTexture solidTexture;
    private static final List<NativeImageBackedTexture> playlistTextures = new ArrayList<>();
    private static long lastMainUpdateNs;
    private static long lastPlaylistUpdateNs;
    private static String lastPlaylistKey = "";

    private static boolean init;
    private static double bx, by, bz, syaw;
    private static long lastNs;
    private static boolean wasLeftDown;

    private WorldPanel() {}

    public static void register() {
        if (ENABLED) WorldRenderEvents.BEFORE_TRANSLUCENT.register(WorldPanel::draw);
    }

    private static void draw(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!SpotifyPanelMod.config.visible || mc.player == null || mc.world == null) {
            wasLeftDown = false;
            return;
        }
        MatrixStack matrices = context.matrices();
        if (matrices == null || context.consumers() == null) return;

        ClientPlayerEntity player = mc.player;
        Vec3d cameraPos = context.worldState().cameraRenderState.pos;
        float tickDelta = mc.getRenderTickCounter().getTickProgress(false);
        Vec3d body = player.getLerpedPos(tickDelta);
        double bodyYaw = player.getBodyYaw();

        long now = System.nanoTime();
        double dt = init ? Math.min(0.1, Math.max(0.0, (now - lastNs) / 1e9)) : 1.0;
        lastNs = now;
        if (!init) {
            syaw = bodyYaw;
            init = true;
        }
        bx = body.x;
        by = body.y;
        bz = body.z;
        double yawError = wrapDegrees(bodyYaw - syaw);
        syaw += yawError * (1.0 - Math.exp(-dt * (35.0 + 0.5 * Math.abs(yawError))));

        // Basis follows the torso, not the camera. Looking around does not flatten the model into the HUD.
        double bodyRad = Math.toRadians(syaw);
        double fx = -Math.sin(bodyRad), fz = Math.cos(bodyRad);
        double rx = -Math.cos(bodyRad), rz = -Math.sin(bodyRad);
        double cx = bx + fx * AHEAD + rx * SIDE;
        double cz = bz + fz * AHEAD + rz * SIDE;

        double turn = Math.toRadians(TURN_DEG);
        double wx = rx * Math.cos(turn) - fx * Math.sin(turn);
        double wz = rz * Math.cos(turn) - fz * Math.sin(turn);
        double nx = -fx * Math.cos(turn) - rx * Math.sin(turn);
        double nz = -fz * Math.cos(turn) - rz * Math.sin(turn);

        List<SpotifyClient.Playlist> playlists = SpotifyPanelMod.spotify.playlists();
        int rows = playlists.isEmpty() ? 0 : (playlists.size() + MINI_COLUMNS - 1) / MINI_COLUMNS;
        // Keep the lowest playlist row hovering just above the player's feet. More rows move the whole assembly up.
        double centerY = playlists.isEmpty() ? by + 1.45 : by + 0.955 + 0.235 * rows;

        ensureTextures(mc, playlists.size());
        updateMainTexture(mc, now);
        updatePlaylistTextures(mc, playlists, now);

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer solid = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(SOLID_TEXTURE_ID));

        // Main volumetric chassis, green bezel, and dynamic front display.
        drawBox(solid, matrix, cx, centerY, cz, wx, wz, nx, nz,
                MAIN_BODY_W, MAIN_BODY_H, MAIN_BODY_D, 0xFF08140D, 0xFF102219);
        drawSolidPlane(solid, matrix, cx, centerY, cz, wx, wz, nx, nz,
                MAIN_BODY_W - 0.025, MAIN_BODY_H - 0.025, MAIN_BODY_D / 2.0 + 0.001, 0xFF1ED760);
        drawTexturedPlane(context, matrices, MAIN_TEXTURE_ID, cx, centerY, cz, wx, wz, nx, nz,
                MAIN_FACE_W, MAIN_FACE_H, MAIN_BODY_D / 2.0 + 0.005);

        // Each playlist is a separate miniature 3D object, all rendered from one reusable layout/texture template.
        // IMPORTANT: drawTexturedPlane switches RenderLayers, which may flush the solid buffer. Reacquire
        // the solid consumer for every miniature panel instead of using a stale VertexConsumer.
        for (int i = 0; i < playlists.size(); i++) {
            int col = i % MINI_COLUMNS;
            int row = i / MINI_COLUMNS;
            double xOffset = (col - (MINI_COLUMNS - 1) / 2.0) * (MINI_BODY_W + 0.025);
            double miniX = cx + wx * xOffset;
            double miniZ = cz + wz * xOffset;
            double miniY = centerY - MAIN_BODY_H / 2.0 - MINI_GAP - MINI_BODY_H / 2.0
                    - row * (MINI_BODY_H + MINI_GAP);
            VertexConsumer miniSolid = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(SOLID_TEXTURE_ID));
            drawBox(miniSolid, matrix, miniX, miniY, miniZ, wx, wz, nx, nz,
                    MINI_BODY_W, MINI_BODY_H, MINI_BODY_D, 0xFF07110B, 0xFF11251A);
            drawSolidPlane(miniSolid, matrix, miniX, miniY, miniZ, wx, wz, nx, nz,
                    MINI_BODY_W - 0.014, MINI_BODY_H - 0.014, MINI_BODY_D / 2.0 + 0.001, 0xFF169C46);
            drawTexturedPlane(context, matrices, playlistTextureId(i), miniX, miniY, miniZ, wx, wz, nx, nz,
                    MINI_FACE_W, MINI_FACE_H, MINI_BODY_D / 2.0 + 0.004);
        }
        matrices.pop();

        handleMouse(mc, cameraPos, cx, centerY, cz, wx, wz, nx, nz, playlists, rows);
    }

    private static void ensureTextures(MinecraftClient mc, int playlistCount) {
        if (solidTexture == null) {
            solidTexture = new NativeImageBackedTexture("Spotify solid geometry", 1, 1, false);
            NativeImage pixel = solidTexture.getImage();
            if (pixel != null) pixel.setColorArgb(0, 0, 0xFFFFFFFF);
            solidTexture.upload();
            mc.getTextureManager().registerTexture(SOLID_TEXTURE_ID, solidTexture);
        }
        if (mainTexture == null) {
            mainTexture = new NativeImageBackedTexture("Spotify main hologram", MAIN_TEX_W, MAIN_TEX_H, false);
            mc.getTextureManager().registerTexture(MAIN_TEXTURE_ID, mainTexture);
            lastMainUpdateNs = 0;
        }
        while (playlistTextures.size() < playlistCount) {
            int index = playlistTextures.size();
            NativeImageBackedTexture texture = new NativeImageBackedTexture("Spotify playlist hologram " + index,
                    MINI_TEX_W, MINI_TEX_H, false);
            mc.getTextureManager().registerTexture(playlistTextureId(index), texture);
            playlistTextures.add(texture);
            lastPlaylistUpdateNs = 0;
        }
    }

    private static Identifier playlistTextureId(int index) {
        return Identifier.of("spotify-panel", "dynamic/playlist_" + index);
    }

    private static void updateMainTexture(MinecraftClient mc, long nowNs) {
        if (mainTexture == null || (nowNs - lastMainUpdateNs) < 350_000_000L) return;
        lastMainUpdateNs = nowNs;
        BufferedImage image = paintMainTexture(SpotifyPanelMod.spotify.snapshot());
        upload(mainTexture, image);
    }

    private static void updatePlaylistTextures(MinecraftClient mc, List<SpotifyClient.Playlist> playlists, long nowNs) {
        StringBuilder key = new StringBuilder();
        for (SpotifyClient.Playlist p : playlists) {
            key.append(p.id()).append('|').append(p.name()).append('|').append(p.total()).append('|')
                    .append(p.coverUrl()).append(';');
        }
        boolean changed = !key.toString().equals(lastPlaylistKey);
        if (!changed && (nowNs - lastPlaylistUpdateNs) < 1_000_000_000L) return;
        lastPlaylistUpdateNs = nowNs;
        lastPlaylistKey = key.toString();
        for (int i = 0; i < playlists.size(); i++) {
            upload(playlistTextures.get(i), paintPlaylistTexture(playlists.get(i), i));
        }
    }

    private static void upload(NativeImageBackedTexture texture, BufferedImage image) {
        NativeImage target = texture.getImage();
        if (target == null) return;
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < image.getHeight(); y++) {
            int base = y * image.getWidth();
            for (int x = 0; x < image.getWidth(); x++) target.setColorArgb(x, y, pixels[base + x]);
        }
        texture.upload();
    }

    // ---------------------------------------------------------------- texture painting

    private static BufferedImage paintMainTexture(SpotifyClient.Snapshot s) {
        BufferedImage image = new BufferedImage(MAIN_TEX_W, MAIN_TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        prepare(g);
        g.setColor(new Color(7, 15, 10));
        g.fillRoundRect(0, 0, MAIN_TEX_W, MAIN_TEX_H, 26, 26);
        g.setColor(new Color(26, 215, 96, 70));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(2, 2, MAIN_TEX_W - 5, MAIN_TEX_H - 5, 24, 24);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.setColor(new Color(30, 215, 96));
        g.drawString("SPOTIFY", 24, 32);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(142, 169, 149));
        g.drawString("WORLD PLAYER  /  LIVE DATA", 132, 30);
        String state = stateLabel(s);
        int stateWidth = g.getFontMetrics().stringWidth(state);
        g.setColor(new Color(18, 44, 29));
        g.fillRoundRect(MAIN_TEX_W - stateWidth - 44, 14, stateWidth + 24, 25, 12, 12);
        g.setColor(s.playing() ? new Color(30, 215, 96) : new Color(151, 169, 157));
        g.fillOval(MAIN_TEX_W - stateWidth - 36, 23, 7, 7);
        g.setColor(new Color(220, 240, 226));
        g.drawString(state, MAIN_TEX_W - stateWidth - 24, 31);
        g.setColor(new Color(30, 72, 45));
        g.drawLine(22, 46, MAIN_TEX_W - 22, 46);

        SpotifyClient.Track track = s.track();
        int[] cover = track == null ? null : SpotifyPanelMod.spotify.pixels(track.coverUrl(), 128);
        drawCover(g, cover, 128, 24, 62, 128);
        g.setColor(new Color(26, 215, 96));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(23, 61, 130, 130, 8, 8);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.setColor(new Color(30, 215, 96));
        g.drawString("NOW PLAYING", 174, 76);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
        g.setColor(new Color(243, 249, 245));
        String title = track == null ? titleForInactive(s) : track.title();
        g.drawString(fit(g, title, 310), 174, 107);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        g.setColor(new Color(175, 194, 181));
        String artists = track == null ? statusDetail(s) : track.artists();
        g.drawString(fit(g, artists == null || artists.isBlank() ? "" : artists, 310), 174, 135);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(119, 147, 128));
        String device = s.device() == null || s.device().isBlank() ? "SPOTIFY CONNECT" : s.device();
        g.drawString("DEVICE  " + fit(g, device, 260), 174, 163);
        g.setColor(new Color(23, 45, 31));
        g.fillRoundRect(174, 178, 308, 7, 4, 4);
        double volume = Math.max(0, Math.min(100, s.volume())) / 100.0;
        g.setColor(new Color(30, 215, 96));
        g.fillRoundRect(174, 178, (int) Math.round(308 * volume), 7, 4, 4);
        g.setColor(new Color(153, 176, 160));
        g.drawString("VOLUME", 174, 199);
        g.drawString(Math.max(0, Math.min(100, s.volume())) + "%", 442, 199);

        long duration = track == null ? 0 : Math.max(0, track.durationMs());
        long progress = duration == 0 ? 0 : Math.max(0, Math.min(duration, s.currentProgress()));
        double fraction = duration == 0 ? 0 : (double) progress / duration;
        g.setColor(new Color(29, 48, 35));
        g.fillRoundRect(24, 221, 464, 7, 4, 4);
        g.setColor(new Color(30, 215, 96));
        g.fillRoundRect(24, 221, (int) (464 * fraction), 7, 4, 4);
        g.fillOval(20 + (int) (464 * fraction), 218, 13, 13);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.setColor(new Color(157, 180, 164));
        g.drawString(formatTime(progress), 24, 246);
        String durationText = formatTime(duration);
        g.drawString(durationText, 488 - g.getFontMetrics().stringWidth(durationText), 246);

        // Large, deliberately separated transport controls are also click targets in the 3D world.
        g.setColor(new Color(116, 145, 126));
        g.drawString(s.shuffle() ? "SHUFFLE ON" : "SHUFFLE", 34, 283);
        g.drawString("REPEAT " + (s.repeat() == null ? "OFF" : s.repeat().toUpperCase()), 385, 283);
        g.setColor(new Color(160, 185, 168));
        drawPrevious(g, 191, 273);
        g.setColor(new Color(30, 215, 96));
        g.fillOval(230, 248, 52, 52);
        g.setColor(new Color(4, 17, 9));
        if (s.playing()) {
            g.fillRoundRect(246, 261, 7, 26, 3, 3);
            g.fillRoundRect(259, 261, 7, 26, 3, 3);
        } else {
            Polygon play = new Polygon(new int[]{248, 248, 267}, new int[]{260, 288, 274}, 3);
            g.fillPolygon(play);
        }
        g.setColor(new Color(160, 185, 168));
        drawNext(g, 302, 273);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(new Color(30, 215, 96));
        g.drawString("CLICK A PLAYLIST BELOW TO PLAY IT", 24, 306);
        g.setColor(new Color(24, 55, 34));
        g.drawLine(24, 311, MAIN_TEX_W - 24, 311);
        g.dispose();
        return image;
    }

    private static BufferedImage paintPlaylistTexture(SpotifyClient.Playlist p, int index) {
        BufferedImage image = new BufferedImage(MINI_TEX_W, MINI_TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        prepare(g);
        g.setColor(new Color(7, 16, 10));
        g.fillRoundRect(0, 0, MINI_TEX_W, MINI_TEX_H, 14, 14);
        g.setColor(new Color(30, 215, 96, 75));
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(1, 1, MINI_TEX_W - 3, MINI_TEX_H - 3, 12, 12);

        int[] cover = SpotifyPanelMod.spotify.pixels(p.coverUrl(), 48);
        drawCover(g, cover, 48, 9, 24, 48);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(new Color(229, 241, 232));
        g.drawString(fit(g, p.name() == null ? "Playlist" : p.name(), 158), 68, 40);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(new Color(143, 171, 151));
        String owner = p.owner() == null || p.owner().isBlank() ? "SPOTIFY PLAYLIST" : p.owner();
        g.drawString(fit(g, owner + "  •  " + p.total() + " tracks", 158), 68, 58);
        g.setColor(new Color(30, 215, 96));
        g.fillOval(219, 31, 27, 27);
        g.setColor(new Color(4, 17, 9));
        Polygon play = new Polygon(new int[]{229, 229, 239}, new int[]{37, 52, 44}, 3);
        g.fillPolygon(play);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
        g.setColor(new Color(30, 215, 96));
        g.drawString(String.format("%02d", index + 1), 68, 77);
        g.dispose();
        return image;
    }

    private static void prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static void drawCover(Graphics2D g, int[] pixels, int size, int x, int y, int target) {
        if (pixels != null && pixels.length >= size * size) {
            BufferedImage cover = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            cover.setRGB(0, 0, size, size, pixels, 0, size);
            g.drawImage(cover, x, y, target, target, null);
        } else {
            g.setColor(new Color(13, 31, 19));
            g.fillRoundRect(x, y, target, target, 8, 8);
            g.setColor(new Color(30, 215, 96, 170));
            g.setStroke(new BasicStroke(Math.max(1, target / 32f)));
            for (int i = 0; i < 7; i++) {
                int bar = (int) (target * (0.18 + 0.6 * Math.abs(Math.sin(i * 1.8))));
                g.drawLine(x + target / 5 + i * target / 12, y + target / 2 - bar / 2,
                        x + target / 5 + i * target / 12, y + target / 2 + bar / 2);
            }
        }
    }

    private static String stateLabel(SpotifyClient.Snapshot s) {
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) return "SETUP REQUIRED";
        if (s.status() == SpotifyClient.Status.LOGGED_OUT) return "NOT CONNECTED";
        if (s.status() == SpotifyClient.Status.ERROR) return "SPOTIFY ERROR";
        if (s.status() == SpotifyClient.Status.ACTIVE) return s.playing() ? "PLAYING" : "PAUSED";
        return "IDLE";
    }

    private static String titleForInactive(SpotifyClient.Snapshot s) {
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) return "Connect Spotify";
        if (s.status() == SpotifyClient.Status.LOGGED_OUT) return "Login required";
        if (s.status() == SpotifyClient.Status.ERROR) return "Playback unavailable";
        return "Nothing playing";
    }

    private static String statusDetail(SpotifyClient.Snapshot s) {
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) return "Use /spotify setup <client_id>";
        if (s.status() == SpotifyClient.Status.LOGGED_OUT) return "Use /spotify login";
        if (s.status() == SpotifyClient.Status.ERROR) return s.error();
        return "Start music on Spotify";
    }

    private static String fit(Graphics2D g, String text, int maxWidth) {
        if (text == null) return "";
        String result = text.replace('\n', ' ').trim();
        if (maxWidth <= 0) return "";
        while (!result.isEmpty() && g.getFontMetrics().stringWidth(result) > maxWidth) {
            if (result.length() <= 3) return result;
            result = result.substring(0, result.length() - 2).stripTrailing() + "…";
        }
        return result;
    }

    private static void drawPrevious(Graphics2D g, int x, int y) {
        g.fillRect(x - 8, y - 13, 4, 26);
        g.fillPolygon(new int[]{x + 11, x + 11, x - 7}, new int[]{y - 13, y + 13, y}, 3);
    }

    private static void drawNext(Graphics2D g, int x, int y) {
        g.fillRect(x + 8, y - 13, 4, 26);
        g.fillPolygon(new int[]{x - 11, x - 11, x + 7}, new int[]{y - 13, y + 13, y}, 3);
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------------------------------------------------------------- true 3D geometry

    private static void drawBox(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz,
                                double wx, double wz, double nx, double nz,
                                double width, double height, double depth, int frontColor, int sideColor) {
        Vec3d[] front = corners(cx, cy, cz, wx, wz, nx, nz, width, height, depth / 2.0);
        Vec3d[] back = corners(cx, cy, cz, wx, wz, nx, nz, width, height, -depth / 2.0);
        drawColorQuad(vc, m, front[0], front[1], front[2], front[3], frontColor);
        drawColorQuad(vc, m, back[3], back[2], back[1], back[0], sideColor);
        drawColorQuad(vc, m, front[3], front[2], back[2], back[3], sideColor); // top
        drawColorQuad(vc, m, front[0], back[0], back[1], front[1], sideColor); // bottom
        drawColorQuad(vc, m, front[0], front[3], back[3], back[0], sideColor); // left
        drawColorQuad(vc, m, front[2], front[1], back[1], back[2], sideColor); // right
    }

    private static Vec3d[] corners(double cx, double cy, double cz, double wx, double wz,
                                   double nx, double nz, double width, double height, double depthOffset) {
        double ox = cx + nx * depthOffset;
        double oz = cz + nz * depthOffset;
        double leftX = ox - wx * width / 2.0, leftZ = oz - wz * width / 2.0;
        double rightX = ox + wx * width / 2.0, rightZ = oz + wz * width / 2.0;
        double bottom = cy - height / 2.0, top = cy + height / 2.0;
        return new Vec3d[]{new Vec3d(leftX, bottom, leftZ), new Vec3d(rightX, bottom, rightZ),
                new Vec3d(rightX, top, rightZ), new Vec3d(leftX, top, leftZ)};
    }

    private static void drawSolidPlane(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz,
                                       double wx, double wz, double nx, double nz,
                                       double width, double height, double depthOffset, int color) {
        Vec3d[] p = corners(cx, cy, cz, wx, wz, nx, nz, width, height, depthOffset);
        drawColorQuad(vc, m, p[0], p[1], p[2], p[3], color);
    }

    private static void drawColorQuad(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, bcol = argb & 255, alpha = (argb >>> 24) & 255;
        coloredVertex(vc, m, a, r, g, bcol, alpha);
        coloredVertex(vc, m, b, r, g, bcol, alpha);
        coloredVertex(vc, m, c, r, g, bcol, alpha);
        coloredVertex(vc, m, d, r, g, bcol, alpha);
        coloredVertex(vc, m, d, r, g, bcol, alpha);
        coloredVertex(vc, m, c, r, g, bcol, alpha);
        coloredVertex(vc, m, b, r, g, bcol, alpha);
        coloredVertex(vc, m, a, r, g, bcol, alpha);
    }

    private static void coloredVertex(VertexConsumer vc, Matrix4f m, Vec3d p, int r, int g, int b, int alpha) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, alpha)
                .texture(0.5f, 0.5f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                .normal(0.0f, 1.0f, 0.0f);
    }

    private static void drawTexturedPlane(WorldRenderContext context, MatrixStack matrices, Identifier textureId,
                                          double cx, double cy, double cz, double wx, double wz, double nx, double nz,
                                          double width, double height, double depthOffset) {
        NativeImageBackedTexture texture = textureId.equals(MAIN_TEXTURE_ID)
                ? mainTexture : textureForId(textureId);
        if (texture == null) return;
        VertexConsumer vc = context.consumers().getBuffer(RenderLayers.entityTranslucent(textureId));
        Vec3d[] p = corners(cx, cy, cz, wx, wz, nx, nz, width, height, depthOffset);
        MatrixStack.Entry entry = matrices.peek();
        texturedVertex(vc, entry, p[0], 0, 1, nx, nz);
        texturedVertex(vc, entry, p[1], 1, 1, nx, nz);
        texturedVertex(vc, entry, p[2], 1, 0, nx, nz);
        texturedVertex(vc, entry, p[3], 0, 0, nx, nz);
        // Back face keeps the physical panel readable from behind too, with the artwork oriented correctly.
        texturedVertex(vc, entry, p[3], 0, 0, -nx, -nz);
        texturedVertex(vc, entry, p[2], 1, 0, -nx, -nz);
        texturedVertex(vc, entry, p[1], 1, 1, -nx, -nz);
        texturedVertex(vc, entry, p[0], 0, 1, -nx, -nz);
    }

    private static NativeImageBackedTexture textureForId(Identifier id) {
        String path = id.getPath();
        if (!path.startsWith("dynamic/playlist_")) return null;
        try {
            int index = Integer.parseInt(path.substring("dynamic/playlist_".length()));
            return index >= 0 && index < playlistTextures.size() ? playlistTextures.get(index) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void texturedVertex(VertexConsumer vc, MatrixStack.Entry entry, Vec3d p,
                                       float u, float v, double nx, double nz) {
        vc.vertex(entry, (float) p.x, (float) p.y, (float) p.z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                .normal(entry, (float) nx, 0.0f, (float) nz);
    }

    // ---------------------------------------------------------------- click handling: raycast against the actual 3D faces

    private static void handleMouse(MinecraftClient mc, Vec3d cameraPos,
                                    double cx, double cy, double cz, double wx, double wz, double nx, double nz,
                                    List<SpotifyClient.Playlist> playlists, int rows) {
        boolean chatOpen = mc.currentScreen instanceof ChatScreen;
        long windowHandle = mc.getWindow().getHandle();
        boolean down = chatOpen && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean clicked = down && !wasLeftDown;
        wasLeftDown = down;
        if (!clicked) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        Vec3d ray = mouseRay(mc, mx, my, sw, sh);
        if (ray == null) return;

        SpotifyClient client = SpotifyPanelMod.spotify;
        Vec3d mainFaceCenter = new Vec3d(cx + nx * (MAIN_BODY_D / 2.0 + 0.005), cy, cz + nz * (MAIN_BODY_D / 2.0 + 0.005));
        double[] uv = intersectUv(cameraPos, ray, mainFaceCenter, wx, wz, nx, nz, MAIN_FACE_W, MAIN_FACE_H);
        if (uv != null) {
            int tx = (int) (uv[0] * MAIN_TEX_W);
            int ty = (int) (uv[1] * MAIN_TEX_H);
            SpotifyClient.Snapshot s = client.snapshot();
            if (ty >= 212 && ty <= 237 && tx >= 24 && tx <= 488 && s.track() != null) {
                double f = Math.max(0, Math.min(1, (tx - 24.0) / 464.0));
                client.seek((long) (s.track().durationMs() * f), SpotifyPanelMod::say);
                return;
            }
            if (ty >= 245 && ty <= 303) {
                if (tx >= 226 && tx <= 286) client.togglePlay(SpotifyPanelMod::say);
                else if (tx >= 176 && tx <= 221) client.previous(SpotifyPanelMod::say);
                else if (tx >= 290 && tx <= 333) client.next(SpotifyPanelMod::say);
                else if (tx >= 26 && tx <= 150) client.shuffle(!s.shuffle(), SpotifyPanelMod::say);
                else if (tx >= 375 && tx <= 490) {
                    String repeat = s.repeat() == null ? "off" : s.repeat();
                    client.repeat("off".equals(repeat) ? "context" : "context".equals(repeat) ? "track" : "off", SpotifyPanelMod::say);
                }
                return;
            }
            if (ty >= 169 && ty <= 210 && tx >= 174 && tx <= 482) {
                client.volume((int) Math.round((tx - 174) / 308.0 * 100.0), SpotifyPanelMod::say);
                return;
            }
            // A click on a non-interactive part of the main display must not pass through to a playlist behind it.
            return;
        }

        for (int i = 0; i < playlists.size(); i++) {
            int col = i % MINI_COLUMNS;
            int row = i / MINI_COLUMNS;
            double xOffset = (col - (MINI_COLUMNS - 1) / 2.0) * (MINI_BODY_W + 0.025);
            double miniX = cx + wx * xOffset;
            double miniZ = cz + wz * xOffset;
            double miniY = cy - MAIN_BODY_H / 2.0 - MINI_GAP - MINI_BODY_H / 2.0
                    - row * (MINI_BODY_H + MINI_GAP);
            Vec3d face = new Vec3d(miniX + nx * (MINI_BODY_D / 2.0 + 0.004), miniY, miniZ + nz * (MINI_BODY_D / 2.0 + 0.004));
            double[] miniUv = intersectUv(cameraPos, ray, face, wx, wz, nx, nz, MINI_FACE_W, MINI_FACE_H);
            if (miniUv != null) {
                SpotifyClient.Playlist p = playlists.get(i);
                client.playContext(p.uri(), null, SpotifyPanelMod::say);
                return;
            }
        }
    }

    private static Vec3d mouseRay(MinecraftClient mc, double mouseX, double mouseY, int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) return null;
        Camera camera = mc.gameRenderer.getCamera();
        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());
        double sinY = Math.sin(yaw), cosY = Math.cos(yaw);
        double sinP = Math.sin(pitch), cosP = Math.cos(pitch);
        Vec3d forward = new Vec3d(-sinY * cosP, -sinP, cosY * cosP).normalize();
        Vec3d right = new Vec3d(-cosY, 0, -sinY).normalize();
        Vec3d up = right.crossProduct(forward).normalize();
        double nx = (mouseX / screenW) * 2.0 - 1.0;
        double ny = 1.0 - (mouseY / screenH) * 2.0;
        double fov = Math.toRadians(mc.options.getFov().getValue());
        double tan = Math.tan(fov / 2.0);
        double aspect = screenW / (double) screenH;
        return forward.add(right.multiply(nx * tan * aspect)).add(up.multiply(ny * tan)).normalize();
    }

    /** Returns normalized front-face UV coordinates, or null if the ray misses the panel. */
    private static double[] intersectUv(Vec3d origin, Vec3d direction, Vec3d planeCenter,
                                        double wx, double wz, double nx, double nz,
                                        double width, double height) {
        Vec3d normal = new Vec3d(nx, 0, nz).normalize();
        double denominator = direction.dotProduct(normal);
        if (Math.abs(denominator) < 1e-7) return null;
        double t = planeCenter.subtract(origin).dotProduct(normal) / denominator;
        if (t <= 0) return null;
        Vec3d hit = origin.add(direction.multiply(t));
        Vec3d delta = hit.subtract(planeCenter);
        double localX = delta.x * wx + delta.z * wz;
        double localY = delta.y;
        if (Math.abs(localX) > width / 2.0 || Math.abs(localY) > height / 2.0) return null;
        return new double[]{localX / width + 0.5, 0.5 - localY / height};
    }

    private static double wrapDegrees(double degrees) {
        return ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }
}
