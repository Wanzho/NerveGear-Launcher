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
