/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.setting.LauncherVisibility;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Process callbacks and delayed UI ordering are tested without a toolkit, native window, process or focus change.
@NotNullByDefault
class SaoExtrasLifecycleTest {
    /// Inert window records real policy actions in order.
    @NotNullByDefault
    private static final class Window implements SaoLauncherLifecycle.Window {
        /// Ordered operations for assertions.
        private final List<String> actions = new ArrayList<>();
        /// Records a hide request.
        @Override public void hide() { actions.add("hide"); }
        /// Records restoration after any kind of exit.
        @Override public void show() { actions.add("show"); }
        /// Records normal launcher shutdown after successful handoff.
        @Override public void close() { actions.add("close"); }
    }

    /// The default hides only after process creation and restores on normal exit, crash or launch failure.
    @Test void hideAndRestoreReusesHmclMapping() {
        assertEquals(LauncherVisibility.HIDE_AND_REOPEN, SaoData.LauncherBehavior.HIDE.visibility());
        Window window = new Window(); var lifecycle = new SaoLauncherLifecycle(Runnable::run, window);
        lifecycle.begin(SaoData.LauncherBehavior.HIDE); assertTrue(window.actions.isEmpty());
        lifecycle.started(); lifecycle.handedOff(); assertEquals(List.of("hide"), window.actions);
        lifecycle.exited(); lifecycle.exited(); assertEquals(List.of("hide", "show"), window.actions);
        lifecycle.begin(SaoData.LauncherBehavior.HIDE); lifecycle.exited(); assertEquals(List.of("hide", "show"), window.actions);
    }

    /// Keep performs no window operations; close waits until protected authentication transfer has succeeded.
    @Test void keepAndClose() {
        Window window = new Window(); var lifecycle = new SaoLauncherLifecycle(Runnable::run, window);
        lifecycle.begin(SaoData.LauncherBehavior.KEEP); lifecycle.started(); lifecycle.handedOff(); lifecycle.exited(); assertTrue(window.actions.isEmpty());
        lifecycle.begin(SaoData.LauncherBehavior.CLOSE); lifecycle.started(); assertTrue(window.actions.isEmpty());
        lifecycle.handedOff(); assertEquals(List.of("close"), window.actions); lifecycle.exited(); assertEquals(List.of("close"), window.actions);
        assertEquals(LauncherVisibility.KEEP, SaoData.LauncherBehavior.KEEP.visibility());
        assertEquals(LauncherVisibility.CLOSE, SaoData.LauncherBehavior.CLOSE.visibility());
    }

    /// A crash before queued hide publication cannot hide the already-exited game; older run callbacks are fenced.
    @Test void fastExitAndOldRunCallbacks() {
        List<Runnable> queue = new ArrayList<>(); Window window = new Window(); var lifecycle = new SaoLauncherLifecycle(queue::add, window);
        lifecycle.begin(SaoData.LauncherBehavior.HIDE); lifecycle.started(); lifecycle.exited(); queue.forEach(Runnable::run); queue.clear();
        assertTrue(window.actions.isEmpty());
        lifecycle.begin(SaoData.LauncherBehavior.HIDE); lifecycle.started();
        lifecycle.begin(SaoData.LauncherBehavior.KEEP); queue.forEach(Runnable::run); assertTrue(window.actions.isEmpty());
    }
}
