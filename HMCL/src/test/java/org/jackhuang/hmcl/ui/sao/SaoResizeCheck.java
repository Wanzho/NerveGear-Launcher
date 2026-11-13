/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SubScene;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Records a deterministic offscreen resize sweep and text geometry without showing or focusing a window.
@NotNullByDefault
public final class SaoResizeCheck {
    /// The page under test, initialized on the FX thread.
    private static @Nullable SaoHelmetPage page;
    /// The unshown stage whose requested size follows the sweep.
    private static @Nullable Stage stage;
    /// Not instantiable.
    private SaoResizeCheck() { }

    /// Writes PNGs and text metrics to the supplied directory; exceptions fail the process.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path output = Path.of(args[0]);
            Files.createDirectories(output);
            Path home = Files.createTempDirectory("sao-resize-check");
            System.setProperty("sao.home", home.resolve("home").toString());
            System.setProperty("sao.dir", home.resolve("local").toString());
            org.jackhuang.hmcl.setting.SettingsManager.init();
            Platform.setImplicitExit(false);
            CountDownLatch up = new CountDownLatch(1);
            Platform.startup(up::countDown);
            if (!up.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("FX startup timed out");
            fx(() -> {
                SaoCjkFont.install();
                SaoFakeService fake = new SaoFakeService();
                fake.forceOath(true);
                page = new SaoHelmetPage(fake);
                stage = new Stage();
                stage.setScene(new Scene(page, 1280, 720));
                double scale = Double.parseDouble(System.getProperty("sao.checkScale", "2"));
                try { Class.forName("com.sun.javafx.stage.WindowHelper").getMethod("notifyScaleChanged", javafx.stage.Window.class, double.class, double.class).invoke(null, stage, scale, scale); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } // reflection: packaging compiles tests as modules
                page.forceAwake(true);
            });
            Thread.sleep(4500);
            fx(() -> {
                if (page.boot().busy()) throw new IllegalStateException("Boot still running");
                page.forceApp(-1);
                page.forceAwake(false);
                page.forceHead(0, 0);
            });
            AtomicReference<javafx.scene.image.@Nullable Image> backing = new AtomicReference<>();
            StringBuilder timings = new StringBuilder("frame,width,frameMillis\n");
            StringBuilder metrics = new StringBuilder("frame,width,height,text,font,localWidth,localHeight\n");
            for (int i = 0; i < 49; i++) {
                int index = i;
                int width = 1120 + 20 * (i <= 24 ? i : 48 - i);
                int height = (int) Math.round(width * 9 / 16.0);
                fx(() -> {
                    stage.setWidth(width);
                    stage.setHeight(height);
                    // Unshown scenes have no native resize delivery; explicitly lay out the same requested size.
                    page.resize(width, height);
                    page.applyCss();
                    page.layout();
                    try {
                        var frame = SaoHelmetPage.class.getDeclaredMethod("frame", double.class);
                        frame.setAccessible(true);
                        long started = System.nanoTime();
                        frame.invoke(page, 0.0);
                        timings.append(index).append(',').append(width).append(',')
                                .append((System.nanoTime() - started) / 1e6).append('\n');
                        if (Boolean.getBoolean("sao.assertResize")) {
                            var field = SaoHelmetPage.class.getDeclaredField("resizePreview");
                            field.setAccessible(true);
                            var preview = (javafx.scene.image.ImageView) field.get(page);
                            if (!preview.isVisible() || preview.getImage() == null)
                                throw new AssertionError("Resize preview missing at " + width);
                            if (backing.get() == null) backing.set(preview.getImage());
                            else if (backing.get() != preview.getImage()) throw new AssertionError("Resize rerasterized");
                        }
                        page.applyCss();
                        page.layout();
                        record(page, index, width, height, metrics);
                        WritableImage shot = page.snapshot(null, new WritableImage(width, height));
                        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                            image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
                        ImageIO.write(image, "png", output.resolve(String.format("%02d-%d.png", index, width)).toFile());
                    } catch (ReflectiveOperationException | java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            Files.writeString(output.resolve("metrics.csv"), metrics);
            Files.writeString(output.resolve("timings.csv"), timings);
            for (int width : new int[]{1120, 1280, 1600}) {
                int height = (int) Math.round(width * 9 / 16.0);
                fx(() -> {
                    page.resize(width, height);
                    invokeFrame();
                });
                Thread.sleep(220);
                fx(() -> {
                    invokeFrame();
                    if (Boolean.getBoolean("sao.assertResize")) {
                        try {
                            var field = SaoHelmetPage.class.getDeclaredField("resizePreview");
                            field.setAccessible(true);
                            var preview = (javafx.scene.image.ImageView) field.get(page);
                            if (preview.isVisible() || preview.getImage() != null)
                                throw new AssertionError("Preview retained after settling");
                        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                    }
                    WritableImage shot = page.snapshot(null, new WritableImage(width, height));
                    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                        image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
                    try { ImageIO.write(image, "png", output.resolve("rest-" + width + ".png").toFile()); }
                    catch (java.io.IOException e) { throw new AssertionError(e); }
                });
            }
            System.out.println("RESIZE CHECK CAPTURED 49 frames; SCENE3D=" + Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D));
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /// Advances one deterministic frame for settled-state checks.
    private static void invokeFrame() {
        try {
            var frame = SaoHelmetPage.class.getDeclaredMethod("frame", double.class);
            frame.setAccessible(true);
            frame.invoke(page, 0.0);
            page.applyCss();
            page.layout();
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /// Records unscaled glyph geometry to distinguish font changes from scene projection changes.
    private static void record(Node node, int index, int width, int height, StringBuilder result) {
        if (node instanceof Text text && !text.getText().isBlank()) {
            result.append(index).append(',').append(width).append(',').append(height).append(',')
                    .append(text.getText().replace(',', ' ').replace('\n', ' ')).append(',')
                    .append(text.getFont().getSize()).append(',').append(text.getLayoutBounds().getWidth())
                    .append(',').append(text.getLayoutBounds().getHeight()).append('\n');
        }
        if (node instanceof SubScene sub) record(sub.getRoot(), index, width, height, result);
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) record(child, index, width, height, result);
    }

    /// Runs a check on the FX thread, propagating exceptions instead of silently reporting success.
    private static void fx(Runnable action) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); } finally { done.countDown(); }
        });
        if (!done.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("FX check timed out");
        if (failure.get() != null) throw new AssertionError("FX check failed", failure.get());
    }
}
