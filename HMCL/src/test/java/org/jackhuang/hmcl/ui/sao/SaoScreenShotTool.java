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
    public static void main(String[] args) {
        // Never hang on a failure: a stuck tool once held the shared build slot for three hours.
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /// Writes every shot.
    ///
    /// @param args the output folder
    /// @throws Exception on any failure
    private static void run(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "sao-shots");
        int[][] sizes = {{1024, 576, 0}, {1920, 1080, 0}, {1920, 1080, 1}};
        Files.createDirectories(out);
        Platform.setImplicitExit(false);
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        System.out.println("SCENE3D supported: " + Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D));
        for (int[] size : sizes) {
            int w = size[0], h = size[1];
            boolean win = size[2] == 1, big = w > 1500 && !win;
            // The small size and the second big one show the Windows/Linux layout of the window buttons (top right),
            // the first big one the Mac's.
            System.setProperty("sao.windowButtons", big ? "left" : "right");
            SaoFakeService fake = new SaoFakeService();
            SaoHelmetPage[] page = new SaoHelmetPage[1];
            Scene[] scene = new Scene[1];
            fx(() -> {
                page[0] = new SaoHelmetPage(fake);
                scene[0] = new Scene(page[0], w, h);
            });
            Thread.sleep(2500);
            String p = w + "x" + h + (win ? "-win-" : "-");
            if (win) {
                // Signed in, as on the Mac, with the buttons on the right.
                fx(() -> fake.forceOath(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "21-home-signed-in.png"));
                fx(() -> page[0].forceNodes());
                Thread.sleep(500);
                WritableImage[] im = new WritableImage[1];
                fx(() -> im[0] = scene[0].snapshot(null));
                write(im[0], out.resolve(p + "40-server-nodes.png"));
                // The nickname edited in place: a free name, then a taken one.
                for (String[] t : new String[][]{{"Kirito_Black", "41-name-edit-free"}, {"Asuna", "42-name-edit-taken"}}) {
                    fx(() -> page[0].forceNameEdit(t[0]));
                    Thread.sleep(900);
                    fx(() -> im[0] = scene[0].snapshot(null));
                    write(im[0], out.resolve(p + t[1] + ".png"));
                }
            } else if (big) {
                String[] apps = {"settings", "skin", "worlds", "multiplayer", "packs", "screenshots", "logs", "java", "downloads", "about"};
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "00-home.png"));
                for (int i = 0; i < apps.length; i++) {
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, i, out.resolve(p + String.format("%02d-%s.png", i + 1, apps[i])));
                }
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 0, out.resolve(p + "20-settings-uninstall.png"));
                fx(() -> page[0].forceDanger());
                Thread.sleep(500);
                WritableImage[] img = new WritableImage[1];
                fx(() -> img[0] = scene[0].snapshot(null));
                write(img[0], out.resolve(p + "20-settings-uninstall.png"));
                // The battery, as the computer reports it: charging, on battery, and low (red).
                int[][] batt = {{85, 1}, {60, 0}, {15, 0}, {12, 1}};
                for (int[] b : batt) {
                    SaoBattery.now = new SaoBattery.State(b[0], b[1] == 1);
                    SaoBattery.version++;
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1,
                            out.resolve(p + "30-battery-" + b[0] + (b[1] == 1 ? "-charging" : "") + ".png"));
                }
                SaoBattery.now = new SaoBattery.State(100, true);
                SaoBattery.version++;
                // Signed in: 确定退出吗？; then signed out, the ARGUS ID form and the 正版 tab.
                fx(() -> fake.forceOath(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "tmp.png"));
                fx(() -> page[0].forceConfirmOut());
                Thread.sleep(400);
                fx(() -> img[0] = scene[0].snapshot(null));
                write(img[0], out.resolve(p + "31-confirm-logout.png"));
                fx(() -> {
                    fake.remembered = null;
                    fake.oathLogout();
                });
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "32-signed-out.png"));
                // 誓约账号: signed in (verified, then waiting for the code), and each page of its window.
                fx(() -> fake.forceOath(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "21-home-signed-in.png"));
                fx(() -> fake.forceOath(false));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "22-home-verify-pending.png"));
                SaoAccountApp.Page[] pages = {SaoAccountApp.Page.REGISTER, SaoAccountApp.Page.VERIFY, SaoAccountApp.Page.FORGOT, SaoAccountApp.Page.MANAGE};
                for (int i = 0; i < pages.length; i++) {
                    SaoAccountApp.Page pg = pages[i];
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "tmp.png"));
                    fx(() -> page[0].forceAccount(pg));
                    Thread.sleep(1200);
                    fx(() -> img[0] = scene[0].snapshot(null));
                    write(img[0], out.resolve(p + String.format("%02d-account-%s.png", 23 + i, pg.name().toLowerCase(java.util.Locale.ROOT))));
                }
                Files.deleteIfExists(out.resolve(p + "tmp.png"));
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
