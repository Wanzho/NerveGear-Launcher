/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.game.GameComponentAnalyzer;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Disk-backed restart regressions using the real bridge and repository, without a toolkit, game or downloads.
@NotNullByDefault
class SaoPackRestartTest {
    /// All fixture data, including a synthetic client JAR and accepted archive, stays in this temporary directory.
    @TempDir Path temporary;

    /// Closing and reopening an untouched installation retains readiness through real repository discovery.
    @Test void unchangedInstallRestartsReady() throws Exception {
        Path root = install();
        assertReady(root);
    }

    /// An unchanged data-root copy is discovered without either installation or a stale original path.
    @Test void unchangedCopiedInstallRestartsReady() throws Exception {
        assertReady(relocate(install()));
    }

    /// The same live engine accepts a game-written config change without requesting preparation.
    @Test void rewrittenConfigRemainsReadyInSameSession() throws Exception {
        Path root = install();
        try (SaoPackEngine engine = open(root)) {
            Files.writeString(instance(root).resolve("config/example-common.toml"), "enabled = false\n# changed\n");
            assertEquals(SaoReadiness.READY, engine.computeReadiness());
        }
    }

    /// The committed receipt locates the cached pack even if a crash prevented active.json publication.
    @Test void receiptRecoversSourceWithoutActivePointer() throws Exception {
        Path root = install();
        Files.delete(root.resolve("pack-cache/active.json"));
        assertReady(root);
    }

    /// Installed profiles created before exact cache identities still resolve their legacy archive names.
    @Test void legacyReceiptStillFindsManifestKeyedArchive() throws Exception {
        Path root = install();
        Path cache = root.resolve("pack-cache");
        var installer = new SaoPackInstaller(instance(root), (file, destination) -> { throw new AssertionError("Unexpected download"); },
                Map.of(), (label, progress) -> { });
        SaoPackManifest manifest = java.util.Objects.requireNonNull(installer.installed());
        String receipt = manifest.toJson();
        String oldKey = SaoPackArchive.sha1(new ByteArrayInputStream(receipt.getBytes(StandardCharsets.UTF_8)));
        Files.move(cache.resolve(java.util.Objects.requireNonNull(installer.installedArchive())), cache.resolve(oldKey + ".zip"));
        Files.writeString(instance(root).resolve(SaoPackInstaller.RECEIPT), receipt);
        Files.delete(cache.resolve("active.json"));
        assertReady(root);
    }

    /// Normal config rewrites must not turn an installed pack back into a download on the next startup.
    @Test void rewrittenConfigRestartsReady() throws Exception {
        Path root = install();
        Files.writeString(instance(root).resolve("config/example-common.toml"), "enabled = false\n# rewritten by game\n");
        assertReady(root);
        assertEquals("enabled = false\n# rewritten by game\n", Files.readString(instance(root).resolve("config/example-common.toml")));
    }

    /// An entire data-root copy remains playable even after the old location has ceased to exist.
    @Test void copiedInstallRestartsReady() throws Exception {
        Path root = install();
        Files.writeString(instance(root).resolve("config/example-common.toml"), "enabled = false\n# rewritten by game\n");
        Path moved = relocate(root);
        assertReady(moved);
        assertEquals("enabled = false\n# rewritten by game\n", Files.readString(instance(moved).resolve("config/example-common.toml")));
    }

    /// Legacy absolute Java choices must not hide available Java 17 after the old data root is moved away.
    @Test void movedInstallDiscardsMissingLegacyJavaChoice() throws Exception {
        Path root = install();
        Path oldJava = root.resolve("java/old-runtime/bin/java");
        Files.createDirectories(oldJava.getParent());
        Files.writeString(oldJava, "synthetic stale runtime choice");
        new SaoSettings(root).put("java", oldJava.toString());
        assertReady(relocate(root));
    }

    /// Missing config files and authored payloads still require preparation.
    @Test void missingConfigAndPayloadRemainIncomplete() throws Exception {
        Path root = install();
        Files.delete(instance(root).resolve("config/example-common.toml"));
        try (SaoPackEngine engine = open(root)) {
            assertEquals(SaoReadinessCheck.INCOMPLETE, engine.computeReadiness().reason());
        }
        Files.delete(instance(root).resolve("config/authored.txt"));
        try (SaoPackEngine engine = open(root)) {
            assertEquals(SaoReadinessCheck.INCOMPLETE, engine.computeReadiness().reason());
        }
    }

