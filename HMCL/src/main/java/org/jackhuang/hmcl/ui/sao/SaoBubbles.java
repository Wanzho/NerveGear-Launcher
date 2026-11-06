/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Random;

/// The OP's cyan light bubbles rising from the bottom of the window, drawn additively on a canvas.
@NotNullByDefault
public final class SaoBubbles extends Pane {
    /// How many bubbles live at once.
    private static final int COUNT = 46;
    /// The canvas the bubbles are drawn on.
    private final Canvas canvas = new Canvas();
    /// Bubble x, in parts of the width.
    private final double[] x = new double[COUNT];
    /// Bubble height above the bottom, in parts of the height.
    private final double[] y = new double[COUNT];
    /// Bubble radius in pixels at 720 p.
    private final double[] r = new double[COUNT];
    /// Rise speed in parts of the height per second.
    private final double[] v = new double[COUNT];
    /// Wobble phase.
    private final double[] p = new double[COUNT];
    /// Random source for new bubbles.
    private final Random rnd = new Random(7);
    /// The frame clock.
    private final AnimationTimer timer;
    /// Time of the last frame in nanoseconds, or 0 before the first.
    private long last;

    /// Builds the layer; it starts at once and never takes mouse input.
    public SaoBubbles() {
        setMouseTransparent(true);
        getChildren().add(canvas);
        canvas.setBlendMode(BlendMode.ADD);
        for (int i = 0; i < COUNT; i++) spawn(i, rnd.nextDouble());
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double dt = last == 0 ? 0 : Math.min(0.1, (now - last) / 1e9);
                last = now;
                step(dt, now / 1e9);
            }
        };
        timer.start();
    }

    /// Places a bubble.
    ///
    /// @param i      the bubble
    /// @param height where it starts, in parts of the height
    private void spawn(int i, double height) {
        x[i] = 0.08 + rnd.nextDouble() * 0.84;
        y[i] = height;
        boolean big = rnd.nextDouble() < 0.18;
        r[i] = big ? 9 + rnd.nextDouble() * 12 : 1.6 + rnd.nextDouble() * 3.4;
        v[i] = (big ? 0.035 : 0.05) + rnd.nextDouble() * 0.05;
        p[i] = rnd.nextDouble() * Math.PI * 2;
    }

    /// Moves and draws every bubble.
    ///
    /// @param dt seconds since the last frame
    /// @param t  seconds on the clock
    private void step(double dt, double t) {
        double w = getWidth(), h = getHeight();
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        double k = h / 720;
        for (int i = 0; i < COUNT; i++) {
            y[i] += v[i] * dt;
            if (y[i] > 0.75) spawn(i, -0.02);
            // Fade in from the bottom edge, fade out by three quarters of the height.
            double life = Math.min(1, (y[i] + 0.02) / 0.12) * Math.max(0, 1 - Math.max(0, y[i] - 0.35) / 0.4);
            double cx = x[i] * w + Math.sin(t * 0.7 + p[i]) * 14 * k;
            double cy = h - y[i] * h;
            double rad = r[i] * k;
            double a = 0.55 * life;
            g.setFill(new RadialGradient(0, 0, cx, cy, rad * 2.2, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(0.55, 0.92, 1, a)),
                    new Stop(0.45, Color.color(0.35, 0.8, 1, a * 0.55)),
                    new Stop(1, Color.color(0.2, 0.6, 1, 0))));
            g.fillOval(cx - rad * 2.2, cy - rad * 2.2, rad * 4.4, rad * 4.4);
        }
    }
}
