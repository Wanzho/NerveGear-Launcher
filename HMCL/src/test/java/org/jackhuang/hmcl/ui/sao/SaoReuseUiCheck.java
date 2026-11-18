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

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Offscreen reuse settings interaction check; never shows a window or reads a real game directory.
@NotNullByDefault
public final class SaoReuseUiCheck {
    /// Utility class.
    private SaoReuseUiCheck() { }

    /// Runs with an explicit fresh output PNG path; it must not already exist.
    public static void main(String @Unmodifiable [] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a new PNG output path");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite screenshot");
        CountDownLatch started = new CountDownLatch(1);
        Platform.setImplicitExit(false);
        Platform.startup(started::countDown);
        if (!started.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("FX startup timeout");
        try { run(output); } finally { Platform.exit(); }
    }

    /// Performs discovery, explicit confirmation and failure presentation against an isolated fake service.
    private static void run(Path output) throws Exception {
        Path candidate = Path.of("/isolated-pcl/.minecraft");
        SaoFakeService fake = new SaoFakeService();
        AtomicReference<@Nullable List<Path>> saved = new AtomicReference<>();
        AtomicBoolean fail = new AtomicBoolean();
        SaoLauncherService service = (SaoLauncherService) Proxy.newProxyInstance(SaoLauncherService.class.getClassLoader(),
                new Class<?>[]{SaoLauncherService.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findExistingGames": return CompletableFuture.completedFuture(List.of(candidate));
                        case "reuseSources": return CompletableFuture.completedFuture(List.of());
                        case "setReuseSources":
                            if (fail.get()) return CompletableFuture.failedFuture(new IllegalArgumentException("测试目录不可用"));
                            @SuppressWarnings("unchecked") List<Path> paths = (List<Path>) args[0];
                            saved.set(List.copyOf(paths));
                            return CompletableFuture.completedFuture(null);
                        default:
                            try { return method.invoke(fake, args); }
                            catch (InvocationTargetException error) { throw error.getCause(); }
                    }
                });
        AtomicReference<VBox> root = new AtomicReference<>();
        AtomicReference<Scene> scene = new AtomicReference<>();
        fx(() -> {
            root.set(SaoApps.reusableGames(service));
            scene.set(new Scene(root.get(), 340, 350));
            root.get().getStyleClass().add("theme-sao");
            root.get().setStyle("-fx-background-color: #101b2a; -fx-padding: 10;");
            root.get().getStylesheets().add(java.util.Objects.requireNonNull(
                    SaoHelmetPage.class.getResource("/assets/css/sao.css")).toExternalForm());
        });
        fx(() -> {}); // Initial completed future enqueues its UI callback.
        fx(() -> {
            Button search = button(root.get(), "自动查找");
            require(search.getStyleClass().contains("vr-button"), "search button style");
            search.fire();
        });
        fx(() -> {});
        fx(() -> {
            root.get().applyCss(); root.get().layout();
            ToggleButton choice = root.get().lookupAll(".vr-switch").stream().map(ToggleButton.class::cast).findFirst().orElseThrow();
            require(!choice.isSelected() && saved.get() == null, "discovery does not approve");
            choice.setSelected(true);
            require(saved.get() == null, "checking alone does not save");
            button(root.get(), "使用所选目录").fire();
        });
        fx(() -> {});
        fx(() -> {
            require(List.of(candidate).equals(saved.get()), "confirmation saves selected directory");
            fail.set(true); button(root.get(), "使用所选目录").fire();
        });
        fx(() -> {});
        fx(() -> {
            require(root.get().getChildren().stream().filter(Text.class::isInstance).map(Text.class::cast)
                    .anyMatch(t -> t.getText().contains("测试目录不可用")), "save error visible");
            root.get().applyCss(); root.get().layout();
            WritableImage image = scene.get().snapshot(null);
            try {
                Files.createDirectories(output.toAbsolutePath().getParent());
                BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
                    png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    if (!ImageIO.write(png, "png", stream)) throw new IllegalStateException("PNG writer missing");
                }
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
        System.out.println("PASS offscreen search, explicit confirmation, failure and vr-button/vr-switch styles");
    }

    /// Finds a button by its stable displayed label.
    private static Button button(VBox root, String label) {
        return root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> label.equals(button.getText())).findFirst().orElseThrow();
    }

    /// Reports an actionable assertion without requiring a test runner.
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /// Runs a task on the FX thread and propagates exceptions.
    private static void fx(Runnable task) throws Exception {
        FutureTask<Void> pending = new FutureTask<>(task, null);
        Platform.runLater(pending);
        pending.get(30, TimeUnit.SECONDS);
    }
}
