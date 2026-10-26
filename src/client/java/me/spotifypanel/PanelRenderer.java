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
