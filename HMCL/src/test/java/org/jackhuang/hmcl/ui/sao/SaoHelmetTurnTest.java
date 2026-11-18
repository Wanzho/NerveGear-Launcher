/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.ui.sao;

import javafx.geometry.Point3D;
import javafx.scene.transform.Rotate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// James, 14 Nov: "画面跟着鼠标走". The helmet page's windows move the same way as the mouse on both axes.
/// Uses the page's own angles through the same pivot setup as SaoHelmetPage.frame (pivot at the eye, in front of z = 0).
final class SaoHelmetTurnTest {
    private static Point3D shift(double lookX, double lookY) {
        double eye = -1000; // the eye sits toward the viewer (JavaFX z grows into the screen)
        Rotate y = new Rotate(SaoHelmetPage.turnYDeg(lookX), 0, 0, eye, Rotate.Y_AXIS);
        Rotate x = new Rotate(SaoHelmetPage.turnXDeg(lookY), 0, 0, eye, Rotate.X_AXIS);
        return y.createConcatenation(x).transform(0, 0, 0); // a window at the screen centre
    }

    @Test void mouseRightMovesWindowsRight() {
        assertTrue(shift(0.5, 0).getX() > 1, "mouse right → windows right");
        assertTrue(shift(-0.5, 0).getX() < -1, "mouse left → windows left");
    }

    @Test void mouseUpMovesWindowsUp() {
        assertTrue(shift(0, -0.5).getY() < -1, "mouse up (negative) → windows up (screen y smaller)");
        assertTrue(shift(0, 0.5).getY() > 1, "mouse down → windows down");
    }

    @Test void smallTurn() {
        assertTrue(Math.abs(SaoHelmetPage.turnYDeg(0.5)) <= 0.6 && Math.abs(SaoHelmetPage.turnXDeg(0.5)) <= 0.45);
    }

    @Test void outsideWindowPointerClampedToEdgeMaximum() {
        assertEquals(-0.5, SaoHelmetPage.clampHead(-3.2), 1e-9);
        assertEquals(0.5, SaoHelmetPage.clampHead(4.8), 1e-9);
        assertEquals(0.25, SaoHelmetPage.clampHead(0.25), 1e-9);
        assertEquals(SaoHelmetPage.turnYDeg(0.5), SaoHelmetPage.turnYDeg(5.0), 1e-9);
        assertEquals(SaoHelmetPage.turnYDeg(-0.5), SaoHelmetPage.turnYDeg(-5.0), 1e-9);
        assertEquals(SaoHelmetPage.turnXDeg(0.5), SaoHelmetPage.turnXDeg(5.0), 1e-9);
        assertEquals(SaoHelmetPage.turnXDeg(-0.5), SaoHelmetPage.turnXDeg(-5.0), 1e-9);
    }

    @Test void darkGlassDitherRemovesBandingWithoutShiftingMeanColour() {
        int w = 580, h = 860;
        int[] px = SaoUiKit.darkGlassPixels(w, h);
        double ka = 230.0 / 255.0;
        double maxRowErr = 0;
        for (int y = 0; y < h; y++) {
            double t = y / (double) (h - 1);
            double expR = (40.0 + (26.0 - 40.0) * t) * ka;
            double expG = (42.0 + (27.0 - 42.0) * t) * ka;
            double expB = (49.0 + (32.0 - 49.0) * t) * ka;
            double sr = 0, sg = 0, sb = 0;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                assertEquals(230, c >>> 24);
                sr += c >> 16 & 255;
                sg += c >> 8 & 255;
                sb += c & 255;
            }
            maxRowErr = Math.max(maxRowErr, Math.abs(sr / w - expR));
            maxRowErr = Math.max(maxRowErr, Math.abs(sg / w - expG));
            maxRowErr = Math.max(maxRowErr, Math.abs(sb / w - expB));
        }
        assertTrue(maxRowErr < 0.12, "row mean drift too high: " + maxRowErr);

        int maxRun = 0;
        for (int x = 0; x < w; x++) {
            int cur = px[x] & 0xffffff;
            int len = 1;
            for (int y = 1; y < h; y++) {
                int next = px[y * w + x] & 0xffffff;
                if (next == cur) len++;
                else {
                    maxRun = Math.max(maxRun, len);
                    cur = next;
                    len = 1;
                }
            }
            maxRun = Math.max(maxRun, len);
        }
        assertTrue(maxRun <= 14, "expected dither to break 55px flat bands, got maxRun=" + maxRun);
    }
}
