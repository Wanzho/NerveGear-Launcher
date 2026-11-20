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
package org.jackhuang.hmcl.ui.sao;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/// Off-screen JavaFX regression for switchable shader rows above the dashboard after the game returns.
@NotNullByDefault
public final class SaoShaderWindowCheck {
    /// Runs as a standalone JavaFX check.
    private SaoShaderWindowCheck() {
    }

    /// Runs the observed ordering without opening Minecraft or making account requests.
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            Fixture fixture = fx(() -> {
                SaoFakeService fake = new SaoFakeService();
                fake.forceOath(true);
                fake.force(SaoPackState.READY, 1, "", null);
                SaoHelmetPage page = new SaoHelmetPage(fake);
                new Scene(page, 960, 540);
                return new Fixture(fake, page, field(page, "player", SaoFloatWindow.class),
                        field(page, "windows", Group.class));
            });
            await(() -> !fixture.page.boot().busy() && fixture.player.resting());
            int dashboardChildren = fx(() -> fixture.windows.getChildren().size());
            fx(() -> {
                fixture.service.force(SaoPackState.RUNNING, 1, "", null);
                fixture.service.force(SaoPackState.READY, 1, "", null);
                for (int index = 0; index < SaoApps.APPS.length; index++) {
                    if (SaoApps.APPS[index].label().equals("光影")) fixture.page.dockButtons[index].fire();
                }
                if (fixture.windows.getChildren().size() != dashboardChildren) {
                    throw new AssertionError("Shader window opened before the game return animation");
                }
                return true;
            });
            await(() -> fixture.windows.getChildren().size() == dashboardChildren + 1
                    && Math.abs(fixture.player.glass.get() - 0.22) < 0.01 && fixture.player.recede.get() > 299);
            fx(() -> {
                SaoFloatWindow shaders = (SaoFloatWindow) fixture.windows.getChildren().get(dashboardChildren);
                if (!fixture.player.getParent().isMouseTransparent()) {
                    throw new AssertionError("Dashboard accepts presses through the shader window");
                }
                fixture.player.toFront();
                if (fixture.windows.getChildren().get(dashboardChildren) != shaders) {
                    throw new AssertionError("Player card moved in front of the shader window");
                }
                shaders.body.applyCss();
                var button = shaders.body.lookupAll(".toggle-button").stream()
                        .filter(node -> node instanceof ToggleButton toggle && toggle.getText().equals("使用"))
                        .map(ToggleButton.class::cast).findFirst().orElseThrow();
                button.fire();
                if (fixture.service.shaderPacks().get(0).enabled()) {
                    throw new AssertionError("The previously enabled shader stayed selected");
                }
                return true;
            });
            fx(() -> {
                fixture.page.debugCloseApp();
                return true;
            });
            await(() -> fixture.windows.getChildren().size() == dashboardChildren && fixture.player.resting()
                    && !fixture.player.getParent().isMouseTransparent());
            System.out.println("PASS shader window opens after game return, stays in front and restores the dashboard");
        } finally {
            Platform.exit();
        }
    }

    /// Holds the off-screen launcher and the windows inspected by the regression.
    @NotNullByDefault
    private record Fixture(SaoFakeService service, SaoHelmetPage page, SaoFloatWindow player, Group windows) {
    }

    /// Reads a private node without adding inspection hooks to the launcher.
    private static <T> T field(SaoHelmetPage page, String name, Class<T> type) throws ReflectiveOperationException {
        var field = SaoHelmetPage.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(page));
    }

    /// Waits for a visual state instead of assuming a particular frame rate.
    private static void await(BooleanSupplier ready) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fx(ready::getAsBoolean)) {
            if (System.nanoTime() >= limit) throw new AssertionError("Window did not settle");
            Thread.sleep(30);
        }
    }

    /// Reads or changes scene state on the owning JavaFX thread.
    private static <T> T fx(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
