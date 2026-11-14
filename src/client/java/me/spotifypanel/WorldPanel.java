package me.spotifypanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GpuSampler;
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
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renders the user's flat, layered Blockbench Spotify panel in world space.
 * The .bbmodel provides thin planar parts; a crisp antialiased dynamic texture is
 * projected onto the front to show live Spotify data. Pixel styling is reserved for frames and controls.
 */
public final class WorldPanel {
    public static final boolean ENABLED = true;

    // Keep all UI layout and pointer coordinates in this logical space. The
    // backing texture uses 1.5x supersampling so glyphs and small icons remain
    // sharper than the original 1x raster without the previous 4x pixel cost.
    private static final int UI_W = 768;
    private static final int UI_H = 500;
    // 1.5x supersampling keeps text/icons sharper than the original 1x canvas,
    // while avoiding the 4x pixel cost of the previous 2x backing texture.
    private static final double RENDER_SCALE = 1.5;
    private static final int TEX_W = (int) Math.round(UI_W * RENDER_SCALE);
    private static final int TEX_H = (int) Math.round(UI_H * RENDER_SCALE);
    // The updated Blockbench model spans 22 x 14 units. Keep the same world scale
    // per model unit as the previous model so the interface does not change physical size.
    private static final double MODEL_WIDTH = 1.82 * 22.0 / 20.0;
    // Previous Y scale was 1.20 / 13.0 world units per Blockbench unit.
    // The new 14-unit body is therefore slightly shorter, matching the supplied geometry.
    // The model is intentionally a thin, layered plane, not a set of extruded boxes.
    private static final double MODEL_Z_SCALE = MODEL_WIDTH / 22.0;
    private static final double MODEL_DEPTH_SCALE = MODEL_WIDTH / 22.0;
    private static final double MODEL_Y_SCALE = 1.20 / 13.0;
    private static final double MODEL_HEIGHT = MODEL_Y_SCALE * 14.0;
    private static final double MODEL_OVERLAY_X = -0.82;
    private static final double MODEL_CENTER_Y = 7.0;

    // World anchor follows the player's interpolated coordinates, exactly one block ahead.
    // It is not camera-relative and is not rendered as a child/attachment of the player.
    private static final double AHEAD = 1.0;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    private static final Identifier MATERIAL_TEXTURE_ID = Identifier.of("spotify-panel", "textures/misc/panel_material.png");
    private static final Identifier OVERLAY_TEXTURE_ID = Identifier.of("spotify-panel", "dynamic/model_overlay");
    private static NativeImageBackedTexture overlayTexture;
    // Overlay is retained in memory and only dirty UI regions are repainted/uploaded.
    // The 3D quad still renders every world frame; this cache only controls texture work.
    private static boolean overlayInitialized;
    private static List<SpotifyClient.LibraryEntry> paintedLibraryRef;
    private static List<SpotifyClient.Item> paintedItemsRef;
    private static List<SpotifyClient.Item> paintedSearchResultsRef;
    private static String paintedSelectedKey = "";
    private static String paintedSearchText = "";
    private static String paintedSearchError = "";
    private static String paintedTrackUri = "";
    private static String paintedTrackTitle = "";
    private static String paintedTrackArtists = "";
    private static String paintedTrackCover = "";
    private static long paintedTrackDuration;
    private static SpotifyClient.Status paintedStatus;
    private static boolean paintedSearchMode;
    private static boolean paintedSearchFocused;
    private static boolean paintedSearchLoading;
    private static boolean paintedSearchPreparing;
    private static boolean paintedPlaying;
    private static int paintedVolume = -1;
    private static long paintedProgressSecond = Long.MIN_VALUE;
    private static long lastProgressRegionPaintNs;
    private static long lastVolumeRegionPaintNs;
    private static long lastScrollRegionPaintNs;
    private static long lastHighlightRegionPaintNs;
    private static long paintedCaretPhase = Long.MIN_VALUE;
    private static double paintedSideScroll = Double.NaN;
    private static double paintedTrackScroll = Double.NaN;
    private static double paintedViewTransition = Double.NaN;
    private static int paintedHighlightSignature = Integer.MIN_VALUE;

    private static final class HighQualityOverlayTexture extends NativeImageBackedTexture {
        private final GpuSampler panelSampler;

        private HighQualityOverlayTexture(int width, int height) {
            super("Spotify Blockbench live overlay", width, height, false);
            int supportedAnisotropy = RenderSystem.getDevice().getMaxSupportedAnisotropy();
            int anisotropy = Math.max(1, Math.min(2, supportedAnisotropy));
            this.panelSampler = RenderSystem.getDevice().createSampler(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    anisotropy,
                    OptionalDouble.of(0.0)
            );
            this.sampler = panelSampler;
        }

        @Override
        public void close() {
            super.close();
            panelSampler.close();
        }
    }

