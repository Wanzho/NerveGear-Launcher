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

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/// The launcher's motion (James, 14 Nov: "可以给所有东西都加动效/动画吗", "比如，login时，成功等，总之有的东西都做一遍").
///
/// One language everywhere, so nothing jumps:
/// - things that appear rise a few pixels out of transparency, fast at first and settling (lists come in one after
///   another);
/// - buttons lean in a little under the mouse, give under a press and spring back;
/// - a switch's knob slides, a tick pops;
/// - a field being typed in glows in the accent;
/// - a mistake shakes, success glows, work in progress breathes, a live light pulses.
///
/// Moves use their own [Translate] (never `translateX/Y`, which some nodes use for layout) and stop the previous
/// move of the same kind on the same node. With HMCL's animations off, everything is placed at once.
@NotNullByDefault
final class SaoMotion {
    private SaoMotion() {
    }

    /// Fast, then settling (an exponential ease out).
    static final Interpolator OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);
    /// Past the mark and back: springs, pops.
    static final Interpolator BACK = new Interpolator() {
        // Ease-out-back (JavaFX splines cannot pass 1); a mild overshoot, about 8 % of the way.
        @Override protected double curve(double t) {
            double c = 1.4, u = t - 1;
            return 1 + (c + 1) * u * u * u + c * u * u;
        }
    };
    /// Even in and out: breathing.
    static final Interpolator SOFT = Interpolator.SPLINE(0.45, 0, 0.55, 1);
    /// The lit buttons' orange (sao.css .vr-accent).
    static final Color ACCENT = Color.web("#f2a43a");

    /// How far a thing rises as it comes in, in design pixels.
    static final double RISE = 10;
    /// Between one list item and the next.
    static final double STAGGER_MS = 38;

    private static final String KEY = "sao.motion.";
    private static final boolean ON = enabled();

    /// HMCL's 动画 setting; on when the settings are not loaded (tools).
    private static boolean enabled() {
        try {
            return org.jackhuang.hmcl.ui.animation.AnimationUtils.isAnimationEnabled();
        } catch (Throwable notLoaded) {
            return true;
        }
    }

    /// Whether anything moves.
    static boolean on() {
        return ON;
    }

    // ------------------------------------------------------------------ plumbing

    /// Plays a move on a channel of a node, stopping the one before it.
    private static void run(Node n, String channel, Animation a) {
        @Nullable Object old = n.getProperties().put(KEY + channel, a);
        if (old instanceof Animation o) o.stop();
        a.play();
    }

    /// Stops a node's move on a channel.
    private static void stop(Node n, String channel) {
        @Nullable Object old = n.getProperties().remove(KEY + channel);
        if (old instanceof Animation o) o.stop();
    }

    /// The node's own shift, added once in front of its other transforms.
    private static Translate shift(Node n) {
        @Nullable Object t = n.getProperties().get(KEY + "shift");
        if (t instanceof Translate s) return s;
        Translate s = new Translate();
        n.getTransforms().add(0, s);
        n.getProperties().put(KEY + "shift", s);
        return s;
    }

    /// The opacity the node rests at (an empty state is drawn a little dim).
    private static double rest(Node n) {
        @Nullable Object r = n.getProperties().get(KEY + "rest");
        if (r instanceof Double d) return d;
        double d = n.getOpacity() <= 0 ? 1 : n.getOpacity();
        n.getProperties().put(KEY + "rest", d);
        return d;
    }

    private static KeyFrame at(double ms, KeyValue... values) {
        return new KeyFrame(Duration.millis(ms), values);
    }

    // ------------------------------------------------------------------ coming in

    /// Comes in: rises [#RISE] pixels out of transparency.
    ///
    /// @param n       the node
    /// @param delayMs wait first (a list's later items)
    static void enter(Node n, double delayMs) {
        if (!ON) return;
        double r = rest(n);
        Translate s = shift(n);
        n.setOpacity(0);
        s.setY(RISE);
        Timeline t = new Timeline(
                at(0, new KeyValue(n.opacityProperty(), 0), new KeyValue(s.yProperty(), RISE)),
                at(230, new KeyValue(n.opacityProperty(), r, OUT)),
                at(360, new KeyValue(s.yProperty(), 0, OUT)));
        t.setDelay(Duration.millis(delayMs));
        run(n, "enter", t);
    }

    /// Comes in at once.
    static void enter(Node n) {
        enter(n, 0);
    }

    /// The shown children of a pane come in one after another.
    ///
    /// @param p       the pane
    /// @param delayMs before the first
    static void stagger(Pane p, double delayMs) {
        if (!ON) return;
        int i = 0;
        for (Node c : p.getChildren()) {
            if (!c.isVisible()) continue;
            enter(c, delayMs + Math.min(i, 10) * STAGGER_MS);
            i++;
        }
    }

    /// A pane comes in, its children one after another, each time it is shown again.
    ///
    /// @param p the pane
    /// @return the pane
    static <P extends Pane> P reveal(P p) {
        if (!ON) return p;
        p.visibleProperty().addListener((o, a, shown) -> {
            if (shown) stagger(p, 0);
        });
        return p;
    }

    /// A node comes in each time it is shown again.
    ///
    /// @param n the node
    /// @return the node
    static <N extends Node> N revealOne(N n) {
        if (!ON) return n;
        n.visibleProperty().addListener((o, a, shown) -> {
            if (shown) enter(n);
        });
        return n;
    }

    /// Replaces a pane's content; the new content comes in.
    ///
    /// @param p       the pane
    /// @param content the new content
    static void swap(Pane p, Node... content) {
        p.getChildren().setAll(content);
        for (int i = 0; i < content.length; i++) enter(content[i], i * STAGGER_MS);
    }

    /// Fills a list. The first time (a window just opened), the rows come in one after another; later refills
    /// (presence, pings) only add the rows that were not there by key, so a live list does not keep flashing.
    ///
    /// @param p    the list
    /// @param rows the rows
    static void list(Pane p, Collection<? extends Node> rows) {
        boolean first = !p.getProperties().containsKey(KEY + "listed");
        java.util.Set<Object> before = new java.util.HashSet<>();
        for (Node c : p.getChildren()) if (c.getUserData() != null) before.add(c.getUserData());
        p.getChildren().setAll(rows);
        p.getProperties().put(KEY + "listed", Boolean.TRUE);
        if (!ON) return;
        int i = 0;
        for (Node c : rows) {
            boolean fresh = first || c.getUserData() != null && !before.contains(c.getUserData());
            if (fresh) enter(c, Math.min(i++, 10) * STAGGER_MS);
        }
    }

    // ------------------------------------------------------------------ hands on

    /// A button leans in under the mouse, gives under a press and springs back. The amounts are in pixels, so a wide
    /// button moves as much as a small round one, not more.
    ///
    /// @param b the button
    /// @return the button
    static <B extends ButtonBase> B press(B b) {
        if (!ON) return b;
        b.hoverProperty().addListener((o, a, h) -> settle(b, false));
        b.pressedProperty().addListener((o, a, p) -> settle(b, p));
        return b;
    }

    private static void settle(ButtonBase b, boolean down) {
        double big = Math.max(1, Math.max(b.getWidth(), b.getHeight()));
        double hover = 1 + Math.min(0.06, 7 / big), pressed = 1 - Math.min(0.07, 9 / big);
        double to = b.isPressed() ? pressed : b.isHover() ? hover : 1;
        double ms = down ? 80 : b.isHover() && !b.isPressed() ? 260 : 180;
        Interpolator i = down ? OUT : b.isHover() ? BACK : OUT;
        run(b, "scale", new Timeline(at(ms, new KeyValue(b.scaleXProperty(), to, i), new KeyValue(b.scaleYProperty(), to, i))));
    }

    /// A switch's knob slides to its side.
    ///
    /// @param knob the knob
    /// @param x    where it goes
    static void slide(Node knob, double x) {
        if (!ON) {
            knob.setTranslateX(x);
            return;
        }
        run(knob, "slide", new Timeline(at(240, new KeyValue(knob.translateXProperty(), x, BACK))));
    }

    /// A tick (or any small mark) pops in.
    ///
    /// @param n the mark
    static void pop(Node n) {
        if (!ON) return;
        run(n, "pop", new Timeline(
                at(0, new KeyValue(n.scaleXProperty(), 0.3), new KeyValue(n.scaleYProperty(), 0.3)),
                at(280, new KeyValue(n.scaleXProperty(), 1, BACK), new KeyValue(n.scaleYProperty(), 1, BACK))));
    }

    /// A field glows in the accent while it is typed in.
    ///
    /// @param f the field
    /// @return the field
    static <F extends TextInputControl> F focusGlow(F f) {
        if (!ON) return f;
        DropShadow glow = new DropShadow(0, ACCENT.deriveColor(0, 1, 1, 0));
        f.focusedProperty().addListener((o, a, focused) -> {
            f.setEffect(glow);
            Timeline t = new Timeline(at(focused ? 220 : 180,
                    new KeyValue(glow.radiusProperty(), focused ? 12 : 0, OUT),
                    new KeyValue(glow.colorProperty(), ACCENT.deriveColor(0, 1, 1, focused ? 0.55 : 0), OUT)));
            if (!focused) t.setOnFinished(e -> {
                if (!f.isFocused()) f.setEffect(null);
            });
            run(f, "glow", t);
        });
        return f;
    }

    // ------------------------------------------------------------------ outcomes

    /// A mistake: a short shake sideways.
    ///
    /// @param n the node
    static void shake(Node n) {
        if (!ON) return;
        Translate s = shift(n);
        run(n, "shake", new Timeline(
                at(0, new KeyValue(s.xProperty(), 0)),
                at(50, new KeyValue(s.xProperty(), -7, SOFT)),
                at(110, new KeyValue(s.xProperty(), 6, SOFT)),
                at(170, new KeyValue(s.xProperty(), -4, SOFT)),
                at(230, new KeyValue(s.xProperty(), 3, SOFT)),
                at(290, new KeyValue(s.xProperty(), -1, SOFT)),
                at(340, new KeyValue(s.xProperty(), 0, SOFT))));
    }

    /// A line that reports (a form's problem, the account panel's): a new message comes in, and a mistake ("! …")
    /// shakes. A message that only changes its numbers (a percentage) stays still.
    ///
    /// @param t the line
    /// @return the line
    static Text report(Text t) {
        if (!ON) return t;
        t.textProperty().addListener((o, a, b) -> {
            if (b == null || b.isEmpty()) return;
            String was = a == null ? "" : a.replaceAll("[0-9.%,/ ]", "");
            if (!was.isEmpty() && was.equals(b.replaceAll("[0-9.%,/ ]", ""))) return;
            enter(t);
            if (b.startsWith("!")) shake(t);
        });
        return t;
    }

    /// Success: the node swells in past its size and settles, and an accent glow blooms round it and fades.
    ///
    /// @param n       the node (no effect of its own)
    /// @param delayMs wait first
    static void success(Node n, double delayMs) {
        if (!ON) return;
        DropShadow glow = new DropShadow(0, ACCENT.deriveColor(0, 1, 1, 0));
        n.setEffect(glow);
        Timeline t = new Timeline(
                at(0, new KeyValue(n.scaleXProperty(), 1.14), new KeyValue(n.scaleYProperty(), 1.14),
                        new KeyValue(n.opacityProperty(), 0),
                        new KeyValue(glow.radiusProperty(), 0), new KeyValue(glow.colorProperty(), ACCENT.deriveColor(0, 1, 1, 0))),
                at(200, new KeyValue(n.opacityProperty(), 1, OUT),
                        new KeyValue(glow.radiusProperty(), 26, OUT), new KeyValue(glow.colorProperty(), ACCENT.deriveColor(0, 1, 1, 0.85), OUT)),
                at(460, new KeyValue(n.scaleXProperty(), 1, BACK), new KeyValue(n.scaleYProperty(), 1, BACK)),
                at(1100, new KeyValue(glow.radiusProperty(), 0, SOFT), new KeyValue(glow.colorProperty(), ACCENT.deriveColor(0, 1, 1, 0), SOFT)));
        t.setDelay(Duration.millis(delayMs));
        t.setOnFinished(e -> {
            if (n.getEffect() == glow) n.setEffect(null);
        });
        run(n, "success", t);
    }

    /// Work in progress: the node breathes until [#idle] is called.
    ///
    /// @param n the node
    static void busy(Node n) {
        if (!ON) return;
        double r = rest(n);
        Timeline t = new Timeline(
                at(0, new KeyValue(n.opacityProperty(), r)),
                at(650, new KeyValue(n.opacityProperty(), r * 0.55, SOFT)),
                at(1300, new KeyValue(n.opacityProperty(), r, SOFT)));
        t.setCycleCount(Animation.INDEFINITE);
        run(n, "busy", t);
    }

    /// Stops [#busy] and puts the node back at rest.
    ///
    /// @param n the node
    static void idle(Node n) {
        stop(n, "busy");
        if (ON) n.setOpacity(rest(n));
    }

    /// A live light pulses a few times when it comes on screen, then rests lit. Not for ever: windows keep their glass
    /// as a cached picture, and a light inside that never stopped would redraw the whole window every frame.
    ///
    /// @param n the light
    /// @return the light
    static <N extends Node> N pulse(N n) {
        if (!ON) return n;
        Timeline t = new Timeline(
                at(0, new KeyValue(n.opacityProperty(), 1), new KeyValue(n.scaleXProperty(), 1), new KeyValue(n.scaleYProperty(), 1)),
                at(900, new KeyValue(n.opacityProperty(), 0.5, SOFT), new KeyValue(n.scaleXProperty(), 0.82, SOFT), new KeyValue(n.scaleYProperty(), 0.82, SOFT)),
                at(1800, new KeyValue(n.opacityProperty(), 1, SOFT), new KeyValue(n.scaleXProperty(), 1, SOFT), new KeyValue(n.scaleYProperty(), 1, SOFT)));
        t.setCycleCount(3);
        // Only once it is in a scene (lights are built before their panel is shown); a removed one stops.
        n.sceneProperty().addListener((o, a, s) -> {
            if (s == null) t.stop();
            else if (t.getStatus() != Animation.Status.RUNNING && t.getCurrentTime().equals(Duration.ZERO)) t.play();
        });
        if (n.getScene() != null) t.play();
        return n;
    }

    /// A progress bar's fill glides to its new width instead of jumping.
    ///
    /// @param fill  the fill
    /// @param width its new width
    static void width(javafx.scene.layout.Region fill, double width) {
        if (!ON || fill.getScene() == null || fill.getPrefWidth() < 0) {
            fill.setPrefWidth(width);
            return;
        }
        run(fill, "width", new Timeline(at(320, new KeyValue(fill.prefWidthProperty(), width, OUT))));
    }

    /// A fill follows a width that changes (a binding), gliding.
    ///
    /// @param fill  the fill
    /// @param width the width to follow
    static void follow(javafx.scene.layout.Region fill, javafx.beans.value.ObservableDoubleValue width) {
        fill.setPrefWidth(width.get());
        // Held by the fill: a binding is only weakly held by what it watches.
        fill.getProperties().put(KEY + "follow", width);
        width.addListener((o, a, b) -> width(fill, b.doubleValue()));
    }

    /// Lifts a surface a little (a drop zone under a dragged file, a list item under the mouse), or puts it back.
    ///
    /// @param n  the surface
    /// @param up lifted
    static void lift(Node n, boolean up) {
        if (!ON) return;
        double big = Math.max(1, Math.max(n.getBoundsInLocal().getWidth(), n.getBoundsInLocal().getHeight()));
        double to = up ? 1 + Math.min(0.03, 8 / big) : 1;
        run(n, "scale", new Timeline(at(up ? 260 : 200, new KeyValue(n.scaleXProperty(), to, up ? BACK : OUT),
                new KeyValue(n.scaleYProperty(), to, up ? BACK : OUT))));
    }

    /// A number counts up to its value (a level, a sum of Col).
    ///
    /// @param t      the text
    /// @param to     the value
    /// @param format how the number reads
    static void count(Text t, long to, java.util.function.LongFunction<String> format) {
        if (!ON || to <= 0) {
            t.setText(format.apply(to));
            return;
        }
        javafx.beans.property.SimpleDoubleProperty v = new javafx.beans.property.SimpleDoubleProperty(0);
        v.addListener((o, a, b) -> t.setText(format.apply(Math.round(b.doubleValue()))));
        t.setText(format.apply(0));
        run(t, "count", new Timeline(at(700, new KeyValue(v, (double) to, OUT))));
    }
}
