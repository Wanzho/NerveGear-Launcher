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
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.setting.LauncherVisibility;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.function.Consumer;

/// Process-lifetime visibility policy, independent of stage ownership and testable without JavaFX initialization.
@NotNullByDefault
final class SaoLauncherLifecycle {
    /// Window/application operations supplied by the production adapter or an inert fixture.
    @NotNullByDefault
    interface Window {
        /// Holds completion until the screen has finished; inert adapters may complete synchronously.
        default void handover(Runnable complete) { complete.run(); }

        /// Cancels any pending screen completion.
        default void cancel() {}

        /// Keeps JavaFX alive and hides the main window.
        void hide();

        /// Restores the main window when the game process ends.
        void show();

        /// Exits the launcher after the launch handoff is complete.
        void close();
    }

    /// UI publication dispatcher, replaced by a direct or queued executor in tests.
    private final Consumer<Runnable> publish;
    /// The only integration with window operations.
    private final Window window;
    /// Invalidates delayed callbacks from older launches.
    private long generation;
    /// Captured policy for this game run.
    private LauncherVisibility visibility = LauncherVisibility.HIDE_AND_REOPEN;
    /// Screen completion and authentication transfer are independent gates for CLOSE.
    private boolean screenDone, authDone, requested, closed;
    /// True only between begin and an actual exit/failure.
    private boolean active;
    /// Whether a hide operation was actually performed.
    private boolean hidden;

    /// Constructs an inert controller until begin/start callbacks arrive.
    SaoLauncherLifecycle(Consumer<Runnable> publish, Window window) { this.publish = publish; this.window = window; }

    /// Captures the next run's policy before process creation.
    synchronized void begin(SaoData.LauncherBehavior behavior) {
        generation++; visibility = behavior.visibility(); active = true; hidden = false; screenDone = authDone = requested = closed = false;
    }

    /// Arms all policies after process creation; the callback retains its run generation.
    synchronized void started() {
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected != generation || !active || requested) return;
                requested = true;
                window.handover(() -> complete(expected));
            }
        });
    }

    /// Applies visibility only after the screen fade; KEEP still completes the screen contract.
    private synchronized void complete(long expected) {
        if (expected != generation || !active || screenDone) return;
        screenDone = true;
        if (visibility == LauncherVisibility.HIDE_AND_REOPEN) { hidden = true; window.hide(); }
        closeIfReady();
    }

    /// CLOSE requires both protected authentication transfer and the screen's completed fade.
    synchronized void handedOff() {
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected != generation || !active) return;
                authDone = true;
                closeIfReady();
            }
        });
    }

    /// Executes normal shutdown once, and never during authentication or after an early exit.
    private void closeIfReady() {
        if (active && !closed && screenDone && authDone && visibility == LauncherVisibility.CLOSE) {
            closed = true;
            window.close();
        }
    }

    /// Invalidates any pending fade for this game run.
    void processEnded() {
        if (!active) return;
        active = false;
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected == generation) window.cancel();
            }
        });
    }

    /// Restores the launcher independently of this run's exit diagnostics.
    synchronized void exited() {
        processEnded();
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected != generation) return;
                if (hidden) { hidden = false; window.show(); }
            }
        });
    }
}
