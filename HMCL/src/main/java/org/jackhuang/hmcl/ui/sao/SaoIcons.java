/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.jetbrains.annotations.NotNullByDefault;

/// The few line icons the SAO screen draws on its round white buttons, in the panel blue.
@NotNullByDefault
public final class SaoIcons {
    /// The panel blue used for icon strokes.
    static final Color BLUE = Color.web("#1b7cc6");

    /// Not instantiable.
    private SaoIcons() {
    }

    /// Builds an icon from a 24×24 path.
    ///
    /// @param d    the SVG path data
    /// @param fill whether the path is filled (true) or stroked (false)
    /// @return the icon node
    private static SVGPath icon(String d, boolean fill) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        if (fill) {
            p.setFill(BLUE);
        } else {
            p.setFill(Color.TRANSPARENT);
            p.setStroke(BLUE);
            p.setStrokeWidth(2);
        }
        p.setScaleX(0.72);
        p.setScaleY(0.72);
        return p;
    }

    /// A music note.
    ///
    /// @return the icon
    static SVGPath music() {
        return icon("M9 18V5l11-2v13 M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0z M20 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z", false);
    }

    /// A shirt, for the skin picker.
    ///
    /// @return the icon
    static SVGPath skin() {
        return icon("M8 3l-5 3 2 5 3-1v11h8V10l3 1 2-5-5-3c0 2-1.8 3-4 3s-4-1-4-3z", false);
    }

    /// A minus, to minimise the window.
    ///
    /// @return the icon
    static SVGPath minimize() {
        return icon("M5 12h14", false);
    }

    /// A cross, to close the window.
    ///
    /// @return the icon
    static SVGPath close() {
        return icon("M6 6l12 12M18 6L6 18", false);
    }
}
