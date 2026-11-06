/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// The title lyrics at the bottom left: the Japanese line fills with light as it is sung (by the file's
/// per-word timing), the Chinese line sits under it; lines fade in and out.
@NotNullByDefault
public final class SaoLyricsView extends VBox {
    /// The sung colour.
    private static final Color SUNG = Color.web("#8ff0ff");

    /// The lyrics.
    private final SaoLyrics lyrics;
    /// The Japanese line, unsung colour.
    private final Text base = new Text();
    /// The Japanese line, sung colour, clipped to the sung part.
    private final Text lit = new Text();
    /// The clip that reveals [#lit].
    private final Rectangle reveal = new Rectangle();
    /// The Chinese line.
    private final Text sub = new Text();
    /// The line shown now, or null.
    private SaoLyrics.@Nullable Line shown;

    /// Builds the view.
    ///
    /// @param lyrics the lyrics to show
    public SaoLyricsView(SaoLyrics lyrics) {
        this.lyrics = lyrics;
        setMouseTransparent(true);
        setSpacing(2);
        Font jp = Font.font(null, FontWeight.BOLD, 20);
        for (Text t : new Text[]{base, lit}) {
            t.setFont(jp);
            t.getStyleClass().add("sao-lyric-main");
            t.setManaged(false);
        }
        base.setFill(Color.WHITE);
        base.setEffect(new DropShadow(7, 0, 1, Color.color(0, 0, 0, 0.85)));
        lit.setFill(SUNG);
        lit.setEffect(new DropShadow(10, 0, 0, Color.color(0.3, 0.85, 1, 0.9)));
        lit.setClip(reveal);
        Pane mainRow = new Pane(base, lit);
        mainRow.setPrefHeight(28);
        mainRow.setMinHeight(28);
        sub.setFont(Font.font(14));
        sub.getStyleClass().add("sao-lyric-sub");
        getChildren().setAll(mainRow, sub);
        setOpacity(0);
    }

    /// Shows the lyrics at a moment of the track.
    ///
    /// @param t seconds into the track, or a negative number when the music is off
    public void update(double t) {
        SaoLyrics.@Nullable Line l = t < 0 ? null : lyrics.at(t);
        if (l != shown && l != null) {
            base.setText(l.main());
            lit.setText(l.main());
            sub.setText(l.sub());
            base.setY(22);
            lit.setY(22);
        }
        if (l != null) shown = l;
        double target = l == null ? 0 : fade(t, l);
        setOpacity(getOpacity() + (target - getOpacity()) * 0.25);
        if (shown != null) {
            double w = base.getLayoutBounds().getWidth();
            reveal.setWidth(w * shown.sung(t));
            reveal.setHeight(40);
            reveal.setY(-10);
        }
    }

    /// How visible a line is near its ends.
    ///
    /// @param t the moment
    /// @param l the line
    /// @return 0 to 1
    private static double fade(double t, SaoLyrics.Line l) {
        double in = Math.min(1, (t - l.start() + 0.25) / 0.35);
        double out = Math.min(1, (l.end() - t) / 0.35);
        return Math.max(0, Math.min(in, out));
    }
}
