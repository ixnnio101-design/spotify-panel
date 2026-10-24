package me.spotifypanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Low-poly панель: всё рисуется плоскими цветными гранями (треугольниками), без текстур. */
public final class PanelRenderer {
    private static final int W = 176, H = 64;
    private static final int MARGIN = 6;
    private static final int CELL = 4; // 12 * 4 = 48 px обложка

    private static final int BG = 0xE60E1217;
    private static final int GREEN = 0xFF1DB954;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B8C2;
    private static final int TRACK = 0xFF2A323C;

    private PanelRenderer() {}

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        if (!SpotifyPanelMod.config.visible) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        SpotifyClient.Snapshot s = SpotifyPanelMod.spotify.snapshot();

        int x = MARGIN, y = MARGIN;
        drawBackground(ctx, x, y);

        int textX = x + 64;
        int textW = W - 64 - 8;

        switch (s.status()) {
            case NO_CLIENT_ID -> {
                drawCoverPlaceholder(ctx, x + 8, y + 8);
                text(ctx, tr, "Spotify не настроен", textX, y + 12, WHITE, textW);
                text(ctx, tr, "/spotify setup <client_id>", textX, y + 26, GRAY, textW);
            }
            case LOGGED_OUT -> {
                drawCoverPlaceholder(ctx, x + 8, y + 8);
                text(ctx, tr, "Не подключён", textX, y + 12, WHITE, textW);
                text(ctx, tr, "/spotify login", textX, y + 26, GREEN, textW);
            }
            case IDLE -> {
                drawCoverPlaceholder(ctx, x + 8, y + 8);
                text(ctx, tr, "Ничего не играет", textX, y + 12, WHITE, textW);
                text(ctx, tr, "Запусти трек в Spotify", textX, y + 26, GRAY, textW);
            }
            case ERROR -> {
                drawCoverPlaceholder(ctx, x + 8, y + 8);
                text(ctx, tr, "Ошибка Spotify", textX, y + 12, 0xFFFF6B6B, textW);
                text(ctx, tr, s.error(), textX, y + 26, GRAY, textW);
            }
            case ACTIVE -> drawActive(ctx, tr, s, x, y, textX, textW);
        }
    }

    private static void drawActive(DrawContext ctx, TextRenderer tr, SpotifyClient.Snapshot s,
                                   int x, int y, int textX, int textW) {
        SpotifyClient.Track t = s.track();
        int[] cover = SpotifyPanelMod.spotify.cover();
        if (cover != null) drawCover(ctx, cover, x + 8, y + 8);
        else drawCoverPlaceholder(ctx, x + 8, y + 8);

        text(ctx, tr, t.title(), textX, y + 8, WHITE, textW);
        text(ctx, tr, t.artists(), textX, y + 19, GRAY, textW);

        // прогресс-бар
        int bx = textX, by = y + 33, bw = textW, bh = 4;
        long dur = Math.max(1, t.durationMs());
        long pos = s.currentProgress();
        int fillW = (int) (bw * pos / dur);
        ctx.fill(bx, by, bx + bw, by + bh, TRACK);
        ctx.fill(bx, by, bx + fillW, by + bh, GREEN);
        // ромбик-головка
        int hx = bx + fillW, hy = by + bh / 2;
        tri(ctx, hx - 4, hy, hx, hy - 4, hx + 4, hy, WHITE);
        tri(ctx, hx - 4, hy, hx + 4, hy, hx, hy + 4, 0xFFD9D9D9);

        // иконка play/pause (грани)
        int ix = textX, iy = y + 43;
        if (s.playing()) {
            ctx.fill(ix, iy, ix + 3, iy + 9, GREEN);
            ctx.fill(ix + 5, iy, ix + 8, iy + 9, GREEN);
        } else {
            tri(ctx, ix, iy, ix + 8, iy + 4.5f, ix, iy + 9, GREEN);
        }
        String time = fmt(pos) + " / " + fmt(dur);
        ctx.drawText(tr, time, ix + 13, iy + 1, GRAY, false);

        String vol = s.volume() + "%";
        ctx.drawText(tr, vol, x + W - 8 - tr.getWidth(vol), iy + 1, GRAY, false);
    }

    // --------------------------------------------------------------- shapes

    private static void drawBackground(DrawContext ctx, int x, int y) {
        ctx.fill(x, y, x + W, y + H, BG);
        // «осколки» — полупрозрачные грани разной яркости
        tri(ctx, x, y, x + W * 0.55f, y, x, y + H * 0.85f, 0x16FFFFFF);
        tri(ctx, x + W * 0.55f, y, x + W, y, x + W, y + H * 0.6f, 0x0CFFFFFF);
        tri(ctx, x, y + H, x + W * 0.4f, y + H, x, y + H * 0.45f, 0x20000000);
        tri(ctx, x + W * 0.35f, y + H, x + W, y + H * 0.35f, x + W, y + H, 0x26000000);
        // зелёный акцент в нижнем правом углу
        tri(ctx, x + W, y + H - 22, x + W, y + H, x + W - 22, y + H, 0xCC1DB954);
        tri(ctx, x + W, y + H - 10, x + W, y + H, x + W - 10, y + H, 0xFF1DB954);
        // рамка
        ctx.fill(x, y, x + W, y + 1, 0x501DB954);
        ctx.fill(x, y + H - 1, x + W, y + H, 0x30000000);
    }

    private static void drawCover(DrawContext ctx, int[] cover, int x, int y) {
        int g = SpotifyClient.GRID;
        for (int cy = 0; cy < g; cy++) {
            for (int cx = 0; cx < g; cx++) {
                int i = (cy * g + cx) * 2;
                int lo = cover[i], hi = cover[i + 1];
                int px = x + cx * CELL, py = y + cy * CELL;
                for (int dy = 0; dy < CELL; dy++) {
                    ctx.fill(px, py + dy, px + dy + 1, py + dy + 1, lo);
                    if (dy + 1 < CELL) ctx.fill(px + dy + 1, py + dy, px + CELL, py + dy + 1, hi);
                }
            }
        }
    }

    private static void drawCoverPlaceholder(DrawContext ctx, int x, int y) {
        int s = SpotifyClient.GRID * CELL;
        ctx.fill(x, y, x + s, y + s, 0xFF1C2430);
        tri(ctx, x, y, x + s, y, x, y + s, 0xFF263142);
        tri(ctx, x + s, y, x + s, y + s, x + s * 0.3f, y + s, 0xFF161D28);
        tri(ctx, x + s * 0.5f, y + s * 0.25f, x + s * 0.8f, y + s * 0.5f, x + s * 0.5f, y + s * 0.75f, GREEN);
    }

    /** Закрашенный треугольник построчной заливкой (по 1 px). */
    private static void tri(DrawContext c, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
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
                if (r > l) c.fill(l, y, r, y + 1, color);
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

    // ----------------------------------------------------------------- text

    private static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color, int maxW) {
        if (s == null) s = "";
        if (tr.getWidth(s) > maxW) {
            s = tr.trimToWidth(s, maxW - tr.getWidth("…")) + "…";
        }
        ctx.drawText(tr, s, x, y, color, true);
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }
}
