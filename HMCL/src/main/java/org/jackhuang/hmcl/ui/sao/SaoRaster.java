/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.jetbrains.annotations.NotNullByDefault;

/// A tiny software rasteriser: flat polygons and per-vertex-coloured triangles blended source-over into an
/// ARGB buffer. It lets the launcher draw the visor with the same geometry the pack's helmet screen uses.
@NotNullByDefault
final class SaoRaster {
    /// Width in pixels.
    final int w;
    /// Height in pixels.
    final int h;
    /// The pixels, ARGB, premultiplied by nothing (straight alpha, opaque ground).
    final int[] px;

    /// Builds a buffer filled with one colour.
    ///
    /// @param w      width
    /// @param h      height
    /// @param ground opaque RGB to start from
    SaoRaster(int w, int h, int ground) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        px = new int[this.w * this.h];
        java.util.Arrays.fill(px, 0xff000000 | ground);
    }

    /// Builds a transparent buffer (an overlay drawn over something else).
    ///
    /// @param w width
    /// @param h height
    SaoRaster(int w, int h) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        px = new int[this.w * this.h];
    }

    /// Fills a convex or fan-able polygon in one colour.
    ///
    /// @param argb the colour
    /// @param xy   x, y pairs
    void polygon(int argb, float... xy) {
        int n = xy.length / 2;
        for (int i = 1; i + 1 < n; i++)
            tri(xy[0], xy[1], xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3], argb, argb, argb);
    }

    /// Draws triangles with a colour per corner.
    ///
    /// @param xy      x, y per corner, three corners per triangle
    /// @param argb    one colour per corner
    /// @param corners how many corners to use
    void mesh(float[] xy, int[] argb, int corners) {
        for (int i = 0; i + 2 < corners; i += 3)
            tri(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3], xy[2 * i + 4], xy[2 * i + 5], argb[i], argb[i + 1], argb[i + 2]);
    }

    /// Rasterises one triangle by pixel centres with the top-left rule, so two triangles that share an edge
    /// never both cover a pixel on it (a double blend would draw a visible seam through the glass).
    private void tri(float x0, float y0, float x1, float y1, float x2, float y2, int c0, int c1, int c2) {
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        if (area < 0) {
            // Make the winding consistent: swap corners 1 and 2.
            float tx = x1, ty = y1;
            x1 = x2;
            y1 = y2;
            x2 = tx;
            y2 = ty;
            int tc = c1;
            c1 = c2;
            c2 = tc;
            area = -area;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        boolean flat = c0 == c1 && c1 == c2;
        // Edge k is the one opposite corner k; it is "top-left" when it runs leftward horizontally or upward.
        boolean tl0 = topLeft(x1, y1, x2, y2), tl1 = topLeft(x2, y2, x0, y0), tl2 = topLeft(x0, y0, x1, y1);
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float pxf = x + 0.5f;
                float e0 = (x2 - x1) * (py - y1) - (y2 - y1) * (pxf - x1);
                float e1 = (x0 - x2) * (py - y2) - (y0 - y2) * (pxf - x2);
                float e2 = (x1 - x0) * (py - y0) - (y1 - y0) * (pxf - x0);
                if (!inside(e0, tl0) || !inside(e1, tl1) || !inside(e2, tl2)) continue;
                int c = flat ? c0 : lerp3(c0, c1, c2, e0 / area, e1 / area, e2 / area);
                blend(y * w + x, c);
            }
        }
    }

    /// Whether a pixel is inside an edge: strictly, or exactly on it for a top-left edge.
    private static boolean inside(float e, boolean topLeft) {
        return e > 0 || e == 0 && topLeft;
    }

    /// Whether an edge (for corners wound as in [#tri]) is a top or left edge.
    private static boolean topLeft(float ax, float ay, float bx, float by) {
        return ay == by && bx < ax || by < ay;
    }

    /// Mixes three colours by weights.
    private static int lerp3(int a, int b, int c, float wa, float wb, float wc) {
        int al = Math.round((a >>> 24) * wa + (b >>> 24) * wb + (c >>> 24) * wc);
        int r = Math.round((a >> 16 & 255) * wa + (b >> 16 & 255) * wb + (c >> 16 & 255) * wc);
        int g = Math.round((a >> 8 & 255) * wa + (b >> 8 & 255) * wb + (c >> 8 & 255) * wc);
        int bl = Math.round((a & 255) * wa + (b & 255) * wb + (c & 255) * wc);
        return clamp(al) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(bl);
    }

    /// Clamps to a byte.
    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    /// Blends a colour over one pixel.
    private void blend(int i, int c) {
        int a = c >>> 24;
        if (a == 0) return;
        int d = px[i];
        if (a == 255) {
            px[i] = c;
            return;
        }
        int da = d >>> 24;
        if (da != 255) {
            // Over a see-through pixel: straight-alpha "over".
            int oa = a + da * (255 - a) / 255;
            if (oa == 0) return;
            int k = da * (255 - a) / 255;
            int r = ((c >> 16 & 255) * a + (d >> 16 & 255) * k) / oa;
            int g = ((c >> 8 & 255) * a + (d >> 8 & 255) * k) / oa;
            int b = ((c & 255) * a + (d & 255) * k) / oa;
            px[i] = oa << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
            return;
        }
        int r = ((c >> 16 & 255) * a + (d >> 16 & 255) * (255 - a)) / 255;
        int g = ((c >> 8 & 255) * a + (d >> 8 & 255) * (255 - a)) / 255;
        int b = ((c & 255) * a + (d & 255) * (255 - a)) / 255;
        px[i] = 0xff000000 | r << 16 | g << 8 | b;
    }

    /// Averages every s × s block into one pixel (the shapes' edges come out smooth): drawn at s times the size,
    /// shown at 1.
    ///
    /// @param s the factor the buffer was drawn at
    /// @return a buffer s times smaller
    SaoRaster downsample(int s) {
        SaoRaster o = new SaoRaster(w / s, h / s);
        int n = s * s;
        for (int y = 0; y < o.h; y++) {
            for (int x = 0; x < o.w; x++) {
                long sa = 0, sr = 0, sg = 0, sb = 0;
                for (int j = 0; j < s; j++) {
                    int row = (y * s + j) * w + x * s;
                    for (int i = 0; i < s; i++) {
                        int c = px[row + i], a = c >>> 24;
                        sa += a;
                        sr += (long) (c >> 16 & 255) * a;
                        sg += (long) (c >> 8 & 255) * a;
                        sb += (long) (c & 255) * a;
                    }
                }
                if (sa == 0) continue;
                o.px[y * o.w + x] = (int) ((sa + n / 2) / n) << 24 | clamp((int) (sr / sa)) << 16
                        | clamp((int) (sg / sa)) << 8 | clamp((int) (sb / sa));
            }
        }
        return o;
    }

    /// Copies the buffer into a JavaFX image.
    ///
    /// @return the image
    WritableImage image() {
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return img;
    }
}
