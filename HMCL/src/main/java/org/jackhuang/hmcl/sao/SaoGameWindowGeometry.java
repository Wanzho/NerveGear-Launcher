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
package org.jackhuang.hmcl.sao;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.Optional;

/// Content-area geometry; GLFW positions exclude the normal OS title bar.
@NotNullByDefault
public final class SaoGameWindowGeometry {
    /// No instances.
    private SaoGameWindowGeometry() {}

    /// Converts primary-monitor video-mode centring to JavaFX coordinates without opening a window.
    public static Optional<Rectangle2D> content(Rectangle2D primary, double scaleX, double scaleY,
                                                boolean mac, SaoData.GameWindow game) {
        if (game.fullscreen()) return Optional.empty();
        // Cocoa GLFW coordinates are points. Win32 GLFW 3.3 sizes are physical pixels.
        // Linux uses scale 1 until a measured backend contract establishes otherwise.
        double w = game.width() / (mac ? 1 : scaleX), h = game.height() / (mac ? 1 : scaleY);
        return Optional.of(new Rectangle2D(primary.getMinX() + (primary.getWidth() - w) / 2,
                primary.getMinY() + (primary.getHeight() - h) / 2, w, h));
    }

    /// Adapts the primary JavaFX display; fullscreen games have no alignable rectangle.
    public static Optional<Rectangle2D> content(Screen primary, SaoData.GameWindow game) {
        String os = System.getProperty("os.name", "");
        boolean windows = os.startsWith("Windows");
        return content(primary.getBounds(), windows ? primary.getOutputScaleX() : 1,
                windows ? primary.getOutputScaleY() : 1, os.startsWith("Mac"), game);
    }

    /// Chooses cover alignment only on the game monitor, outside either fullscreen mode, for a 16:9 game.
    public static boolean alignable(Rectangle2D primary, Rectangle2D stage, boolean launcherFullscreen,
                                    SaoData.GameWindow game) {
        return !launcherFullscreen && !game.fullscreen()
                && primary.contains(stage.getMinX() + stage.getWidth() / 2, stage.getMinY() + stage.getHeight() / 2)
                && Math.abs((double) game.width() / game.height() - 16.0 / 9) <= 0.002;
    }

    /// Scales the captured page and its letterboxing, retaining fixed stage/decorator insets.
    /// A maximized stage must be un-maximized by the caller before applying these bounds.
    public static Rectangle2D target(Rectangle2D stage, Rectangle2D page, Rectangle2D picture, Rectangle2D game) {
        double r = game.getWidth() / picture.getWidth();
        double left = page.getMinX() - stage.getMinX(), top = page.getMinY() - stage.getMinY();
        return new Rectangle2D(game.getMinX() - left - (picture.getMinX() - page.getMinX()) * r,
                game.getMinY() - top - (picture.getMinY() - page.getMinY()) * r,
                stage.getWidth() - page.getWidth() + page.getWidth() * r,
                stage.getHeight() - page.getHeight() + page.getHeight() * r);
    }

    /// Straight interpolation supplied to the screen; this class owns no animation timing.
    public static Rectangle2D interpolate(Rectangle2D a, Rectangle2D b, double t) {
        double u = Math.max(0, Math.min(1, t));
        return new Rectangle2D(a.getMinX() + (b.getMinX() - a.getMinX()) * u,
                a.getMinY() + (b.getMinY() - a.getMinY()) * u,
                a.getWidth() + (b.getWidth() - a.getWidth()) * u,
                a.getHeight() + (b.getHeight() - a.getHeight()) * u);
    }
}
