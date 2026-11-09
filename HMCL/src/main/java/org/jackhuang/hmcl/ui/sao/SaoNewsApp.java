/*
 * NerveGear启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.sao.SaoData;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// One announcement in its own window (James, 9 Nov: "启动器点击某个公告能像别的一样单开一个窗口吗"): the title big,
/// its kind and date, then the whole body, wrapped with its line breaks kept and scrolled inside the window.
@NotNullByDefault
final class SaoNewsApp {
    /// The body's width inside the window.
    private static final double BODY_W = SaoApps.W - 64;

    /// Not instantiable.
    private SaoNewsApp() {
    }

    /// The short preview on the 公告 card: the lines joined, cut at about `max` characters with an ellipsis.
    ///
    /// @param body the whole body
    /// @param max  the most characters kept
    /// @return the preview
    static String preview(String body, int max) {
        String flat = body.strip().replaceAll("\\s*\\n\\s*", "  ");
        return flat.codePointCount(0, flat.length()) <= max ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, max)).stripTrailing() + "…";
    }

    /// Builds the window.
    ///
    /// @param n     the announcement
    /// @param close closes it
    /// @return the window
    static SaoFloatWindow make(SaoData.News n, Runnable close) {
        Text title = ink(n.title(), 22);
        SaoUiKit.font(title, 22, true);
        title.setWrappingWidth(BODY_W);
        HBox kind = new HBox(sao(n.kind() == SaoData.NewsKind.UPDATE ? "更新" : "公告", 12, false));
        kind.setAlignment(Pos.CENTER);
        kind.getStyleClass().add("vr-name");
        HBox meta = new HBox(10, kind, dim(n.date(), 13));
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox lines = new VBox(7);
        for (String line : n.body().split("\n", -1)) {
            String l = line.stripTrailing();
            if (l.isBlank()) {
                javafx.scene.layout.Region gap = new javafx.scene.layout.Region();
                gap.setMinHeight(6);
                lines.getChildren().add(gap);
            } else if (l.startsWith("· ") || l.startsWith("•")) {
                // A bullet keeps its dot apart, so wrapped lines hang under the text.
                Text dot = dim("·", 15);
                Text t = ink(l.substring(l.startsWith("· ") ? 2 : 1).strip(), 15);
                t.setLineSpacing(4);
                t.setWrappingWidth(BODY_W - 22);
                HBox row = new HBox(10, dot, t);
                row.setAlignment(Pos.TOP_LEFT);
                lines.getChildren().add(row);
            } else {
                Text t = ink(l, 15);
                t.setLineSpacing(4);
                t.setWrappingWidth(BODY_W);
                lines.getChildren().add(t);
            }
        }
        lines.setPadding(new Insets(4, 12, 12, 0));
        VBox head = new VBox(8, title, meta);
        head.setPadding(new Insets(0, 0, 6, 0));
        VBox box = new VBox(10, head, rule(), scroll(lines));
        VBox.setVgrow(box, Priority.ALWAYS);
        return new SaoFloatWindow("公告", SaoApps.W, SaoApps.H, close).add(box);
    }
}
