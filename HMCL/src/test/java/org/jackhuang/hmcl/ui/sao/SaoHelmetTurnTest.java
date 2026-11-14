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
}
