/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Service-level headless regressions for missing distributions and accepted-update persistence.
@NotNullByDefault
class SaoPackEngineTest {
    /// One disposable launcher root per test.
    @TempDir Path temporary;

    /// An explicitly absent test release never claims READY or silently manufactures a playable pack.
    @Test void missingPackRemainsNotInstalledAndExplainsLinkStart() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            awaitCheck(engine);
            assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
            IOException failure = assertThrows(IOException.class, engine::requireSource);
            assertTrue(failure.getMessage().contains("-PsaoPack"));
            assertNull(engine.installedVersion().get());
        }
    }

    /// Previously this restart selected embedded 0.8 after a 0.9 drop, risking wrong-server launch and repair downgrade.
    @Test void restartRetainsNewerAcceptedReleaseForRepairAndServerSelection() throws Exception {
        SaoPackArchive embedded = archive("0.8", "old.example:25565", "old");
        SaoPackArchive update = archive("0.9", "new.example:25565", "new");
        Path root = temporary.resolve("launcher");
        Path instance;
        try (SaoPackEngine engine = new SaoPackEngine(root, embedded, false)) {
            awaitCheck(engine);
            instance = engine.instanceDir();
            SaoPackInstaller installer = installer(instance);
            installer.apply(update, false);
            SaoPackArchive cached = engine.cacheSource(update);
            engine.persistAcceptedSource(cached);
        }
        Files.delete(update.zip());
        try (SaoPackEngine reopened = new SaoPackEngine(root, embedded, false)) {
            awaitCheck(reopened);
            assertEquals(SaoPackState.READY, reopened.state().get());
            assertEquals("0.9", reopened.installedVersion().get());
            assertEquals("0.9", reopened.requireSource().manifest().version());
            assertEquals("new.example:25565", reopened.requireSource().manifest().server());
            Files.writeString(instance.resolve("config/managed.txt"), "damaged");
            assertEquals(1, installer(instance).apply(reopened.requireSource(), true).written());
            assertEquals("new", Files.readString(instance.resolve("config/managed.txt")));
        }
    }

    /// Closing an engine prevents subsequently submitted operations from starting.
    @Test void closedEngineRejectsPendingRequests() throws Exception {
        SaoPackEngine engine = new SaoPackEngine(temporary, null, false);
        awaitCheck(engine);
        engine.close();
        engine.repair();
        engine.applyUpdateZip(temporary.resolve("does-not-exist.zip"));
        assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
    }

    /// Waits for asynchronous inspection only; this never starts a toolkit or performs network work.
    private static void awaitCheck(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(5);
            assertNull(engine.lastError().get());
        });
    }

    /// Constructs a file-only installer that fails if a test accidentally requests a remote dependency.
    private static SaoPackInstaller installer(Path instance) {
        return new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Unexpected network request"); }, Map.of(), (label, progress) -> { });
    }

    /// Writes a tiny complete release with a distinct server and inventory for restart regression coverage.
    private SaoPackArchive archive(String version, String server, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        SaoPackManifest.PackFile file = new SaoPackManifest.PackFile("config/managed.txt", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, "overrides/config/managed.txt", List.of());
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", server, List.of(file), List.of());
        Path zip = Files.createTempFile(temporary, "release-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            output.putNextEntry(new ZipEntry(file.entry())); output.write(bytes); output.closeEntry();
        }
        return SaoPackArchive.open(zip);
    }
}
