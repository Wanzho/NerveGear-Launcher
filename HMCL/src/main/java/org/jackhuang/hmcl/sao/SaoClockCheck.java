/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Measures clock skew using existing HTTPS responses, without changing clocks or weakening TLS.
@NotNullByDefault
public final class SaoClockCheck {
    /// Strictly greater than five minutes is rejected.
    static final long LIMIT = 300_000;
    /// Session-wide evidence shared by account requests, metadata and launch admission.
    static final SaoClockCheck SYSTEM = new SaoClockCheck(System::currentTimeMillis, System::nanoTime);
    /// Injectable wall clock, including corrections made by Windows time synchronization.
    private final LongSupplier wall;
    /// Injectable elapsed-time source, unaffected by wall-clock corrections.
    private final LongSupplier monotonic;
    /// Last valid server sample; missing/malformed headers never invent or erase evidence.
    private volatile @Nullable Sample latest;

    /// Server epoch milliseconds and monotonic receive timestamp.
    @NotNullByDefault
    private record Sample(long serverMillis, long receivedNanos) { }

    /// Constructs an isolated checker for deterministic tests or the shared production instance.
    SaoClockCheck(LongSupplier wall, LongSupplier monotonic) { this.wall = wall; this.monotonic = monotonic; }

    /// Records an HTTP Date received over the existing validated connection and logs the measured signed skew.
    void observe(@Nullable String date) {
        if (date == null) return;
        try {
            long server = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            latest = new Sample(server, monotonic.getAsLong());
            LOG.info("Clock check: local-minus-server milliseconds=" + (wall.getAsLong() - server));
        } catch (RuntimeException malformed) { LOG.warning("Clock check: invalid HTTP Date header"); }
    }

    /// Re-evaluates evidence against the current wall clock so correcting Windows time clears the warning immediately.
    @Nullable String currentProblem() {
        @Nullable Sample sample = latest;
        if (sample == null) return null;
        long elapsed = (monotonic.getAsLong() - sample.receivedNanos()) / 1_000_000;
        if (elapsed < 0) return null;
        double delta = (double) wall.getAsLong() - sample.serverMillis() - elapsed;
        if (Math.abs(delta) <= LIMIT) return null;
        long minutes = Math.max(1, Math.round(Math.abs(delta) / 60_000));
        return "电脑时间不对（差了 " + minutes + " 分钟），请在 Windows 设置里打开'自动设置时间'后重试";
    }

    /// Exposes session evidence to the pre-login Link Start screen without performing network or disk work.
    public static @Nullable String problem() { return SYSTEM.currentProblem(); }

    /// Gives measured skew precedence over session expiry and transport errors; unknown clocks are not diagnosed.
    void requireCorrect() throws SaoAccountException {
        @Nullable String problem = currentProblem();
        if (problem != null) throw new SaoAccountException(problem, "clockSkew");
    }
}
