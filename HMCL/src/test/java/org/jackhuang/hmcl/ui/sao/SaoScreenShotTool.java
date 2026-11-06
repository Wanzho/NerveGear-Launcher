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
        for (int[] size : sizes) {
        int w = size[0], h = size[1];
        SaoFakeService fake = new SaoFakeService();
        SaoMainPage[] page = new SaoMainPage[1];
        Scene[] scene = new Scene[1];
        fx(() -> {
            page[0] = new SaoMainPage(fake);
            scene[0] = new Scene(page[0], w, h);
        });
        Thread.sleep(2500); // wallpapers load in the background
        Object[][] states = {
                {"1-first-start", SaoPackState.NOT_INSTALLED, -1.0, "", null, 26.0},
                {"2-installing", SaoPackState.WORKING, 0.42, "安装 NeoForge", null, 30.5},
                {"3-ready", SaoPackState.READY, 1.0, "", null, 41.0},
                {"4-failed", SaoPackState.FAILED, -1.0, "", "下载失败，请检查网络后重试", 48.0},
        };
        for (Object[] s : states) {
            double t = (double) s[5];
            fx(() -> {
                fake.force((SaoPackState) s[1], (double) s[2], (String) s[3], (String) s[4]);
                page[0].lyricsClock(() -> t);
            });
            Thread.sleep(900);
            WritableImage[] img = new WritableImage[1];
            fx(() -> img[0] = scene[0].snapshot(null));
            write(img[0], out.resolve(w + "x" + h + "-" + s[0] + ".png"));
        }
        }
        Platform.exit();
        System.exit(0);
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
