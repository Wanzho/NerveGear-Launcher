/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jetbrains.annotations.NotNullByDefault;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// Renders the SAO screen off-screen to PNGs, one per pack state, with no window, no focus and no sound
/// (run with `-Dglass.taskbarApplication=false -Dsao.mute=true`).
@NotNullByDefault
public final class SaoScreenShotTool {
    /// Not instantiable.
    private SaoScreenShotTool() {
    }

    /// Writes the shots.
    ///
    /// @param args the output folder, then optional width and height
    /// @throws Exception when rendering or writing fails
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "sao-shots");
        int[][] sizes = {{1000, 620}, {1920, 1080}};
        Files.createDirectories(out);
        Platform.setImplicitExit(false);
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        System.out.println("SCENE3D supported: " + Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D));
        for (int[] size : sizes) {
            int w = size[0], h = size[1];
            boolean big = w > 1500;
            SaoFakeService fake = new SaoFakeService();
            SaoHelmetPage[] page = new SaoHelmetPage[1];
            Scene[] scene = new Scene[1];
            fx(() -> {
                page[0] = new SaoHelmetPage(fake);
                scene[0] = new Scene(page[0], w, h);
            });
            Thread.sleep(2500);
            String p = w + "x" + h + "-";
            if (big) {
                for (String theme : SaoHelmetPage.THEMES) {
                    fx(() -> page[0].setTheme(theme));
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + theme + "-1-home.png"));
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 0, out.resolve(p + theme + "-2-settings.png"));
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 1, out.resolve(p + theme + "-3-worlds.png"));
                }
            } else {
                shot(scene[0], page[0], fake, SaoPackState.WORKING, 0.42, "安装 NeoForge", 0, 0, -1, out.resolve(p + "01-home-installing.png"));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 0, out.resolve(p + "10-app-settings.png"));
            }
        }
        Platform.exit();
        System.exit(0);
    }

    /// Puts the page in a state, an app and a head turn, waits for the windows to settle and writes a shot.
    private static void shot(Scene scene, SaoHelmetPage page, SaoFakeService fake, SaoPackState st, double p, String label,
                             double hx, double hy, int app, Path file) throws Exception {
        fx(() -> {
            fake.force(st, p, label, null);
            page.forceLit();
            page.forceApp(-1);
            page.forceHead(hx, hy);
            if (app >= 0) page.forceApp(app);
        });
        Thread.sleep(app >= 0 ? 1200 : 700);
        WritableImage[] img = new WritableImage[1];
        fx(() -> img[0] = scene.snapshot(null));
        write(img[0], file);
    }

    /// Runs a job on the JavaFX thread and waits for it.
    ///
    /// @param r the job
    /// @throws InterruptedException when interrupted
    private static void fx(Runnable r) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("FX thread stuck");
    }

    /// Writes a JavaFX image as PNG.
    ///
    /// @param img  the image
    /// @param file the file
    /// @throws Exception when writing fails
    private static void write(WritableImage img, Path file) throws Exception {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.setRGB(x, y, r.getArgb(x, y));
        ImageIO.write(b, "png", file.toFile());
    }
}
