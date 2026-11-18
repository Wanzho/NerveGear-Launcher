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

import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.*;

/// Owns one HMCL task graph and its cancellation fence, without requiring JavaFX or a task dialog.
/// Start and close share a monitor because HMCL cannot cancel an executor before its start method returns.
@NotNullByDefault
public final class SaoTaskRunner implements AutoCloseable {
    /// Started graph, null when idle; guarded by this object.
    private @Nullable TaskExecutor active;
    /// Waiter is released by either the real graph's onStop event or explicit shutdown.
    private @Nullable CompletableFuture<Boolean> completion;
    /// Prevents a late queued operation from starting a graph after launcher/room shutdown.
    private boolean closed;

    /// Creates a dormant owner with no task, worker, network request or toolkit activity.
    public SaoTaskRunner() { }

    /// Runs one complete graph, including dependencies/post-execution; void tasks may return null.
    public <T> @Nullable T run(Task<T> task) throws Exception { return run(task, null); }

    /// Starts a graph atomically with respect to close and forwards its actual lifecycle to an optional observer.
    public <T> @Nullable T run(Task<T> task, @Nullable TaskListener observer) throws Exception {
        TaskExecutor executor = task.executor();
        CompletableFuture<Boolean> finished = new CompletableFuture<>();
        if (observer != null) executor.addTaskListener(observer);
        executor.addTaskListener(new TaskListener() {
            /// Completes only after the complete graph has stopped, preserving actual HMCL success/failure.
            @Override public void onStop(boolean success, TaskExecutor stopped) { finished.complete(success); }
        });
        synchronized (this) {
            if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Task owner has closed");
            if (active != null) throw new IllegalStateException("Task owner already has an active graph");
            active = executor; completion = finished;
            try { executor.start(); }
            catch (RuntimeException failure) { active = null; completion = null; throw failure; }
        }
        try {
            if (!finished.get()) {
                @Nullable Exception failure = executor.getException();
                if (failure != null) throw failure;
                throw new IOException("HMCL task was cancelled");
            }
            return task.getResult();
        } catch (InterruptedException interrupted) {
            executor.cancel(); Thread.currentThread().interrupt(); throw interrupted;
        } finally {
            synchronized (this) {
                if (active == executor) { active = null; completion = null; }
            }
        }
    }

    /// Cancels only an already-started graph and prevents future starts, without waiting on the caller's thread.
    @Override public synchronized void close() {
        closed = true;
        if (active != null) active.cancel();
        if (completion != null) completion.complete(false);
    }
}
