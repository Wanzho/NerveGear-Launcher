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
// 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.ui.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniWavingArms;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static org.jackhuang.hmcl.ui.sao.SaoHelmetPage.*;

/// Renders the player skin, drag rotation and skin transition inside the helmet.
@NotNullByDefault
final class SaoHelmetCharacter extends StackPane {
    /// HMCL's skin model.
    private final Turntable canvas = new Turntable();
    /// Where a drag started, and the turn then.
    private double pressX, pressY, pressYaw, pressTilt;

    /// HMCL's model, turned only about its own upright axis and tipped a little, never rolled
    /// (James, 8 Nov: "你的这个3d皮肤拖动鼠标的这个很搞笑，根本没做好反人类").
    @NotNullByDefault
    private static final class Turntable extends SkinCanvas {
        /// The camera's tip, after its turn: up and down tip the view about the screen's horizontal whichever side
        /// faces you (James, 8 Nov: "上下划怎么是反的", "像个pendulum一样").
        private final javafx.scene.transform.Rotate pitch = new javafx.scene.transform.Rotate(0, javafx.scene.transform.Rotate.X_AXIS);

        Turntable() {
            super(TexturesLoader.getDefaultSkinImage(), STAGE_W, STAGE_H, true);
            camera.getTransforms().add(camera.getTransforms().indexOf(yRotate) + 1, pitch);
        }

        double yaw() {
            return yRotate.getAngle();
        }

        double tilt() {
            return pitch.getAngle();
        }

        void turn(double yaw, double tilt) {
            yRotate.setAngle(yaw);
            // The camera faces the front from yaw 180, where its own horizontal is the model's reversed, so a
            // camera tip of +a looks like the model's old tilt of +a.
            pitch.setAngle(Math.max(-20, Math.min(20, tilt)));
            xRotate.setAngle(0);
            zRotate.setAngle(0);
        }
    }

