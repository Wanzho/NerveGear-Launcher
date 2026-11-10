/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/// Monotonic attempt limiter shared by startup, timer and explicit-button version-policy refreshes.
@NotNullByDefault
final class SaoPolicyRefresh {
    /// Production nanoTime or a deterministic fixture clock; never wall-clock time.
    private final LongSupplier clock;
    /// Last reserved attempt, including failed requests; null permits the initial fetch immediately.
    private @Nullable Long attempted;

    /// Creates a limiter without reading the clock or doing network work.
    SaoPolicyRefresh(LongSupplier clock) { this.clock = clock; }

    /// Reserves an attempt atomically; explicit actions need 30 seconds, ordinary timer/startup actions three minutes.
    synchronized boolean acquire(boolean force) {
        long now = clock.getAsLong();
        long interval = TimeUnit.SECONDS.toNanos(force ? 30 : 180);
        if (attempted != null && now - attempted < interval) return false;
        attempted = now;
        return true;
    }
}
