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

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Headless clock evidence, exact capacity admission and real installer rollback under injected disk-full failures.
@NotNullByDefault
class SaoClockAndSpaceTest {
    /// Disposable sources and target drives; never uses a real pack or account.
    @TempDir Path temporary;
    /// Fixed RFC 1123 header, independent of the computer running these tests.
    private static final String DATE = "Sun, 15 Nov 2026 12:00:00 GMT";
    /// Matching server epoch milliseconds.
    private static final long SERVER = ZonedDateTime.parse(DATE, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();

    /// Both clock directions fail strictly beyond five minutes; unknown, malformed and threshold samples pass.
    @Test void fakeDateDetectsSkewAndExactBoundary() throws Exception {
        AtomicLong now = new AtomicLong(SERVER);
        SaoClockCheck clock = new SaoClockCheck(now::get, () -> 0);
        assertNull(clock.currentProblem()); clock.observe(null); clock.observe("bad date"); clock.requireCorrect();
        clock.observe(DATE);
        for (long delta : new long @Unmodifiable []{0, 299_999, 300_000, -300_000}) {
            now.set(SERVER + delta); assertNull(clock.currentProblem()); clock.requireCorrect();
        }
        for (long delta : new long @Unmodifiable []{300_001, -300_001, 3_600_000, -3_600_000}) {
            now.set(SERVER + delta);
            SaoAccountException error = assertThrows(SaoAccountException.class, clock::requireCorrect);
            assertEquals("clockSkew", error.code());
            assertTrue(error.getMessage().contains("电脑时间不对（差了 "));
            assertTrue(error.getMessage().endsWith("请在 Windows 设置里打开'自动设置时间'后重试"));
        }
    }

    /// Monotonic time ages the server sample; an OS correction clears the diagnosis without another HTTP request.
    @Test void clockCorrectionAndMissingHeaderKeepEvidenceHonest() {
        AtomicLong wall = new AtomicLong(SERVER + 600_000), nanos = new AtomicLong();
        SaoClockCheck clock = new SaoClockCheck(wall::get, nanos::get);
        clock.observe(DATE); assertTrue(Objects.requireNonNull(clock.currentProblem()).contains("10 分钟"));
        nanos.set(900_000_000_000L); wall.addAndGet(900_000);
        clock.observe(null); clock.observe("malformed");
        assertTrue(Objects.requireNonNull(clock.currentProblem()).contains("10 分钟"));
        wall.addAndGet(-600_000); assertNull(clock.currentProblem());
    }

    /// Pack and offline budgets, saturation and ceil-GB drive messages use injectable capacity rather than host disk size.
    @Test void exactBudgetsAndInjectableFreeSpace() throws Exception {
        long gb = SaoDiskSpace.GB;
        assertEquals(11 * gb, SaoDiskSpace.packRequired(3 * gb));
        assertEquals(7 * gb, SaoDiskSpace.offlineRequired(3 * gb));
        assertEquals(Long.MAX_VALUE, SaoDiskSpace.packRequired(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, SaoDiskSpace.offlineRequired(Long.MAX_VALUE));
        Path target = Path.of("X:\\pack");
        SaoDiskSpace.require(target, 11 * gb, ignored -> 11 * gb);
        IOException shortByOne = assertThrows(IOException.class, () -> SaoDiskSpace.require(target, 11 * gb, ignored -> 11 * gb - 1));
        assertEquals("X 盘空间不够，还需要 1 GB", shortByOne.getMessage());
        assertEquals("X 盘空间不够，还需要 2 GB", assertThrows(IOException.class,
                () -> SaoDiskSpace.requireOffline(target, 3 * gb, ignored -> 6 * gb - 1)).getMessage());
        SaoDiskSpace.requireOffline(target, 3 * gb, ignored -> 7 * gb);
        assertThrows(IOException.class, () -> SaoDiskSpace.require(target, 1, ignored -> -1));
        IOException unrelated = new IOException("checksum mismatch");
        assertSame(unrelated, SaoDiskSpace.explain(target, gb, ignored -> 0, unrelated));
    }

    /// A short target is rejected before instance creation; enough space installs and a later short update preserves it.
    @Test void installerAdmissionBeforeAnyWrites() throws Exception {
        Path instance = temporary.resolve("game");
        SaoPackArchive first = pack("1.0", "first", false);
        AtomicLong free = new AtomicLong(0);
        SaoPackInstaller installer = installer(instance, free, false);
        assertThrows(IOException.class, () -> installer.apply(first, false));
        assertFalse(Files.exists(instance));
        free.set(SaoDiskSpace.required(first)); installer.apply(first, false);
        String receipt = Files.readString(instance.resolve(SaoPackInstaller.RECEIPT));
        free.set(0);
        assertThrows(IOException.class, () -> installer.applyUpdate(pack("1.1", "replacement", false)));
        assertEquals("first", Files.readString(instance.resolve("mods/test.jar")));
        assertEquals(receipt, Files.readString(instance.resolve(SaoPackInstaller.RECEIPT)));
        assertNoStage(instance);
    }

    /// A downloader really writes a truncated file before ENOSPC; the installer removes it and leaves no receipt.
    @Test void diskFullMidDownloadDeletesPartialFiles() throws Exception {
        Path instance = temporary.resolve("game"); AtomicLong free = new AtomicLong(Long.MAX_VALUE);
        SaoPackInstaller installer = installer(instance, free, true);
        Exception failure = assertThrows(Exception.class, () -> installer.apply(pack("1.0", "payload", true), false));
        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("盘空间不够，还需要"));
        assertNotNull(failure.getCause()); assertFalse(Files.exists(instance.resolve(SaoPackInstaller.RECEIPT)));
        assertFalse(Files.exists(instance.resolve("mods/test.jar"))); assertNoStage(instance);
        assertFalse(Files.exists(instance.resolve(SaoPackInstaller.DOWNLOAD_CACHE)));
    }

    /// Failure after the first installed replacement restores old payload and receipt, then removes the stage.
    @Test void diskFullDuringCommitRollsBackExistingPack() throws Exception {
        Path instance = temporary.resolve("game"); AtomicLong free = new AtomicLong(Long.MAX_VALUE);
        installer(instance, free, false).apply(pack("1.0", "original", false), false);
        String receipt = Files.readString(instance.resolve(SaoPackInstaller.RECEIPT));
        SaoPackInstaller failing = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError(); },
                Map.of(), (label, progress) -> {
                    if (label.equals("应用整合包")) {
                        free.set(0); throw new UncheckedIOException(new FileSystemException("fixture", null, "No space left on device"));
                    }
                }, ignored -> free.get());
        Exception failure = assertThrows(Exception.class, () -> failing.applyUpdate(pack("1.1", "new bytes", false)));
        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("盘空间不够，还需要"));
        assertEquals("original", Files.readString(instance.resolve("mods/test.jar")));
        assertEquals(receipt, Files.readString(instance.resolve(SaoPackInstaller.RECEIPT))); assertNoStage(instance);
    }

    /// Fake Gitee responses exercise the actual metadata path and precedence over missing-session/TLS launch errors.
    @Test void dateHeaderReachesLaunchAdmissionAndFailureText() throws Exception {
        String skewed = httpDate(Instant.now().minusSeconds(600));
        try {
            try (SaoOnlineData online = new SaoOnlineData(temporary.resolve("metadata"),
                    (request, limit) -> new SaoOnlineData.Response(503, null, new byte[0], skewed))) {
                assertThrows(IOException.class, () -> online.fetchVersion("https://gitee.com/fixture/version.json"));
            }
            String expected = Objects.requireNonNull(SaoClockCheck.problem());
            assertTrue(expected.contains("10 分钟"));
            assertEquals(expected, SaoPackEngine.launchFailure(new IOException("未找到登录会话")));
            assertEquals(expected, SaoPackEngine.launchFailure(new javax.net.ssl.SSLHandshakeException("certificate expired")));
            try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("launcher"), null, false)) {
                var ready = SaoPackEngine.class.getDeclaredField("packReady"); ready.setAccessible(true);
                ((java.util.concurrent.CompletableFuture<?>) ready.get(engine)).get(10, java.util.concurrent.TimeUnit.SECONDS);
                engine.linkStartOath();
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
                    while (engine.lastError().get() == null) Thread.sleep(5);
                });
                assertEquals(expected, engine.lastError().get());
                assertEquals(SaoInstallProgress.Stage.IDLE, engine.installProgress().get().stage());
            }
            try (SaoOnlineData online = new SaoOnlineData(temporary.resolve("corrected"),
                    (request, limit) -> new SaoOnlineData.Response(503, null, new byte[0], httpDate(Instant.now())))) {
                assertThrows(IOException.class, () -> online.fetchVersion("https://gitee.com/fixture/version.json"));
            }
            assertNull(SaoClockCheck.problem());
        } finally { SaoClockCheck.SYSTEM.observe(httpDate(Instant.now())); }
    }

    /// Quick composition checks both target drives before writing its cache, and a rejected import makes no snapshot.
    @Test void quickUpdateAndImportRefuseBeforeCopying() throws Exception {
        SaoPackArchive old = pack("1.0", "old", false), next = pack("1.1", "next", false);
        Path quick = temporary.resolve("quick.zip");
        SaoData.QuickRelease hop = SaoUpdateBuilder.build(old.zip(), next.zip(), quick, Map.of(), null, null);
        Path instance = temporary.resolve("game"), cache = temporary.resolve("cache");
        AtomicLong free = new AtomicLong(Long.MAX_VALUE);
        installer(instance, free, false).apply(old, false);
        SaoPackInstaller blockedCache = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError(); },
                Map.of(), (label, progress) -> { }, target -> target.equals(cache) ? 0 : Long.MAX_VALUE);
        assertThrows(IOException.class, () -> blockedCache.applyQuick(List.of(quick), old, cache));
        assertFalse(Files.exists(cache)); assertEquals("old", Files.readString(instance.resolve("mods/test.jar")));
        SaoData.PackRelease full = new SaoData.PackRelease("1.1", next.zip().getFileName().toString(), Files.size(next.zip()), SaoUpdates.sha256(next.zip()));
        SaoData.VersionMetadata metadata = new SaoData.VersionMetadata(2, SaoUpdates.QUARK_URL, full, List.of(hop),
                new SaoData.LauncherRelease("0.1.0", "0.1.0"));
        try (SaoDropSession session = new SaoDropSession(metadata, "1.0", cache)) {
            IOException error = assertThrows(IOException.class, () -> session.acceptQuick(quick,
                    candidate -> SaoDiskSpace.require(instance, SaoDiskSpace.required(candidate), ignored -> 0)));
            assertTrue(Objects.requireNonNull(error.getMessage()).contains("盘空间不够"));
            assertFalse(Files.exists(cache));
        }
        installer(instance, free, false).applyQuick(List.of(quick), old, cache);
        assertEquals("next", Files.readString(instance.resolve("mods/test.jar"))); assertNoStage(instance);
    }

    /// Produces an RFC 1123 Date value for fake transport evidence, never making an external request.
    private static String httpDate(Instant instant) { return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC)); }

    /// Creates a real staged installer with controlled capacity and an optional partial-download failure.
    private SaoPackInstaller installer(Path instance, AtomicLong free, boolean diskFull) {
        return new SaoPackInstaller(instance, (file, destination) -> {
            if (!diskFull) throw new AssertionError("Unexpected download");
            Files.writeString(destination, "partial"); free.set(0);
            throw new FileSystemException(destination.toString(), null, "There is not enough space on the disk");
        }, Map.of(), (label, progress) -> { }, ignored -> free.get());
    }

    /// Builds a tiny valid full pack with either an embedded file or a fake remote entry.
    private SaoPackArchive pack(String version, String value, boolean remote) throws IOException {
        byte @Unmodifiable [] bytes = value.getBytes(StandardCharsets.UTF_8);
        SaoPackManifest.PackFile file = new SaoPackManifest.PackFile("mods/test.jar", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, remote ? null : "overrides/mods/test.jar", remote ? List.of("https://example.invalid/test.jar") : List.of());
        SaoPackManifest manifest = new SaoPackManifest(version, SaoPackManifest.MINECRAFT, SaoPackManifest.NEOFORGE,
                "localhost", List.of(file), List.of());
        Path archive = Files.createTempFile(temporary, "pack-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); zip.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            if (!remote) { zip.putNextEntry(new ZipEntry("overrides/mods/test.jar")); zip.write(bytes); zip.closeEntry(); }
        }
        return SaoPackArchive.open(archive);
    }

    /// Verifies cleanup directly on disk, independently of the exception returned to the caller.
    private static void assertNoStage(Path instance) throws IOException {
        try (var files = Files.list(instance)) { assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".sao-stage-"))); }
    }
}
