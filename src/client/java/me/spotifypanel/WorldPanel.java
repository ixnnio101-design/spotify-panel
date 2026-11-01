package me.spotifypanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the user's flat, layered Blockbench Spotify panel in world space.
 * The .bbmodel provides thin planar parts; a transparent dynamic texture is
 * projected onto the front to show live Spotify data.
 */
public final class WorldPanel {
    public static final boolean ENABLED = true;

    private static final int TEX_W = 768;
    private static final int TEX_H = 500;
    private static final double MODEL_WIDTH = 1.82;
    private static final double MODEL_HEIGHT = 1.20;
    // Preserve the Blockbench model's proportions: its X coordinate is depth in the world.
    // The model is intentionally a thin, layered plane, not a set of extruded boxes.
    private static final double MODEL_Z_SCALE = MODEL_WIDTH / 20.0;
    private static final double MODEL_DEPTH_SCALE = MODEL_WIDTH / 20.0;
    private static final double MODEL_Y_SCALE = MODEL_HEIGHT / 13.0;
    private static final double MODEL_OVERLAY_X = -0.25;
    private static final double MODEL_CENTER_Y = 6.5;

    // Keep the hologram close to the upper torso instead of floating several blocks away.
    private static final double SIDE = 0.92;
    private static final double AHEAD = 0.95;
    private static final double TURN_DEG = 15.0;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    private static final Identifier MATERIAL_TEXTURE_ID = Identifier.of("spotify-panel", "textures/misc/panel_material.png");
    private static final Identifier OVERLAY_TEXTURE_ID = Identifier.of("spotify-panel", "dynamic/model_overlay");
    private static NativeImageBackedTexture overlayTexture;
    private static long lastOverlayUpdateNs;

    private static final List<ModelPart> MODEL_PARTS = loadBlockbenchModel();
    private static boolean wasLeftDown;
    private static final int DRAG_NONE = 0, DRAG_SEEK = 1, DRAG_VOLUME = 2;
    private static int dragMode = DRAG_NONE;
    private static long lastDragSendMs;
    private static long lastDragSeekMs = -1;
    private static int lastDragVolume = -1;
    private static String selectedLibraryKey = "";
    private static String lastRequestedLibraryKey = "";
    private static long lastItemsRequestNs;
    private static int sideScroll, trackScroll;
    private static volatile int scrollArea;
    private static volatile double wheelDelta;
    private static boolean scrollHooked;
    private static GLFWScrollCallback previousScroll;

    private WorldPanel() {}

    private record ModelPart(String name, double x0, double y0, double z0,
                             double x1, double y1, double z1, int frontColor, int sideColor) {}

    public static void register() {
        if (ENABLED) WorldRenderEvents.BEFORE_TRANSLUCENT.register(WorldPanel::draw);
    }

    private static List<ModelPart> loadBlockbenchModel() {
        List<ModelPart> parts = new ArrayList<>();
        String resource = "/assets/spotify-panel/models/panel.bbmodel";
        try (InputStream in = WorldPanel.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing bundled Blockbench model: " + resource);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray elements = root.getAsJsonArray("elements");
            for (int i = 0; i < elements.size(); i++) {
                JsonObject element = elements.get(i).getAsJsonObject();
                String name = element.has("name") ? element.get("name").getAsString() : "part_" + i;
                JsonArray from = element.getAsJsonArray("from");
                JsonArray to = element.getAsJsonArray("to");
                if (from == null || to == null || from.size() < 3 || to.size() < 3) continue;

                double x0 = Math.min(from.get(0).getAsDouble(), to.get(0).getAsDouble());
                double x1 = Math.max(from.get(0).getAsDouble(), to.get(0).getAsDouble());
                double y0 = Math.min(from.get(1).getAsDouble(), to.get(1).getAsDouble());
                double y1 = Math.max(from.get(1).getAsDouble(), to.get(1).getAsDouble());
                double z0 = Math.min(from.get(2).getAsDouble(), to.get(2).getAsDouble());
                double z1 = Math.max(from.get(2).getAsDouble(), to.get(2).getAsDouble());

                int[] colors = palette(name);
                parts.add(new ModelPart(name, x0, y0, z0, x1, y1, z1, colors[0], colors[1]));
            }
        } catch (Exception e) {
            System.err.println("[SpotifyPanel] Could not load Blockbench model: " + e.getMessage());
        }
        return List.copyOf(parts);
    }

    private static int[] palette(String name) {
        return switch (name) {
            case "main_body" -> new int[]{0xFF17201A, 0xFF35443A};
            case "playback_list_corpus" -> new int[]{0xFF111813, 0xFF29372E};
            case "playlist_icons" -> new int[]{0xFF203B2B, 0xFF1ED760};
            case "body_tracks_from_the_playlist" -> new int[]{0xFF101512, 0xFF2B3930};
            case "playlist_info" -> new int[]{0xFF34245A, 0xFF66519A};
            case "corpus_info_songs" -> new int[]{0xFF0D120F, 0xFF27342C};
            case "big_icon_of_the_song" -> new int[]{0xFF1B2B21, 0xFF54765E};
            case "song_text" -> new int[]{0xFF151D18, 0xFF344238};
            case "icon_song_name_in_playlist" -> new int[]{0xFF1C2A21, 0xFF31543B};
            case "corpus_of_songs" -> new int[]{0xFF080C09, 0xFF26332B};
            case "icon_song_title" -> new int[]{0xFF172019, 0xFF3D5144};
            case "time_of_the_played_song" -> new int[]{0xFF17231B, 0xFF3D6047};
            case "reduction_of_sound_amplification" -> new int[]{0xFF17231B, 0xFF3D6047};
            default -> new int[]{0xFF172019, 0xFF344238};
        };
    }

