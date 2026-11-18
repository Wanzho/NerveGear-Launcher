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

import com.google.gson.Gson;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// 下载 vs Link Start (James, 11 Nov 2026): 下载 prepares and never launches; Link Start checks first and refuses
/// without downloading. Headless: fake game steps, no network, no toolkit, no game process.
@NotNullByDefault
class SaoDownloadButtonTest {
    /// Disposable launcher roots and archives.
    @TempDir Path temporary;

    /// Counts game steps instead of downloading Minecraft or starting a process.
    @NotNullByDefault
    private static final class FakeSteps implements SaoReadinessCheck.GameSteps {
        /// Counts preparation and launch calls without running either implementation.
        final AtomicInteger installs = new AtomicInteger(), launches = new AtomicInteger();
        /// Optional readiness refusal used by file-only fixtures.
        volatile @Nullable String missing;
        /// Optional launch failure after the progress callback.
        @Nullable Exception launchFailure;
        /// Optional preparation failure after the import snapshot is created.
        @Nullable IOException installFailure;
        /// Publishes a synthetic ticket phase through the engine's real progress method.
        Runnable beforeLaunch = () -> { };
        /// Route captured before reporting a launch to the test thread.
        boolean lastOffline;
        /// Optional official server passed to this launch.
        @Nullable String lastServer;
        /// Records preparation without downloading.
        @Override public void ensureInstalled(boolean repair) throws IOException {
            installs.incrementAndGet();
            if (installFailure != null) throw installFailure;
        }

        /// Returns the fixture readiness reason.
        @Override public @Nullable String missing() { return missing; }

