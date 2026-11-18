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
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/// Opt-in real-archive proof; only game/runtime availability is substituted, never update/signature/install logic.
/// Set SAO_QUICKTEST_RUN, SAO_QUICKTEST_OLD, SAO_QUICKTEST_NEW and SAO_QUICKTEST_BASELINE to local paths.
@NotNullByDefault
@EnabledIfEnvironmentVariable(named = "SAO_QUICKTEST_RUN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SaoRealQuickUpdateTest {
    /// Evidence and throwaway signing material, explicitly outside production configuration.
    private final Path run = environment("SAO_QUICKTEST_RUN");
    /// Read-only original archive.
    private final Path oldZip = environment("SAO_QUICKTEST_OLD");
    /// Read-only target archive; its actual numeric manifest version drives reruns.
    private final Path newZip = environment("SAO_QUICKTEST_NEW");
    /// Output filename selected by the proof runner, defaulting to the original stand-in name.
    private final String quickName = System.getenv().getOrDefault("SAO_QUICKTEST_FILENAME", "quick-update.zip");
    /// Verified local external dependencies; no downloads are permitted.
    private final Path baseline = environment("SAO_QUICKTEST_BASELINE");
    /// Test-only signer; never a release key.
    private final KeyPair key = SaoReleaseTestSupport.key();
    /// Explicit package-private public trust injection.
    private final SaoReleaseSignatures trust = new SaoReleaseSignatures(List.of(key.getPublic()));
    /// One independently disposable data root per test.
    @TempDir Path temporary;
    /// Initialized only after the CLI successfully produces its package.
    private @Nullable SaoQuickUpdate update;
    /// Exact signed policy bytes persisted for review.
    private byte @Nullable [] document;
    /// Exact detached signature persisted for review.
    private byte @Nullable [] signature;

    /// Requires explicit inputs so ordinary unit-suite runs do not touch large local archives.
    private static Path environment(String name) { return Path.of(Objects.requireNonNull(System.getenv(name), name)); }

    /// Runs the production CLI and signs its measured output, retaining all evidence in the requested run directory.
    @BeforeAll void buildAndSign() throws Exception {
        Files.createDirectories(run);
        Path quick = run.resolve(quickName);
        assertFalse(Files.exists(quick), "Use a fresh run folder or archive the previous quick-update.zip before rerunning");
        SaoUpdateBuilder.main(new String[]{"--make-update", oldZip.toString(), newZip.toString(), quick.toString(),
                "--baseline", baseline.toString()});
        update = SaoQuickUpdate.open(quick);
        SaoQuickUpdate hop = Objects.requireNonNull(update);
        assertTrue(SaoUpdates.compare(hop.from(), hop.to()) < 0);
        SaoData.PackRelease full = new SaoData.PackRelease(hop.to(), newZip.getFileName().toString(), Files.size(newZip), SaoUpdates.sha256(newZip));
        SaoData.QuickRelease edge = new SaoData.QuickRelease(hop.from(), hop.to(), quick.getFileName().toString(), Files.size(quick), SaoUpdates.sha256(quick));
        String json = new Gson().toJson(new SaoData.VersionMetadata(2, SaoUpdates.QUARK_URL, full, List.of(edge), new SaoData.LauncherRelease("0.1.0", "0.1.0")));
        document = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        signature = SaoReleaseTestSupport.sign(Objects.requireNonNull(document), key);
        Files.write(run.resolve("version.json"), document);
        Files.write(run.resolve("version.json.sig"), Objects.requireNonNull(signature));
        Files.write(run.resolve("test-ed25519-private.pk8"), key.getPrivate().getEncoded());
        Files.setPosixFilePermissions(run.resolve("test-ed25519-private.pk8"), PosixFilePermissions.fromString("rw-------"));
        Files.write(run.resolve("test-ed25519-public.der"), key.getPublic().getEncoded());
        trust.verify(document, signature);
        assertThrows(IOException.class, () -> SaoReleaseSignatures.PRODUCTION.verify(document, signature));
        Files.writeString(run.resolve("quick-identity.json"), new Gson().toJson(edge));
        System.out.printf("REAL QUICK: %s -> %s, added=%d changed=%d removed=%d, size=%d sha256=%s%n",
                hop.from(), hop.to(), hop.added().size(), hop.changed().size(), hop.removed().size(), edge.size(), edge.sha256());
    }

    /// Uses authentic signed-cache ingestion, the same verifier and policy validation as online release handling.
    private void cachePolicy(Path root) throws IOException {
        new SaoReleaseState(root, trust).accept(SaoReleaseState.Kind.VERSION, Objects.requireNonNull(document), Objects.requireNonNull(signature));
    }

    /// Installs every old embedded/external file using verified local content, refusing any network fallback.
    private Path installOld(Path root) throws Exception {
        Path instance;
        try (SaoPackEngine engine = new SaoPackEngine(root, null, false, trust)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            instance = engine.instanceDir();
        }
        SaoPackInstaller installer = new SaoPackInstaller(instance,
                (file, destination) -> { throw new AssertionError("Unexpected download: " + file.path()); },
                SaoPackInstaller.readBaseline(baseline), (label, progress) -> { });
        installer.apply(SaoPackArchive.open(oldZip), false);
        assertEquals(Objects.requireNonNull(update).from(), Objects.requireNonNull(installer.installed()).version());
        return instance;
    }

    /// Creates distinct player data and returns the exact expected bytes, including the disabled opening preference.
    private static @Unmodifiable Map<String, String> players(Path instance) throws IOException {
        @Unmodifiable Map<String, String> values = Map.of("options.txt", "key_key.forward:key.keyboard.p\nresourcePacks:[\"vanilla\"]\nquicktest:custom\n",
                "saves/test/level.dat", "quicktest world bytes\u0000\u0001", "config/sao-entrance.properties", "playWhen=NEVER\n",
                "screenshots/quicktest.png", "quicktest screenshot bytes\u0000");
        for (var entry : values.entrySet()) {
            Path path = instance.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue());
        }
        return values;
    }

    /// Checks every protected file byte-for-byte independently of receipt metadata.
    private static void assertPlayers(Path instance, Map<String, String> expected) throws IOException {
        for (var entry : expected.entrySet()) assertArrayEquals(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(instance.resolve(entry.getKey())), entry.getKey());
    }

    /// Models a previously prepared runtime; any reinstall or launch during quick update immediately fails the test.
    @NotNullByDefault
    private static final class PreparedGame implements SaoReadinessCheck.GameSteps {
        /// A quick update must not call the full game installation path.
        @Override public void ensureInstalled(boolean repair) { fail("Unexpected full runtime installation"); }

        /// Runtime availability is the sole seam, explicitly outside this archive/update test's scope.
        @Override public @Nullable String missing() { return null; }

        /// The test must never launch a game process.
        @Override public void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window,
                                     String jvmArgs, char @Nullable [] password, boolean offline, @Nullable String signedServer,
                                     BooleanSupplier cancelled, ProcessListener listener) { fail("Unexpected game launch"); }
    }

    /// Runs the player's drop path, checks the entire final inventory/removals and player bytes, then restarts the engine.
    @Test void signedDropAppliesCompleteRealInventoryAndSurvivesRestart() throws Exception {
        Path root = temporary.resolve("success"); Path instance = installOld(root);
        @Unmodifiable Map<String, String> playerBytes = players(instance);
        SaoQuickUpdate hop = Objects.requireNonNull(update);
        assertFalse(hop.added().isEmpty()); assertFalse(hop.changed().isEmpty()); assertFalse(hop.removed().isEmpty());
        String untouched = hop.before().files().stream().map(SaoPackManifest.PackFile::path)
                .filter(path -> !SaoPackManifest.isPlayerFile(path) && hop.expected().containsKey(path)
                        && !hop.changed().contains(path)).findFirst().orElseThrow();
        @Nullable Object inode = Files.readAttributes(instance.resolve(untouched), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        cachePolicy(root);
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(oldZip), false, trust)) {
            engine.setGameSteps(new PreparedGame()); await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.acceptDroppedFiles(List.of(run.resolve(quickName))).get(5, TimeUnit.MINUTES);
            assertEquals(hop.to(), engine.installedVersion().get());
            assertEquals(SaoPackState.READY, engine.state().get()); assertEquals(SaoReadiness.READY, engine.readiness().get());
            assertNull(engine.lastError().get());
            int checked = 0, removed = 0;
            SaoPackArchive target = SaoPackArchive.open(newZip);
            for (var file : target.manifest().files()) {
                if (SaoPackManifest.isPlayerFile(file.path())) continue;
                assertTrue(SaoPackFiles.matches(instance.resolve(file.path()), file), file.path());
                assertEquals(hop.expected().get(file.path()), SaoUpdates.sha256(instance.resolve(file.path())), file.path()); checked++;
            }
            for (String path : hop.removed()) if (!SaoPackManifest.isPlayerFile(path)) {
                    assertFalse(Files.exists(instance.resolve(path)), path); removed++;
                }
            assertPlayers(instance, playerBytes);
            if ("true".equals(System.getenv("SAO_QUICKTEST_REAL_0811"))) assertRealRelease(instance, hop);
            assertEquals(inode, Files.readAttributes(instance.resolve(untouched), java.nio.file.attribute.BasicFileAttributes.class).fileKey());
            assertTrue(engine.requireSource().zip().startsWith(root.resolve("pack-cache")));
            Files.writeString(run.resolve("success.json"), new Gson().toJson(Map.of("installedVersion", hop.to(), "managedVerified", checked,
                    "removedVerified", removed, "playerFilesVerified", playerBytes.size(), "state", engine.state().get().name(),
                    "readiness", engine.readiness().get().kind().name(), "runtime", "prepared test seam; no game installation or launch")));
        }
        try (SaoPackEngine restarted = new SaoPackEngine(root, null, false, trust)) {
            restarted.setGameSteps(new PreparedGame());
            await(() -> restarted.state().get() != SaoPackState.CHECKING && restarted.readiness().get().ready());
            assertEquals(hop.to(), restarted.requireSource().manifest().version());
            assertEquals(SaoPackState.READY, restarted.state().get()); assertPlayers(instance, playerBytes);
        }
    }

    /// Verifies the actual 0.8.1.1 release additions and records the protected options behavior.
    private void assertRealRelease(Path instance, SaoQuickUpdate hop) throws Exception {
        assertEquals("0.8.1", hop.from()); assertEquals("0.8.1.1", hop.to());
        String oldJar = "mods/sao_interface_packui-0.8-rc6-pre107.jar";
        String newJar = "mods/sao_interface_packui-0.8-rc6-pre111.jar";
        String oculus = "mods/oculus-mc1.20.1-1.8.0.jar";
        assertTrue(hop.removed().contains(oldJar)); assertFalse(Files.exists(instance.resolve(oldJar)));
        assertTrue(hop.added().contains(newJar)); assertTrue(Files.isRegularFile(instance.resolve(newJar)));
        assertTrue(hop.added().contains(oculus));
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(instance.resolve(oculus))));
        assertEquals("984f774e71790deaec674c7587bd24e0711871b2", sha1);
        assertTrue(SaoPackManifest.isPlayerFile("options.txt"));
        assertTrue(hop.changed().contains("options.txt"));
        assertFalse(Files.readString(instance.resolve("options.txt")).contains("SAO中文补全"));
        Files.writeString(run.resolve("real-release-assertions.json"), new Gson().toJson(Map.of(
                "pre107Removed", true, "pre111Present", true, "oculusSha1", sha1,
                "oculusSource", "verified local baseline embedded by CLI; no apply-time download",
                "optionsPlayerOwned", true, "optionsByteIdentical", true, "chinesePackEnabledByQuickUpdate", false)));
    }

    /// One flipped byte, with identical file length, must fail authentication without modifying installed/player bytes.
    @Test void tamperedQuickDropIsRejectedWithoutMutation() throws Exception {
        Path root = temporary.resolve("tamper"); Path instance = installOld(root);
        @Unmodifiable Map<String, String> playerBytes = players(instance); cachePolicy(root);
        byte @Unmodifiable [] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT));
        Path tampered = temporary.resolve("tampered.zip"); Files.copy(run.resolve(quickName), tampered);
        try (RandomAccessFile bytes = new RandomAccessFile(tampered.toFile(), "rw")) {
            long offset = bytes.length() / 2; bytes.seek(offset); int value = bytes.read(); bytes.seek(offset); bytes.write(value ^ 1);
        }
        assertEquals(Files.size(run.resolve(quickName)), Files.size(tampered));
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(oldZip), false, trust)) {
            engine.setGameSteps(new PreparedGame()); await(() -> engine.state().get() != SaoPackState.CHECKING);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> engine.acceptDroppedFiles(List.of(tampered)).get(60, TimeUnit.SECONDS));
            assertEquals(SaoUpdates.INVALID_ARCHIVE, Objects.requireNonNull(failure.getCause()).getMessage());
            assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT))); assertPlayers(instance, playerBytes);
        }
    }

    /// An installed receipt for a different base must request the full pack with the promised exact user-facing text.
    @Test void wrongBaseQuickDropRequestsFullPack() throws Exception {
        Path root = temporary.resolve("wrong-base"); Path instance = installOld(root);
        @Unmodifiable Map<String, String> playerBytes = players(instance);
        SaoPackManifest original = SaoPackArchive.open(oldZip).manifest();
        Files.writeString(instance.resolve(SaoPackInstaller.RECEIPT), SaoQuickUpdate.relabel(original, "0.7", original.files()).toJson());
        byte @Unmodifiable [] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT)); cachePolicy(root);
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(oldZip), false, trust)) {
            engine.setGameSteps(new PreparedGame()); await(() -> engine.state().get() != SaoPackState.CHECKING);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> engine.acceptDroppedFiles(List.of(run.resolve(quickName))).get(60, TimeUnit.SECONDS));
            assertEquals("当前文件不适合快速更新，请下载完整模组包。", Objects.requireNonNull(failure.getCause()).getMessage());
            assertTrue(engine.downloadPrompt().get().fullPackRequired());
            assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT))); assertPlayers(instance, playerBytes);
        }
    }

    /// Records the numeric policy contract rather than silently claiming that a test-name suffix reaches the receipt.
    @Test void releaseVersionsAreNumeric() {
        assertThrows(IOException.class, () -> SaoUpdates.version("0.8.1.1-test"));
        assertEquals(Objects.requireNonNull(update).to(), assertDoesNotThrow(() -> SaoPackArchive.open(newZip)).manifest().version());
    }

    /// Waits for backend initialization only; never starts JavaFX or a game.
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> { while (!condition.getAsBoolean()) Thread.sleep(20); });
    }
}
