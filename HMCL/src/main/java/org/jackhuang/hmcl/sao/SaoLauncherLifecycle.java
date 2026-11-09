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
        /// Keeps JavaFX alive and hides the main window.
        void hide();
        /// Restores the main window after crash analysis has been published.
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
    /// True only between begin and an actual exit/failure.
    private boolean active;
    /// Whether a hide operation was actually performed.
    private boolean hidden;

    /// Constructs an inert controller until begin/start callbacks arrive.
    SaoLauncherLifecycle(Consumer<Runnable> publish, Window window) { this.publish = publish; this.window = window; }

    /// Captures the next run's policy before process creation.
    synchronized void begin(SaoData.LauncherBehavior behavior) {
        generation++; visibility = behavior.visibility(); active = true; hidden = false;
    }

    /// Hides only after a real OS process exists; a fast crash cannot leave a late queued hide behind.
    synchronized void started() {
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected != generation || !active || visibility != LauncherVisibility.HIDE_AND_REOPEN) return;
                hidden = true; window.hide();
            }
        });
    }

    /// CLOSE waits for the protected stdin/ticket handoff, avoiding shutdown during authentication transfer.
    synchronized void handedOff() {
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected == generation && visibility == LauncherVisibility.CLOSE) window.close();
            }
        });
    }

    /// Called for normal exit, crash and failed preparation; the caller publishes the crash card first.
    synchronized void exited() {
        active = false;
        long expected = generation;
        publish.accept(() -> {
            synchronized (this) {
                if (expected != generation || !hidden) return;
                hidden = false; window.show();
            }
        });
    }
}
