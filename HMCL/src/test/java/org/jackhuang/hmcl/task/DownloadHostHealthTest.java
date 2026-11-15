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