        /// Records a launch and optionally fails before any game process exists.
        @Override public void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window, String jvmArgs,
                                     char @Nullable [] password, boolean offline, @Nullable String signedServer,
                                     BooleanSupplier cancelled, ProcessListener listener) throws Exception {
            lastOffline = offline;
            lastServer = signedServer;
            launches.incrementAndGet(); beforeLaunch.run();
            if (launchFailure != null) throw launchFailure;
            listener.setProcess(new ManagedProcess(new FakeProcess(), List.of("java", "fixture")));
        }
    }

    /// Java Process contract implemented entirely in memory, including the HMCL exit notification.
    @NotNullByDefault
    private static final class FakeProcess extends Process {
        /// Synthetic lifetime.
        private volatile boolean alive = true;
        /// Listener installed by the fake launch step.
        Runnable exit = () -> { };
        /// No input is sent to a child.
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }

        /// No child output exists.
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }

        /// No child error output exists.
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        /// A termination has already completed synchronously.
        @Override public int waitFor() { return exitValue(); }

        /// Reports whether the synchronous termination occurred.
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }

        /// Implements Process's running-state contract.
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }

        /// Completes the synthetic exit only once.
        @Override public void destroy() { if (alive) { alive = false; exit.run(); } }

        /// No OS force operation is needed.
        @Override public Process destroyForcibly() { destroy(); return this; }

        /// Returns synthetic liveness.
        @Override public boolean isAlive() { return alive; }
    }

    /// 下载 runs the preparation (game steps, pack apply), ends READY and never launches.
    @Test void downloadPreparesAndNeverLaunches() throws Exception {
        SaoPackArchive pack = archive("0.8", "old", "pack-0.8.zip");
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("launcher"), pack, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            assertEquals("还没下载", engine.readiness().get().reason());
            assertEquals(SaoReadiness.Action.PREPARE, engine.readiness().get().action());
            assertEquals("下载", engine.readiness().get().buttonText());
            engine.download();
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            await(() -> engine.state().get() == SaoPackState.READY);
            assertEquals(1, steps.installs.get()); assertEquals(0, steps.launches.get());
            assertEquals("0.8", engine.installedVersion().get());
            assertEquals("old", Files.readString(engine.instanceDir().resolve("config/managed.txt")));
            assertEquals("Link Start", engine.readiness().get().buttonText());
        }
    }

    /// A ticket-phase failure or cancellation removes the download summary after the real launch worker returns.
    @Test void failedLinkStartClearsTicketDownloadSummary() throws Exception {
        SaoPackArchive pack = archive("0.8", "old", "pack-0.8.zip");
        for (int attempt = 0; attempt < 3; attempt++) {
            Path root = temporary.resolve("failed-launch-" + attempt);
            installer(instance(root)).apply(pack, false);
            try (SaoPackEngine engine = new SaoPackEngine(root, pack, false)) {
                FakeSteps steps = new FakeSteps();
                steps.launchFailure = attempt == 2 ? new java.util.concurrent.CancellationException("fixture cancellation")
                        : new java.io.IOException("启动凭证不是仅所有者可用");
                var report = SaoPackEngine.class.getDeclaredMethod("reportProgress", String.class, double.class);
                report.setAccessible(true);
                steps.beforeLaunch = () -> {
                    try { report.invoke(engine, "申请进服凭证", -1.0); }
                    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    assertEquals("申请进服凭证", engine.installProgress().get().label());
                };
                engine.setGameSteps(steps);
                engine.setGraphicsPreset(SaoData.GraphicsPreset.FAST);
                awaitReadiness(engine, SaoReadiness.Kind.READY);
                if (attempt == 1) engine.linkStartOffline("Fixture");
                else engine.linkStart(SaoGameBridge.offlineAccount("Fixture"), null);
                await(() -> engine.state().get() == SaoPackState.FAILED
                        && engine.installProgress().get().stage() == SaoInstallProgress.Stage.IDLE);
                assertEquals(1, steps.launches.get());
                assertFalse(engine.lastError().get().isBlank());
                assertTrue(engine.tasks().isEmpty());
            }
        }
    }

    /// Nothing installed: Link Start refuses with 「还没下载」, without installing or launching.
    @Test void linkStartWithMissingInstallDoesNotLaunch() throws Exception {
        SaoPackArchive pack = archive("0.8", "old", "pack-0.8.zip");
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("launcher"), pack, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.FAILED);
            assertEquals("还没下载", engine.lastError().get());
            assertEquals(SaoReadiness.Kind.DOWNLOAD, engine.readiness().get().kind());
            assertEquals(0, steps.installs.get()); assertEquals(0, steps.launches.get());
            assertFalse(Files.exists(engine.instanceDir().resolve(SaoPackInstaller.RECEIPT)));
        }
    }

    /// An older installed pack: 「有新版本 0.9」 and 更新; missing files: 「游戏文件不完整」, and 下载 restores them.
    @Test void linkStartWithOutdatedOrIncompleteInstallDoesNotLaunch() throws Exception {
        SaoPackArchive old = archive("0.8", "old", "pack-0.8.zip"), next = archive("0.9", "new", "pack-0.9.zip");
        Path root = temporary.resolve("launcher");
        installer(instance(root)).apply(old, false);
        try (SaoPackEngine engine = new SaoPackEngine(root, next, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.UPDATE);
            assertEquals("更新", engine.readiness().get().buttonText());
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.FAILED);
            assertEquals("有新版本 0.9", engine.lastError().get());
            assertEquals(0, steps.installs.get()); assertEquals(0, steps.launches.get());
            assertEquals("old", Files.readString(engine.instanceDir().resolve("config/managed.txt")));
            engine.download();
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            assertEquals("0.9", engine.installedVersion().get()); assertEquals(0, steps.launches.get());

            Files.delete(engine.instanceDir().resolve("config/managed.txt"));
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.FAILED);
            assertEquals("游戏文件不完整", engine.lastError().get());
            assertEquals(SaoReadiness.Kind.DOWNLOAD, engine.readiness().get().kind());
            assertEquals(0, steps.launches.get());
            engine.download();
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            assertEquals("new", Files.readString(engine.instanceDir().resolve("config/managed.txt")));

            steps.missing = "缺少 Java 17";
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.FAILED);
            assertEquals("缺少 Java 17", engine.lastError().get());
            assertEquals(0, steps.launches.get());
        }
    }

    /// Ready: Link Start launches as before (dependency check, then the game).
    @Test void linkStartReadyLaunches() throws Exception {
        SaoPackArchive pack = archive("0.8", "old", "pack-0.8.zip");
        Path root = temporary.resolve("launcher");
        installer(instance(root)).apply(pack, false);
        try (SaoPackEngine engine = new SaoPackEngine(root, pack, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            engine.setGraphicsPreset(SaoData.GraphicsPreset.FAST);
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            engine.linkStartOffline("Fixture");
            // RUNNING requires the fixture to deliver the real process-listener contract.
            await(() -> steps.launches.get() == 1);
            await(() -> engine.state().get() == SaoPackState.RUNNING);
            assertEquals(1, steps.installs.get());
            assertNull(engine.lastError().get());
        }
    }

    /// A newer signed pack than any local one: Link Start opens Quark once and watches Downloads; no launch, no download.
    @Test void newerSignedPackOpensQuarkWithoutLaunchOrDownload() throws Exception {
        SaoPackArchive old = archive("0.8", "old", "pack-0.8.zip");
        Path signed = archive("0.9", "new", "pack.zip").zip();
        Path root = temporary.resolve("launcher");
        installer(instance(root)).apply(old, false);
        SaoReleaseTestSupport.cacheVersion(root, policy(signed));
        Path downloads = Files.createDirectories(temporary.resolve("Downloads"));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, old, false)) {
            List<String> opened = Collections.synchronizedList(new ArrayList<>()); engine.setLinkOpener(opened::add);
            engine.setDownloadDirectories(() -> List.of(downloads));
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.UPDATE);
            assertEquals(new SaoReadiness(SaoReadiness.Kind.UPDATE, "有新版本 0.9", SaoReadiness.Action.QUARK), engine.readiness().get());
            engine.linkStart(SaoGameBridge.offlineAccount("Fixture"), null);
            await(() -> engine.state().get() == SaoPackState.FAILED);
            await(() -> engine.downloadPrompt().get().watching());
            assertEquals("有新版本 0.9", engine.lastError().get());
            assertEquals(List.of(SaoUpdates.QUARK_URL), opened);
            assertEquals(0, steps.launches.get()); assertEquals(0, steps.installs.get());
            assertEquals("0.8", engine.installedVersion().get());
            // 更新 does the same.
            engine.download();
            await(() -> opened.size() == 2);
            assertEquals(List.of(SaoUpdates.QUARK_URL, SaoUpdates.QUARK_URL), opened);
            assertEquals(0, steps.installs.get()); assertEquals(0, steps.launches.get());
        }
    }

    /// Readiness follows an import (drop of the signed full pack) and an uninstall.
    @Test void readinessUpdatesAfterImportAndUninstall() throws Exception {
        Path signed = archive("0.9", "new", "pack.zip").zip();
        Path root = temporary.resolve("launcher");
        SaoReleaseTestSupport.cacheVersion(root, policy(signed));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            assertEquals(SaoReadiness.Action.PACK_WINDOW, engine.readiness().get().action());
            engine.acceptDroppedFiles(List.of(signed)).get(10, TimeUnit.SECONDS);
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            assertEquals("0.9", engine.installedVersion().get()); assertEquals(0, steps.launches.get());
        }

        Path root2 = temporary.resolve("launcher2");
        installer(instance(root2)).apply(SaoPackArchive.open(signed), false);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        SaoUninstaller plan = new SaoUninstaller(List.of(root2), instance(root2), temporary.resolve("Desktop"), new SaoUninstaller.Trash() {
            @Override public boolean supported() { return false; }

            @Override public void move(Path path) { throw new AssertionError("Unexpected native Trash call"); }
        }, false);
        SaoUninstallHelper.Factory helper = (roots, apps) -> new SaoUninstallHelper.Prepared() {
            @Override public void arm() { events.add("armed"); }

            @Override public void cancel() { events.add("cancelled"); }
        };
        try (SaoPackEngine engine = new SaoPackEngine(root2, plan, helper, () -> { }, () -> events.add("quit"))) {
            engine.setGameSteps(new FakeSteps());
            await(() -> "0.9".equals(engine.installedVersion().get()));
            assertEquals(SaoReadiness.Kind.DOWNLOAD, engine.readiness().get().kind());
            assertTrue(engine.computeReadiness(true).ready(), "complete installation remains playable offline without its source");
            engine.uninstall(false);
            await(() -> events.contains("quit"));
            assertEquals(new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, "还没下载", SaoReadiness.Action.PACK_WINDOW), engine.readiness().get());
        }
    }

    /// Local imports require no release policy, retain provenance after restart and refuse official-server entry.
    @Test void localImportRestartAndOfflineLaunch() throws Exception {
        SaoPackArchive local = archive("0.8", "local", "local.zip");
        Path root = temporary.resolve("local-import");
        try (SaoPackEngine engine = new SaoPackEngine(root, null, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            engine.acceptDroppedFiles(List.of(local.zip())).get(10, TimeUnit.SECONDS);
            assertTrue(engine.localPack());
            assertEquals("local", Files.readString(instance(root).resolve("config/managed.txt")));
            engine.linkStart(SaoGameBridge.offlineAccount("Fixture"), null);
            await(() -> engine.lastError().get() != null && engine.lastError().get().contains("仅支持离线单人"));
            assertEquals(0, steps.launches.get());
        }
        try (SaoPackEngine reopened = new SaoPackEngine(root, null, false)) {
            FakeSteps steps = new FakeSteps(); reopened.setGameSteps(steps);
            awaitReadiness(reopened, SaoReadiness.Kind.READY);
            assertTrue(reopened.localPack());
            reopened.linkStartOffline("Fixture");
            await(() -> steps.launches.get() == 1);
            assertFalse(Files.exists(instance(root).resolve(SaoOathLaunchTicket.FILE_NAME)));
        }
    }

    /// A same-inventory candidate that fails after staging leaves the accepted ZIP usable after restart.
    @Test void failedRepackKeepsTrustedSourceAndRepairRestoresOldFiles() throws Exception {
        SaoPackArchive original = archive("0.8", "good", "original.zip");
        Path repacked = repack(original, "repacked.zip", "good");
        assertEquals(original.manifest(), SaoPackArchive.open(repacked).manifest());
        String expectedHash = SaoUpdates.sha256(original.zip());
        assertNotEquals(expectedHash, SaoUpdates.sha256(repacked));
        Path root = temporary.resolve("failed-repack");
        SaoReleaseTestSupport.cacheVersion(root, policy(original.zip()));
        Path cached;
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            FakeSteps steps = new FakeSteps();
            engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            engine.acceptDroppedFiles(List.of(original.zip())).get(10, TimeUnit.SECONDS);
            cached = engine.requireSource().zip();
            Path receipt = engine.instanceDir().resolve(SaoPackInstaller.RECEIPT);
            String committed = Files.readString(receipt);
            assertFalse(engine.localPack());
            steps.installFailure = new IOException("Isolated dependency setup failed");
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.acceptDroppedFiles(List.of(repacked)).get(10, TimeUnit.SECONDS));
            assertSame(steps.installFailure, error.getCause());
            assertEquals(committed, Files.readString(receipt));
            assertEquals(expectedHash, SaoUpdates.sha256(cached));
            assertFalse(engine.localPack());
            Files.delete(engine.instanceDir().resolve("config/managed.txt"));
            Files.writeString(engine.instanceDir().resolve("options.txt"), "player settings\n");
        }
        Files.delete(original.zip());
        Files.delete(repacked);
        // Even a missing secondary pointer cannot be restored by looking at the source drop.
        Files.delete(root.resolve("pack-cache/active.json"));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            FakeSteps steps = new FakeSteps();
            engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            assertEquals(cached, engine.requireSource().zip());
            assertFalse(engine.localPack());
            engine.repair();
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            assertEquals(1, steps.installs.get());
            assertEquals("good", Files.readString(engine.instanceDir().resolve("config/managed.txt")));
            assertEquals("player settings\n", Files.readString(engine.instanceDir().resolve("options.txt")));
            assertEquals(expectedHash, SaoUpdates.sha256(cached));
        }
    }

    /// Installing a repack with identical managed bytes can still switch to a distinct committed identity.
    @Test void successfulRepackUsesNewExactSourceAcrossRestart() throws Exception {
        SaoPackArchive original = archive("0.8", "good", "original.zip");
        Path repacked = repack(original, "local-repack.zip", "good");
        Path root = temporary.resolve("accepted-repack");
        SaoReleaseTestSupport.cacheVersion(root, policy(original.zip()));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            engine.setGameSteps(new FakeSteps());
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            engine.acceptDroppedFiles(List.of(original.zip())).get(10, TimeUnit.SECONDS);
            Path cached = engine.requireSource().zip();
            engine.acceptDroppedFiles(List.of(repacked)).get(10, TimeUnit.SECONDS);
            assertNotEquals(cached, engine.requireSource().zip());
            assertFalse(Files.exists(cached));
        }
        Files.delete(root.resolve("pack-cache/active.json"));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            engine.setGameSteps(new FakeSteps());
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            assertEquals(SaoUpdates.sha256(repacked), SaoUpdates.sha256(engine.requireSource().zip()));
            assertTrue(engine.localPack());
        }
    }

    /// A newer official release does not force an update before offline single-player play.
    @Test void offlineLaunchIgnoresRemoteUpdate() throws Exception {
        SaoPackArchive old = archive("0.8", "old", "old.zip");
        Path signed = archive("0.9", "new", "new.zip").zip();
        Path root = temporary.resolve("offline-old");
        installer(instance(root)).apply(old, false);
        SaoReleaseTestSupport.cacheVersion(root, policy(signed).replace("0.1.0", "99.0"));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, old, false)) {
            FakeSteps steps = new FakeSteps(); engine.setGameSteps(steps);
            List<String> opened = Collections.synchronizedList(new ArrayList<>());
            engine.setLinkOpener(opened::add);
            awaitReadiness(engine, SaoReadiness.Kind.UPDATE);
            assertFalse(engine.launcherPermitted());
            engine.linkStartOffline("Fixture");
            await(() -> steps.launches.get() == 1);
            assertTrue(opened.isEmpty());
            assertEquals("0.8", engine.installedVersion().get());
        }
    }

    /// Relaxation persists only for the offline action, with managed data and signed multiplayer validation retained.
    @Test void offlineModPreferenceDoesNotRelaxOfficialReadiness() throws Exception {
        assumeFalse(Metadata.SAO_BETA);
        SaoPackArchive pack = archive("0.8", "complete", "modded.zip", true);
        Path root = temporary.resolve("offline-modded");
        Path game = instance(root);
        installer(game).apply(pack, false);
        Path mod = Files.writeString(game.resolve("mods/test.jar"), "user's modified mod");
        Path data = game.resolve("config/managed.txt");
        try (SaoPackEngine engine = new SaoPackEngine(root, pack, false)) {
            FakeSteps steps = new FakeSteps();
            engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            assertFalse(engine.allowModifiedModsOffline());
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.FAILED);
            assertEquals(0, steps.launches.get());
            engine.setAllowModifiedModsOffline(true);
        }
        try (SaoPackEngine engine = new SaoPackEngine(root, pack, false)) {
            FakeSteps steps = new FakeSteps();
            engine.setGameSteps(steps);
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            assertTrue(engine.allowModifiedModsOffline());
            engine.linkStart(SaoGameBridge.offlineAccount("Fixture"), null);
            await(() -> engine.state().get() == SaoPackState.FAILED);
            assertEquals(0, steps.launches.get());

            Files.delete(data);
            assertFalse(engine.computeReadiness(true).ready());
            Files.writeString(data, "complete");
            Files.delete(mod);
            assertTrue(engine.computeReadiness(true).ready());
            Path outside = Files.writeString(temporary.resolve("outside.jar"), "outside");
            Files.createSymbolicLink(mod, outside);
            assertFalse(engine.computeReadiness(true).ready());
            Files.delete(mod);
            Files.writeString(mod, "user's modified mod");
            Path staleTicket = Files.writeString(game.resolve(SaoOathLaunchTicket.FILE_NAME), "stale");
            engine.linkStartOffline("Fixture");
            await(() -> steps.launches.get() == 1);
            assertTrue(steps.lastOffline);
            assertNull(steps.lastServer);
            assertFalse(Files.exists(staleTicket));
            assertEquals("user's modified mod", Files.readString(mod));
        }
    }

    /// Removing remote policy requirements must not accept corrupt payload bytes declared by a local manifest.
    @Test void localImportStillRejectsDamagedPayload() throws Exception {
        SaoPackArchive original = archive("0.8", "good", "good.zip");
        Path damaged = repack(original, "damaged.zip", "bad!");
        Path root = temporary.resolve("damaged-local");
        try (SaoPackEngine engine = new SaoPackEngine(root, null, false)) {
            engine.setGameSteps(new FakeSteps());
            awaitReadiness(engine, SaoReadiness.Kind.DOWNLOAD);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.acceptDroppedFiles(List.of(damaged)).get(10, TimeUnit.SECONDS));
            assertNull(engine.installedVersion().get());
            assertFalse(Files.exists(instance(root).resolve("config/managed.txt")));
            engine.acceptDroppedFiles(List.of(original.zip())).get(10, TimeUnit.SECONDS);
            String installedHash = SaoUpdates.sha256(engine.requireSource().zip());
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.acceptDroppedFiles(List.of(damaged)).get(10, TimeUnit.SECONDS));
            assertEquals(installedHash, SaoUpdates.sha256(engine.requireSource().zip()));
        }
    }

    /// Keeps the explicit inventory identical while producing distinct ZIP bytes.
    private Path repack(SaoPackArchive original, String name, String managedValue) throws IOException {
        Path result = temporary.resolve(name);
        try (var input = new java.util.zip.ZipFile(original.zip().toFile());
             var output = new ZipOutputStream(Files.newOutputStream(result))) {
            output.setComment("Repacked fixture");
            for (var entries = input.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (var data = input.getInputStream(entry)) {
                    output.write(entry.getName().equals("overrides/config/managed.txt")
                            ? managedValue.getBytes(StandardCharsets.UTF_8) : data.readAllBytes());
                }
                output.closeEntry();
            }
        }
        return result;
    }

    /// The fixed instance directory, the same layout the engine uses.
    private static Path instance(Path root) { return new SaoGameBridge(root, (label, progress) -> { }).instanceDir(); }

    /// A file-only installer that fails on any network request.
    private static SaoPackInstaller installer(Path instance) {
        return new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Unexpected network request"); }, Map.of(), (label, progress) -> { });
    }

    /// A tiny complete pack with one managed file and a player-owned options seed.
    private SaoPackArchive archive(String version, String value, String name) throws Exception {
        return archive(version, value, name, false);
    }

    /// Adds a managed mod when testing the explicit offline launch preference.
    private SaoPackArchive archive(String version, String value, String name, boolean includeMod) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8), options = "seed".getBytes(StandardCharsets.UTF_8);
        SaoPackManifest.PackFile file = new SaoPackManifest.PackFile("config/managed.txt", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, "overrides/config/managed.txt", List.of());
        SaoPackManifest.PackFile seed = new SaoPackManifest.PackFile("options.txt", SaoPackArchive.sha1(new ByteArrayInputStream(options)),
                options.length, "overrides/options.txt", List.of());
        SaoPackManifest.PackFile mod = new SaoPackManifest.PackFile("mods/test.jar", file.sha1(),
                bytes.length, "overrides/mods/test.jar", List.of());
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", "",
                includeMod ? List.of(file, seed, mod) : List.of(file, seed), List.of());
        Path zip = Files.createDirectories(temporary.resolve("packs-" + version)).resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            output.putNextEntry(new ZipEntry(file.entry())); output.write(bytes); output.closeEntry();
            output.putNextEntry(new ZipEntry(seed.entry())); output.write(options); output.closeEntry();
            if (includeMod) {
                output.putNextEntry(new ZipEntry(mod.entry()));
                output.write(bytes);
                output.closeEntry();
            }
        }
        return SaoPackArchive.open(zip);
    }

    /// A signed-policy document advertising this full pack with the standard Quark link.
    private static String policy(Path full) throws Exception {
        SaoData.PackRelease release = new SaoData.PackRelease(SaoPackArchive.open(full).manifest().version(), full.getFileName().toString(),
                Files.size(full), SaoUpdates.sha256(full));
        SaoData.VersionMetadata metadata = new SaoData.VersionMetadata(2, SaoUpdates.QUARK_URL, release, List.of(), new SaoData.LauncherRelease("0.1.0", "0.1.0"));
        return new Gson().toJson(SaoUpdates.parse(new Gson().toJson(metadata)));
    }

    /// Waits for the readiness kind computed on the engine worker and for the operation gate to open.
    private static void awaitReadiness(SaoPackEngine engine, SaoReadiness.Kind kind) throws Exception {
        var field = SaoPackEngine.class.getDeclaredField("busy");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean busy = (java.util.concurrent.atomic.AtomicBoolean) field.get(engine);
        await(() -> engine.readiness().get().kind() == kind && !busy.get());
    }

    /// Short background work with a hard deadline.
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!condition.getAsBoolean()) Thread.sleep(5); });
    }
}
