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
