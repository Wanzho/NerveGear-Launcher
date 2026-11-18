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

import java.util.concurrent.*;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

/// Times fixed step labels and unwraps parallel read failures without changing their public mapping.
@NotNullByDefault
final class SaoLoginSteps {
    /// Utility class.
    private SaoLoginSteps() { }

    /// Executes a named operation and logs its outcome without raw exception messages or account data.
    static <T> T timed(String step, Callable<T> task) throws Exception {
        long started = System.nanoTime();
        try { return task.call(); }
        catch (Exception failure) {
            String kind = SaoAccountClient.networkFailureKind(failure);
            if (failure instanceof InterruptedException
                    || (failure instanceof SaoAccountException account && account.code().equals("cancelled")))
                kind = "cancelled";
            else if (failure instanceof SaoAccountException && kind.equals("transport"))
                kind = "account";
            Logger.LOG.warning("Sign-in failure step=" + step + " kind=" + kind);
            throw failure;
        }
        finally { elapsed(step, started); }
    }

    /// Emits exactly one INFO duration for a completed step, including failures.
    static void elapsed(String step, long started) {
        Logger.LOG.info("Sign-in timing step=" + step + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /// Starts a pure network read; callers apply results only on the serialized account worker.
    static <T> CompletableFuture<T> read(Executor executor, Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, executor);
    }

    /// Keeps original checked account errors rather than mapping CompletionException to operationFailed.
    static <T> T await(CompletableFuture<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
}
