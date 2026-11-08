/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.jetbrains.annotations.NotNullByDefault;

/// The still reflection on the visor glass, painted once per window size.
///
/// James, 8 Nov: "光泽留着但不动，变成一块固定的反光。这个吧", then "我看不到任何光泽" / "调亮到看得见吧". It follows
/// S01E01 at 86.5 s, the NerveGear visor close up: a thin pale streak down the glass's right edge, a rounded head that
/// tapers to a hairline, then two short dashes below it, with a faint glow around them. It sits in the right-hand
/// strip of glass between the battery and the server window, which no window or text reaches.
@NotNullByDefault
final class SaoGlint {
    /// Not instantiable.
    private SaoGlint() {
    }

    /// One stroke: a quadratic curve in design pixels (1280 x 720) and its width at the start, widest point and end.
    private record Stroke(double x0, double y0, double cx, double cy, double x1, double y1,
                          double w0, double wMax, double peak, double w1) {
        double x(double s) {
            return (1 - s) * (1 - s) * x0 + 2 * (1 - s) * s * cx + s * s * x1;
        }

        double y(double s) {
            return (1 - s) * (1 - s) * y0 + 2 * (1 - s) * s * cy + s * s * y1;
        }

        /// The width along the stroke: a quick swell to the head, then a long taper.
        double width(double s) {
            if (s < peak) return w0 + (wMax - w0) * Math.sqrt(s / peak);
            double u = (s - peak) / (1 - peak);
            return w1 + (wMax - w1) * Math.pow(1 - u, 1.4);
        }
    }

    /// The streak and its dashes, top to bottom, bowed slightly outward like the curved glass.
    private static final Stroke[] STROKES = {
            new Stroke(1192, 122, 1203, 210, 1205, 330, 3, 10, 0.07, 1.2),
            new Stroke(1207, 352, 1209, 385, 1209, 422, 1.4, 3.4, 0.35, 1.0),
            new Stroke(1209, 446, 1209, 482, 1206, 520, 1.2, 2.6, 0.4, 0.8),
    };
    /// The box the strokes and their glow stay inside, in design pixels.
    static final double LEFT = 1176, TOP = 106, RIGHT = 1226, BOTTOM = 536;
    /// The glow's reach beyond a stroke's edge, in design pixels.
    private static final double GLOW = 5;
    /// The light: near-white with a little of the visor's cyan.
    private static final double CORE_R = 0.90, CORE_G = 0.97, CORE_B = 1.0, CORE_A = 0.82;
    private static final double GLOW_R = 0.62, GLOW_G = 0.84, GLOW_B = 1.0, GLOW_A = 0.20;

    /// Paints the reflection for one size.
    ///
    /// @param scale device pixels per design pixel
    /// @return the picture of the box [#LEFT]..[#BOTTOM] at that scale
    static WritableImage render(double scale) {
        int w = (int) Math.ceil((RIGHT - LEFT) * scale), h = (int) Math.ceil((BOTTOM - TOP) * scale);
        int[] px = new int[w * h];
        int n = 160;
        double[][] xs = new double[STROKES.length][n + 1], ys = new double[STROKES.length][n + 1], ws = new double[STROKES.length][n + 1];
        for (int i = 0; i < STROKES.length; i++)
            for (int j = 0; j <= n; j++) {
                double s = j / (double) n;
                xs[i][j] = (STROKES[i].x(s) - LEFT) * scale;
                ys[i][j] = (STROKES[i].y(s) - TOP) * scale;
                ws[i][j] = STROKES[i].width(s) * scale;
            }
        double glow = GLOW * scale;
        // Each stroke's reach, so a pixel only measures the strokes near it (the picture is remade on every resize).
        double[][] reach = new double[STROKES.length][4];
        for (int i = 0; i < STROKES.length; i++) {
            double m = STROKES[i].wMax * scale / 2 + glow + 1;
            reach[i] = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int j = 0; j <= n; j++) {
                reach[i][0] = Math.min(reach[i][0], xs[i][j] - m);
                reach[i][1] = Math.min(reach[i][1], ys[i][j] - m);
                reach[i][2] = Math.max(reach[i][2], xs[i][j] + m);
                reach[i][3] = Math.max(reach[i][3], ys[i][j] + m);
            }
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double px0 = x + 0.5, py0 = y + 0.5, core = 0, halo = 0;
                for (int i = 0; i < STROKES.length; i++) {
                    if (px0 < reach[i][0] || py0 < reach[i][1] || px0 > reach[i][2] || py0 > reach[i][3]) continue;
                    // The distance to the stroke's middle line and the half width there.
                    double best = Double.MAX_VALUE, half = 0;
                    for (int j = 0; j < n; j++) {
                        double ax = xs[i][j], ay = ys[i][j], dx = xs[i][j + 1] - ax, dy = ys[i][j + 1] - ay;
                        double len = dx * dx + dy * dy, t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px0 - ax) * dx + (py0 - ay) * dy) / len));
                        double ex = px0 - ax - t * dx, ey = py0 - ay - t * dy, d = ex * ex + ey * ey;
                        if (d < best) {
                            best = d;
                            half = (ws[i][j] + t * (ws[i][j + 1] - ws[i][j])) / 2;
                        }
                    }
                    double out = Math.sqrt(best) - half;
                    core = Math.max(core, Math.max(0, Math.min(1, 0.5 - out)));
                    if (out < glow) halo = Math.max(halo, out <= 0 ? 1 : Math.pow(1 - out / glow, 2));
                }
                double ca = core * CORE_A, ga = halo * GLOW_A * (1 - ca), a = ca + ga;
                if (a <= 0) continue;
                double r = (CORE_R * ca + GLOW_R * ga) / a, g = (CORE_G * ca + GLOW_G * ga) / a, b = (CORE_B * ca + GLOW_B * ga) / a;
                px[y * w + x] = (int) Math.round(a * 255) << 24 | (int) Math.round(r * 255) << 16 | (int) Math.round(g * 255) << 8 | (int) Math.round(b * 255);
            }
        WritableImage image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return image;
    }
}
