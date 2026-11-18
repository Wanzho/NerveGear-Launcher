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

import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Socket-free Minecraft SRV regressions; all DNS answers, failures and clocks are injected.
@NotNullByDefault
class SaoServerResolverTest {
    /// Disposable release-state root; fixtures never read user credentials or production metadata.
    @TempDir Path temporary;

    /// An advertised target and nondefault port become the socket destination.
    @Test void presentSrvResolvesAdvertisedTargetAndPort() throws Exception {
        AtomicLong clock = new AtomicLong();
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> {
            assertEquals("play.example", host); assertEquals(3000, timeout);
            return new ServerAddress("node.example", 61784);
        }, clock::get);
        assertEquals(new ServerAddress("node.example", 61784), resolver.resolve(new ServerAddress("play.example"), deadline(clock)));
    }

    /// Missing SRV records preserve the logical host and Minecraft's default port.
    @Test void absentSrvFallsBackToLogicalHost() throws Exception {
        AtomicLong clock = new AtomicLong();
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> null, clock::get);
        assertEquals(new ServerAddress("play.example", 25565), resolver.resolve(new ServerAddress("play.example"), deadline(clock)));
    }

    /// Transport failure and an unavailable JNDI provider both retain direct-host fallback.
    @Test void failedAndUnavailableSrvFallBack() throws Exception {
        AtomicLong clock = new AtomicLong();
        SaoServerResolver failed = new SaoServerResolver((host, timeout) -> { throw new IOException("offline fixture"); }, clock::get);
        SaoServerResolver unavailable = new SaoServerResolver((host, timeout) -> { throw new NoClassDefFoundError("offline provider fixture"); }, clock::get);
        ServerAddress expected = new ServerAddress("play.example", 25565);
        assertEquals(expected, failed.resolve(new ServerAddress("play.example"), deadline(clock)));
        assertEquals(expected, unavailable.resolve(new ServerAddress("play.example"), deadline(clock)));
    }

    /// An explicit port, including 25565, must never trigger an SRV lookup.
    @Test void explicitPortsBypassSrv() throws Exception {
        AtomicLong clock = new AtomicLong();
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> { throw new AssertionError("Unexpected SRV lookup"); }, clock::get);
        for (int port : java.util.List.of(25565, 25567)) {
            ServerAddress explicit = new ServerAddress("play.example", port);
            assertSame(explicit, resolver.resolve(explicit, deadline(clock)));
        }
    }

    /// A positive answer is reused until exactly sixty seconds, then replaced by a fresh answer.
    @Test void positiveCacheExpiresAtSixtySecondsAndIsHostSpecific() throws Exception {
        AtomicLong clock = new AtomicLong(-TimeUnit.MINUTES.toNanos(2)); AtomicInteger calls = new AtomicInteger();
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> new ServerAddress("node.example", 61000 + calls.incrementAndGet()), clock::get);
        ServerAddress logical = new ServerAddress("play.example");
        ServerAddress first = resolver.resolve(logical, deadline(clock));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertEquals(first, resolver.resolve(new ServerAddress("PLAY.EXAMPLE"), deadline(clock)));
        assertEquals(1, calls.get());
        assertEquals(61002, resolver.resolve(new ServerAddress("other.example"), deadline(clock)).port());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(61003, resolver.resolve(logical, deadline(clock)).port());
        assertEquals(3, calls.get());
    }

    /// Absent and failed answers are also cached, preventing repeated DNS failures on refresh.
    @Test void absentAndFailureCacheExpireAtSixtySeconds() throws Exception {
        for (boolean fail : java.util.List.of(false, true)) {
            AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
            SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> {
                calls.incrementAndGet();
                if (fail) throw new IOException("offline fixture");
                return null;
            }, clock::get);
            ServerAddress logical = new ServerAddress("play.example");
            ServerAddress fallback = resolver.resolve(logical, deadline(clock));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
            assertEquals(fallback, resolver.resolve(logical, deadline(clock))); assertEquals(1, calls.get());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertEquals(fallback, resolver.resolve(logical, deadline(clock))); assertEquals(2, calls.get());
        }
    }

    /// Slow DNS consumes the existing deadline and cannot return a cached or newly resolved destination after expiry.
    @Test void expiredDeadlineRejectsNewAndCachedAnswers() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> {
            calls.incrementAndGet(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            return new ServerAddress("node.example", 61784);
        }, clock::get);
        assertThrows(SocketTimeoutException.class, () -> resolver.resolve(new ServerAddress("play.example"), deadline(clock)));
        assertEquals(1, calls.get());
        assertThrows(SocketTimeoutException.class, () -> resolver.resolve(new ServerAddress("play.example"), clock.get()));
        assertEquals(1, calls.get());
        SaoServerResolver cached = new SaoServerResolver((host, timeout) -> null, clock::get);
        cached.resolve(new ServerAddress("play.example"), deadline(clock));
        assertThrows(SocketTimeoutException.class, () -> cached.resolve(new ServerAddress("play.example"), clock.get()));
    }

    /// First-record parsing accepts DNS root dots but rejects malformed records and service-unavailable targets.
    @Test void srvRecordParsingValidatesFieldsAndTrailingDot() {
        assertEquals(new ServerAddress("node.example", 61784), SaoServerResolver.parseSrv("0 5 61784 NODE.EXAMPLE."));
        assertNull(SaoServerResolver.parseSrv("0 5 61784 ."));
        assertNull(SaoServerResolver.parseSrv("0 5 0 node.example"));
        assertNull(SaoServerResolver.parseSrv("0 5 65536 node.example"));
        assertNull(SaoServerResolver.parseSrv("-1 5 61784 node.example"));
        assertNull(SaoServerResolver.parseSrv("0 weight 61784 node.example"));
        assertNull(SaoServerResolver.parseSrv("0 5 61784 https://node.example"));
        assertNull(SaoServerResolver.parseSrv("0 5 61784 node.example extra"));
    }

    /// The ping worker resolves the advertised host, while the separately retained logical address stays unchanged.
    @Test void pingWorkerUsesSrvHostForAddressLookup() throws Exception {
        AtomicReference<String> queried = new AtomicReference<>("");
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> new ServerAddress("node.example", 61784), System::nanoTime);
        try (SaoOnlineData online = online(resolver, host -> {
            assertEquals("sao-status-dns", Thread.currentThread().getName());
            queried.set(host); return InetAddress.getLoopbackAddress();
        })) {
            assertEquals("", queried.get(), "Construction must not resolve DNS");
            ServerAddress logical = new ServerAddress("play.example");
            SaoOnlineData.PingTarget target = online.resolvePingTarget(logical, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
            assertEquals("node.example", queried.get()); assertEquals(61784, target.endpoint().port());
            assertEquals("play.example", logical.host()); assertEquals(-1, logical.port());
        }
    }

    /// An unresponsive injected DNS lookup cannot extend ping's original three-second budget or reach sockets.
    @Test void stalledDnsCannotExtendPingDeadline() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> {
            stalled.await(); return new ServerAddress("node.example", 61784);
        }, System::nanoTime);
        try (SaoOnlineData online = online(resolver, host -> { throw new AssertionError("DNS timeout reached address lookup"); })) {
            long start = System.nanoTime();
            assertThrows(TimeoutException.class, () -> online.ping("play.example"));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 2700 && elapsed < 4200, "Total DNS time: " + elapsed);
        } finally { stalled.countDown(); }
    }

    /// Address DNS shares the same budget as SRV and is canceled before the ping can open a socket.
    @Test void stalledAddressLookupCannotExtendPingDeadline() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        SaoServerResolver resolver = new SaoServerResolver((host, timeout) -> { throw new AssertionError("Explicit port queried SRV"); }, System::nanoTime);
        try (SaoOnlineData online = online(resolver, host -> {
            stalled.await(); return InetAddress.getLoopbackAddress();
        })) {
            long start = System.nanoTime();
            assertThrows(TimeoutException.class, () -> online.ping("play.example:25565"));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 2700 && elapsed < 4200, "Total address DNS time: " + elapsed);
        } finally { stalled.countDown(); }
    }

    /// Constructs an entirely inert online transport with the supplied offline DNS fixtures.
    private SaoOnlineData online(SaoServerResolver resolver, SaoOnlineData.HostLookup addresses) {
        return new SaoOnlineData(temporary, (request, limit) -> { throw new AssertionError("Unexpected HTTP request"); },
                SaoReleaseSignatures.PRODUCTION, resolver, addresses);
    }

    /// Returns a three-second deadline in the fake monotonic clock's domain.
    private static long deadline(AtomicLong clock) { return clock.get() + TimeUnit.SECONDS.toNanos(3); }
}