    /// Builds it with the default skin.
    SaoHelmetCharacter() {
        // James, 8 Nov: "史蒂夫神秘跑步": HMCL's preview broke into a run at random; only the slight arm sway is left.
        if (SWAY) canvas.getAnimationPlayer().addSkinAnimation(new SkinAniWavingArms(100, 2000, 7.5, canvas));
        // The front follows the hand: dragging right turns the side under the pointer to the right.
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            pressX = e.getScreenX();
            pressY = e.getScreenY();
            pressYaw = canvas.yaw();
            pressTilt = canvas.tilt();
            e.consume();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            canvas.turn(pressYaw + TURN_SIGN * (e.getScreenX() - pressX) * 0.7, pressTilt + TILT_SIGN * (e.getScreenY() - pressY) * 0.25);
            e.consume();
        });
        getChildren().add(canvas);
    }

    /// Stops the sway while nobody can see it.
    void pause() {
        if (band != null) band.finish();
        canvas.getAnimationPlayer().stop();
    }

    /// Starts the sway again.
    void resume() {
        canvas.getAnimationPlayer().start();
    }

    /// Shows a skin, or the default one.
    ///
    /// @param png    the skin file, or null
    /// @param isSlim Slim arms
    void show(@Nullable Path png, boolean isSlim) {
        String key = skinKey(png, isSlim);
        if (key.equals(shownKey)) return;
        boolean first = shownKey == null;
        shownKey = key;
        Image img = png == null ? TexturesLoader.getDefaultSkinImage() : new Image(png.toUri().toString());
        if (img.isError()) img = TexturesLoader.getDefaultSkinImage();
        Image skinImage = img;
        if (first || !SaoMotion.on() || getScene() == null || !isVisible() || getWidth() <= 0) {
            if (band != null) band.finish();
            canvas.updateSkin(skinImage, isSlim, null);
            return;
        }
        if (band != null) band.finish();
        band = new SkinBand(skinImage, isSlim);
    }

    /// What the preview shows: the file's bytes (not its name or date) and the arm model; a refresh that brings the same
    /// skin back changes nothing and plays nothing.
    @Nullable private String shownKey;

    private static String skinKey(@Nullable Path png, boolean isSlim) {
        String what = "default";
        if (png != null) {
            try {
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(java.nio.file.Files.readAllBytes(png));
                what = Long.toHexString(crc.getValue());
            } catch (java.io.IOException e) {
                what = png.toString();
            }
        }
        return what + (isSlim ? "/slim" : "/wide");
    }

    @Nullable private SkinBand band;

    /// The skin change of our Progressive opening's character creation (the mod's own opening, filmed 19 Oct,
    /// "film vs game 10-19/02", measured at 30 fps; James, 14 Nov: "参考我们做progressive opening时候的那个效果",
    /// "我不记得是扫过去的啊"): the old figure dims to a dark silhouette, flashes white, turns amber, the amber turns
    /// to white light with a halo, and the new skin comes out of the light from the head down while the halo goes.
    /// Drawn on two pictures of the model (before and after), with the sway held for the moment so they match.
    @NotNullByDefault
    private final class SkinBand extends AnimationTimer {
        /// Milliseconds, measured on the opening (24.9-25.5 s and 27.1-28.3 s of the film).
        static final double DARK = 150, WHITE_AT = 220, WHITE = 100, AMBER_AT = 320, AMBER = 80, LIGHT_AT = 560,
                LIGHT = 200, SHAPE_AT = 700, REVEAL_AT = 900, REVEAL = 350, GLOW_OUT = 1400, END = 1400;
        /// The figure's band of change as it takes its colours, a share of its height (FilmLoginFigure.BAND).
        static final double BAND = .22;
        /// The silhouette's colours as filmed: dark, the white flash and the light, amber.
        static final int DARK_INK = 0x2D2A21, LIGHT_INK = 0xFFF3CE, AMBER_INK = 0xC47A0A;
        private final int w, h, top, bottom;
        private final int[] from, to, out;
        private final javafx.scene.image.WritableImage picture;
        private final ImageView view, halo;
        private final boolean swaying;
        private long start = -1;
        private boolean done;

        SkinBand(Image skin, boolean isSlim) {
            double scale = getScene().getWindow() != null ? getScene().getWindow().getOutputScaleX() : 1;
            swaying = canvas.getAnimationPlayer().isRunning();
            if (swaying) canvas.getAnimationPlayer().stop();
            javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            sp.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
            javafx.scene.image.WritableImage before = canvas.snapshot(sp, null);
            canvas.updateSkin(skin, isSlim, null);
            javafx.scene.image.WritableImage after = canvas.snapshot(sp, null);
            w = (int) Math.min(before.getWidth(), after.getWidth());
            h = (int) Math.min(before.getHeight(), after.getHeight());
            from = new int[w * h];
            to = new int[w * h];
            out = new int[w * h];
            javafx.scene.image.WritablePixelFormat<java.nio.IntBuffer> fmt = javafx.scene.image.PixelFormat.getIntArgbInstance();
            before.getPixelReader().getPixels(0, 0, w, h, fmt, from, 0, w);
            after.getPixelReader().getPixels(0, 0, w, h, fmt, to, 0, w);
            // the figure's own height (head to feet, both skins), so the colours come down the figure
            int t = h, b = -1;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if ((from[y * w + x] >>> 24) > 8 || (to[y * w + x] >>> 24) > 8) {
                        if (y < t) t = y;
                        b = y;
                        break;
                    }
            top = t;
            bottom = Math.max(b, t + 1);
            picture = new javafx.scene.image.WritableImage(w, h);
            view = new ImageView(picture);
            halo = new ImageView(picture);
            for (ImageView v : new ImageView[]{halo, view}) {
                v.setFitWidth(w / scale);
                v.setFitHeight(h / scale);
                v.setManaged(false);
                v.setMouseTransparent(true);
                v.relocate(canvas.getBoundsInParent().getMinX(), canvas.getBoundsInParent().getMinY());
            }
            // the light's halo: the same picture, blurred and added
            halo.setEffect(new javafx.scene.effect.GaussianBlur(30 * scale));
            halo.setBlendMode(javafx.scene.effect.BlendMode.ADD);
            halo.setOpacity(0);
            paint(0);
            getChildren().addAll(halo, view);
            canvas.setOpacity(0);
            start();
        }

        @Override
        public void handle(long now) {
            if (start < 0) start = now;
            double t = (now - start) / 1e6;
            if (t >= END) {
                finish();
                return;
            }
            paint(t);
        }

        private static double ease(double x) {
            x = x < 0 ? 0 : x > 1 ? 1 : x;
            return x * x * (3 - 2 * x);
        }

        /// One moment of the change, t in milliseconds.
        private void paint(double t) {
            // the silhouette's flat colour now (the old figure under it until the light)
            double dark = ease(t / DARK), white = ease((t - WHITE_AT) / WHITE), amber = ease((t - AMBER_AT) / AMBER),
                    light = ease((t - LIGHT_AT) / LIGHT);
            int ink = mixRgb(mixRgb(mixRgb(DARK_INK, LIGHT_INK, white), AMBER_INK, amber), LIGHT_INK, light);
            double flat = t < LIGHT_AT ? dark : 1;
            int[] shape = t < SHAPE_AT ? from : to;
            double c = (t - REVEAL_AT) / REVEAL, front = -BAND + c * (1 + 2 * BAND), span = bottom - top;
            for (int y = 0; y < h; y++) {
                double k = 0;
                if (t >= REVEAL_AT) {
                    double s = (y - top) / span;
                    k = ease((front - s) / BAND * .5 + .5);
                }
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int base = shape[row + x];
                    if ((base >>> 24) == 0 && k <= 0) {
                        out[row + x] = 0;
                        continue;
                    }
                    int sil = base & 0xff000000 | (flat >= 1 ? ink : mixRgb(base & 0xffffff, ink, flat));
                    out[row + x] = k <= 0 ? sil : k >= 1 ? to[row + x] : blend(sil, to[row + x], k);
                }
            }
            picture.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), out, 0, w);
            // the halo: a little with the white flash, full with the light, gone as the colours come in
            double glow = Math.max(.35 * white * (1 - amber), light * (1 - ease((t - REVEAL_AT) / (GLOW_OUT - REVEAL_AT))));
            halo.setOpacity(glow);
        }

        private static int mixRgb(int a, int b, double k) {
            int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * k);
            int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * k);
            int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
            return r << 16 | g << 8 | bl;
        }

        private int blend(int a, int b, double k) {
            double aa = (a >>> 24) * (1 - k), ba = (b >>> 24) * k, sum = aa + ba;
            if (sum <= 0) return 0;
            int r = (int) Math.round(((a >> 16 & 255) * aa + (b >> 16 & 255) * ba) / sum);
            int g = (int) Math.round(((a >> 8 & 255) * aa + (b >> 8 & 255) * ba) / sum);
            int bl = (int) Math.round(((a & 255) * aa + (b & 255) * ba) / sum);
            return (int) Math.round(Math.min(255, sum)) << 24 | r << 16 | g << 8 | bl;
        }

        /// Ends at once: the real model again, swaying as before.
        void finish() {
            if (done) return;
            done = true;
            stop();
            getChildren().removeAll(halo, view);
            canvas.setOpacity(1);
            if (swaying) canvas.getAnimationPlayer().start();
            if (band == this) band = null;
        }
    }
}