    private static void draw(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!SpotifyPanelMod.config.visible || mc.player == null || mc.world == null) {
            wasLeftDown = false;
            dragMode = DRAG_NONE;
            return;
        }
        MatrixStack matrices = context.matrices();
        if (matrices == null || context.consumers() == null) return;

        ClientPlayerEntity player = mc.player;
        Vec3d cameraPos = context.worldState().cameraRenderState.pos;
        float tickDelta = mc.getRenderTickCounter().getTickProgress(false);
        hookScroll(mc.getWindow().getHandle());
        // Use the BODY yaw getter available in Minecraft 1.21.11.
        // Do not use camera/head yaw: moving the mouse alone must not swing the panel.
        Vec3d body = player.getLerpedPos(tickDelta);
        double bodyYaw = player.getBodyYaw();
        long now = System.nanoTime();

        double bodyRad = Math.toRadians(bodyYaw);
        double fx = -Math.sin(bodyRad), fz = Math.cos(bodyRad);
        double rx = -Math.cos(bodyRad), rz = -Math.sin(bodyRad);
        double cx = body.x + fx * AHEAD + rx * SIDE;
        double cz = body.z + fz * AHEAD + rz * SIDE;
        double turn = Math.toRadians(TURN_DEG);
        double wx = rx * Math.cos(turn) - fx * Math.sin(turn);
        double wz = rz * Math.cos(turn) - fz * Math.sin(turn);
        double nx = -fx * Math.cos(turn) - rx * Math.sin(turn);
        double nz = -fz * Math.cos(turn) - rz * Math.sin(turn);
        double centerY = body.y + 1.28;

        List<SpotifyClient.LibraryEntry> libraryEntries = SpotifyPanelMod.spotify.library();
        SpotifyClient.LibraryEntry selected = selectedLibraryEntry(libraryEntries);
        updateScrollArea(mc, cameraPos, cx, centerY, cz, wx, wz, nx, nz);
        applyWheel(libraryEntries.size(), selected == null ? 0 : safeItemCount(selected.key()));
        List<SpotifyClient.Item> items = null;
        if (selected != null) {
            if (!selected.key().equals(lastRequestedLibraryKey) || now - lastItemsRequestNs > 60_000_000_000L) {
                lastRequestedLibraryKey = selected.key();
                lastItemsRequestNs = now;
                SpotifyPanelMod.spotify.requestItems(selected, false);
            }
            items = SpotifyPanelMod.spotify.items(selected.key());
        }
        sideScroll = Math.min(sideScroll, Math.max(0, libraryEntries.size() - 7));
        if (items != null) trackScroll = Math.min(trackScroll, Math.max(0, items.size() - 7));