    /// Mutable-format exceptions cannot conceal a directory in place of a config or a truncated managed payload.
    @Test void invalidConfigAndTruncatedPayloadRemainIncomplete() throws Exception {
        Path root = install();
        Path config = instance(root).resolve("config/example-common.toml");
        Files.delete(config);
        Files.createDirectory(config);
        try (SaoPackEngine engine = open(root)) {
            assertEquals(SaoReadinessCheck.INCOMPLETE, engine.computeReadiness().reason());
        }
        Files.delete(config);
        Files.writeString(config, "enabled = true\n");
        Files.writeString(instance(root).resolve("config/authored.txt"), "truncated");
        try (SaoPackEngine engine = open(root)) {
            assertEquals(SaoReadinessCheck.INCOMPLETE, engine.computeReadiness().reason());
        }
    }

    /// A selected executable under the data root is persisted relatively and resolved after relocation.
    @Test void selectedLocalJavaUsesPortableSetting() throws Exception {
        Path root = temporary.resolve("portable/.sao-oath-launcher");
        Path executable = root.resolve("java/runtime/bin/java");
        Files.createDirectories(executable.getParent());
        // Gradle can run on a different JVM. Link a compatible game runtime without copying the host JDK.
        Path gameJava = new SaoGameBridge(root, (label, progress) -> {}).availableJava().getFirst().getBinary().toRealPath();
        Files.createSymbolicLink(executable, gameJava);
        try (SaoPackEngine engine = open(root)) {
            engine.setJava(executable).get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(root.relativize(executable).toString(), new SaoSettings(root).text("java", ""));
        }
        Path moved = temporary.resolve("portable-moved/.sao-oath-launcher");
        Files.createDirectories(moved.getParent());
        Files.move(root, moved);
        try (SaoPackEngine engine = open(moved)) {
            assertEquals(moved.resolve("java/runtime/bin/java"), engine.java());
        }
    }

    /// The real service switches every game helper, persists the pointer and removes old game data only after READY.
    @Test void inLauncherMoveKeepsPlayerBytesAndRestarts() throws Exception {
        Path original = install(), moved = temporary.resolve("玩家游戏");
        try (SaoPackEngine engine = open(original)) {
            engine.gameLocationInfo().get(10, java.util.concurrent.TimeUnit.SECONDS);
            engine.chooseGameLocation(moved).get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(moved, engine.gameDataRoot());
            assertEquals(instance(moved), engine.instanceDir());
            assertEquals(SaoReadiness.READY, engine.computeReadiness());
            assertTrue(engine.requireSource().zip().startsWith(moved));
            for (String path : playerPaths()) assertEquals("player-owned:" + path, Files.readString(instance(moved).resolve(path)));
            assertFalse(Files.exists(original.resolve("minecraft")));
            assertFalse(Files.exists(original.resolve("pack-cache")));
        }
        try (SaoPackEngine restarted = open(original)) {
            assertEquals(moved, restarted.gameDataRoot());
            assertEquals(SaoReadiness.READY, restarted.computeReadiness());
        }
    }

    /// A real bridge that cannot reach READY restores its original pointer and leaves both full copies.
    @Test void inLauncherMoveRollsBackWhenClientMissing() throws Exception {
        Path original = install(), moved = temporary.resolve("incomplete");
        Files.delete(instance(original).resolve("sao-oath.jar"));
        try (SaoPackEngine engine = open(original)) {
            engine.gameLocationInfo().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.chooseGameLocation(moved).get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(original, engine.gameDataRoot());
            for (String path : playerPaths()) assertArrayEquals(Files.readAllBytes(instance(original).resolve(path)), Files.readAllBytes(instance(moved).resolve(path)));
            assertEquals(original.toString(), new SaoSettings(original).text("gameDataRoot", ""));
        }
    }

