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
package org.jackhuang.hmcl.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// 自动选择 (15 Nov): a host that keeps failing goes to the back, comes back after its bench or on one success.
final class DownloadHostHealthTest {
    private static final String MIRROR = "bmclapi2.bangbang93.com", OFFICIAL = "maven.neoforged.net";

    @Test void tripsAfterConsecutiveFailuresAndReorders() {
        AtomicLong now = new AtomicLong(1_000);
        DownloadHostHealth h = new DownloadHostHealth(now::get);
        List<String> both = List.of(MIRROR, OFFICIAL);
        for (int i = 0; i < DownloadHostHealth.TRIP - 1; i++) h.failure(MIRROR);
        assertFalse(h.benched(MIRROR), "one short of the trip");
        assertEquals(both, h.order(both, x -> x));
        h.failure(MIRROR);
        assertTrue(h.benched(MIRROR));
        assertEquals(List.of(OFFICIAL, MIRROR), h.order(both, x -> x), "benched host is the last resort, not dropped");
        now.addAndGet(DownloadHostHealth.FIRST_BENCH_MILLIS);
        assertFalse(h.benched(MIRROR), "the bench ends");
        assertEquals(both, h.order(both, x -> x));
    }

    @Test void successClearsAndRepeatedTripsBenchLonger() {
        AtomicLong now = new AtomicLong(1_000);
        DownloadHostHealth h = new DownloadHostHealth(now::get);
        for (int i = 0; i < DownloadHostHealth.TRIP; i++) h.failure(OFFICIAL);
        assertTrue(h.benched(OFFICIAL));
        h.success(OFFICIAL);
        assertFalse(h.benched(OFFICIAL), "one success brings it back");
        for (int i = 0; i < DownloadHostHealth.TRIP; i++) h.failure(MIRROR);
        now.addAndGet(DownloadHostHealth.FIRST_BENCH_MILLIS);
        for (int i = 0; i < DownloadHostHealth.TRIP; i++) h.failure(MIRROR);
        now.addAndGet(DownloadHostHealth.FIRST_BENCH_MILLIS);
        assertTrue(h.benched(MIRROR), "second bench lasts 2x");
        now.addAndGet(DownloadHostHealth.FIRST_BENCH_MILLIS);
        assertFalse(h.benched(MIRROR));
    }

    @Test void interleavedSuccessNeverTrips() {
        DownloadHostHealth h = new DownloadHostHealth(() -> 1_000);
        for (int i = 0; i < 100; i++) { h.failure(MIRROR); if (i % 4 == 3) h.success(MIRROR); }
        assertFalse(h.benched(MIRROR), "a flaky but working mirror stays first");
        assertFalse(h.benched(null));
        assertEquals("example.org", FetchTask.hostOf(org.glavo.url.WebURL.parse("https://example.org/a/b.jar")));
    }
}
