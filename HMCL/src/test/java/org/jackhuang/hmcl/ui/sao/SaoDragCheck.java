/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import com.sun.javafx.scene.SceneHelper;
import javafx.application.Platform;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// Checks that the launcher window still drags (James, 8 Nov: "为什么没有任何一个可以拖动整个窗口的地方？", then
/// "得，我现在又拖不动窗口了"): the SAO page inside HMCL's real decorator, in a stage that is never shown (no window,
/// no focus), gets synthetic presses and drags as the mouse would send them. A drag from the empty space around the
/// windows must move the stage by exactly the drag; a press on a window, a dock button or the character must not
/// ("现在steve焊上面了"). Exits 1 when any case fails.
///
/// Run with the test classpath and `-Djava.awt.headless=true -Dglass.taskbarApplication=false -Dsao.mute=true`.
@NotNullByDefault
public final class SaoDragCheck {
    /// Not instantiable.
    private SaoDragCheck() {
    }

    public static void main(String[] args) {
        int failed;
        try {
            failed = run();
        } catch (Throwable t) {
            t.printStackTrace();
            failed = 1;
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int run() throws Exception {
        // A throwaway launcher home, never the player's.
        var home = Files.createTempDirectory("sao-drag-check");
        System.setProperty("sao.home", home.resolve("home").toString());
        System.setProperty("sao.dir", home.resolve("local").toString());
        org.jackhuang.hmcl.setting.SettingsManager.init();
        Platform.setImplicitExit(false);
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        SaoFakeService fake = new SaoFakeService();
        SaoHelmetPage[] page = new SaoHelmetPage[1];
        Scene[] scene = new Scene[1];
        Stage[] stage = new Stage[1];
        fx(() -> {
            page[0] = new SaoHelmetPage(fake);
            Decorator decorator = new Decorator(page[0], s -> {
            });
            stage[0] = new Stage(StageStyle.TRANSPARENT);
            stage[0].setWidth(1296);
            stage[0].setHeight(736);
            scene[0] = decorator.attachStage(stage[0]);
            // The stage is never shown, so the page would rest as if minimised.
            page[0].forceAwake(true);
        });
        Thread.sleep(2500);
        fx(() -> {
            fake.forceOath(true);
            page[0].forceLit();
            page[0].forceApp(-1);
            scene[0].snapshot(null);
        });
        Thread.sleep(800);
        fx(() -> scene[0].snapshot(null));
        double w = scene[0].getWidth(), h = scene[0].getHeight();
        // x, y in parts of the scene, and whether the drag must move the window.
        Object[][] cases = {
                {0.50, 0.975, true, "below the windows"},
                {0.97, 0.10, true, "top right, beside the battery"},
                {0.50, 0.05, true, "top middle"},
                {0.975, 0.50, true, "right of 服务器"},
                {0.26, 0.93, true, "under 公告"},
                {0.50, 0.46, false, "the player window"},
                {0.40, 0.58, false, "the character"},
                {0.05, 0.47, false, "a dock button"},
        };
        List<String> lines = new ArrayList<>();
        int[] failed = {0};
        for (Object[] c : cases) {
            double x = w * (double) c[0], y = h * (double) c[1];
            boolean shouldMove = (boolean) c[2];
            fx(() -> {
                stage[0].setX(100);
                stage[0].setY(100);
                scene[0].getRoot().applyCss();
                scene[0].getRoot().layout();
                // The pointer comes in from the middle of the window, as a hand would.
                for (int i = 0; i <= 12; i++) {
                    double t = i / 12.0;
                    send(scene[0], MouseEvent.MOUSE_MOVED, w / 2 + (x - w / 2) * t, h / 2 + (y - h / 2) * t, false);
                }
                send(scene[0], MouseEvent.MOUSE_PRESSED, x, y, true);
                send(scene[0], MouseEvent.MOUSE_DRAGGED, x + 25, y + 15, true);
                send(scene[0], MouseEvent.MOUSE_DRAGGED, x + 40, y + 30, true);
                double dx = stage[0].getX() - 100, dy = stage[0].getY() - 100;
                send(scene[0], MouseEvent.MOUSE_RELEASED, x + 40, y + 30, false);
                boolean ok = shouldMove ? Math.abs(dx - 40) < 0.5 && Math.abs(dy - 30) < 0.5 : dx == 0 && dy == 0;
                if (!ok) failed[0]++;
                lines.add(String.format("%s %-30s press %4.0f,%4.0f  window moved %3.0f,%3.0f  (%s)",
                        ok ? "ok  " : "FAIL", (String) c[3], x, y, dx, dy, shouldMove ? "must move 40,30" : "must stay"));
            });
        }
        lines.forEach(System.out::println);
        System.out.println(failed[0] == 0 ? "DRAG CHECK PASSED" : "DRAG CHECK FAILED: " + failed[0]);
        return failed[0];
    }

    /// One mouse event as the system sends it: the screen position is where the pointer is, which does not follow
    /// the window as it moves (the stage started at 100, 100).
    private static void send(Scene scene, EventType<MouseEvent> type, double x, double y, boolean down) {
        Stage st = (Stage) scene.getWindow();
        double screenX = 100 + scene.getX() + x, screenY = 100 + scene.getY() + y;
        // Scene coordinates follow the window: the pointer stays put while the window moves under it.
        double sx = screenX - st.getX() - scene.getX(), sy = screenY - st.getY() - scene.getY();
        MouseButton button = down || type == MouseEvent.MOUSE_RELEASED ? MouseButton.PRIMARY : MouseButton.NONE;
        MouseEvent e = new MouseEvent(type, sx, sy, screenX, screenY, button, 1, false, false, false, false,
                down, false, false, true, false, type != MouseEvent.MOUSE_DRAGGED, null);
        SceneHelper.processMouseEvent(scene, e);
    }

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
}