    /// Seeds a complete synthetic installation with real installer receipts and accepted-source persistence.
    private Path install() throws Exception {
        Path root = temporary.resolve("original/张三/.sao-oath-launcher");
        Path instance = instance(root);
        Files.createDirectories(instance);
        GameInstanceManifest game = new GameInstanceManifest(SaoGameBridge.INSTANCE_ID).withRoot(false)
                .withPatches(List.of(new GameInstancePatch("neoforge").withVersion("47.1.106")))
                .withMainClass(GameComponentAnalyzer.BOOTSTRAP_LAUNCHER_MAIN)
                .withLibraries(List.of(new Library("net.neoforged.fancymodloader", "loader", "1.20.1-47.1.106")));
        Files.writeString(instance.resolve("sao-oath.json"), JsonUtils.GSON.toJson(game));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(instance.resolve("sao-oath.jar")))) {
            member(output, "version.json", "{\"id\":\"1.20.1\"}");
        }
        @Unmodifiable Map<String, String> contents = Map.of("config/example-common.toml", "enabled = true\n",
                "config/authored.txt", "unchanged authored payload");
        @Unmodifiable List<SaoPackManifest.PackFile> inventory = contents.entrySet().stream().map(entry -> {
            try {
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                return new SaoPackManifest.PackFile(entry.getKey(), SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                        bytes.length, "overrides/" + entry.getKey(), List.of());
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }).toList();
        SaoPackManifest manifest = new SaoPackManifest("0.8", "1.20.1", "47.1.106", "", inventory, List.of());
        Path zip = temporary.resolve("release.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            member(output, SaoPackManifest.NAME, manifest.toJson());
            for (var entry : contents.entrySet()) member(output, "overrides/" + entry.getKey(), entry.getValue());
        }
        SaoPackArchive archive = SaoPackArchive.open(zip);
        var installer = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Unexpected download"); },
                Map.of(), (label, progress) -> { });
        installer.apply(archive, false);
        for (String path : playerPaths()) {
            Path destination = instance.resolve(path);
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, "player-owned:" + path);
        }
        try (SaoPackEngine engine = new SaoPackEngine(root, archive, false)) {
            awaitCheck(engine);
            SaoPackArchive cached = engine.cacheSource(archive);
            installer.applyCachedFull(cached, false);
            engine.persistAcceptedSource(cached);
            assertEquals(SaoReadiness.READY, engine.computeReadiness());
            // Reopen before close: no clean-exit callback or settings flush can be responsible for readiness.
            assertReady(root);
        }
        Files.delete(zip);
        return root;
    }

    /// Copies every data-root entry, then moves the original within the test directory to make stale paths fail.
    private Path relocate(Path root) throws Exception {
        Path moved = temporary.resolve("other-drive/移动目录/.sao-oath-launcher");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                Path target = moved.resolve(root.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        Files.move(root, root.resolveSibling("old-location-unavailable"));
        return moved;
    }

    /// Asserts actual launch readiness, source recovery and unchanged player bytes, rather than the coarse pack state alone.
    private static void assertReady(Path root) throws Exception {
        try (SaoPackEngine engine = open(root)) {
            assertEquals(SaoPackState.READY, engine.state().get());
            assertEquals(SaoReadiness.READY, engine.computeReadiness());
            assertEquals("0.8", engine.requireSource().manifest().version());
            assertTrue(engine.requireSource().zip().startsWith(root));
            for (String path : playerPaths()) assertEquals("player-owned:" + path, Files.readString(instance(root).resolve(path)));
            assertFalse(Files.exists(root.resolve("minecraft/.sao-unfinished")));
        }
    }

    /// Opens a fresh production-check engine with no embedded distribution or network metadata source.
    private static SaoPackEngine open(Path root) {
        SaoPackEngine engine = new SaoPackEngine(root, null, false);
        awaitCheck(engine);
        return engine;
    }

    /// Waits only for startup inspection, never initializes JavaFX or starts a game.
    private static void awaitCheck(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(5);
            assertNull(engine.lastError().get());
        });
    }

    /// Returns protected fixture paths covering each player-data category in the regression contract.
    private static @Unmodifiable List<String> playerPaths() {
        return List.of("saves/world/level.dat", "options.txt", "screenshots/test.png", "shaderpacks/custom.zip");
    }

    /// Re-derives the fixed instance path from the current data root.
    private static Path instance(Path root) { return root.resolve("minecraft/versions/sao-oath"); }

    /// Writes a UTF-8 fixture ZIP member and closes only that member.
    private static void member(ZipOutputStream output, String name, String value) throws java.io.IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
