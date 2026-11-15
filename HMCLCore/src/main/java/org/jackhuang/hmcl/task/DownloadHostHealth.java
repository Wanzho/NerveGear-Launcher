/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Picks the download source automatically (James, 15 Nov: "自动选择").
///
/// Players hit two failures the same night: BMCLAPI cutting every handshake after a burst of parallel requests, and
/// maven.neoforged.net timing out from China. Each file used to retry the dead host five times before trying the
/// next one, so thousands of files waited on a host that was not answering. This tracks failures per host across all
/// downloads: after [#TRIP] consecutive connection failures a host is benched for a while, and every download tries
/// the other hosts first. A benched host is still the last resort, and one success brings it straight back.
/// 404 and checksum failures say nothing about a host's health and are not counted.
@NotNullByDefault
public final class DownloadHostHealth {
    /// Consecutive failures that bench a host.
    static final int TRIP = 8;
    /// First bench length; it doubles each time the same host trips again, up to [#MAX_BENCH_MILLIS].
    static final long FIRST_BENCH_MILLIS = 30_000;
    static final long MAX_BENCH_MILLIS = 5 * 60_000;

    private static final DownloadHostHealth SHARED = new DownloadHostHealth(System::currentTimeMillis);

    /// The one tracker every download shares.
    public static DownloadHostHealth shared() { return SHARED; }

    private final LongSupplier clock;
    private final Map<String, State> hosts = new ConcurrentHashMap<>();

    DownloadHostHealth(LongSupplier clock) { this.clock = clock; }

    private static final class State {
        int failures;
        int trips;
        long benchedUntil;
    }

    private static String key(@Nullable String host) { return host == null ? "" : host.toLowerCase(Locale.ROOT); }

    /// Records a connection-level failure (refused, reset, handshake cut, timeout, 5xx).
    public void failure(@Nullable String host) {
        if (host == null) return;
        State s = hosts.computeIfAbsent(key(host), h -> new State());
        synchronized (s) {
            s.failures++;
            long now = clock.getAsLong();
            if (s.failures >= TRIP && now >= s.benchedUntil) {
                long bench = Math.min(MAX_BENCH_MILLIS, FIRST_BENCH_MILLIS << Math.min(s.trips, 8));
                s.trips++;
                s.failures = 0;
                s.benchedUntil = now + bench;
                LOG.warning("Download host " + host + " failed " + TRIP + " times in a row; other sources first for " + bench / 1000 + " s");
            }
        }
    }

    /// Records a finished download; the host is healthy again at once.
    public void success(@Nullable String host) {
        if (host == null) return;
        @Nullable State s = hosts.get(key(host));
        if (s == null) return;
        synchronized (s) {
            if (s.benchedUntil > clock.getAsLong()) LOG.info("Download host " + host + " answered again");
            s.failures = 0;
            s.trips = 0;
            s.benchedUntil = 0;
        }
    }

    /// Whether the host is benched right now.
    public boolean benched(@Nullable String host) {
        if (host == null) return false;
        @Nullable State s = hosts.get(key(host));
        if (s == null) return false;
        synchronized (s) { return clock.getAsLong() < s.benchedUntil; }
    }

    /// The same candidates with benched hosts moved to the end, the order within each group kept.
    public <T> List<T> order(List<T> candidates, Function<T, @Nullable String> host) {
        List<T> healthy = new ArrayList<>(candidates.size()), last = new ArrayList<>();
        for (T c : candidates) (benched(host.apply(c)) ? last : healthy).add(c);
        if (last.isEmpty()) return candidates;
        healthy.addAll(last);
        return healthy;
    }

    /// Forgets everything; tests only.
    void reset() { hosts.clear(); }
}