    // Reuse the supersampled backing image instead of allocating a new image per refresh.
    // Rendering happens on the client render thread, so these buffers are not shared concurrently.
    private static final BufferedImage OVERLAY_IMAGE = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
    // Direct RGBA upload buffer avoids calling NativeImage.setColorArgb once per pixel.
    // Minecraft 1.21.11's CommandEncoder accepts a ByteBuffer for texture uploads.
    private static final ByteBuffer OVERLAY_UPLOAD_BUFFER = ByteBuffer
            .allocateDirect(TEX_W * TEX_H * Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private static final IntBuffer OVERLAY_UPLOAD_INTS = OVERLAY_UPLOAD_BUFFER.asIntBuffer();
    private static final int[] OVERLAY_RGBA_PIXELS = new int[TEX_W * TEX_H];
    private static final Map<Integer, BufferedImage> COVER_IMAGE_BUFFERS = new HashMap<>();
    // Three smooth, pure-white Spotify-style speaker sprites for muted, low and high volume.
    private static final BufferedImage VOLUME_ICON_MUTED = loadVolumeIcon(
            "/assets/spotify-panel/textures/gui/volume_speaker_muted.png");
    private static final BufferedImage VOLUME_ICON_LOW = loadVolumeIcon(
            "/assets/spotify-panel/textures/gui/volume_speaker_low.png");
    private static final BufferedImage VOLUME_ICON_HIGH = loadVolumeIcon(
            "/assets/spotify-panel/textures/gui/volume_speaker_high.png");

    private static final List<ModelPart> MODEL_PARTS = loadBlockbenchModel();
    private static boolean wasLeftDown;
    private static boolean panelWasVisible;
    private static boolean bodyYawInitialized;
    private static double smoothedBodyYaw;
    private static long lastBodyYawUpdateNs;
    private static final int DRAG_NONE = 0, DRAG_SEEK = 1, DRAG_VOLUME = 2;
    private static int dragMode = DRAG_NONE;
    private static long lastDragSeekMs = -1;
    private static int lastDragVolume = -1;
    private static String selectedLibraryKey = "";
    private static String lastRequestedLibraryKey = "";
    private static long lastItemsRequestNs;
    // Wheel input changes integer targets; the rendered lists ease toward them at frame rate.
    private static int sideScroll, trackScroll;
    private static double sideScrollVisual, trackScrollVisual;
    private static boolean scrollVisualInitialized;
    private static long lastUiAnimationNs;
    private static double viewTransition = 1.0;
    private static String lastAnimatedLibraryKey = "";
    private static double displayedVolume;
    private static boolean volumeVisualInitialized;
    private static double displayedProgressMs;
    private static boolean progressVisualInitialized;
    private static String displayedProgressTrackUri = "";
    private static final Map<String, Double> playingHighlightAlpha = new HashMap<>();
    // Debounce playback URI changes: brief empty/stale API snapshots during track transitions
    // must not make the green playing-row highlight flicker or disappear.
    private static String stablePlayingTrackUri = "";
    private static String candidatePlayingTrackUri = "";
    private static long candidatePlayingTrackSinceNs;
    private static volatile int scrollArea;
    private static volatile double wheelDelta;
    private static boolean scrollHooked;
    private static GLFWScrollCallback previousScroll;
    private static long inputHookWindow;
    private static GLFWCharCallback previousChar;
    private static GLFWKeyCallback previousKey;

    // Search is owned by the world-space panel; only its API call runs asynchronously.
    private static String searchText = "";
    private static boolean searchFocused;
    private static boolean searchLoading;
    private static String searchError = "";
    private static List<SpotifyClient.Item> searchResults = List.of();
    private static long searchGeneration;
    private static long searchDebounceAtMs;
    // The search results now start immediately below the search housing, covering
    // the otherwise unused playlist-info surface. The taller area fits eight rows.
    private static final double SEARCH_RESULTS_TOP = 12.5;
    private static final int SEARCH_RESULT_ROW_HEIGHT = 35;
    private static final int SEARCH_VISIBLE_ROWS = 8;

    private WorldPanel() {}

    private record ModelPart(String name, double x0, double y0, double z0,
                             double x1, double y1, double z1, int frontColor, int sideColor) {}

    private record UiRect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
    }

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
            case "search_corpus" -> new int[]{0xFF080C09, 0xFF26332B};
            case "search" -> new int[]{0xFF1B2B21, 0xFF54765E};
            default -> new int[]{0xFF172019, 0xFF344238};
        };
    }

    private static void draw(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!SpotifyPanelMod.config.visible || mc.player == null || mc.world == null) {
            panelWasVisible = false;
            if (SpotifyPanelMod.spotify != null) SpotifyPanelMod.spotify.setVisibleSelection(null);
            wasLeftDown = false;
            dragMode = DRAG_NONE;
            searchFocused = false;
            // Do not keep consuming the game's mouse wheel while the panel is hidden or unavailable.
            scrollArea = 0;
            wheelDelta = 0;
            return;
        }
        MatrixStack matrices = context.matrices();
        if (matrices == null || context.consumers() == null) {
            scrollArea = 0;
            wheelDelta = 0;
            return;
        }

        ClientPlayerEntity player = mc.player;
        Vec3d cameraPos = context.worldState().cameraRenderState.pos;
        float tickDelta = mc.getRenderTickCounter().getTickProgress(false);
        hookScroll(mc.getWindow().getHandle());
        hookSearchInput(mc.getWindow().getHandle());
        if (!(mc.currentScreen instanceof ChatScreen)) searchFocused = false;
        runPendingSearch(mc);
        // Position is recalculated from the player's interpolated WORLD coordinates every frame.
        // Only the body direction is used to find the point one block ahead; camera/head yaw is ignored.
        Vec3d body = player.getLerpedPos(tickDelta);
        long now = System.nanoTime();
        double targetBodyYaw = player.getBodyYaw();
        if (!bodyYawInitialized) {
            smoothedBodyYaw = targetBodyYaw;
            bodyYawInitialized = true;
        } else {
            double yawDt = Math.min(0.1, Math.max(0.0, (now - lastBodyYawUpdateNs) / 1_000_000_000.0));
            double yawError = ((targetBodyYaw - smoothedBodyYaw + 540.0) % 360.0) - 180.0;
            smoothedBodyYaw += yawError * (1.0 - Math.exp(-yawDt * 24.0));
        }
        lastBodyYawUpdateNs = now;

        double bodyRad = Math.toRadians(smoothedBodyYaw);
        double fx = -Math.sin(bodyRad), fz = Math.cos(bodyRad);
        double rx = -Math.cos(bodyRad), rz = -Math.sin(bodyRad);
        double cx = body.x + fx * AHEAD;
        double cz = body.z + fz * AHEAD;
        // The flat panel faces back toward the player, with no side offset or extra tilt.
        double wx = rx;
        double wz = rz;
        double nx = -fx;
        double nz = -fz;
        double centerY = body.y + 1.28;

        List<SpotifyClient.LibraryEntry> libraryEntries = SpotifyPanelMod.spotify.library();
        SpotifyClient.LibraryEntry selected = selectedLibraryEntry(libraryEntries);
        SpotifyPanelMod.spotify.setVisibleSelection(selected);
        boolean panelJustOpened = !panelWasVisible;
        if (panelJustOpened) {
            panelWasVisible = true;
            // On reopening, revalidate the library and the currently selected rows immediately.
            lastRequestedLibraryKey = "";
            lastItemsRequestNs = 0;
            SpotifyPanelMod.spotify.onPanelOpened();
        }
        if (selected != null && !selected.key().equals(lastAnimatedLibraryKey)) {
            lastAnimatedLibraryKey = selected.key();
            trackScroll = 0;
            trackScrollVisual = 0.0;
            viewTransition = 0.0;
        }
        updateScrollArea(mc, cameraPos, cx, centerY, cz, wx, wz, nx, nz);
        boolean searchMode = searchMode();
        applyWheel(libraryEntries.size(), searchMode ? searchResults.size()
                : (selected == null ? 0 : safeItemCount(selected.key())),
                searchMode ? SEARCH_VISIBLE_ROWS : 5);
        List<SpotifyClient.Item> items = null;
        if (selected != null) {
            boolean selectionChanged = !selected.key().equals(lastRequestedLibraryKey);
            if (selectionChanged || now - lastItemsRequestNs > 120_000_000_000L) {
                lastRequestedLibraryKey = selected.key();
                lastItemsRequestNs = now;
                // Switching sections triggers a lightweight metadata revalidation.
                // Playlist snapshot/ETag checks avoid re-downloading unchanged contents.
                if (selectionChanged && !panelJustOpened) {
                    SpotifyPanelMod.spotify.onLibrarySectionOpened(selected);
                }
                SpotifyPanelMod.spotify.requestItems(selected, selectionChanged);
            }
            items = SpotifyPanelMod.spotify.items(selected.key());
        }
        sideScroll = Math.min(sideScroll, Math.max(0, libraryEntries.size() - 7));
        if (searchMode) trackScroll = Math.min(trackScroll, Math.max(0, searchResults.size() - SEARCH_VISIBLE_ROWS));
        else if (items != null) trackScroll = Math.min(trackScroll, Math.max(0, items.size() - 5));
        SpotifyClient.Snapshot snapshot = SpotifyPanelMod.spotify.snapshot();
        updateScrollAnimation(now, snapshot, items);

        ensureOverlayTexture(mc);
        refreshOverlayIncrementally(snapshot, libraryEntries, selected, items, panelJustOpened, now);

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Render the 15 Blockbench elements as thin, world-space planes. Their small X offsets
        // are preserved as subtle layer separation, but no thickness or side faces are invented.
        VertexConsumer material = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(MATERIAL_TEXTURE_ID));
        for (ModelPart part : MODEL_PARTS) {
            drawModelPart(material, matrix, cx, centerY, cz, wx, wz, nx, nz, part);
        }
        // Reuse the library-icon plane for each visible library item. Every icon remains flat;
        // the only depth is the subtle layer offset from its original Blockbench X coordinate.
        int firstLibraryIcon = Math.max(0, (int) Math.floor(sideScrollVisual));
        double iconFraction = sideScrollVisual - firstLibraryIcon;
        int visibleLibraryIcons = Math.min(8, Math.max(0, libraryEntries.size() - firstLibraryIcon));
        int[] iconColors = palette("playlist_icons");
        for (int i = 0; i < visibleLibraryIcons; i++) {
            double top = 11.5 - (i - iconFraction) * 1.2;
            ModelPart iconPart = new ModelPart("playlist_icon_" + i,
                    -0.2, top - 0.9, -10.7,
                    -0.2, top, -9.7,
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
            // Wheel is reserved for scrolling the library and track lists only while
            // the panel is visible in a world with chat open. Otherwise pass it to Minecraft.
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean panelInputActive = SpotifyPanelMod.config.visible && mc.player != null
                    && mc.world != null && mc.currentScreen instanceof ChatScreen;
            if (panelInputActive && scrollArea != 0) wheelDelta += yOffset;
            else if (previousScroll != null) previousScroll.invoke(window, xOffset, yOffset);
        });
    }

    /**
     * The panel is a world-space surface, not a Screen widget. While its search housing
     * is focused, route GLFW character/edit keys here and leave Minecraft's chat input
     * untouched. When it is not focused, the original Minecraft callbacks are invoked.
     */
    private static void hookSearchInput(long handle) {
        if (inputHookWindow == handle) return;
        inputHookWindow = handle;
        previousChar = GLFW.glfwSetCharCallback(handle, (window, codepoint) -> {
            if (searchFocused) {
                if (Character.isValidCodePoint(codepoint) && !Character.isISOControl(codepoint)) {
                    String value = searchText + new String(Character.toChars(codepoint));
                    if (value.codePointCount(0, value.length()) <= 96) setSearchText(value);
                }
                return;
            }
            if (previousChar != null) previousChar.invoke(window, codepoint);
        });
        previousKey = GLFW.glfwSetKeyCallback(handle, (window, key, scanCode, action, mods) -> {
            if (searchFocused) {
                if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
                    boolean control = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
                    if (key == GLFW.GLFW_KEY_BACKSPACE) {
                        if (!searchText.isEmpty()) {
                            int end = searchText.offsetByCodePoints(searchText.length(), -1);
                            setSearchText(searchText.substring(0, end));
                        }
                    } else if (key == GLFW.GLFW_KEY_DELETE || (control && key == GLFW.GLFW_KEY_A)) {
                        setSearchText("");
                    } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                            || key == GLFW.GLFW_KEY_ESCAPE) {
                        searchFocused = false;
                    }
                }
                // Keep keyboard shortcuts and navigation out of the Minecraft chat while
                // the search field owns focus; text itself arrives through the char callback.
                return;
            }
            if (previousKey != null) previousKey.invoke(window, key, scanCode, action, mods);
        });
    }

    private static void setSearchText(String value) {
        searchText = value == null ? "" : value;
        searchGeneration++;
        searchResults = List.of();
        searchError = "";
        searchLoading = false;
        trackScroll = 0;
        trackScrollVisual = 0.0;
        searchDebounceAtMs = searchText.isBlank() ? 0 : System.currentTimeMillis() + 320;
    }

    private static boolean searchMode() {
        return !searchText.isBlank();
    }

    private static void runPendingSearch(MinecraftClient mc) {
        if (searchDebounceAtMs == 0 || System.currentTimeMillis() < searchDebounceAtMs) return;
        String query = searchText.trim();
        long generation = searchGeneration;
        searchDebounceAtMs = 0;
        if (query.isEmpty()) return;
        searchLoading = true;
        searchError = "";
        SpotifyPanelMod.spotify.searchTracks(query).whenComplete((result, failure) -> mc.execute(() -> {
            if (generation != searchGeneration || !query.equals(searchText.trim())) return;
            searchLoading = false;
            if (failure != null) {
                searchResults = List.of();
                searchError = "Поиск Spotify: " + (failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage());
            } else if (result == null) {
                searchResults = List.of();
                searchError = "Spotify не вернул результат поиска.";
            } else {
                searchResults = result.items() == null ? List.of() : result.items();
                searchError = result.error() == null ? "" : result.error();
            }
            trackScroll = 0;
            trackScrollVisual = 0.0;
        }));
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
        int tx = (int) (uv[0] * UI_W);
        int ty = (int) (uv[1] * UI_H);
        // 1 = library sidebar, 2 = track table, 3 = any other part of the panel.
        scrollArea = 3;
        if (tx >= px(-10.8) && tx <= px(-9.6) && ty >= py(12.7) && ty <= py(2.4)) scrollArea = 1;
        else if (tx >= px(-9.4) && tx <= px(5.2)
                && ty >= py(searchMode() ? SEARCH_RESULTS_TOP : 9.2) && ty <= py(2.4)) scrollArea = 2;
    }

    private static void applyWheel(int playlistCount, int itemCount, int visibleTrackRows) {
        // Unmodified wheel only scrolls the library and selected content list.
        while (Math.abs(wheelDelta) >= 1.0) {
            int direction = wheelDelta > 0 ? -1 : 1;
            if (scrollArea == 1) sideScroll = Math.max(0, Math.min(Math.max(0, playlistCount - 7), sideScroll + direction));
            else if (scrollArea == 2) trackScroll = Math.max(0, Math.min(Math.max(0, itemCount - visibleTrackRows), trackScroll + direction));
            wheelDelta -= Math.signum(wheelDelta);
        }
    }

    private static void updateScrollAnimation(long now, SpotifyClient.Snapshot snapshot,
                                              List<SpotifyClient.Item> items) {
        if (!scrollVisualInitialized) {
            sideScrollVisual = sideScroll;
            trackScrollVisual = trackScroll;
            displayedVolume = snapshot.volume();
            displayedProgressMs = snapshot.currentProgress();
            displayedProgressTrackUri = snapshot.track() == null ? "" : snapshot.track().uri();
            volumeVisualInitialized = true;
            progressVisualInitialized = true;
            scrollVisualInitialized = true;
            lastUiAnimationNs = now;
            return;
        }
        double dt = Math.max(0.0, Math.min(0.1, (now - lastUiAnimationNs) / 1_000_000_000.0));
        lastUiAnimationNs = now;

        // Ease wheel movement over a longer interval: the target changes by rows,
        // while the rendered offset moves continuously at frame rate.
        double scrollEase = 1.0 - Math.exp(-dt * 10.0);
        sideScrollVisual += (sideScroll - sideScrollVisual) * scrollEase;
        trackScrollVisual += (trackScroll - trackScrollVisual) * scrollEase;
        if (Math.abs(sideScroll - sideScrollVisual) < 0.002) sideScrollVisual = sideScroll;
        if (Math.abs(trackScroll - trackScrollVisual) < 0.002) trackScrollVisual = trackScroll;

        // Smooth the visible volume and playhead independently from HTTP polling.
        // The volume control therefore eases rather than jumping between stale snapshots.
        double controlEase = 1.0 - Math.exp(-dt * 20.0);
        displayedVolume += (snapshot.volume() - displayedVolume) * controlEase;
        if (Math.abs(snapshot.volume() - displayedVolume) < 0.08) displayedVolume = snapshot.volume();

        String trackUri = snapshot.track() == null ? "" : snapshot.track().uri();
        String optimisticUri = SpotifyPanelMod.spotify.optimisticTrackUri();
        if (!optimisticUri.isEmpty()) trackUri = optimisticUri;
        long targetProgress = dragMode == DRAG_SEEK && lastDragSeekMs >= 0
                ? lastDragSeekMs : snapshot.currentProgress();
        if (!progressVisualInitialized || !trackUri.equals(displayedProgressTrackUri)) {
            displayedProgressMs = targetProgress;
            displayedProgressTrackUri = trackUri;
            progressVisualInitialized = true;
        } else {
            double playheadEase = 1.0 - Math.exp(-dt * 24.0);
            displayedProgressMs += (targetProgress - displayedProgressMs) * playheadEase;
            if (Math.abs(targetProgress - displayedProgressMs) < 12.0) displayedProgressMs = targetProgress;
        }

        // Smooth and stabilize the confirmed Spotify track URI before animating the row.
        // A brief 204/empty snapshot is common around track transitions; keep the previous
        // URI during that gap, and accept a new non-empty URI after it remains stable briefly.
        if (!trackUri.equals(candidatePlayingTrackUri)) {
            candidatePlayingTrackUri = trackUri;
            candidatePlayingTrackSinceNs = now;
        }
        long candidateAgeNs = Math.max(0L, now - candidatePlayingTrackSinceNs);
        // SpotifyClient already rejects stale /me/player responses after a newer click.
        // Accept a real non-empty track URI immediately so the row highlight changes in
        // the same render update as the now-playing cover/title. Keep only the longer
        // empty-state grace period to avoid flicker during Spotify's brief 204 transitions.
        long settleNs = candidatePlayingTrackUri.isEmpty() ? 2_500_000_000L : 0L;
        if (candidateAgeNs >= settleNs) {
            stablePlayingTrackUri = candidatePlayingTrackUri;
        }

        // Fade the playing-row accent between confirmed track URIs. If the item list is
        // temporarily loading, preserve the current alpha instead of fading the highlight
        // away and making it pop back in when the same list finishes loading.
        String playingUri = !optimisticUri.isEmpty() ? optimisticUri : stablePlayingTrackUri;
        if (items != null) {
            boolean playingTrackVisible = false;
            if (!playingUri.isEmpty()) {
                for (SpotifyClient.Item item : items) {
                    if (playingUri.equals(item.uri())) {
                        playingTrackVisible = true;
                        break;
                    }
                }
            }
            // Start with a faint visible accent on the exact frame the now-playing
            // snapshot changes, then finish the fade quickly. This keeps the row and
            // the cover/title update visually in sync instead of making the highlight lag.
            if (playingTrackVisible) playingHighlightAlpha.putIfAbsent(playingUri, 0.10);
            double highlightEase = 1.0 - Math.exp(-dt * 22.0);
            Iterator<Map.Entry<String, Double>> highlightIterator = playingHighlightAlpha.entrySet().iterator();
            while (highlightIterator.hasNext()) {
                Map.Entry<String, Double> entry = highlightIterator.next();
                double target = playingTrackVisible && entry.getKey().equals(playingUri) ? 1.0 : 0.0;
                double next = entry.getValue() + (target - entry.getValue()) * highlightEase;
                if (Math.abs(target - next) < 0.004) next = target;
                if (next <= 0.004 && target == 0.0) highlightIterator.remove();
                else entry.setValue(next);
            }
        }

        // Newly selected albums/playlists ease in with a subtle upward settle.
        double transitionEase = 1.0 - Math.exp(-dt * 6.0);
        viewTransition += (1.0 - viewTransition) * transitionEase;
        if (viewTransition > 0.995) viewTransition = 1.0;
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
        overlayTexture = new HighQualityOverlayTexture(TEX_W, TEX_H);
        mc.getTextureManager().registerTexture(OVERLAY_TEXTURE_ID, overlayTexture);
        overlayInitialized = false;
    }

    private static void upload(NativeImageBackedTexture texture, BufferedImage image) {
        if (texture.getGlTexture() == null) return;

        int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        // NativeImage.Format.RGBA is byte-ordered RGBA on little-endian systems.
        // Convert Java2D's 0xAARRGGBB pixels to 0xAABBGGRR in a reusable heap
        // array, then copy the whole array into the direct upload buffer in one
        // bulk operation. This is used for the initial/full refresh; later changes
        // use uploadRegion() to transfer only dirty rectangles.
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            OVERLAY_RGBA_PIXELS[i] = (pixel & 0xFF00FF00)
                    | ((pixel & 0x00FF0000) >>> 16)
                    | ((pixel & 0x000000FF) << 16);
        }

        OVERLAY_UPLOAD_INTS.clear();
        OVERLAY_UPLOAD_INTS.put(OVERLAY_RGBA_PIXELS);
        OVERLAY_UPLOAD_BUFFER.position(0);
        OVERLAY_UPLOAD_BUFFER.limit(TEX_W * TEX_H * Integer.BYTES);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture.getGlTexture(), OVERLAY_UPLOAD_BUFFER, NativeImage.Format.RGBA,
                0, 0, 0, 0, TEX_W, TEX_H
        );
    }

    /**
     * Detects which visual groups changed and repaints only those groups.
     * Static regions remain in OVERLAY_IMAGE and are never regenerated for playhead ticks.
     */
    private static void refreshOverlayIncrementally(SpotifyClient.Snapshot snapshot,
                                                     List<SpotifyClient.LibraryEntry> libraryEntries,
                                                     SpotifyClient.LibraryEntry selected,
                                                     List<SpotifyClient.Item> items,
                                                     boolean panelJustOpened, long now) {
        String selectedKey = selected == null ? "" : selected.key();
        String trackUri = snapshot.track() == null ? "" : safe(snapshot.track().uri(), "");
        String trackTitle = snapshot.track() == null ? titleForInactive(snapshot)
                : safe(snapshot.track().title(), "");
        String trackArtists = snapshot.track() == null ? statusDetail(snapshot)
                : safe(snapshot.track().artists(), "");
        String trackCover = snapshot.track() == null ? "" : safe(snapshot.track().coverUrl(), "");
        long trackDuration = snapshot.track() == null ? 0L : snapshot.track().durationMs();
        boolean isSearchMode = searchMode();

        if (!overlayInitialized || panelJustOpened) {
            // Texture allocation can complete one render tick after registration.
            // Do not mark the cache initialized until the full base image was uploaded.
            if (overlayTexture.getGlTexture() == null) return;
            upload(overlayTexture, paintOverlay(snapshot, libraryEntries, selected, items));
            overlayInitialized = true;
            rememberOverlayState(snapshot, libraryEntries, selectedKey, items, isSearchMode,
                    trackUri, trackTitle, trackArtists, trackCover, trackDuration, now);
            paintedVolume = (int) Math.round(Math.max(0.0, Math.min(100.0, displayedVolume)));
            paintedProgressSecond = Math.max(0L, Math.round(displayedProgressMs) / 1000L);
            paintedSideScroll = sideScrollVisual;
            paintedTrackScroll = trackScrollVisual;
            paintedViewTransition = viewTransition;
            paintedHighlightSignature = highlightSignature();
            paintedCaretPhase = searchFocused ? System.currentTimeMillis() / 500L : Long.MIN_VALUE;
            lastProgressRegionPaintNs = now;
            lastVolumeRegionPaintNs = now;
            lastScrollRegionPaintNs = now;
            lastHighlightRegionPaintNs = now;
            return;
        }

        boolean libraryChanged = libraryEntries != paintedLibraryRef;
        boolean selectionChanged = !selectedKey.equals(paintedSelectedKey);
        boolean itemsChanged = items != paintedItemsRef;
        boolean modeChanged = isSearchMode != paintedSearchMode;
        boolean searchTextChanged = !searchText.equals(paintedSearchText);
        boolean searchFocusChanged = searchFocused != paintedSearchFocused;
        boolean searchLoadingChanged = searchLoading != paintedSearchLoading;
        boolean searchPreparing = searchDebounceAtMs > 0;
        boolean searchPreparingChanged = searchPreparing != paintedSearchPreparing;
        boolean searchErrorChanged = !searchError.equals(paintedSearchError);
        boolean searchResultsChanged = searchResults != paintedSearchResultsRef;
        boolean trackChanged = !trackUri.equals(paintedTrackUri)
                || !trackTitle.equals(paintedTrackTitle)
                || !trackArtists.equals(paintedTrackArtists)
                || !trackCover.equals(paintedTrackCover)
                || trackDuration != paintedTrackDuration;
        boolean statusChanged = snapshot.status() != paintedStatus;
        boolean playingChanged = snapshot.playing() != paintedPlaying;

        boolean contentChanged = !isSearchMode && (selectionChanged || itemsChanged || libraryChanged);
        boolean resultsChanged = modeChanged || (isSearchMode && (searchTextChanged || searchLoadingChanged
                || searchPreparingChanged || searchErrorChanged || searchResultsChanged));

        if (libraryChanged || selectionChanged) {
            repaintRegion(overlayTexture, sidebarRect(), g -> paintSidebar(g, libraryEntries, selected));
            paintedSideScroll = sideScrollVisual;
        }

        if (modeChanged || contentChanged || resultsChanged) {
            repaintRegion(overlayTexture, contentRect(), g -> {
                if (isSearchMode) {
                    paintSearchResults(g, snapshot);
                } else {
                    paintHeaderAndTrackWithTransition(g, snapshot, items, selected);
                }
            });
            paintedViewTransition = viewTransition;
            paintedTrackScroll = trackScrollVisual;
            paintedHighlightSignature = highlightSignature();
            lastScrollRegionPaintNs = now;
            lastHighlightRegionPaintNs = now;
        } else if (isSearchMode && Math.abs(trackScrollVisual - paintedTrackScroll) > 0.001
                && now - lastScrollRegionPaintNs >= 41_666_667L) {
            repaintRegion(overlayTexture, contentRect(), g -> paintSearchResults(g, snapshot));
            paintedTrackScroll = trackScrollVisual;
            lastScrollRegionPaintNs = now;
        } else if (!isSearchMode && Math.abs(trackScrollVisual - paintedTrackScroll) > 0.001
                && now - lastScrollRegionPaintNs >= 41_666_667L) {
            repaintRegion(overlayTexture, trackTableRect(), g -> paintTrackTable(g, snapshot, items, selected));
            paintedTrackScroll = trackScrollVisual;
            lastScrollRegionPaintNs = now;
        }

        if (searchTextChanged || searchFocusChanged || searchPreparingChanged
                || (searchFocused && (System.currentTimeMillis() / 500L) != paintedCaretPhase)) {
            repaintRegion(overlayTexture, searchBarRect(), WorldPanel::paintSearchBar);
            paintedCaretPhase = searchFocused ? System.currentTimeMillis() / 500L : Long.MIN_VALUE;
        }

        if (trackChanged || statusChanged) {
            repaintRegion(overlayTexture, nowPlayingRect(), g -> paintNowPlaying(g, snapshot));
        } else if (playingChanged) {
            repaintRegion(overlayTexture, nowPlayingStatusRect(), g -> paintNowPlayingStatus(g, snapshot));
        }

        if (trackChanged) {
            repaintRegion(overlayTexture, bottomMetadataRect(), g -> paintBottomMetadataPartial(g, snapshot));
            if (isSearchMode) {
                repaintRegion(overlayTexture, contentRect(), g -> paintSearchResults(g, snapshot));
                paintedTrackScroll = trackScrollVisual;
            } else {
                repaintRegion(overlayTexture, trackTableRect(), g -> paintTrackTable(g, snapshot, items, selected));
                paintedHighlightSignature = highlightSignature();
                lastHighlightRegionPaintNs = now;
            }
        }
        if (playingChanged) {
            repaintRegion(overlayTexture, bottomPlayButtonRect(), g -> paintBottomPlayButtonPartial(g, snapshot));
        }

        int volumeNow = (int) Math.round(Math.max(0.0, Math.min(100.0, displayedVolume)));
        boolean volumeNeedsPaint = volumeNow != paintedVolume
                && (dragMode != DRAG_VOLUME || now - lastVolumeRegionPaintNs >= 33_333_333L);
        if (volumeNeedsPaint) {
            repaintRegion(overlayTexture, bottomVolumeRect(), g -> paintBottomVolumePartial(g, volumeNow));
            paintedVolume = volumeNow;
            lastVolumeRegionPaintNs = now;
        }

        boolean scrollMoving = Math.abs(sideScrollVisual - paintedSideScroll) > 0.001;
        if (scrollMoving && now - lastScrollRegionPaintNs >= 41_666_667L) {
            repaintRegion(overlayTexture, sidebarRect(), g -> paintSidebar(g, libraryEntries, selected));
            paintedSideScroll = sideScrollVisual;
            lastScrollRegionPaintNs = now;
        }

        boolean transitionMoving = Math.abs(viewTransition - paintedViewTransition) > 0.001;
        if (!isSearchMode && transitionMoving && now - lastScrollRegionPaintNs >= 41_666_667L) {
            repaintRegion(overlayTexture, contentRect(), g -> {
                paintHeaderAndTrackWithTransition(g, snapshot, items, selected);
            });
            paintedViewTransition = viewTransition;
            paintedTrackScroll = trackScrollVisual;
            lastScrollRegionPaintNs = now;
        }

        int currentHighlightSignature = highlightSignature();
        if (!isSearchMode && currentHighlightSignature != paintedHighlightSignature
                && now - lastHighlightRegionPaintNs >= 41_666_667L) {
            repaintRegion(overlayTexture, trackTableRect(), g -> paintTrackTable(g, snapshot, items, selected));
            paintedHighlightSignature = currentHighlightSignature;
            lastHighlightRegionPaintNs = now;
        }

        long progressSecond = Math.max(0L, Math.round(displayedProgressMs) / 1000L);
        boolean progressChanged = trackChanged || progressSecond != paintedProgressSecond
                || (snapshot.playing() && now - lastProgressRegionPaintNs >= 66_666_667L)
                || (dragMode == DRAG_SEEK && now - lastProgressRegionPaintNs >= 33_333_333L);
        if (progressChanged) {
            repaintRegion(overlayTexture, bottomProgressRect(), g -> paintBottomProgressPartial(g, snapshot));
            paintedProgressSecond = progressSecond;
            lastProgressRegionPaintNs = now;
        }

        rememberOverlayState(snapshot, libraryEntries, selectedKey, items, isSearchMode,
                trackUri, trackTitle, trackArtists, trackCover, trackDuration, now);
    }

    private static int highlightSignature() {
        // Order-independent hash: stable full-opacity highlights do not trigger redraws,
        // while any actual alpha change during a track-transition fade invalidates the rows.
        long sum = 0L;
        long xor = 0L;
        for (Map.Entry<String, Double> entry : playingHighlightAlpha.entrySet()) {
            long value = ((long) entry.getKey().hashCode() << 32)
                    ^ Math.round(entry.getValue() * 10_000.0);
            sum += value;
            xor ^= Long.rotateLeft(value, (int) (value & 31));
        }
        long combined = sum ^ xor;
        return (int) (combined ^ (combined >>> 32));
    }

    private static void rememberOverlayState(SpotifyClient.Snapshot snapshot,
                                             List<SpotifyClient.LibraryEntry> libraryEntries,
                                             String selectedKey, List<SpotifyClient.Item> items,
                                             boolean isSearchMode, String trackUri, String trackTitle,
                                             String trackArtists, String trackCover, long trackDuration,
                                             long now) {
        paintedLibraryRef = libraryEntries;
        paintedSelectedKey = selectedKey;
        paintedItemsRef = items;
        paintedSearchResultsRef = searchResults;
        paintedSearchMode = isSearchMode;
        paintedSearchText = searchText;
        paintedSearchFocused = searchFocused;
        paintedSearchLoading = searchLoading;
        paintedSearchPreparing = searchDebounceAtMs > 0;
        paintedSearchError = searchError;
        paintedTrackUri = trackUri;
        paintedTrackTitle = trackTitle;
        paintedTrackArtists = trackArtists;
        paintedTrackCover = trackCover;
        paintedTrackDuration = trackDuration;
        paintedStatus = snapshot.status();
        paintedPlaying = snapshot.playing();
        // Dynamic painted values are advanced only by the region that actually
        // uploads them. Do not overwrite these here: throttled scroll/progress
        // regions must remain dirty until their next partial refresh.
        overlayInitialized = true;
    }

    private static void repaintRegion(NativeImageBackedTexture texture, UiRect logicalRect,
                                      Consumer<Graphics2D> painter) {
        if (texture == null || texture.getGlTexture() == null || logicalRect.width() <= 0 || logicalRect.height() <= 0) return;
        int x = Math.max(0, (int) Math.floor(logicalRect.x() * RENDER_SCALE));
        int y = Math.max(0, (int) Math.floor(logicalRect.y() * RENDER_SCALE));
        int right = Math.min(TEX_W, (int) Math.ceil(logicalRect.right() * RENDER_SCALE));
        int bottom = Math.min(TEX_H, (int) Math.ceil(logicalRect.bottom() * RENDER_SCALE));
        int width = right - x, height = bottom - y;
        if (width <= 0 || height <= 0) return;

        Graphics2D g = OVERLAY_IMAGE.createGraphics();
        // Set and clear the dirty rectangle in backing-texture pixels before applying
        // the logical UI scale. Its clip then constrains every painter to this region.
        g.setClip(x, y, width, height);
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(x, y, width, height);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.scale(RENDER_SCALE, RENDER_SCALE);
        prepare(g);
        painter.accept(g);
        g.dispose();
        uploadRegion(texture, x, y, width, height);
    }

    private static void uploadRegion(NativeImageBackedTexture texture, int x, int y, int width, int height) {
        int[] pixels = ((DataBufferInt) OVERLAY_IMAGE.getRaster().getDataBuffer()).getData();
        OVERLAY_UPLOAD_INTS.clear();
        for (int row = 0; row < height; row++) {
            int source = (y + row) * TEX_W + x;
            for (int col = 0; col < width; col++) {
                int argb = pixels[source + col];
                int rgba = (argb & 0xFF00FF00)
                        | ((argb & 0x00FF0000) >>> 16)
                        | ((argb & 0x000000FF) << 16);
                OVERLAY_UPLOAD_INTS.put(rgba);
            }
        }
        OVERLAY_UPLOAD_BUFFER.position(0);
        OVERLAY_UPLOAD_BUFFER.limit(width * height * Integer.BYTES);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture.getGlTexture(), OVERLAY_UPLOAD_BUFFER, NativeImage.Format.RGBA,
                0, 0, x, y, width, height
        );
        OVERLAY_UPLOAD_BUFFER.clear();
    }

    private static UiRect sidebarRect() {
        int x0 = px(-10.8), x1 = px(-9.6), y0 = py(12.7), y1 = py(2.4);
        return new UiRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static UiRect searchBarRect() {
        int x0 = px(-3.85), x1 = px(3.85), y0 = py(13.78), y1 = py(12.92);
        return new UiRect(x0 - 3, y0 - 3, x1 - x0 + 6, y1 - y0 + 6);
    }

    private static UiRect contentRect() {
        int x0 = px(-9.4), x1 = px(5.2), y0 = py(12.5), y1 = py(2.4);
        return new UiRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static UiRect trackTableRect() {
        int x0 = px(-9.4), x1 = px(5.2), y0 = py(9.2), y1 = py(2.4);
        return new UiRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static UiRect nowPlayingRect() {
        int x0 = px(5.4), x1 = px(10.8), y0 = py(12.7), y1 = py(2.4);
        return new UiRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static UiRect nowPlayingStatusRect() {
        int x0 = px(5.4), x1 = px(10.8), baseline = py(7.1) + 101;
        return new UiRect(x0, baseline - 22, x1 - x0, 30);
    }

    private static UiRect bottomMetadataRect() {
        int left = px(-10.4) - 4;
        int center = (px(-3.0) + px(3.0)) / 2;
        int y0 = py(2.3) + 4, y1 = py(0.2) - 3;
        return new UiRect(left, y0, center - 70 - left, y1 - y0);
    }

    private static UiRect bottomPlayButtonRect() {
        int center = (px(-3.0) + px(3.0)) / 2;
        int y = py(2.3) + 26;
        return new UiRect(center - 17, y - 17, 34, 34);
    }

    private static UiRect bottomProgressRect() {
        int barX = px(-3.0), barRight = px(3.0), barY = py(0.2) - 12;
        return new UiRect(barX - 38, barY - 19, barRight - barX + 43, 29);
    }

    private static UiRect bottomVolumeRect() {
        int left = volumeGroupLeft() - 3;
        int right = volumeSliderX1() + 3;
        int centerY = volumeSliderY();
        return new UiRect(left, centerY - 20, right - left, 40);
    }

    private static void paintHeaderAndTrackWithTransition(Graphics2D g, SpotifyClient.Snapshot snapshot,
                                                            List<SpotifyClient.Item> items,
                                                            SpotifyClient.LibraryEntry selected) {
        java.awt.Composite previousComposite = g.getComposite();
        java.awt.geom.AffineTransform previousTransform = g.getTransform();
        float transitionAlpha = (float) Math.max(0.0, Math.min(1.0, viewTransition));
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, transitionAlpha));
        g.translate(0, Math.round((1.0 - viewTransition) * 10.0));
        paintPlaylistHeader(g, selected, items);
        paintTrackTable(g, snapshot, items, selected);
        g.setTransform(previousTransform);
        g.setComposite(previousComposite);
    }

    // -------------------------------------------------------------------- live overlay painting

    private static BufferedImage paintOverlay(SpotifyClient.Snapshot s, List<SpotifyClient.LibraryEntry> libraryEntries,
                                              SpotifyClient.LibraryEntry selected, List<SpotifyClient.Item> items) {
        BufferedImage image = OVERLAY_IMAGE;
        Graphics2D g = image.createGraphics();
        // A reused image must be explicitly cleared; drawing transparent SRC_OVER pixels
        // would otherwise leave fragments from the previous frame.
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, TEX_W, TEX_H);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        // All existing layout values, fonts, stroke widths and hit areas remain
        // expressed in UI_W x UI_H units. Java2D rasterizes them at supersampled density.
        g.scale(RENDER_SCALE, RENDER_SCALE);
        prepare(g);

        paintSidebar(g, libraryEntries, selected);
        paintSearchBar(g);
        if (searchMode()) {
            paintSearchResults(g, s);
        } else {
            java.awt.Composite previousComposite = g.getComposite();
            java.awt.geom.AffineTransform previousTransform = g.getTransform();
            float transitionAlpha = (float) Math.max(0.0, Math.min(1.0, viewTransition));
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, transitionAlpha));
            g.translate(0, Math.round((1.0 - viewTransition) * 10.0));
            paintPlaylistHeader(g, selected, items);
            paintTrackTable(g, s, items, selected);
            g.setTransform(previousTransform);
            g.setComposite(previousComposite);
        }
        paintNowPlaying(g, s);
        paintBottomPlayer(g, s);

        g.dispose();
        return image;
    }

    private static void paintSearchBar(Graphics2D g) {
        // The new model adds a dedicated centered "search" element inside
        // the full-width search_corpus. Keep the editable overlay inside that housing.
        int x0 = px(-3.85), x1 = px(3.85);
        int y0 = py(13.78), y1 = py(12.92);
        g.setColor(new Color(5, 10, 7, 248));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        paintPixelFrame(g, x0, y0, x1 - x0, y1 - y0,
                searchFocused ? new Color(30, 215, 96, 245) : new Color(115, 145, 123, 190), 2, 7);

        // Magnifying glass mark at the left of the editable field.
        int iconX = x0 + 17, iconY = y0 + (y1 - y0) / 2 - 4;
        g.setColor(new Color(190, 211, 196));
        g.setStroke(new BasicStroke(2.2f));
        g.drawOval(iconX, iconY - 5, 13, 13);
        g.drawLine(iconX + 11, iconY + 7, iconX + 19, iconY + 15);

        String shown = searchText.isEmpty() ? "Поиск треков" : searchText;
        int textX = x0 + 47;
        int baseline = y0 + (y1 - y0) / 2 + 8;
        g.setFont(new Font(Font.SANS_SERIF, searchText.isEmpty() ? Font.PLAIN : Font.BOLD, 22));
        Color textColor = searchText.isEmpty() ? new Color(162, 180, 168) : new Color(248, 252, 249);
        String fitted = fit(g, shown, x1 - textX - 42);
        drawReadableText(g, fitted, textX, baseline, textColor);
        if (searchFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int caretX = textX + g.getFontMetrics().stringWidth(fitted) + 2;
            caretX = Math.min(caretX, x1 - 40);
            g.setColor(new Color(30, 215, 96));
            g.fillRect(caretX, baseline - 19, 2, 23);
        }

        int clearX = x1 - 27;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
        g.setColor(searchText.isEmpty() ? new Color(90, 110, 96) : new Color(230, 240, 233));
        drawUiText(g, "×", clearX, baseline, g.getColor());
    }

    private static void paintSearchResults(Graphics2D g, SpotifyClient.Snapshot snapshot) {
        int x0 = px(-9.4), x1 = px(5.2), y0 = py(SEARCH_RESULTS_TOP), y1 = py(2.4);
        g.setColor(new Color(7, 12, 9, 250));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.setColor(new Color(30, 225, 100));
        drawUiText(g, "РЕЗУЛЬТАТЫ ПОИСКА", x0 + 13, y0 + 22, g.getColor());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.setColor(new Color(184, 201, 189));
        String queryLabel = fit(g, searchText, Math.max(40, x1 - x0 - 230));
        drawUiText(g, queryLabel, x0 + 210, y0 + 22, g.getColor());
        g.setColor(new Color(255, 255, 255, 48));
        g.drawLine(x0 + 10, y0 + 31, x1 - 10, y0 + 31);

        if (searchDebounceAtMs > 0) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
            g.setColor(new Color(215, 225, 218));
            drawUiText(g, "Подготовка поиска…", x0 + 20, y0 + 78, g.getColor());
            return;
        }
        if (searchLoading) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
            g.setColor(new Color(215, 225, 218));
            drawUiText(g, "Ищу треки в Spotify…", x0 + 20, y0 + 78, g.getColor());
            return;
        }
        if (!searchError.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(255, 178, 130));
            drawUiText(g, fit(g, searchError, x1 - x0 - 36), x0 + 18, y0 + 75, g.getColor());
            return;
        }
        if (searchResults.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            g.setColor(new Color(202, 215, 206));
            drawUiText(g, "Ничего не найдено. Попробуй другое название.", x0 + 18, y0 + 78, g.getColor());
            return;
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.setColor(new Color(213, 224, 216));
        drawUiText(g, "#", x0 + 15, y0 + 49, g.getColor());
        drawUiText(g, "НАЗВАНИЕ", x0 + 43, y0 + 49, g.getColor());
        drawUiText(g, "ИСПОЛНИТЕЛЬ", x0 + 290, y0 + 49, g.getColor());
        int searchDurationLeft = x1 - 62, searchDurationRight = x1 - 10;
        String searchTimeHeader = "ВРЕМЯ";
        drawUiText(g, searchTimeHeader, searchDurationLeft
                + (searchDurationRight - searchDurationLeft - g.getFontMetrics().stringWidth(searchTimeHeader)) / 2,
                y0 + 49, g.getColor());
        g.setColor(new Color(255, 255, 255, 38));
        g.drawLine(x0 + 10, y0 + 56, x1 - 10, y0 + 56);

        int first = Math.max(0, Math.min(searchResults.size(), (int) Math.floor(trackScrollVisual)));
        double fraction = trackScrollVisual - first;
        int rows = Math.min(SEARCH_VISIBLE_ROWS, Math.max(0, searchResults.size() - first));
        int rowH = SEARCH_RESULT_ROW_HEIGHT;
        int startY = y0 + 59;
        java.awt.Shape oldClip = g.getClip();
        g.clipRect(x0 + 4, startY - 3, x1 - x0 - 8, y1 - startY + 1);
        String playingUri = snapshot.track() == null ? "" : snapshot.track().uri();
        for (int i = 0; i < rows; i++) {
            int itemIndex = first + i;
            SpotifyClient.Item item = searchResults.get(itemIndex);
            int y = startY + (int) Math.round((i - fraction) * rowH);
            boolean playing = !playingUri.isEmpty() && playingUri.equals(item.uri());
            if (playing) {
                g.setColor(new Color(30, 215, 96, 42));
                g.fillRect(x0 + 7, y, x1 - x0 - 14, rowH - 1);
                g.setColor(new Color(30, 215, 96, 210));
                g.fillRect(x0 + 7, y, 4, rowH - 1);
            }

            int baseline = y + 25;
            int titleLeft = x0 + 43, titleRight = x0 + 278;
            int artistLeft = x0 + 290;
            int durationLeft = searchDurationLeft, durationRight = searchDurationRight;
            // Keep a real gutter between artist names and the time column.
            int artistRight = durationLeft - 14;
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
            g.setColor(playing ? new Color(30, 235, 105) : new Color(220, 232, 223));
            String number = Integer.toString(itemIndex + 1);
            drawUiText(g, number, x0 + 14, baseline, g.getColor());

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.setColor(playing ? new Color(220, 255, 232) : Color.WHITE);
            String title = fit(g, safe(item.title(), "Без названия"), titleRight - titleLeft);
            drawReadableText(g, title, titleLeft, baseline, g.getColor());

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.setColor(new Color(223, 234, 226));
            String artists = fit(g, safe(item.artists(), ""), artistRight - artistLeft);
            drawReadableText(g, artists, artistLeft, baseline, g.getColor());

            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
            g.setColor(new Color(235, 241, 236));
            String duration = formatTime(item.durationMs());
            drawUiText(g, duration, durationLeft + Math.max(0,
                    (durationRight - durationLeft - g.getFontMetrics().stringWidth(duration)) / 2), baseline, g.getColor());
        }
        g.setClip(oldClip);
        if (searchResults.size() > SEARCH_VISIBLE_ROWS) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(160, 181, 167));
            drawUiText(g, "Колёсико — прокрутка результатов", x1 - 220, y1 - 7, g.getColor());
        }
    }

    private static void paintSidebar(Graphics2D g, List<SpotifyClient.LibraryEntry> libraryEntries, SpotifyClient.LibraryEntry selected) {
        int left = px(-10.8), right = px(-9.6), top = py(12.7), bottom = py(2.4);
        g.setColor(new Color(5, 9, 7, 250));
        g.fillRect(left, top, right - left, bottom - top);
        paintPixelFrame(g, left, top, right - left, bottom - top, new Color(30, 215, 96, 175), 2, 7);
        g.setColor(new Color(255, 255, 255, 18));
        g.fillRect(left + 5, top + 5, 3, 3);
        g.fillRect(right - 8, bottom - 8, 3, 3);

        // Small library mark at the top, then one icon per saved library item.
        int libX = left + 8, libY = top + 10;
        g.setColor(new Color(30, 215, 96));
        g.fillRect(libX, libY, Math.max(18, right - left - 16), 28);
        g.setColor(new Color(5, 18, 10));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        drawUiText(g, "≡", libX + 8, libY + 21, g.getColor());

        int first = Math.max(0, (int) Math.floor(sideScrollVisual));
        double fraction = sideScrollVisual - first;
        int shown = Math.min(8, Math.max(0, libraryEntries.size() - first));
        int iconSize = Math.max(22, Math.min(34, right - left - 12));
        int x = left + (right - left - iconSize) / 2;
        int y0 = top + 53;
        java.awt.Shape oldClip = g.getClip();
        g.clipRect(left + 2, y0 - 4, right - left - 4, 7 * 46 + 8);
        for (int i = 0; i < shown; i++) {
            SpotifyClient.LibraryEntry p = libraryEntries.get(first + i);
            int y = y0 + (int) Math.round((i - fraction) * 46.0);
            int[] pixels = SpotifyPanelMod.spotify.pixels(p.coverUrl(), 32);
            drawCover(g, pixels, 32, x, y, iconSize);
            if (selected != null && p.key().equals(selected.key())) {
                g.setColor(new Color(30, 215, 96));
                g.setStroke(new BasicStroke(2.5f));
                paintPixelFrame(g, x - 2, y - 2, iconSize + 3, iconSize + 3, new Color(30, 215, 96), 2, 5);
            }
            // Tiny type glyph distinguishes playlists, albums, artists and saved collections.
            g.setColor(new Color(5, 12, 8, 220));
            g.fillRect(x + iconSize - 13, y + iconSize - 13, 13, 13);
            g.setColor(new Color(235, 245, 238));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            drawUiText(g, p.iconMark(), x + iconSize - 11, y + iconSize - 3, g.getColor());
        }
        g.setClip(oldClip);
        if (sideScrollVisual > 0.01) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(new Color(180, 204, 188));
            drawUiText(g, "▲", left + 15, y0 - 8, g.getColor());
        }
        if (sideScrollVisual + 7.0 < libraryEntries.size()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(new Color(180, 204, 188));
            drawUiText(g, "▼", left + 15, y0 + 7 * 46 + 9, g.getColor());
        }
    }

    private static void paintPlaylistHeader(Graphics2D g, SpotifyClient.LibraryEntry selected, List<SpotifyClient.Item> items) {
        int x0 = px(-9.2), x1 = px(5.0), y0 = py(12.5), y1 = py(9.2);
        int accentRgb = selected == null ? 0xFF52635B
                : SpotifyPanelMod.spotify.coverAccentColor(selected.coverUrl());
        Color[] headerColors = headerGradient(accentRgb);
        fillSteppedGradient(g, x0, y0, x1 - x0, y1 - y0, headerColors[0], headerColors[1], 18);
        paintPixelFrame(g, x0, y0, x1 - x0, y1 - y0, new Color(255, 255, 255, 42), 2, 9);
        g.setColor(new Color(30, 215, 96, 180));
        g.fillRect(x0 + 13, y0 + 12, 14, 4);
        g.fillRect(x0 + 13, y0 + 20, 5, 4);
        g.setColor(new Color(255, 255, 255, 35));
        g.fillRect(x0 + 18, y1 - 8, x1 - x0 - 36, 2);

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
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        g.setColor(new Color(250, 252, 250));
        drawReadableText(g, selected == null ? "МЕДИАТЕКА" : selected.kindLabel(), textX, y0 + 27, new Color(255, 255, 255));
        String title = selected == null ? "Твоя медиатека" : safe(selected.name(), "Элемент Spotify");
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
        g.setColor(Color.WHITE);
        drawReadableText(g, fit(g, title, textWidth), textX, y0 + 76, Color.WHITE);
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
        drawReadableText(g, fit(g, meta, textWidth), textX + 2, y1 - 17, new Color(250, 252, 250));
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
        int x0 = px(-9.4), x1 = px(5.2), y0 = py(9.2), y1 = py(2.4);
        g.setColor(new Color(8, 12, 9, 250));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.setColor(new Color(248, 250, 248));
        drawUiText(g, "#", x0 + 14, y0 + 22, g.getColor());
        drawUiText(g, "НАЗВАНИЕ", x0 + 42, y0 + 22, g.getColor());
        drawUiText(g, "ИСПОЛНИТЕЛЬ", x0 + 290, y0 + 22, g.getColor());
        int durationColumnLeft = x1 - 62, durationColumnRight = x1 - 10;
        String timeHeader = "ВРЕМЯ";
        drawUiText(g, timeHeader, durationColumnLeft
                + (durationColumnRight - durationColumnLeft - g.getFontMetrics().stringWidth(timeHeader)) / 2,
                y0 + 22, g.getColor());
        g.setColor(new Color(255, 255, 255, 42));
        g.drawLine(x0 + 10, y0 + 30, x1 - 10, y0 + 30);

        if (selected == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(205, 215, 208));
            drawUiText(g, "Выбери элемент своей медиатеки слева", x0 + 18, y0 + 70, g.getColor());
            return;
        }
        if (items == null) {
            String error = SpotifyPanelMod.spotify.itemError(selected);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(205, 215, 208));
            String message = error.isEmpty() ? "Загружаю содержимое из Spotify…" : error;
            drawUiText(g, fit(g, message, x1 - x0 - 36), x0 + 18, y0 + 70, g.getColor());
            return;
        }
        if (items.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(205, 215, 208));
            String error = SpotifyPanelMod.spotify.itemError(selected);
            String message = error.isEmpty()
                    ? "В этом разделе пока нет доступных элементов" : error;
            drawUiText(g, fit(g, message, x1 - x0 - 36), x0 + 18, y0 + 70, g.getColor());
            return;
        }

        int first = Math.max(0, Math.min(items.size(), (int) Math.floor(trackScrollVisual)));
        double fraction = trackScrollVisual - first;
        int rows = Math.min(5, Math.max(0, items.size() - first));
        int rowH = 42;
        int startY = y0 + 60;
        java.awt.Shape oldClip = g.getClip();
        g.clipRect(x0 + 4, startY - 19, x1 - x0 - 8, y1 - startY + 22);
        for (int i = 0; i < rows; i++) {
            int itemIndex = first + i;
            SpotifyClient.Item item = items.get(itemIndex);
            int y = startY + (int) Math.round((i - fraction) * rowH);
            double highlight = Math.max(0.0, Math.min(1.0,
                    playingHighlightAlpha.getOrDefault(item.uri(), 0.0)));
            if (highlight > 0.002) {
                // Exact URI matching plus a short fade makes the active row feel stable.
                g.setColor(new Color(30, 215, 96, (int) Math.round(36 * highlight)));
                g.fillRect(x0 + 7, y - 17, x1 - x0 - 14, rowH - 1);
                g.setColor(new Color(30, 215, 96, (int) Math.round(205 * highlight)));
                g.fillRect(x0 + 7, y - 17, 4, rowH - 1);
                g.setColor(new Color(30, 215, 96, (int) Math.round(90 * highlight)));
                g.drawRect(x0 + 12, y - 16, x1 - x0 - 24, rowH - 3);
            }
            Color numberBase = new Color(163, 179, 168);
            Color numberActive = new Color(30, 235, 105);
            Color titleBase = Color.WHITE;
            Color titleActive = new Color(220, 255, 232);
            // Match search-result typography while keeping the original library-row layout.
            // No horizontal row separators: they made the panel look visually split.
            int textBaseline = y + 8;
            int numberCenter = x0 + 25;
            int titleLeft = x0 + 43, titleRight = x0 + 278;
            int artistLeft = x0 + 290;
            int durationLeft = durationColumnLeft, durationRight = durationColumnRight;
            // Reserve a separate time column and a visible gutter so artist text cannot touch it.
            int artistRight = durationLeft - 14;

            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
            g.setColor(blend(numberBase, numberActive, highlight));
            String number = Integer.toString(itemIndex + 1);
            drawReadableText(g, number, numberCenter - g.getFontMetrics().stringWidth(number) / 2,
                    textBaseline, blend(new Color(235, 240, 236), numberActive, highlight));

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.setColor(blend(titleBase, titleActive, highlight));
            String title = fit(g, safe(item.title(), "Без названия"), titleRight - titleLeft);
            drawReadableText(g, title, titleLeft, textBaseline,
                    blend(Color.WHITE, titleActive, highlight));

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.setColor(new Color(235, 240, 236));
            String artists = fit(g, safe(item.artists(), ""), artistRight - artistLeft);
            drawReadableText(g, artists, artistLeft, textBaseline, new Color(242, 246, 243));

            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
            g.setColor(new Color(245, 247, 245));
            String duration = formatTime(item.durationMs());
            drawReadableText(g, duration, durationLeft + (durationRight - durationLeft - g.getFontMetrics().stringWidth(duration)) / 2,
                    textBaseline, new Color(245, 248, 246));
        }
        g.setClip(oldClip);
    }

    private static Color blend(Color from, Color to, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
                (int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t));
    }

    private static void paintNowPlaying(Graphics2D g, SpotifyClient.Snapshot s) {
        int x0 = px(5.4), x1 = px(10.8), y0 = py(12.7), y1 = py(2.4);
        g.setColor(new Color(7, 11, 8, 248));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        paintPixelFrame(g, x0, y0, x1 - x0, y1 - y0, new Color(255, 255, 255, 25), 1, 8);
        g.setColor(new Color(30, 215, 96, 175));
        g.fillRect(x0 + 8, y0 + 8, 18, 3);

        int coverX = px(5.6), coverY = py(12.5), coverSize = Math.min(px(10.6) - coverX, py(7.5) - coverY);
        int[] cover = s.track() == null ? null : SpotifyPanelMod.spotify.pixels(s.track().coverUrl(), 128);
        drawCover(g, cover, 128, coverX + 5, coverY + 5, coverSize - 10);
        g.setColor(new Color(30, 215, 96, 210));
        g.setStroke(new BasicStroke(2.5f));
        g.drawRect(coverX + 4, coverY + 4, coverSize - 8, coverSize - 8);

        int textY = py(7.1);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.setColor(new Color(30, 235, 105));
        drawReadableText(g, "СЕЙЧАС ИГРАЕТ", coverX + 4, textY + 17, new Color(30, 245, 110));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
        g.setColor(Color.WHITE);
        String title = s.track() == null ? titleForInactive(s) : s.track().title();
        drawReadableText(g, fit(g, safe(title, "Ничего не играет"), x1 - x0 - 16), coverX + 4, textY + 49, Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        g.setColor(new Color(248, 250, 248));
        String artist = s.track() == null ? statusDetail(s) : s.track().artists();
        drawReadableText(g, fit(g, safe(artist, ""), x1 - x0 - 16), coverX + 4, textY + 76, new Color(246, 248, 246));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.setColor(new Color(220, 230, 223));
        drawReadableText(g, s.playing() ? "● ВОСПРОИЗВЕДЕНИЕ" : "Ⅱ ПАУЗА", coverX + 4, textY + 101, new Color(230, 238, 232));
    }

    private static void paintBottomPlayer(Graphics2D g, SpotifyClient.Snapshot s) {
        int x0 = px(-10.8), x1 = px(10.8), y0 = py(2.3), y1 = py(0.2);
        g.setColor(new Color(4, 7, 5, 250));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        paintPixelFrame(g, x0, y0, x1 - x0, y1 - y0, new Color(30, 215, 96, 125), 1, 10);
        g.setColor(new Color(30, 215, 96, 170));
        g.fillRect(x0 + 10, y0 + 1, x1 - x0 - 20, 2);

        int leftCover = px(-10.4), leftY = y0 + 12;
        int[] cover = s.track() == null ? null : SpotifyPanelMod.spotify.pixels(s.track().coverUrl(), 48);
        drawCover(g, cover, 48, leftCover, leftY, 50);
        int center = (px(-3.0) + px(3.0)) / 2;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        g.setColor(Color.WHITE);
        String bottomTitle = s.track() == null ? "Spotify" : s.track().title();
        int trackTextX = leftCover + 58;
        int trackTextMax = Math.max(120, Math.min(190, center - 60 - trackTextX));
        drawReadableText(g, fit(g, bottomTitle, trackTextMax), trackTextX, leftY + 22, Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.setColor(new Color(242, 245, 242));
        String bottomArtist = s.track() == null ? "Подключи аккаунт" : s.track().artists();
        drawReadableText(g, fit(g, bottomArtist, trackTextMax), trackTextX, leftY + 43, new Color(238, 242, 239));

        // Slightly larger transport controls, spaced evenly around the central play button.
        int controlCenterY = y0 + 26;
        g.setColor(new Color(177, 194, 182));
        drawPrevious(g, center - 38, controlCenterY);
        g.setColor(new Color(30, 215, 96));
        fillPixelButton(g, center - 13, controlCenterY - 13, 26, 26);
        g.setColor(new Color(4, 16, 8));
        if (s.playing()) {
            g.fillRect(center - 5, controlCenterY - 7, 4, 14);
            g.fillRect(center + 2, controlCenterY - 7, 4, 14);
        } else {
            g.fillPolygon(new Polygon(new int[]{center - 4, center - 4, center + 8},
                    new int[]{controlCenterY - 8, controlCenterY + 8, controlCenterY}, 3));
        }
        g.setColor(new Color(177, 194, 182));
        drawNext(g, center + 38, controlCenterY);

        long duration = s.track() == null ? 0 : Math.max(0, s.track().durationMs());
        long visualProgress = progressVisualInitialized
                ? Math.round(displayedProgressMs) : s.currentProgress();
        long progress = duration == 0 ? 0 : Math.max(0, Math.min(duration, visualProgress));
        double fraction = duration == 0 ? 0 : progress / (double) duration;
        int barX = px(-3.0), barRight = px(3.0), barY = y1 - 12;
        g.setColor(new Color(103, 122, 109, 170));
        g.fillRect(barX, barY, barRight - barX, 4);
        g.setColor(new Color(30, 215, 96));
        g.fillRect(barX, barY, (int) ((barRight - barX) * fraction), 4);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        g.setColor(new Color(238, 242, 239));
        drawUiText(g, formatTime(progress), barX, barY - 5, g.getColor());
        String total = formatTime(duration);
        drawUiText(g, total, barRight - g.getFontMetrics().stringWidth(total), barY - 5, g.getColor());

        int visualVolume = (int) Math.round(Math.max(0.0, Math.min(100.0,
                volumeVisualInitialized ? displayedVolume : s.volume())));
        // Compact Spotify-like volume control: centered icon + a shorter, slim slider.
        int volumeBarX = volumeSliderX0();
        int volumeBarRight = volumeSliderX1();
        int volumeCenterY = volumeSliderY();
        // Draw the volume-sensitive white speaker at 32px UI size, centered vertically.
        drawVolumeIcon(g, volumeGroupLeft(), volumeCenterY - 16, visualVolume);
        int volumeBarY = volumeCenterY - 2;
        g.setColor(new Color(75, 94, 81));
        g.fillRect(volumeBarX, volumeBarY, volumeBarRight - volumeBarX, 4);
        g.setColor(new Color(30, 215, 96));
        g.fillRect(volumeBarX, volumeBarY,
                (int) ((volumeBarRight - volumeBarX) * visualVolume / 100.0), 4);
    }

    private static void paintBottomMetadataPartial(Graphics2D g, SpotifyClient.Snapshot s) {
        UiRect rect = bottomMetadataRect();
        g.setColor(new Color(4, 7, 5, 250));
        g.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
        int y0 = py(2.3), leftCover = px(-10.4), leftY = y0 + 12;
        int[] cover = s.track() == null ? null : SpotifyPanelMod.spotify.pixels(s.track().coverUrl(), 48);
        drawCover(g, cover, 48, leftCover, leftY, 50);
        int center = (px(-3.0) + px(3.0)) / 2;
        int trackTextX = leftCover + 58;
        int trackTextMax = Math.max(120, Math.min(190, center - 60 - trackTextX));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        drawReadableText(g, fit(g, s.track() == null ? "Spotify" : s.track().title(), trackTextMax),
                trackTextX, leftY + 22, Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        drawReadableText(g, fit(g, s.track() == null ? "Подключи аккаунт" : s.track().artists(), trackTextMax),
                trackTextX, leftY + 43, new Color(238, 242, 239));
    }

    private static void paintBottomPlayButtonPartial(Graphics2D g, SpotifyClient.Snapshot s) {
        UiRect rect = bottomPlayButtonRect();
        g.setColor(new Color(4, 7, 5, 250));
        g.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
        int center = (px(-3.0) + px(3.0)) / 2;
        int controlCenterY = py(2.3) + 26;
        g.setColor(new Color(30, 215, 96));
        fillPixelButton(g, center - 13, controlCenterY - 13, 26, 26);
        g.setColor(new Color(4, 16, 8));
        if (s.playing()) {
            g.fillRect(center - 5, controlCenterY - 7, 4, 14);
            g.fillRect(center + 2, controlCenterY - 7, 4, 14);
        } else {
            g.fillPolygon(new Polygon(new int[]{center - 4, center - 4, center + 8},
                    new int[]{controlCenterY - 8, controlCenterY + 8, controlCenterY}, 3));
        }
    }

    private static void paintBottomProgressPartial(Graphics2D g, SpotifyClient.Snapshot s) {
        UiRect rect = bottomProgressRect();
        g.setColor(new Color(4, 7, 5, 250));
        g.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
        long duration = s.track() == null ? 0 : Math.max(0, s.track().durationMs());
        long visualProgress = progressVisualInitialized ? Math.round(displayedProgressMs) : s.currentProgress();
        long progress = duration == 0 ? 0 : Math.max(0, Math.min(duration, visualProgress));
        double fraction = duration == 0 ? 0 : progress / (double) duration;
        int barX = px(-3.0), barRight = px(3.0), barY = py(0.2) - 12;
        g.setColor(new Color(103, 122, 109, 170));
        g.fillRect(barX, barY, barRight - barX, 4);
        g.setColor(new Color(30, 215, 96));
        g.fillRect(barX, barY, (int) ((barRight - barX) * fraction), 4);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        g.setColor(new Color(238, 242, 239));
        drawUiText(g, formatTime(progress), barX, barY - 5, g.getColor());
        String total = formatTime(duration);
        drawUiText(g, total, barRight - g.getFontMetrics().stringWidth(total), barY - 5, g.getColor());
    }

    private static void paintBottomVolumePartial(Graphics2D g, int volume) {
        UiRect rect = bottomVolumeRect();
        g.setColor(new Color(4, 7, 5, 250));
        g.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
        int volumeBarX = volumeSliderX0(), volumeBarRight = volumeSliderX1();
        int volumeCenterY = volumeSliderY();
        drawVolumeIcon(g, volumeGroupLeft(), volumeCenterY - 16, volume);
        int volumeBarY = volumeCenterY - 2;
        g.setColor(new Color(75, 94, 81));
        g.fillRect(volumeBarX, volumeBarY, volumeBarRight - volumeBarX, 4);
        g.setColor(new Color(30, 215, 96));
        g.fillRect(volumeBarX, volumeBarY,
                (int) ((volumeBarRight - volumeBarX) * volume / 100.0), 4);
    }

    private static void paintNowPlayingStatus(Graphics2D g, SpotifyClient.Snapshot s) {
        UiRect rect = nowPlayingStatusRect();
        g.setColor(new Color(7, 11, 8, 248));
        g.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
        int coverX = px(5.6);
        int textY = py(7.1);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.setColor(new Color(220, 230, 223));
        drawReadableText(g, s.playing() ? "● ВОСПРОИЗВЕДЕНИЕ" : "Ⅱ ПАУЗА",
                coverX + 4, textY + 101, new Color(230, 238, 232));
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
        // Correct vertical-plane normal plus full-bright lightmap keeps the surface
        // consistently lit while the player's body turns.
        drawMaterialQuad(vc, matrix, bottomLeft, bottomRight, topRight, topLeft,
                part.frontColor(), nx, 0, nz);
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
        // One quad only. The selected entity render layer is no-cull, so emitting a
        // second reverse-wound copy creates coincident fragments and visible shimmer.
        materialVertex(vc, matrix, a, 0, 1, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, b, 1, 1, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, c, 1, 0, r, g, bcol, nx, ny, nz);
        materialVertex(vc, matrix, d, 0, 0, r, g, bcol, nx, ny, nz);
    }

    private static void materialVertex(VertexConsumer vc, Matrix4f matrix, Vec3d p,
                                       float u, float v, int r, int g, int b,
                                       double nx, double ny, double nz) {
        vc.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                // Constant normal prevents diffuse shading from changing with player yaw.
                .normal(0.0f, 1.0f, 0.0f);
    }

    private static void drawOverlay(WorldRenderContext context, MatrixStack matrices,
                                    double cx, double cy, double cz, double wx, double wz, double nx, double nz) {
        if (overlayTexture == null) return;
        VertexConsumer vc = context.consumers().getBuffer(RenderLayers.entityCutoutNoCull(OVERLAY_TEXTURE_ID));
        MatrixStack.Entry entry = matrices.peek();
        Vec3d p0 = modelPoint(MODEL_OVERLAY_X, 0, -11, cx, cy, cz, wx, wz, nx, nz);
        Vec3d p1 = modelPoint(MODEL_OVERLAY_X, 0, 11, cx, cy, cz, wx, wz, nx, nz);
        Vec3d p2 = modelPoint(MODEL_OVERLAY_X, 14.0, 11, cx, cy, cz, wx, wz, nx, nz);
        Vec3d p3 = modelPoint(MODEL_OVERLAY_X, 14.0, -11, cx, cy, cz, wx, wz, nx, nz);
        // The overlay is offset forward from the model surfaces and emitted once,
        // avoiding coplanar alpha blending and the resulting sparkle/flicker.
        overlayVertex(vc, entry, p0, 0, 1, nx, nz);
        overlayVertex(vc, entry, p1, 1, 1, nx, nz);
        overlayVertex(vc, entry, p2, 1, 0, nx, nz);
        overlayVertex(vc, entry, p3, 0, 0, nx, nz);
    }

    private static void overlayVertex(VertexConsumer vc, MatrixStack.Entry entry, Vec3d p,
                                      float u, float v, double nx, double nz) {
        vc.vertex(entry, (float) p.x, (float) p.y, (float) p.z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                // Constant normal prevents yaw-dependent diffuse shading on the overlay.
                .normal(entry, 0.0f, 1.0f, 0.0f);
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
            searchFocused = false;
            finishSeekDrag(SpotifyPanelMod.spotify);
            dragMode = DRAG_NONE;
            lastDragSeekMs = -1;
            lastDragVolume = -1;
            wasLeftDown = false;
            return;
        }

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        Vec3d ray = mouseRay(mc, mx, my, sw, sh);
        if (ray == null) {
            if (released) {
                finishSeekDrag(SpotifyPanelMod.spotify);
                dragMode = DRAG_NONE;
                lastDragSeekMs = -1;
                lastDragVolume = -1;
            }
            wasLeftDown = down;
            return;
        }

        Vec3d faceCenter = modelPoint(MODEL_OVERLAY_X, MODEL_CENTER_Y, 0, cx, cy, cz, wx, wz, nx, nz);
        // Unbounded intersection is used while dragging so the pointer can move slightly
        // outside the bar and still clamp to 0% / 100% without losing the drag.
        double[] uv = intersectUvUnbounded(cameraPos, ray, faceCenter, wx, wz, nx, nz, MODEL_WIDTH, MODEL_HEIGHT);
        if (uv == null) {
            if (released) {
                finishSeekDrag(SpotifyPanelMod.spotify);
                dragMode = DRAG_NONE;
                lastDragSeekMs = -1;
                lastDragVolume = -1;
            }
            wasLeftDown = down;
            return;
        }
        int tx = (int) Math.round(uv[0] * UI_W);
        int ty = (int) Math.round(uv[1] * UI_H);
        SpotifyClient client = SpotifyPanelMod.spotify;

        int bottomTop = py(2.3), bottomBottom = py(0.2);
        int progressX0 = px(-3.0), progressX1 = px(3.0), progressY = bottomBottom - 12;
        int volumeX0 = volumeSliderX0(), volumeX1 = volumeSliderX1(), volumeY = volumeSliderY();

        // Update the visual thumb on each frame. Seeking is committed only on release;
        // volume is smoothed/coalesced by SpotifyClient while the pointer moves.
        if (dragMode != DRAG_NONE) {
            if (down || released) updateDrag(client, tx, dragMode == DRAG_SEEK);
            if (released) {
                finishSeekDrag(client);
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
                updateDrag(client, tx, true);
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
                updateDrag(client, tx, false);
                wasLeftDown = down;
                return;
            }
        }

        // All remaining click actions must start inside the actual panel surface.
        if (uv[0] < 0 || uv[0] > 1 || uv[1] < 0 || uv[1] > 1) {
            wasLeftDown = down;
            return;
        }

        // The new dedicated Blockbench search element defines the centered input housing.
        if (tx >= px(-4.0) && tx <= px(4.0) && ty >= py(13.9) && ty <= py(12.85)) {
            searchFocused = true;
            if (tx >= px(3.35)) setSearchText("");
            wasLeftDown = down;
            return;
        }
        searchFocused = false;

        if (tx >= px(-10.8) && tx <= px(-9.6) && ty >= py(12.7) + 45 && ty <= py(2.4)) {
            double relativeSlot = (ty - (py(12.7) + 53)) / 46.0;
            int visibleSlot = Math.max(0, (int) Math.floor(relativeSlot));
            int entryIndex = Math.max(0, (int) Math.floor(Math.max(0.0, relativeSlot) + sideScrollVisual));
            if (visibleSlot < 7 && entryIndex < libraryEntries.size()) {
                SpotifyClient.LibraryEntry clickedEntry = libraryEntries.get(entryIndex);
                selectedLibraryKey = clickedEntry.key();
                trackScroll = 0;
                lastRequestedLibraryKey = "";
                // Clicking any library icon (playlist, album, liked songs, artist,
                // podcast or audiobook) starts that section instead of only selecting it.
                // SpotifyClient loads the first item asynchronously where needed.
                client.playLibraryEntry(clickedEntry, SpotifyPanelMod::say);

            }
            wasLeftDown = down;
            return;
        }

        // Search results occupy the central track area while a query is present.
        if (searchMode() && tx >= px(-9.4) && tx <= px(5.2)
                && ty >= py(SEARCH_RESULTS_TOP) + 59 && ty <= py(2.4)) {
            int itemIndex = (int) Math.floor((ty - (py(SEARCH_RESULTS_TOP) + 59))
                    / (double) SEARCH_RESULT_ROW_HEIGHT + trackScrollVisual);
            if (itemIndex >= 0 && itemIndex < searchResults.size()) {
                client.playSearchResult(searchResults.get(itemIndex), SpotifyPanelMod::say);
            }
            wasLeftDown = down;
            return;
        }

        // Content rows inside the selected library section.
        if (!searchMode() && selected != null && items != null && tx >= px(-9.4) && tx <= px(5.2)
                && ty >= py(9.2) + 43 && ty <= py(2.4)) {
            int itemIndex = (int) Math.floor((ty - (py(9.2) + 43)) / 42.0 + trackScrollVisual);
            if (itemIndex >= 0 && itemIndex < items.size()) {
                client.playLibraryItem(selected, items.get(itemIndex), SpotifyPanelMod::say);
                wasLeftDown = down;
                return;
            }
        }

        // Bottom transport controls (progress and volume are handled by drag above).
        if (ty >= bottomTop && ty <= bottomBottom) {
            int center = (px(-3.0) + px(3.0)) / 2;
            if (ty <= bottomTop + 49 && tx >= center - 58 && tx <= center + 58) {
                if (tx < center - 17) client.previous(SpotifyPanelMod::say);
                else if (tx > center + 17) client.next(SpotifyPanelMod::say);
                else client.togglePlay(SpotifyPanelMod::say);
                wasLeftDown = down;
                return;
            }
        }
        if (tx >= px(5.4) && ty >= py(7.3) && ty <= py(2.6)) {
            client.togglePlay(SpotifyPanelMod::say);
        }
        wasLeftDown = down;
    }

    private static void finishSeekDrag(SpotifyClient client) {
        if (dragMode == DRAG_SEEK && lastDragSeekMs >= 0) {
            client.seek(lastDragSeekMs, SpotifyPanelMod::say);
        }
    }

    private static void updateDrag(SpotifyClient client, int textureX, boolean seek) {
        if (seek) {
            SpotifyClient.Snapshot snapshot = client.snapshot();
            SpotifyClient.Track track = snapshot.track();
            if (track == null || track.durationMs() <= 0) return;
            int x0 = px(-3.0), x1 = px(3.0);
            double fraction = Math.max(0.0, Math.min(1.0, (textureX - x0) / (double) (x1 - x0)));
            long target = Math.max(0, Math.min(track.durationMs(), Math.round(track.durationMs() * fraction)));
            if (target != lastDragSeekMs) {
                // Scrub locally while dragging. Send a single seek when the mouse is released;
                // issuing repeated remote seeks is what makes Spotify playback stutter.
                lastDragSeekMs = target;
            }
        } else {
            int x0 = volumeSliderX0(), x1 = volumeSliderX1();
            double fraction = Math.max(0.0, Math.min(1.0, (textureX - x0) / (double) (x1 - x0)));
            int target = (int) Math.round(fraction * 100.0);
            if (target != lastDragVolume) {
                // Snapshot changes immediately; backend HTTP calls keep only the newest value.
                client.volume(target, SpotifyPanelMod::say);
                lastDragVolume = target;
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

    /**
     * Draw UI text directly at its final texture resolution.
     * The previous half-resolution glyph rasterizer enlarged already aliased glyphs;
     * at oblique world-space angles this created long blocky streaks and overlapping rows.
     * Pixel character is now kept in the panels, frames and controls, not forced into text.
     */
    private static void drawUiText(Graphics2D target, String text, int x, int baselineY, Color color) {
        if (text == null || text.isEmpty()) return;
        Color oldColor = target.getColor();
        Object oldTextAA = target.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Object oldFractional = target.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
        target.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        target.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        target.setColor(color == null ? oldColor : color);
        target.drawString(text, x, baselineY);
        target.setColor(oldColor);
        if (oldTextAA != null) target.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldTextAA);
        if (oldFractional != null) target.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, oldFractional);
    }

    private static void drawReadableText(Graphics2D g, String text, int x, int y, Color color) {
        if (text == null || text.isEmpty()) return;
        Color old = g.getColor();
        drawUiText(g, text, x + 1, y + 1, new Color(0, 0, 0, 170));
        drawUiText(g, text, x, y, color == null ? old : color);
    }

    /** Square, stair-stepped corner frame; typography itself stays smooth and readable. */
    private static void paintPixelFrame(Graphics2D g, int x, int y, int w, int h, Color color, int thickness, int corner) {
        if (w <= 0 || h <= 0) return;
        Color old = g.getColor();
        g.setColor(color);
        g.fillRect(x + corner, y, Math.max(0, w - corner * 2), thickness);
        g.fillRect(x + corner, y + h - thickness, Math.max(0, w - corner * 2), thickness);
        g.fillRect(x, y + corner, thickness, Math.max(0, h - corner * 2));
        g.fillRect(x + w - thickness, y + corner, thickness, Math.max(0, h - corner * 2));
        g.fillRect(x + 2, y + 2, Math.max(0, corner - 2), thickness);
        g.fillRect(x + w - corner, y + 2, Math.max(0, corner - 2), thickness);
        g.fillRect(x + 2, y + h - thickness - 2, Math.max(0, corner - 2), thickness);
        g.fillRect(x + w - corner, y + h - thickness - 2, Math.max(0, corner - 2), thickness);
        g.setColor(old);
    }

    private static void fillSteppedGradient(Graphics2D g, int x, int y, int w, int h, Color top, Color bottom, int bands) {
        int count = Math.max(2, bands);
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            int r = Math.round(top.getRed() * (1 - t) + bottom.getRed() * t);
            int gr = Math.round(top.getGreen() * (1 - t) + bottom.getGreen() * t);
            int b = Math.round(top.getBlue() * (1 - t) + bottom.getBlue() * t);
            int y0 = y + i * h / count;
            int y1 = y + (i + 1) * h / count;
            g.setColor(new Color(r, gr, b, 242));
            g.fillRect(x, y0, w, Math.max(1, y1 - y0));
        }
    }

    private static void fillPixelButton(Graphics2D g, int x, int y, int w, int h) {
        // Three rectangular tiers create a simple pixel-rounded button with square pixels.
        g.fillRect(x + 5, y, w - 10, h);
        g.fillRect(x + 2, y + 3, w - 4, h - 6);
        g.fillRect(x, y + 6, w, h - 12);
    }

    private static void prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static void drawCover(Graphics2D g, int[] pixels, int size, int x, int y, int target) {
        if (target <= 0) return;
        if (pixels != null && pixels.length >= size * size) {
            BufferedImage cover = COVER_IMAGE_BUFFERS.computeIfAbsent(size,
                    imageSize -> new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB));
            cover.setRGB(0, 0, size, size, pixels, 0, size);
            g.drawImage(cover, x, y, target, target, null);
        } else {
            g.setColor(new Color(17, 34, 23, 230));
            g.fillRect(x, y, target, target);
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
        return (int) Math.round((modelZ + 11.0) / 22.0 * UI_W);
    }

    private static int py(double modelY) {
        return (int) Math.round((14.0 - modelY) / 14.0 * UI_H);
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
        g.fillRect(x - 8, y - 8, 2, 16);
        g.fillPolygon(new Polygon(new int[]{x + 7, x + 7, x - 5}, new int[]{y - 8, y + 8, y}, 3));
    }

    private static void drawNext(Graphics2D g, int x, int y) {
        g.fillRect(x + 5, y - 8, 2, 16);
        g.fillPolygon(new Polygon(new int[]{x - 7, x - 7, x + 5}, new int[]{y - 8, y + 8, y}, 3));
    }

    private static int volumeGroupLeft() {
        // Center the larger speaker + slider group directly below the right-hand panel.
        int panelLeft = px(5.4), panelRight = px(10.8);
        int groupWidth = 170;
        return (panelLeft + panelRight - groupWidth) / 2;
    }

    private static int volumeSliderX0() {
        // Keep the slider close to the speaker: about 10px of clear space after its visible edge.
        return volumeGroupLeft() + 42;
    }

    private static int volumeSliderX1() {
        return volumeGroupLeft() + 170;
    }

    private static int volumeSliderY() {
        return (py(2.3) + py(0.2)) / 2;
    }

    private static BufferedImage loadVolumeIcon(String resource) {
        try (InputStream in = WorldPanel.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing volume icon: " + resource);
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() != 32 || image.getHeight() != 32) {
                throw new IllegalStateException("Volume icon must be a 32x32 PNG: " + resource);
            }
            return image;
        } catch (Exception e) {
            System.err.println("[SpotifyPanel] Could not load volume icon " + resource + ": " + e.getMessage());
            return null;
        }
    }

    private static void drawVolumeIcon(Graphics2D g, int x, int y, int volume) {
        BufferedImage icon = volume <= 0 ? VOLUME_ICON_MUTED
                : volume < 45 ? VOLUME_ICON_LOW : VOLUME_ICON_HIGH;
        if (icon == null) return;

        Object previousInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(icon, x, y, 32, 32, null);
        if (previousInterpolation != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previousInterpolation);
        }
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