        ensureOverlayTexture(mc);
        if (now - lastOverlayUpdateNs >= 350_000_000L) {
            lastOverlayUpdateNs = now;
            upload(overlayTexture, paintOverlay(SpotifyPanelMod.spotify.snapshot(), libraryEntries, selected, items));
        }

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Render the 13 Blockbench elements as thin, world-space planes. Their small X offsets
        // are preserved as subtle layer separation, but no thickness or side faces are invented.
        VertexConsumer material = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(MATERIAL_TEXTURE_ID));
        for (ModelPart part : MODEL_PARTS) {
            drawModelPart(material, matrix, cx, centerY, cz, wx, wz, nx, nz, part);
        }
        // Reuse the library-icon plane for each visible library item. Every icon remains flat;
        // the only depth is the subtle layer offset from its original Blockbench X coordinate.
        int visibleLibraryIcons = Math.min(7, Math.max(0, libraryEntries.size() - sideScroll));
        int[] iconColors = palette("playlist_icons");
        for (int i = 0; i < visibleLibraryIcons; i++) {
            double top = 11.5 - i * 1.2;
            ModelPart iconPart = new ModelPart("playlist_icon_" + i,
                    -0.2, top - 0.9, -9.7,
                    -0.2, top, -8.7,
                    iconColors[0], iconColors[1]);
            drawModelPart(material, matrix, cx, centerY, cz, wx, wz, nx, nz, iconPart);
        }

        // A transparent live-information layer is mapped to the model's front surface, not screen coordinates.
        drawOverlay(context, matrices, cx, centerY, cz, wx, wz, nx, nz);
        matrices.pop();

        handleMouse(mc, cameraPos, cx, centerY, cz, wx, wz, nx, nz, libraryEntries, selected, items);
    }

    private static void hookScroll(long handle) {
        if (scrollHooked) return;
        scrollHooked = true;
        previousScroll = GLFW.glfwSetScrollCallback(handle, (window, xOffset, yOffset) -> {
            // Wheel is reserved for scrolling the library and track lists.
            // Volume and seeking are controlled by holding the left mouse button on their sliders.
            if (scrollArea != 0) wheelDelta += yOffset;
            else if (previousScroll != null) previousScroll.invoke(window, xOffset, yOffset);
        });
    }

    private static void updateScrollArea(MinecraftClient mc, Vec3d cameraPos,
                                         double cx, double cy, double cz, double wx, double wz, double nx, double nz) {
        scrollArea = 0;
        if (!(mc.currentScreen instanceof ChatScreen)) {
            wheelDelta = 0;
            return;
        }
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        Vec3d ray = mouseRay(mc, mx, my, sw, sh);
        if (ray == null) return;
        Vec3d faceCenter = modelPoint(MODEL_OVERLAY_X, MODEL_CENTER_Y, 0, cx, cy, cz, wx, wz, nx, nz);
        double[] uv = intersectUv(cameraPos, ray, faceCenter, wx, wz, nx, nz, MODEL_WIDTH, MODEL_HEIGHT);
        if (uv == null) return;
        int tx = (int) (uv[0] * TEX_W);
        int ty = (int) (uv[1] * TEX_H);
        // 1 = library sidebar, 2 = track table, 3 = any other part of the panel.
        scrollArea = 3;
        if (tx >= px(-9.8) && tx <= px(-8.6) && ty >= py(12.8) && ty <= py(2.5)) scrollArea = 1;
        else if (tx >= px(-8.4) && tx <= px(4.2) && ty >= py(9.1) && ty <= py(2.5)) scrollArea = 2;
    }

    private static void applyWheel(int playlistCount, int itemCount) {
        // Unmodified wheel only scrolls the library and selected content list.
        while (Math.abs(wheelDelta) >= 1.0) {
            int direction = wheelDelta > 0 ? -1 : 1;
            if (scrollArea == 1) sideScroll = Math.max(0, Math.min(Math.max(0, playlistCount - 7), sideScroll + direction));
            else if (scrollArea == 2) trackScroll = Math.max(0, Math.min(Math.max(0, itemCount - 7), trackScroll + direction));
            wheelDelta -= Math.signum(wheelDelta);
        }
    }

    private static int safeItemCount(String playlistId) {
        List<SpotifyClient.Item> loaded = SpotifyPanelMod.spotify.items(playlistId);
        return loaded == null ? 0 : loaded.size();
    }

    private static SpotifyClient.LibraryEntry selectedLibraryEntry(List<SpotifyClient.LibraryEntry> entries) {
        if (entries.isEmpty()) {
            selectedLibraryKey = "";
            return null;
        }
        for (SpotifyClient.LibraryEntry entry : entries) {
            if (entry.key().equals(selectedLibraryKey)) return entry;
        }
        selectedLibraryKey = entries.get(0).key();
        return entries.get(0);
    }

    private static void ensureOverlayTexture(MinecraftClient mc) {
        if (overlayTexture != null) return;
        overlayTexture = new NativeImageBackedTexture("Spotify Blockbench live overlay", TEX_W, TEX_H, false);
        mc.getTextureManager().registerTexture(OVERLAY_TEXTURE_ID, overlayTexture);
        lastOverlayUpdateNs = 0;
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

    // -------------------------------------------------------------------- live overlay painting

    private static BufferedImage paintOverlay(SpotifyClient.Snapshot s, List<SpotifyClient.LibraryEntry> libraryEntries,
                                              SpotifyClient.LibraryEntry selected, List<SpotifyClient.Item> items) {
        BufferedImage image = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        prepare(g);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, TEX_W, TEX_H);

        paintSidebar(g, libraryEntries, selected);
        paintPlaylistHeader(g, selected, items);
        paintTrackTable(g, s, items, selected);
        paintNowPlaying(g, s);
        paintBottomPlayer(g, s);

        g.dispose();
        return image;
    }

    private static void paintSidebar(Graphics2D g, List<SpotifyClient.LibraryEntry> libraryEntries, SpotifyClient.LibraryEntry selected) {
        int left = px(-9.8), right = px(-8.6), top = py(12.8), bottom = py(2.5);
        g.setColor(new Color(5, 9, 7, 220));
        g.fillRoundRect(left, top, right - left, bottom - top, 12, 12);
        paintLowPolyFacets(g, left, top, right - left, bottom - top, 5);
        g.setColor(new Color(30, 215, 96, 155));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(left + 1, top + 1, right - left - 3, bottom - top - 3, 10, 10);

        // Small library mark at the top, then one icon per saved library item.
        int libX = left + 8, libY = top + 10;
        g.setColor(new Color(30, 215, 96));
        g.fillRoundRect(libX, libY, Math.max(18, right - left - 16), 28, 7, 7);
        g.setColor(new Color(5, 18, 10));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("≡", libX + 8, libY + 21);

        int shown = Math.min(7, Math.max(0, libraryEntries.size() - sideScroll));
        int iconSize = Math.max(22, Math.min(34, right - left - 12));
        int x = left + (right - left - iconSize) / 2;
        int y0 = top + 53;
        for (int i = 0; i < shown; i++) {
            SpotifyClient.LibraryEntry p = libraryEntries.get(sideScroll + i);
            int y = y0 + i * 46;
            int[] pixels = SpotifyPanelMod.spotify.pixels(p.coverUrl(), 32);
            drawCover(g, pixels, 32, x, y, iconSize);
            if (selected != null && p.key().equals(selected.key())) {
                g.setColor(new Color(30, 215, 96));
                g.setStroke(new BasicStroke(2.5f));
                g.drawRoundRect(x - 2, y - 2, iconSize + 3, iconSize + 3, 7, 7);
            }
            // Tiny type glyph distinguishes playlists, albums, artists and saved collections.
            g.setColor(new Color(5, 12, 8, 220));
            g.fillRoundRect(x + iconSize - 13, y + iconSize - 13, 13, 13, 4, 4);
            g.setColor(new Color(235, 245, 238));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            g.drawString(p.iconMark(), x + iconSize - 11, y + iconSize - 3);
        }
        if (sideScroll > 0) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(new Color(180, 204, 188));
            g.drawString("▲", left + 15, y0 - 8);
        }
        if (sideScroll + shown < libraryEntries.size()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(new Color(180, 204, 188));
            g.drawString("▼", left + 15, y0 + 7 * 46 + 9);
        }
    }

    private static void paintPlaylistHeader(Graphics2D g, SpotifyClient.LibraryEntry selected, List<SpotifyClient.Item> items) {
        int x0 = px(-8.2), x1 = px(4.0), y0 = py(12.6), y1 = py(9.3);
        int accentRgb = selected == null ? 0xFF52635B
                : SpotifyPanelMod.spotify.coverAccentColor(selected.coverUrl());
        Color[] headerColors = headerGradient(accentRgb);
        g.setPaint(new GradientPaint(0, y0, headerColors[0], 0, y1, headerColors[1]));
        g.fillRoundRect(x0, y0, x1 - x0, y1 - y0, 15, 15);
        paintLowPolyFacets(g, x0, y0, x1 - x0, y1 - y0, 1);
        g.setColor(new Color(255, 255, 255, 35));
        g.drawLine(x0 + 18, y1 - 8, x1 - 18, y1 - 8);

        // Selected playlist artwork belongs in the header (separate from the currently
        // playing track artwork on the right side of the panel).
        int coverSize = Math.min(104, y1 - y0 - 18);
        int coverX = x0 + 12;
        int coverY = y0 + (y1 - y0 - coverSize) / 2;
        int[] cover = selected == null ? null : SpotifyPanelMod.spotify.pixels(selected.coverUrl(), 128);
        drawCover(g, cover, 128, coverX, coverY, coverSize);
        g.setColor(new Color(30, 215, 96, 220));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRect(coverX, coverY, coverSize, coverSize);

        int textX = coverX + coverSize + 16;
        int textWidth = Math.max(80, x1 - textX - 16);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(250, 252, 250));
        g.drawString(selected == null ? "МЕДИАТЕКА" : selected.kindLabel(), textX, y0 + 27);
        String title = selected == null ? "Твоя медиатека" : safe(selected.name(), "Элемент Spotify");
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 46));
        g.setColor(Color.WHITE);
        g.drawString(fit(g, title, textWidth), textX, y0 + 76);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.setColor(new Color(245, 248, 245, 245));
        String countLabel = selected == null ? "" : switch (selected.kind()) {
            case "artist" -> "релизов";
            case "show" -> "эпизодов";
            case "audiobook" -> "глав";
            case "saved_tracks" -> "сохранённых треков";
            case "saved_episodes" -> "сохранённых эпизодов";
            case "album" -> "треков в альбоме";
            default -> "треков";
        };
        String meta = selected == null ? "Подключи Spotify, чтобы увидеть медиатеку"
                : safe(selected.owner(), "Spotify") + "  •  " + (items == null ? selected.total() : items.size()) + " " + countLabel;
        g.drawString(fit(g, meta, textWidth), textX + 2, y1 - 17);
    }

    /** Build Spotify-like top/bottom shades from the selected artwork's dominant hue. */
    private static Color[] headerGradient(int argb) {
        Color source = new Color(argb, true);
        float[] hsb = Color.RGBtoHSB(source.getRed(), source.getGreen(), source.getBlue(), null);
        float hue = hsb[0];
        float saturation = hsb[1];
        float brightness = hsb[2];

        // Keep monochrome covers grey; boost colorful covers enough to read like Spotify's
        // artwork-tinted header without turning them into a neon flat fill.
        if (saturation < 0.12f) {
            int neutralTop = Math.max(64, Math.min(142, Math.round(62 + brightness * 78)));
            int neutralBottom = Math.max(24, Math.round(neutralTop * 0.34f));
            return new Color[]{new Color(neutralTop, neutralTop, neutralTop, 238),
                    new Color(neutralBottom, neutralBottom, neutralBottom, 238)};
        }

        float topSat = Math.max(0.56f, Math.min(0.92f, saturation * 1.48f));
        float bottomSat = Math.max(0.62f, Math.min(0.96f, topSat * 1.08f));
        float topBrightness = Math.max(0.48f, Math.min(0.82f, 0.46f + brightness * 0.40f));
        float bottomBrightness = Math.max(0.16f, topBrightness * 0.34f);
        Color top = new Color(Color.HSBtoRGB(hue, topSat, topBrightness));
        Color bottom = new Color(Color.HSBtoRGB(hue, bottomSat, bottomBrightness));
        return new Color[]{new Color(top.getRed(), top.getGreen(), top.getBlue(), 238),
                new Color(bottom.getRed(), bottom.getGreen(), bottom.getBlue(), 238)};
    }

    private static void paintTrackTable(Graphics2D g, SpotifyClient.Snapshot snapshot,
                                        List<SpotifyClient.Item> items, SpotifyClient.LibraryEntry selected) {
        int x0 = px(-8.4), x1 = px(4.2), y0 = py(9.1), y1 = py(2.5);
        g.setColor(new Color(8, 12, 9, 215));
        g.fillRoundRect(x0, y0, x1 - x0, y1 - y0, 9, 9);
        paintLowPolyFacets(g, x0, y0, x1 - x0, y1 - y0, 2);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(215, 226, 218));
        g.drawString("#", x0 + 14, y0 + 22);
        g.drawString("НАЗВАНИЕ", x0 + 42, y0 + 22);
        g.drawString("ИСПОЛНИТЕЛЬ / АВТОР", x0 + 265, y0 + 22);
        g.drawString("ВРЕМЯ", x1 - 48, y0 + 22);
        g.setColor(new Color(255, 255, 255, 42));
        g.drawLine(x0 + 10, y0 + 30, x1 - 10, y0 + 30);

        if (selected == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.setColor(new Color(205, 215, 208));
            g.drawString("Выбери элемент своей медиатеки слева", x0 + 18, y0 + 70);
            return;
        }
        if (items == null) {
            String error = SpotifyPanelMod.spotify.itemError(selected);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.setColor(new Color(205, 215, 208));
            String message = error.isEmpty() ? "Загружаю содержимое из Spotify…" : error;
            g.drawString(fit(g, message, x1 - x0 - 36), x0 + 18, y0 + 70);
            return;
        }
        if (items.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.setColor(new Color(205, 215, 208));
            g.drawString("В этом разделе пока нет доступных элементов", x0 + 18, y0 + 70);
            return;
        }

        int rows = Math.min(7, Math.max(0, items.size() - trackScroll));
        int rowH = 31;
        int startY = y0 + 57;
        for (int i = 0; i < rows; i++) {
            SpotifyClient.Item item = items.get(trackScroll + i);
            int y = startY + i * rowH;
            boolean active = snapshot.track() != null && safe(snapshot.track().title(), "").equalsIgnoreCase(safe(item.title(), ""));
            if (active) {
                g.setColor(new Color(30, 215, 96, 48));
                g.fillRoundRect(x0 + 7, y - 17, x1 - x0 - 14, rowH, 7, 7);
                g.setColor(new Color(30, 215, 96));
                g.fillRoundRect(x0 + 7, y - 17, 3, rowH, 3, 3);
            }
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            g.setColor(new Color(163, 179, 168));
            g.drawString(Integer.toString(i + 1), x0 + 15, y + 2);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.setColor(active ? new Color(30, 235, 105) : new Color(250, 252, 250));
            g.drawString(fit(g, safe(item.title(), "Без названия"), 210), x0 + 42, y - 1);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(195, 208, 198));
            g.drawString(fit(g, safe(item.artists(), ""), 130), x0 + 265, y - 1);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            g.setColor(new Color(200, 214, 204));
            String duration = formatTime(item.durationMs());
            g.drawString(duration, x1 - 48, y - 1);
            if (i < rows - 1) {
                g.setColor(new Color(255, 255, 255, 20));
                g.drawLine(x0 + 12, y + 9, x1 - 12, y + 9);
            }
        }
    }

    private static void paintNowPlaying(Graphics2D g, SpotifyClient.Snapshot s) {
        int x0 = px(4.4), x1 = px(9.8), y0 = py(12.8), y1 = py(2.5);
        g.setColor(new Color(7, 11, 8, 218));
        g.fillRoundRect(x0, y0, x1 - x0, y1 - y0, 9, 9);
        paintLowPolyFacets(g, x0, y0, x1 - x0, y1 - y0, 3);

        int coverX = px(4.6), coverY = py(12.6), coverSize = Math.min(px(9.6) - coverX, py(7.6) - coverY);
        int[] cover = s.track() == null ? null : SpotifyPanelMod.spotify.pixels(s.track().coverUrl(), 128);
        drawCover(g, cover, 128, coverX + 5, coverY + 5, coverSize - 10);
        g.setColor(new Color(30, 215, 96, 210));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRect(coverX + 4, coverY + 4, coverSize - 8, coverSize - 8);

        int textY = py(7.1);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(30, 235, 105));
        g.drawString("СЕЙЧАС ИГРАЕТ", coverX + 4, textY + 17);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
        g.setColor(Color.WHITE);
        String title = s.track() == null ? titleForInactive(s) : s.track().title();
        g.drawString(fit(g, safe(title, "Ничего не играет"), x1 - x0 - 16), coverX + 4, textY + 49);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
        g.setColor(new Color(215, 226, 218));
        String artist = s.track() == null ? statusDetail(s) : s.track().artists();
        g.drawString(fit(g, safe(artist, ""), x1 - x0 - 16), coverX + 4, textY + 76);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.setColor(new Color(165, 190, 172));
        g.drawString(s.playing() ? "● ВОСПРОИЗВЕДЕНИЕ" : "Ⅱ ПАУЗА", coverX + 4, textY + 101);
    }

    private static void paintBottomPlayer(Graphics2D g, SpotifyClient.Snapshot s) {
        int x0 = px(-9.8), x1 = px(9.8), y0 = py(2.3), y1 = py(0.2);
        g.setColor(new Color(4, 7, 5, 235));
        g.fillRoundRect(x0, y0, x1 - x0, y1 - y0, 8, 8);
        paintLowPolyFacets(g, x0, y0, x1 - x0, y1 - y0, 4);
        g.setColor(new Color(30, 215, 96, 120));
        g.drawLine(x0 + 10, y0 + 1, x1 - 10, y0 + 1);

        int leftCover = px(-9.4), leftY = y0 + 12;
        int[] cover = s.track() == null ? null : SpotifyPanelMod.spotify.pixels(s.track().coverUrl(), 48);
        drawCover(g, cover, 48, leftCover, leftY, 50);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        g.setColor(Color.WHITE);
        String bottomTitle = s.track() == null ? "Spotify" : s.track().title();
        g.drawString(fit(g, bottomTitle, 190), leftCover + 58, leftY + 22);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.setColor(new Color(205, 219, 209));
        String bottomArtist = s.track() == null ? "Подключи аккаунт" : s.track().artists();
        g.drawString(fit(g, bottomArtist, 190), leftCover + 58, leftY + 43);

        int center = (px(-4.4) + px(3.1)) / 2;
        g.setColor(new Color(177, 194, 182));
        drawPrevious(g, center - 43, y0 + 27);
        g.setColor(new Color(30, 215, 96));
        g.fillOval(center - 13, y0 + 8, 34, 34);
        g.setColor(new Color(4, 16, 8));
        if (s.playing()) {
            g.fillRoundRect(center - 4, y0 + 17, 5, 17, 2, 2);
            g.fillRoundRect(center + 5, y0 + 17, 5, 17, 2, 2);
        } else {
            g.fillPolygon(new Polygon(new int[]{center - 3, center - 3, center + 11},
                    new int[]{y0 + 16, y0 + 33, y0 + 24}, 3));
        }
        g.setColor(new Color(177, 194, 182));
        drawNext(g, center + 45, y0 + 27);

        long duration = s.track() == null ? 0 : Math.max(0, s.track().durationMs());
        long progress = duration == 0 ? 0 : Math.max(0, Math.min(duration, s.currentProgress()));
        double fraction = duration == 0 ? 0 : progress / (double) duration;
        int barX = px(-4.0), barRight = px(2.7), barY = y1 - 12;
        g.setColor(new Color(103, 122, 109, 170));
        g.fillRoundRect(barX, barY, barRight - barX, 4, 3, 3);
        g.setColor(new Color(30, 215, 96));
        g.fillRoundRect(barX, barY, (int) ((barRight - barX) * fraction), 4, 3, 3);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.setColor(new Color(170, 187, 175));
        g.drawString(formatTime(progress), barX, barY - 5);
        String total = formatTime(duration);
        g.drawString(total, barRight - g.getFontMetrics().stringWidth(total), barY - 5);

        int volX = px(3.5), volR = px(9.6);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.setColor(new Color(205, 218, 209));
        g.drawString("ГРОМКОСТЬ", volX + 8, y0 + 21);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.drawString(Math.max(0, Math.min(100, s.volume())) + "%", volR - 33, y0 + 21);
        int vBarX = volX + 8, vBarR = volR - 8, vBarY = y0 + 34;
        g.setColor(new Color(75, 94, 81));
        g.fillRoundRect(vBarX, vBarY, vBarR - vBarX, 5, 4, 4);
        g.setColor(new Color(30, 215, 96));
        g.fillRoundRect(vBarX, vBarY, (int) ((vBarR - vBarX) * Math.max(0, Math.min(100, s.volume())) / 100.0), 5, 4, 4);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(new Color(165, 184, 171));
        g.drawString("Зажми ЛКМ и тяни ползунок", vBarX, y0 + 57);
    }

    /** Subtle angular facets: a low-poly surface treatment that does not compete with labels. */
    private static void paintLowPolyFacets(Graphics2D g, int x, int y, int width, int height, int variant) {
        if (width < 24 || height < 24) return;
        Color[] colors = {
                new Color(85, 210, 135, 28),
                new Color(115, 95, 190, 27),
                new Color(255, 255, 255, 15),
                new Color(30, 215, 96, 20)
        };
        Polygon a = new Polygon(
                new int[]{x, x + width / 3, x + width / 5},
                new int[]{y, y, y + height / 2}, 3);
        Polygon b = new Polygon(
                new int[]{x + width, x + width * 2 / 3, x + width * 4 / 5},
                new int[]{y + height, y + height, y + height / 2}, 3);
        Polygon c = new Polygon(
                new int[]{x + width / 2, x + width * 3 / 4, x + width * 2 / 3},
                new int[]{y + height / 3, y + height / 5, y + height * 3 / 4}, 3);
        g.setColor(colors[Math.floorMod(variant, colors.length)]);
        g.fillPolygon(a);
        g.setColor(colors[Math.floorMod(variant + 1, colors.length)]);
        g.fillPolygon(b);
        g.setColor(colors[Math.floorMod(variant + 2, colors.length)]);
        g.fillPolygon(c);
    }

    // -------------------------------------------------------------------- model geometry

    private static void drawModelPart(VertexConsumer vc, Matrix4f matrix,
                                      double cx, double cy, double cz, double wx, double wz, double nx, double nz,
                                      ModelPart part) {
        // Blockbench elements in this design are single planes. Use their original X position
        // as a world-space layer offset and draw one face only—never extrude them into cuboids.
        double planeX = (part.x0() + part.x1()) * 0.5;
        Vec3d bottomLeft = modelPoint(planeX, part.y0(), part.z0(), cx, cy, cz, wx, wz, nx, nz);
        Vec3d bottomRight = modelPoint(planeX, part.y0(), part.z1(), cx, cy, cz, wx, wz, nx, nz);
        Vec3d topRight = modelPoint(planeX, part.y1(), part.z1(), cx, cy, cz, wx, wz, nx, nz);
        Vec3d topLeft = modelPoint(planeX, part.y1(), part.z0(), cx, cy, cz, wx, wz, nx, nz);
        // Use a fixed world-up normal. The panel is emissive/full-bright and must not
        // darken or brighten as its body-relative yaw changes.
        drawMaterialQuad(vc, matrix, bottomLeft, bottomRight, topRight, topLeft,
                part.frontColor(), 0, 1, 0);
    }

    private static Vec3d modelPoint(double localX, double localY, double localZ,
                                   double cx, double cy, double cz, double wx, double wz, double nx, double nz) {
        double depth = -localX * MODEL_DEPTH_SCALE;
        return new Vec3d(
                cx + nx * depth + wx * (localZ * MODEL_Z_SCALE),
                cy + (localY - MODEL_CENTER_Y) * MODEL_Y_SCALE,
                cz + nz * depth + wz * (localZ * MODEL_Z_SCALE));
    }

    private static void drawMaterialQuad(VertexConsumer vc, Matrix4f matrix,
                                         Vec3d a, Vec3d b, Vec3d c, Vec3d d, int argb,
                                         double nx, double ny, double nz) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, bcol = argb & 255;
        materialVertex(vc, matrix, a, 0, 1, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, b, 1, 1, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, c, 1, 0, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, d, 0, 0, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, d, 0, 0, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, c, 1, 0, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, b, 1, 1, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, a, 0, 1, r, g, bcol, nx, ny, nz);
    }

    private static void materialVertex(VertexConsumer vc, Matrix4f matrix, Vec3d p,
                                       float u, float v, int r, int g, int b,
                                       double nx, double ny, double nz) {
        vc.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                .normal((float) nx, (float) ny, (float) nz);
    }

    private static void drawOverlay(WorldRenderContext context, MatrixStack matrices,
                                    double cx, double cy, double cz, double wx, double wz, double nx, double nz) {
        if (overlayTexture == null) return;
        VertexConsumer vc = context.consumers().getBuffer(RenderLayers.entityTranslucent(OVERLAY_TEXTURE_ID));
        MatrixStack.Entry entry = matrices.peek();
        Vec3d p0 = modelPoint(MODEL_OVERLAY_X, 0, -10, cx, cy, cz, wx, wz, nx, nz);
        Vec3d p1 = modelPoint(MODEL_OVERLAY_X, 0, 10, cx, cy, cz, wx, wz, nx, nz);
        Vec3d p2 = modelPoint(MODEL_OVERLAY_X, 13, 10, cx, cy, cz, wx, wz, nx, nz);
        Vec3d p3 = modelPoint(MODEL_OVERLAY_X, 13, -10, cx, cy, cz, wx, wz, nx, nz);
        overlayVertex(vc, entry, p0, 0, 1, nx, nz);
        overlayVertex(vc, entry, p1, 1, 1, nx, nz);
        overlayVertex(vc, entry, p2, 1, 0, nx, nz);
        overlayVertex(vc, entry, p3, 0, 0, nx, nz);
        overlayVertex(vc, entry, p3, 0, 0, -nx, -nz);
        overlayVertex(vc, entry, p2, 1, 0, -nx, -nz);
        overlayVertex(vc, entry, p1, 1, 1, -nx, -nz);
        overlayVertex(vc, entry, p0, 0, 1, -nx, -nz);
    }

    private static void overlayVertex(VertexConsumer vc, MatrixStack.Entry entry, Vec3d p,
                                      float u, float v, double nx, double nz) {
        vc.vertex(entry, (float) p.x, (float) p.y, (float) p.z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                .normal(entry, 0, 1, 0);
    }

    // -------------------------------------------------------------------- click handling against the model's world-space front

    private static void handleMouse(MinecraftClient mc, Vec3d cameraPos,
                                    double cx, double cy, double cz, double wx, double wz, double nx, double nz,
                                    List<SpotifyClient.LibraryEntry> libraryEntries, SpotifyClient.LibraryEntry selected,
                                    List<SpotifyClient.Item> items) {
        boolean chatOpen = mc.currentScreen instanceof ChatScreen;
        long windowHandle = mc.getWindow().getHandle();
        boolean down = chatOpen && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean clicked = down && !wasLeftDown;
        boolean released = !down && wasLeftDown;

        // If the chat closes while dragging, stop cleanly instead of keeping a stale drag state.
        if (!chatOpen) {
            dragMode = DRAG_NONE;
            wasLeftDown = false;
            return;
        }

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        Vec3d ray = mouseRay(mc, mx, my, sw, sh);
        if (ray == null) {
            if (released) dragMode = DRAG_NONE;
            wasLeftDown = down;
            return;
        }

        Vec3d faceCenter = modelPoint(MODEL_OVERLAY_X, MODEL_CENTER_Y, 0, cx, cy, cz, wx, wz, nx, nz);
        // Unbounded intersection is used while dragging so the pointer can move slightly
        // outside the bar and still clamp to 0% / 100% without losing the drag.
        double[] uv = intersectUvUnbounded(cameraPos, ray, faceCenter, wx, wz, nx, nz, MODEL_WIDTH, MODEL_HEIGHT);
        if (uv == null) {
            if (released) dragMode = DRAG_NONE;
            wasLeftDown = down;
            return;
        }
        int tx = (int) Math.round(uv[0] * TEX_W);
        int ty = (int) Math.round(uv[1] * TEX_H);
        SpotifyClient client = SpotifyPanelMod.spotify;

        int bottomTop = py(2.3), bottomBottom = py(0.2);
        int progressX0 = px(-4.0), progressX1 = px(2.7), progressY = bottomBottom - 12;
        int volumeX0 = px(3.5) + 8, volumeX1 = px(9.6) - 8, volumeY = bottomTop + 34;

        // Continue changing the value every frame while LMB is held. Send requests at a
        // modest rate and always send the final value on release to avoid flooding Spotify.
        if (dragMode != DRAG_NONE) {
            if (down || released) updateDrag(client, tx, dragMode == DRAG_SEEK, released);
            if (released) {
                dragMode = DRAG_NONE;
                lastDragSeekMs = -1;
                lastDragVolume = -1;
            }
            wasLeftDown = down;
            return;
        }

        if (!clicked) {
            wasLeftDown = down;
            return;
        }

        // Begin dragging the progress bar.
        if (ty >= progressY - 10 && ty <= progressY + 11
                && tx >= progressX0 - 8 && tx <= progressX1 + 8) {
            SpotifyClient.Track track = client.snapshot().track();
            if (track != null && track.durationMs() > 0) {
                dragMode = DRAG_SEEK;
                lastDragSeekMs = -1;
                lastDragSendMs = 0;
                updateDrag(client, tx, true, true);
                wasLeftDown = down;
                return;
            }
        }

        // Begin dragging the volume slider.
        if (ty >= volumeY - 10 && ty <= volumeY + 12
                && tx >= volumeX0 - 8 && tx <= volumeX1 + 8) {
            if (client.snapshot().status() == SpotifyClient.Status.ACTIVE) {
                dragMode = DRAG_VOLUME;
                lastDragVolume = -1;
                lastDragSendMs = 0;
                updateDrag(client, tx, false, true);
                wasLeftDown = down;
                return;
            }
        }

        // All remaining click actions must start inside the actual panel surface.
        if (uv[0] < 0 || uv[0] > 1 || uv[1] < 0 || uv[1] > 1) {
            wasLeftDown = down;
            return;
        }

        if (tx >= px(-9.8) && tx <= px(-8.6) && ty >= py(12.8) + 45 && ty <= py(2.5)) {
            int slot = Math.max(0, (ty - (py(12.8) + 53)) / 46);
            int entryIndex = sideScroll + slot;
            if (entryIndex < libraryEntries.size() && slot < 7) {
                SpotifyClient.LibraryEntry clickedEntry = libraryEntries.get(entryIndex);
                selectedLibraryKey = clickedEntry.key();
                trackScroll = 0;
                lastRequestedLibraryKey = "";
                client.requestItems(clickedEntry, false);
                client.playLibraryEntry(clickedEntry, SpotifyPanelMod::say);
            }
            wasLeftDown = down;
            return;
        }

        // Content rows inside the selected library section.
        if (selected != null && items != null && tx >= px(-8.4) && tx <= px(4.2)
                && ty >= py(9.1) + 48 && ty <= py(2.5)) {
            int row = (ty - (py(9.1) + 57)) / 31;
            int itemIndex = trackScroll + row;
            if (row >= 0 && itemIndex < Math.min(items.size(), trackScroll + 7)) {
                client.playLibraryItem(selected, items.get(itemIndex), SpotifyPanelMod::say);
                wasLeftDown = down;
                return;
            }
        }

        // Bottom transport controls (progress and volume are handled by drag above).
        if (ty >= bottomTop && ty <= bottomBottom) {
            int center = (px(-4.4) + px(3.1)) / 2;
            if (ty <= bottomTop + 46 && tx >= center - 70 && tx <= center + 70) {
                if (tx < center - 22) client.previous(SpotifyPanelMod::say);
                else if (tx > center + 25) client.next(SpotifyPanelMod::say);
                else client.togglePlay(SpotifyPanelMod::say);
                wasLeftDown = down;
                return;
            }
        }
        if (tx >= px(4.4) && ty >= py(7.4) && ty <= py(2.7)) {
            client.togglePlay(SpotifyPanelMod::say);
        }
        wasLeftDown = down;
    }

    private static void updateDrag(SpotifyClient client, int textureX, boolean seek, boolean force) {
        long now = System.currentTimeMillis();
        if (seek) {
            SpotifyClient.Snapshot snapshot = client.snapshot();
            SpotifyClient.Track track = snapshot.track();
            if (track == null || track.durationMs() <= 0) return;
            int x0 = px(-4.0), x1 = px(2.7);
            double fraction = Math.max(0.0, Math.min(1.0, (textureX - x0) / (double) (x1 - x0)));
            long target = Math.max(0, Math.min(track.durationMs(), Math.round(track.durationMs() * fraction)));
            if (target != lastDragSeekMs && (force || now - lastDragSendMs >= 250)) {
                client.seek(target, SpotifyPanelMod::say);
                lastDragSeekMs = target;
                lastDragSendMs = now;
            }
        } else {
            int x0 = px(3.5) + 8, x1 = px(9.6) - 8;
            double fraction = Math.max(0.0, Math.min(1.0, (textureX - x0) / (double) (x1 - x0)));
            int target = (int) Math.round(fraction * 100.0);
            if (target != lastDragVolume && (force || now - lastDragSendMs >= 180)) {
                client.volume(target, SpotifyPanelMod::say);
                lastDragVolume = target;
                lastDragSendMs = now;
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

    private static double[] intersectUv(Vec3d origin, Vec3d direction, Vec3d planeCenter,
                                        double wx, double wz, double nx, double nz,
                                        double width, double height) {
        double[] uv = intersectUvUnbounded(origin, direction, planeCenter, wx, wz, nx, nz, width, height);
        if (uv == null || uv[0] < 0 || uv[0] > 1 || uv[1] < 0 || uv[1] > 1) return null;
        return uv;
    }

    private static double[] intersectUvUnbounded(Vec3d origin, Vec3d direction, Vec3d planeCenter,
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
        return new double[]{localX / width + 0.5, 0.5 - localY / height};
    }

    // -------------------------------------------------------------------- drawing helpers

    private static void prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static void drawCover(Graphics2D g, int[] pixels, int size, int x, int y, int target) {
        if (target <= 0) return;
        if (pixels != null && pixels.length >= size * size) {
            BufferedImage cover = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            cover.setRGB(0, 0, size, size, pixels, 0, size);
            g.drawImage(cover, x, y, target, target, null);
        } else {
            g.setColor(new Color(17, 34, 23, 230));
            g.fillRoundRect(x, y, target, target, Math.max(5, target / 7), Math.max(5, target / 7));
            g.setColor(new Color(30, 215, 96, 190));
            g.setStroke(new BasicStroke(Math.max(1, target / 32f)));
            for (int i = 0; i < 7; i++) {
                int bar = (int) (target * (0.18 + 0.6 * Math.abs(Math.sin(i * 1.8))));
                int bx = x + target / 5 + i * target / 12;
                g.drawLine(bx, y + target / 2 - bar / 2, bx, y + target / 2 + bar / 2);
            }
        }
    }

    private static int px(double modelZ) {
        return (int) Math.round((modelZ + 10.0) / 20.0 * TEX_W);
    }

    private static int py(double modelY) {
        return (int) Math.round((13.0 - modelY) / 13.0 * TEX_H);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String fit(Graphics2D g, String text, int maxWidth) {
        if (text == null) return "";
        String result = text.replace('\n', ' ').trim();
        while (!result.isEmpty() && g.getFontMetrics().stringWidth(result) > maxWidth) {
            if (result.length() <= 3) return result;
            result = result.substring(0, result.length() - 2).stripTrailing() + "…";
        }
        return result;
    }

    private static void drawPrevious(Graphics2D g, int x, int y) {
        g.fillRect(x - 8, y - 10, 3, 20);
        g.fillPolygon(new Polygon(new int[]{x + 8, x + 8, x - 6}, new int[]{y - 10, y + 10, y}, 3));
    }

    private static void drawNext(Graphics2D g, int x, int y) {
        g.fillRect(x + 5, y - 10, 3, 20);
        g.fillPolygon(new Polygon(new int[]{x - 8, x - 8, x + 6}, new int[]{y - 10, y + 10, y}, 3));
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String titleForInactive(SpotifyClient.Snapshot s) {
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) return "Подключи Spotify";
        if (s.status() == SpotifyClient.Status.LOGGED_OUT) return "Нужен вход в Spotify";
        if (s.status() == SpotifyClient.Status.ERROR) return "Воспроизведение недоступно";
        return "Ничего не играет";
    }

    private static String statusDetail(SpotifyClient.Snapshot s) {
        if (s.status() == SpotifyClient.Status.NO_CLIENT_ID) return "/spotify setup <client_id>";
        if (s.status() == SpotifyClient.Status.LOGGED_OUT) return "/spotify login";
        if (s.status() == SpotifyClient.Status.ERROR) return s.error();
        return "Запусти музыку в Spotify";
    }

}
