/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// A window floating in the space in front of the player, like a window in VR: a pane of lit glass with its
/// title, standing at a place and depth and turned to face the player, with the grab bar under it that moves it.
/// Coordinates are the page's design space (1280 x 720 at depth 0); depth grows away from the player.
@NotNullByDefault
final class SaoFloatWindow extends Group {
    /// The glass and its content.
    final VBox body = new VBox();
    /// The grab bar under the glass.
    private final Region pill = new Region();
    /// Where the window's centre stands.
    private final Translate at = new Translate();
    /// Turned toward the player about its own vertical middle.
    private final Rotate yaw = new Rotate(0, Rotate.Y_AXIS);
    /// Tipped back about its own horizontal middle.
    private final Rotate pitch = new Rotate(0, Rotate.X_AXIS);
    /// Extra depth while another window is in front (animated).
    final DoubleProperty recede = new SimpleDoubleProperty(0);
    /// The size of the glass in design pixels.
    private final double w, h;
    /// The home place, depth and turn.
    private double homeX, homeY, homeZ;
    /// Moved by dragging the grab bar.
    private double dragX, dragY;
    /// Where a drag started.
    private double pressX, pressY, pressDX, pressDY;

    /// Builds a window.
    ///
    /// @param en    the English title in the SAO face, or empty for none
    /// @param cn    the Chinese title, or empty
    /// @param w     glass width
    /// @param h     glass height
    /// @param close what the close button does, or null for no close button
    SaoFloatWindow(String en, String cn, double w, double h, @Nullable Runnable close) {
        this.w = w;
        this.h = h;
        body.getStyleClass().add("vr-window");
        body.setPrefSize(w, h);
        body.setMinSize(w, h);
        body.setMaxSize(w, h);
        body.resize(w, h);
        body.setPadding(new Insets(14, 18, 16, 18));
        body.setSpacing(10);
        if (!en.isEmpty() || !cn.isEmpty() || close != null) {
            HBox title = new HBox(8);
            title.setAlignment(Pos.BASELINE_LEFT);
            if (!en.isEmpty()) {
                Text a = new Text(en);
                a.getStyleClass().add("vr-title");
                a.setFont(SaoAssets.sao(19, true));
                title.getChildren().add(a);
            }
            if (!cn.isEmpty()) {
                Text b = new Text(cn);
                b.getStyleClass().add("vr-sub");
                b.setFont(Font.font(13));
                title.getChildren().add(b);
            }
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            title.getChildren().add(grow);
            if (close != null) {
                Button x = SaoUiKit.round(org.jackhuang.hmcl.ui.SVG.CLOSE, 26, 14);
                x.setOnAction(e -> close.run());
                title.getChildren().add(x);
            }
            body.getChildren().add(title);
        }
        pill.getStyleClass().add("vr-pill");
        pill.resizeRelocate(w / 2 - 36, h + 10, 72, 6);
        pill.setManaged(false);
        pill.setOnMousePressed(e -> {
            pressX = e.getSceneX();
            pressY = e.getSceneY();
            pressDX = dragX;
            pressDY = dragY;
            e.consume();
        });
        pill.setOnMouseDragged(e -> {
            double k = scaleOfScene();
            dragX = pressDX + (e.getSceneX() - pressX) / k;
            dragY = pressDY + (e.getSceneY() - pressY) / k;
            place();
            e.consume();
        });
        pill.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                dragX = 0;
                dragY = 0;
                place();
            }
        });
        body.setOnMousePressed(e -> toFront());
        getChildren().addAll(body, pill);
        yaw.setPivotX(w / 2);
        yaw.setPivotY(h / 2);
        pitch.setPivotX(w / 2);
        pitch.setPivotY(h / 2);
        getTransforms().addAll(at, yaw, pitch);
        recede.addListener((o, a, b) -> place());
    }

    /// The design-to-screen scale, read off the page's fit (set by the page).
    static double fitScale = 1;

    /// How far the scene moves per design pixel at depth 0.
    private static double scaleOfScene() {
        return Math.max(0.1, fitScale);
    }

    /// Puts the window at its home.
    ///
    /// @param cx    centre x
    /// @param top   top edge y
    /// @param z     depth
    /// @param turn  degrees about the vertical, positive turns its right edge away
    /// @param tip   degrees about the horizontal, positive tips its top away
    /// @return this window
    SaoFloatWindow home(double cx, double top, double z, double turn, double tip) {
        homeX = cx;
        homeY = top + h / 2;
        homeZ = z;
        yaw.setAngle(turn);
        pitch.setAngle(tip);
        place();
        return this;
    }

    /// Moves the transforms to the home, the drag and the recede.
    private void place() {
        at.setX(homeX + dragX - w / 2);
        at.setY(homeY + dragY - h / 2);
        at.setZ(homeZ + recede.get());
    }

    /// Adds content under the title.
    ///
    /// @param nodes the content
    /// @return this window
    SaoFloatWindow add(Node... nodes) {
        body.getChildren().addAll(nodes);
        return this;
    }

    /// Animates the depth and the light of the window.
    ///
    /// @param depth   extra depth
    /// @param opacity glass opacity
    /// @param ms      duration
    void moveBack(double depth, double opacity, double ms) {
        new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(recede, depth, Interpolator.SPLINE(0.2, 0.8, 0.2, 1)),
                new KeyValue(opacityProperty(), opacity, Interpolator.EASE_BOTH))).play();
    }

    /// Flies the window in from behind and below, growing out of the dock's light.
    ///
    /// @param ms duration
    void flyIn(double ms) {
        recede.set(420);
        setOpacity(0);
        moveBack(0, 1, ms);
    }
}
