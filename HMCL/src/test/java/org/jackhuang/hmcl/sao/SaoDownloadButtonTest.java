/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.Gson;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
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

/// 下载 vs Link Start (James, 11 Nov 2026): 下载 prepares and never launches; Link Start checks first and refuses
/// without downloading. Headless: fake game steps, no network, no toolkit, no game process.
@NotNullByDefault
class SaoDownloadButtonTest {
    /// Disposable launcher roots and archives.
    @TempDir Path temporary;

    /// Counts game steps instead of downloading Minecraft or starting a process.
    @NotNullByDefault
    private static final class FakeSteps implements SaoReadinessCheck.GameSteps {
        final AtomicInteger installs = new AtomicInteger(), launches = new AtomicInteger();
        volatile @Nullable String missing;
        @Override public void ensureInstalled(boolean repair) { installs.incrementAndGet(); }
        @Override public @Nullable String missing() { return missing; }
        @Override public void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window, String jvmArgs,
                                     char @Nullable [] password, boolean offline, @Nullable String signedServer,
                                     BooleanSupplier cancelled, ProcessListener listener) { launches.incrementAndGet(); }
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
            // RUNNING is published just before the launch call; wait for the call itself.
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
            engine.linkStartOffline("Fixture");
            await(() -> engine.state().get() == SaoPackState.FAILED);
            await(() -> engine.downloadPrompt().get().watching());
            assertEquals("有新版本 0.9", engine.lastError().get());
            assertEquals(List.of(SaoUpdates.QUARK_URL), opened);
            assertEquals(0, steps.launches.get()); assertEquals(0, steps.installs.get());
            assertEquals("0.8", engine.installedVersion().get());
            // 更新 does the same.
            engine.download();
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
            awaitReadiness(engine, SaoReadiness.Kind.READY);
            engine.uninstall(false);
            await(() -> events.contains("quit"));
            assertEquals(new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, "还没下载", SaoReadiness.Action.PACK_WINDOW), engine.readiness().get());
        }
    }

    /// The fixed instance directory, the same layout the engine uses.
    private static Path instance(Path root) { return new SaoGameBridge(root, (label, progress) -> { }).instanceDir(); }

    /// A file-only installer that fails on any network request.
    private static SaoPackInstaller installer(Path instance) {
        return new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Unexpected network request"); }, Map.of(), (label, progress) -> { });
    }

    /// A tiny complete pack with one managed file and a player-owned options seed.
    private SaoPackArchive archive(String version, String value, String name) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8), options = "seed".getBytes(StandardCharsets.UTF_8);
        SaoPackManifest.PackFile file = new SaoPackManifest.PackFile("config/managed.txt", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, "overrides/config/managed.txt", List.of());
        SaoPackManifest.PackFile seed = new SaoPackManifest.PackFile("options.txt", SaoPackArchive.sha1(new ByteArrayInputStream(options)),
                options.length, "overrides/options.txt", List.of());
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", "", List.of(file, seed), List.of());
        Path zip = Files.createDirectories(temporary.resolve("packs-" + version)).resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            output.putNextEntry(new ZipEntry(file.entry())); output.write(bytes); output.closeEntry();
            output.putNextEntry(new ZipEntry(seed.entry())); output.write(options); output.closeEntry();
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
