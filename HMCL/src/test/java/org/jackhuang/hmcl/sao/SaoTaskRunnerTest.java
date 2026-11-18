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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/// Start/shutdown race regressions for HMCL's task executor ownership, without network or JavaFX.
@NotNullByDefault
class SaoTaskRunnerTest {
    /// Closing a dormant runner must refuse a task before any of its code executes.
    @Test void closeBeforeStartNeverRunsTask() {
        SaoTaskRunner runner = new SaoTaskRunner(); runner.close();
        AtomicBoolean executed = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> runner.run(Task.runAsync(() -> executed.set(true))));
        assertFalse(executed.get());
    }

    /// Complete task graphs still return their actual result and permit a later graph while the owner is open.
    @Test void finishedRunnerCanExecuteAnotherGraph() throws Exception {
        try (SaoTaskRunner runner = new SaoTaskRunner()) {
            assertEquals("first", runner.run(Task.completed("first")));
            assertEquals("second", runner.run(Task.completed("second")));
        }
    }

    /// An active graph observes cancellation, its caller is released, and close never calls an unstarted executor.
    @Test void closeCancelsActiveGraphWithoutBlockingCaller() throws Exception {
        SaoTaskRunner runner = new SaoTaskRunner();
        CountDownLatch started = new CountDownLatch(1); CountDownLatch stopped = new CountDownLatch(1);
        Task<Void> task = new Task<>() {
            /// Waits only for the real HMCL cancellation flag, then records that owned work has stopped.
            @Override public void execute() throws Exception {
                started.countDown();
                try { while (!isCancelled()) Thread.sleep(5); }
                finally { stopped.countDown(); }
            }
        };
        CompletableFuture<Void> result = CompletableFuture.runAsync(() -> {
            try { runner.run(task); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTimeout(Duration.ofSeconds(1), runner::close);
        assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
    }
}
