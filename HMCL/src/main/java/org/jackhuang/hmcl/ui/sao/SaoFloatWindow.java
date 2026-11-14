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
    /// The title row; a window may add buttons to its right.
    final HBox head = new HBox(8);
    /// Where the window's centre stands.
    private final Translate at = new Translate();
    /// Turned toward the player about its own vertical middle.
    private final Rotate yaw = new Rotate(0, Rotate.Y_AXIS);
    /// Tipped back about its own horizontal middle.
    private final Rotate pitch = new Rotate(0, Rotate.X_AXIS);
    /// Grows a little while the mouse is over the window (James, 7 Nov: "hover动画（对全局）鼠标在的界面会略微放大一点点").
    private final javafx.scene.transform.Scale grow = new javafx.scene.transform.Scale(1, 1, 1);
    /// How lit the glass is, 0 to 1. Kept off the window itself: opacity on the turned window flattened it, so the
    /// side windows stood straight while another window was open (James, 8 Nov: "什么叫做开了别的窗口你俩就不斜了").
    final DoubleProperty glass = new SimpleDoubleProperty(1);
    /// Extra depth while another window is in front (animated).
    final DoubleProperty recede = new SimpleDoubleProperty(0);
    /// The bright outline drawn round the window before its glass comes in at power-on, and left behind for a moment
    /// when the glass goes at Link Start (James, 13 Nov, the boot prototype he approved).
    final javafx.scene.shape.Rectangle edge = new javafx.scene.shape.Rectangle();
    /// How much of [#edge] is drawn, 0 to 1, going round from the top left.
    final DoubleProperty edgeDrawn = new SimpleDoubleProperty(0);
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
    /// @param title the title, or empty for none
    /// @param w     glass width
    /// @param h     glass height
    /// @param close what the close button does, or null for no close button
    /// The window's height in design pixels.
    ///
    /// @return the height
    double height() {
        return h;
    }

    SaoFloatWindow(String title, double w, double h, @Nullable Runnable close) {
        this.w = w;
        this.h = h;
        body.getStyleClass().add("vr-window");
        body.setPrefSize(w, h);
        body.setMinSize(w, h);
        body.setMaxSize(w, h);
        body.resize(w, h);
        body.setPadding(new Insets(14, 18, 16, 18));
        body.setSpacing(10);
        if (!title.isEmpty()) {
            Text t = new Text(title);
            t.getStyleClass().add("vr-title");
            SaoUiKit.font(t, 18, true);
            Region fill = new Region();
            HBox.setHgrow(fill, Priority.ALWAYS);
            head.getChildren().addAll(t, fill);
            head.setAlignment(Pos.CENTER_LEFT);
            body.getChildren().add(head);
        }
        if (close != null) {
            // As in a VR system: the close button floats under the window, in the middle (the grab bar went, James 7 Nov:
            // "每个页面下面那个线可以去掉").
            Button x = SaoUiKit.round(org.jackhuang.hmcl.ui.SVG.CLOSE, 24, 13);
            x.setOnAction(e -> close.run());
            x.setManaged(false);
            x.resizeRelocate(w / 2 - 12, h + 8, 24, 24);
            x.opacityProperty().bind(glass);
            getChildren().add(x);
        }
        body.setOnMousePressed(e -> toFront());
        body.opacityProperty().bind(glass);
        // Only fully lit glass takes clicks: a window pushed back behind an open one cannot be picked through the
        // gaps and pulled to the front (James, 8 Nov: "打开这个了后不应该选择不了别的界面了吗？").
        mouseTransparentProperty().bind(glass.lessThan(0.99));
        getChildren().add(0, body);
        double perimeter = 2 * (w + h);
        edge.setWidth(w);
        edge.setHeight(h);
        edge.setArcWidth(12);
        edge.setArcHeight(12);
        edge.setFill(javafx.scene.paint.Color.TRANSPARENT);
        edge.setStroke(javafx.scene.paint.Color.rgb(223, 241, 255));
        edge.setStrokeWidth(1.6);
        edge.setMouseTransparent(true);
        edge.setOpacity(0);
        edge.getStrokeDashArray().setAll(perimeter, perimeter);
        edge.strokeDashOffsetProperty().bind(edgeDrawn.multiply(-perimeter).add(perimeter));
        edge.visibleProperty().bind(edge.opacityProperty().greaterThan(0.001));
        getChildren().add(edge);
        // The glass and its shadow are kept as a picture between changes so the shadow is not blurred again every
        // frame; the page builds the space at the screen's pixel scale, so the picture is made at full resolution.
        if (Boolean.parseBoolean(System.getProperty("sao.cacheWindows", "true"))) {
            body.setCache(true);
            body.setCacheHint(javafx.scene.CacheHint.QUALITY);
        }
        yaw.setPivotX(w / 2);
        yaw.setPivotY(h / 2);
        pitch.setPivotX(w / 2);
        pitch.setPivotY(h / 2);
        grow.setPivotX(w / 2);
        grow.setPivotY(h / 2);
        getTransforms().addAll(at, yaw, pitch, grow);
        hoverProperty().addListener((o, a, b) -> hoverChanged());
        held.addListener((o, a, b) -> hoverChanged());
        recede.addListener((o, a, b) -> place());
    }

    /// The mouse is over a part of this window drawn outside it (the player's character, drawn over the 3D space
    /// while the window rests); counts as over the window.
    final javafx.beans.property.BooleanProperty held = new javafx.beans.property.SimpleBooleanProperty();
    /// The hover growth running now.
    private @Nullable Timeline growing;

    /// Grows or shrinks back with the mouse. Leaving the window for its held part in one move stops the shrink at
    /// once, so the window does not twitch.
    private void hoverChanged() {
        boolean over = isHover() || held.get();
        double k = over ? 1.02 : 1;
        if (growing != null) growing.stop();
        growing = new Timeline(new KeyFrame(Duration.millis(over ? 160 : 220),
                new KeyValue(grow.xProperty(), k, Interpolator.EASE_OUT),
                new KeyValue(grow.yProperty(), k, Interpolator.EASE_OUT)));
        growing.play();
    }

    /// Whether the window stands at its home, fully lit and not pushed back.
    ///
    /// @return true at rest
    boolean resting() {
        return glass.get() >= 0.999 && Math.abs(recede.get()) < 0.01;
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
    /// @param turn  degrees about the vertical, positive brings its right edge toward the eye (JavaFX's Rotate)
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

    /// Animates the depth and the light of the glass.
    ///
    /// @param depth   extra depth
    /// @param opacity glass opacity
    /// @param ms      duration
    void moveBack(double depth, double opacity, double ms) {
        new Timeline(new KeyFrame(Duration.millis(ms),
                new KeyValue(recede, depth, Interpolator.SPLINE(0.2, 0.8, 0.2, 1)),
                new KeyValue(glass, opacity, Interpolator.EASE_BOTH))).play();
    }

    /// The content comes in after the glass, the title first and then each part under it (James, 14 Nov:
    /// "可以给所有东西都加动效/动画吗").
    ///
    /// @param delayMs after the glass starts to come in
    void contentIn(double delayMs) {
        SaoMotion.stagger(body, delayMs);
    }

    /// Flies the window in from behind and below, growing out of the dock's light.
    ///
    /// @param ms duration
    void flyIn(double ms) {
        recede.set(420);
        glass.set(0);
        moveBack(0, 1, ms);
    }
}
