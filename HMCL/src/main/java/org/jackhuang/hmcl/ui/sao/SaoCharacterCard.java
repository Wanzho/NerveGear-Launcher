/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// The character's face on the visor, read from the skin file (face plus hat layer, drawn pixel-sharp),
/// or a plain outline when no skin is set.
@NotNullByDefault
final class SaoCharacterCard extends VBox {
    /// Pixels per skin pixel.
    private static final int K = 10;
    /// The face canvas.
    private final Canvas face = new Canvas(8 * K + 4, 8 * K + 4);

    /// Builds the card.
    SaoCharacterCard() {
        setAlignment(Pos.CENTER);
        getChildren().add(face);
        show(null);
    }

    /// Shows a skin's face.
    ///
    /// @param skin the PNG, or null for the outline
    void show(@Nullable Path skin) {
        GraphicsContext g = face.getGraphicsContext2D();
        g.clearRect(0, 0, face.getWidth(), face.getHeight());
        g.setStroke(Color.web("#9dbbc8"));
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, face.getWidth() - 1, face.getHeight() - 1);
        @Nullable Image img = null;
        if (skin != null) {
            try {
                img = new Image(skin.toUri().toString(), false);
                if (img.isError() || img.getWidth() != 64) img = null;
            } catch (RuntimeException e) {
                img = null;
            }
        }
        if (img == null) {
            // No skin: a faint head outline with the default-lookup note.
            g.setFill(Color.color(0.62, 0.73, 0.78, 0.18));
            g.fillRect(2 + K, 2 + K, 6 * K, 6 * K);
            g.setFill(Color.color(0.96, 0.99, 1, 0.75));
            g.setFont(SaoAssets.sao(14, false));
            g.fillText("?", 2 + 4 * K - 3, 2 + 4 * K + 5);
            return;
        }
        PixelReader r = img.getPixelReader();
        for (int layer = 0; layer < 2; layer++) {
            int ox = layer == 0 ? 8 : 40;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    int argb = r.getArgb(ox + x, 8 + y);
                    if (argb >>> 24 == 0) continue;
                    g.setFill(Color.rgb(argb >> 16 & 255, argb >> 8 & 255, argb & 255, (argb >>> 24) / 255.0));
                    g.fillRect(2 + x * K, 2 + y * K, K, K);
                }
        }
    }
}
