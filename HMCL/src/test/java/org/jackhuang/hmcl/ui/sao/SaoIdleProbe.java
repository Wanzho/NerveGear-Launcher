/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import com.sun.javafx.scene.NodeHelper;
import com.sun.javafx.sg.prism.NGNode;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.image.WritableImage;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jetbrains.annotations.NotNullByDefault;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// Measures what the main screen costs while nobody touches it, with no window, no focus and no sound (James,
/// 8 Nov: "这个启动器很消耗吗", "请优化"). Every pulse it does what the real painter does: syncs the scene, and only
/// when something changed draws a frame (a snapshot of the whole scene, the 3D world redrawn only when its content
/// changed). It prints the process CPU in percent of one core, frames drawn per second and 3D redraws per second.
///
/// Arguments: seconds per phase (default 10), then width and height (default 2560 x 1440, a Retina 1280 x 720).
@NotNullByDefault
public final class SaoIdleProbe {
    /// Not instantiable.
    private SaoIdleProbe() {
    }

    private static long pulses, frames, worldRenders, characterRenders;
    /// Draws a frame every pulse even when nothing changed: the cost of the drawing and read-back alone.
    private static boolean everyPulse;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        double seconds = args.length > 0 ? Double.parseDouble(args[0]) : 10;
        int w = args.length > 2 ? Integer.parseInt(args[1]) : 2560, h = args.length > 2 ? Integer.parseInt(args[2]) : 1440;
        Platform.setImplicitExit(false);
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        SaoFakeService fake = new SaoFakeService();
        SaoHelmetPage[] page = new SaoHelmetPage[1];
        Scene[] scene = new Scene[1];
        fx(() -> {
            page[0] = new SaoHelmetPage(fake);
            scene[0] = new Scene(page[0], w, h);
        });
        Thread.sleep(2500);
        fx(() -> {
            fake.forceOath(true);
            fake.force(SaoPackState.READY, 1, "", null);
            page[0].forceLit();
            page[0].forceApp(-1);
        });
        Thread.sleep(1500);

        Method sync = Scene.class.getDeclaredMethod("doCSSLayoutSyncForSnapshot", Node.class);
        sync.setAccessible(true);
        Field renderSG = Class.forName("com.sun.javafx.sg.prism.NGSubScene").getDeclaredField("renderSG");
        renderSG.setAccessible(true);
        Field worldField = SaoHelmetPage.class.getDeclaredField("world");
        worldField.setAccessible(true);
        SubScene world = (SubScene) worldField.get(page[0]);
        SubScene[] character = new SubScene[1];
        WritableImage img = new WritableImage(w, h);
        fx(() -> character[0] = (SubScene) page[0].lookupAll("*").stream()
                .filter(n -> n instanceof SubScene && n != world).findFirst().orElse(null));
        if (character[0] == null) {
            fx(() -> character[0] = find(world.getRoot(), world));
        }
        AnimationTimer painter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                try {
                    sync.invoke(scene[0], scene[0].getRoot());
                    NGNode root = NodeHelper.getPeer(scene[0].getRoot());
                    pulses++;
                    if (root.isClean() && !everyPulse) return;
                    frames++;
                    if (renderSG.getBoolean(NodeHelper.getPeer(world))) worldRenders++;
                    if (character[0] != null && renderSG.getBoolean(NodeHelper.getPeer(character[0]))) characterRenders++;
                    scene[0].snapshot(img);
                    root.clearDirtyTree();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        fx(painter::start);
        Thread.sleep(1000);
        measure("idle, showing", seconds);
        Method awake;
        try {
            awake = SaoHelmetPage.class.getDeclaredMethod("forceAwake", boolean.class);
            awake.setAccessible(true);
        } catch (NoSuchMethodException none) {
            awake = null;
        }
        if (awake != null) {
            Method a = awake;
            fx(() -> call(a, page[0], false));
            Thread.sleep(500);
            measure("paused (minimised / hidden)", seconds);
            everyPulse = true;
            measure("still, drawn every pulse", seconds);
            everyPulse = false;
            fx(() -> call(a, page[0], true));
            Thread.sleep(500);
            measure("idle, showing again", seconds);
        }
        fx(painter::stop);
    }

    private static void call(Method m, Object on, Object arg) {
        try {
            m.invoke(on, arg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SubScene find(Node n, SubScene not) {
        if (n instanceof SubScene s && s != not) return s;
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            SubScene s = find(c, not);
            if (s != null) return s;
        }
        return null;
    }

    private static void measure(String name, double seconds) throws Exception {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long[] start = new long[4];
        fx(() -> {
            start[0] = pulses;
            start[1] = frames;
            start[2] = worldRenders;
            start[3] = characterRenders;
        });
        long cpu0 = os.getProcessCpuTime(), t0 = System.nanoTime();
        Thread.sleep((long) (seconds * 1000));
        long cpu1 = os.getProcessCpuTime(), t1 = System.nanoTime();
        long[] end = new long[4];
        fx(() -> {
            end[0] = pulses;
            end[1] = frames;
            end[2] = worldRenders;
            end[3] = characterRenders;
        });
        double s = (t1 - t0) / 1e9;
        System.out.printf("PROBE %-28s cpu %5.1f %% of a core | pulses %5.1f/s | frames %5.1f/s | 3D world redraws %5.1f/s | character redraws %5.1f/s%n",
                name, 100.0 * (cpu1 - cpu0) / (t1 - t0), (end[0] - start[0]) / s, (end[1] - start[1]) / s,
                (end[2] - start[2]) / s, (end[3] - start[3]) / s);
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
