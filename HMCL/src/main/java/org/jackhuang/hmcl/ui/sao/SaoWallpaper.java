/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// The pack's wallpapers, cover-fitted and crossfading one into the next with a slow drift
/// (James 1 Nov: wallpapers may crossfade; only the dark dim overlay never animates, so that lives elsewhere).
@NotNullByDefault
public final class SaoWallpaper extends Pane {
    /// Seconds each wallpaper stays.
    private static final double HOLD = 14;
    /// Seconds of each crossfade.
    private static final double FADE = 2.4;

    /// The wallpaper paths.
    private final @Unmodifiable List<String> paths;
    /// The two image layers that take turns.
    private final ImageView[] layers = {new ImageView(), new ImageView()};
    /// Which layer is on top now.
    private int front;
    /// Which wallpaper is showing.
    private int index;
    /// The timer that moves to the next wallpaper.
    private final Timeline clock;

    /// Builds the wallpaper layer and starts the slideshow.
    ///
    /// @param start the wallpaper to begin with (wrapped into range)
    public SaoWallpaper(int start) {
        paths = SaoAssets.wallpapers();
        setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1b2a4a")), new Stop(1, Color.web("#0b1020"))), null, null)));
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        for (ImageView v : layers) {
            v.setPreserveRatio(true);
            v.setSmooth(true);
            v.setOpacity(0);
            getChildren().add(v);
        }
        index = paths.isEmpty() ? 0 : Math.floorMod(start, paths.size());
        show(layers[front], index);
        layers[front].setOpacity(1);
        drift(layers[front]);
        clock = new Timeline(new KeyFrame(Duration.seconds(HOLD), e -> next()));
        clock.setCycleCount(Timeline.INDEFINITE);
        if (paths.size() > 1) clock.play();
    }

    /// Crossfades to the next wallpaper.
    private void next() {
        index = (index + 1) % paths.size();
        ImageView in = layers[1 - front];
        ImageView out = layers[front];
        show(in, index);
        in.toFront();
        in.setOpacity(0);
        FadeTransition f = new FadeTransition(Duration.seconds(FADE), in);
        f.setToValue(1);
        f.setInterpolator(Interpolator.EASE_BOTH);
        f.setOnFinished(e -> out.setOpacity(0));
        drift(in);
        f.play();
        front = 1 - front;
    }

    /// Puts a wallpaper into a layer.
    ///
    /// @param v the layer
    /// @param i the wallpaper index
    private void show(ImageView v, int i) {
        if (paths.isEmpty()) return;
        @Nullable Image img = SaoAssets.image(paths.get(i));
        v.setImage(img);
        requestLayout();
    }

    /// A very slow push-in over the wallpaper's whole stay.
    ///
    /// @param v the layer
    private static void drift(ImageView v) {
        ScaleTransition s = new ScaleTransition(Duration.seconds(HOLD + FADE * 2), v);
        s.setFromX(1.0);
        s.setFromY(1.0);
        s.setToX(1.05);
        s.setToY(1.05);
        s.setInterpolator(Interpolator.LINEAR);
        new ParallelTransition(s).play();
    }

    /// Cover-fits both layers to the pane.
    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        for (ImageView v : layers) {
            @Nullable Image img = v.getImage();
            double iw = img == null || img.getWidth() <= 0 ? 16 : img.getWidth();
            double ih = img == null || img.getHeight() <= 0 ? 9 : img.getHeight();
            double k = Math.max(w / iw, h / ih);
            v.setFitWidth(iw * k);
            v.setFitHeight(ih * k);
            v.relocate((w - iw * k) / 2, (h - ih * k) / 2);
        }
    }
}
