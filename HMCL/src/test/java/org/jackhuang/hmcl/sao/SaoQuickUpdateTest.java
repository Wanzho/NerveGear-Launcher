/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/// Hermetic schema-2, builder, accumulated-drop and transactional quick-update regressions.
@NotNullByDefault
class SaoQuickUpdateTest {
    /// Each test owns all archives, instances and caches under its disposable fixture root.
    @TempDir Path temporary;

    /// Builds either supported real archive layout from deterministic embedded fixture content.
    private Path pack(String version, String filename, Map<String, String> content, boolean modrinth) throws IOException {
        Path zip = temporary.resolve(filename);
        List<SaoPackManifest.PackFile> files = new ArrayList<>();
        for (var entry : new TreeMap<>(content).entrySet()) {
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            files.add(new SaoPackManifest.PackFile(entry.getKey(), SaoPackArchive.sha1(new ByteArrayInputStream(bytes)), bytes.length,
                    "overrides/" + entry.getKey(), List.of()));
        }
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", "", files, List.of());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            if (modrinth) {
                JsonObject index = new JsonObject(); index.addProperty("name", "SAO OATH" + version); index.addProperty("versionId", "1.0.0");
                JsonObject dependencies = new JsonObject(); dependencies.addProperty("minecraft", "1.20.1"); dependencies.addProperty("neoforge", "47.1.106");
                index.add("dependencies", dependencies); index.add("files", new JsonArray());
                member(output, "modrinth.index.json", index.toString());
            } else member(output, SaoPackManifest.NAME, manifest.toJson());
            for (var entry : new TreeMap<>(content).entrySet()) member(output, "overrides/" + entry.getKey(), entry.getValue());
        }
        return zip;
    }

    /// Writes one UTF-8 ZIP entry; fixture content deliberately stays small.
    private static void member(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name)); output.write(value.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
    }

    /// Generates a quick package using the production builder without any network or game runtime.
    private SaoData.QuickRelease delta(Path old, Path next, String filename) throws IOException {
        return SaoUpdateBuilder.build(old, next, temporary.resolve(filename), Map.of(), null, null);
    }

    /// Complete compressed identity for any full fixture archive.
    private static SaoData.PackRelease release(Path full) throws IOException {
        return new SaoData.PackRelease(SaoPackArchive.open(full).manifest().version(), full.getFileName().toString(), Files.size(full), SaoUpdates.sha256(full));
    }

    /// Serializes a policy using the same schema-2 field names accepted by production metadata parsing.
    private static SaoData.VersionMetadata policy(Path full, List<SaoData.QuickRelease> hops) throws IOException {
        SaoData.VersionMetadata metadata = new SaoData.VersionMetadata(2, SaoUpdates.QUARK_URL, release(full), hops, new SaoData.LauncherRelease("0.1.0", "0.1.0"));
        return SaoUpdates.parse(new Gson().toJson(metadata));
    }

    /// Installer fixture refuses every external dependency; no incidental network preparation can pass unnoticed.
    private static SaoPackInstaller installer(Path instance) {
        return new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Unexpected network payload"); }, Map.of(), (label, progress) -> { });
    }

    /// One/two/three-hop chains use numeric version ordering and the exact requested Chinese punctuation.
    @Test void schemaChainsAndPromptTextAreStrict() throws Exception {
        Path finalPack = pack("0.8.3", "final.zip", Map.of("mods/a.jar", "a"), false);
        String hash = "0".repeat(64);
        var first = new SaoData.QuickRelease("0.8", "0.8.1", "first.zip", 1, hash);
        var second = new SaoData.QuickRelease("0.8.1", "0.8.2", "second.zip", 2, hash);
        var third = new SaoData.QuickRelease("0.8.2", "0.8.3", "third.zip", 3, hash);
        SaoData.VersionMetadata metadata = policy(finalPack, List.of(first, second, third));
        assertEquals(3, SaoUpdates.chain(metadata, "0.8").size());
        assertEquals(2, SaoUpdates.chain(metadata, "0.8.1").size());
        assertEquals(1, SaoUpdates.chain(metadata, "0.8.2").size());
        assertTrue(SaoUpdates.chain(metadata, "0.7").isEmpty()); assertTrue(SaoUpdates.chain(metadata, null).isEmpty());
        assertEquals("请把 [0.8.1] 的快速更新包拖进来", SaoUpdates.prompt(List.of("0.8.1")));
        assertEquals("请把 [0.8.1] 和 [0.8.2] 的快速更新包拖进来", SaoUpdates.prompt(List.of("0.8.1", "0.8.2")));
        assertEquals("请把 [0.8.1]、[0.8.2] 和 [0.8.3] 的快速更新包拖进来", SaoUpdates.prompt(List.of("0.8.1", "0.8.2", "0.8.3")));
        assertEquals("请把在夸克里下载的模组文件拖进来", SaoUpdates.prompt(List.of()));
        String valid = new Gson().toJson(metadata);
        for (String invalid : List.of(valid.replace("\"from\":\"0.8.1\"", "\"from\":\"0.8\""),
                valid.replace("\"from\":\"0.8.1\"", "\"from\":\"0.8.0\""),
                valid.replace("\"from\":\"0.8.2\"", "\"from\":\"0.8.4\""),
                valid.replace("\"to\":\"0.8.1\"", "\"to\":\"0.8\""),
                valid.replace("\"to\":\"0.8.3\"", "\"to\":\"0.8.4\""),
                valid.replace("\"updates\":[", "\"unused\":1,\"updates\":["),
                valid.replace("\"size\":1,", "\"size\":1.0,")))
            assertThrows(IOException.class, () -> SaoUpdates.parse(invalid), invalid);
        assertThrows(IOException.class, () -> policy(finalPack, List.of(first, third)));
    }

    /// Extras capacity refusal sees the final inventory and leaves the old receipt and managed/player bytes intact.
    @Test void composedCapacityGatePrecedesCommit() throws Exception {
        Path old = pack("0.8", "capacity-old.zip", Map.of("mods/a.jar", "old", "options.txt", "seed"), false);
        Path next = pack("0.8.1", "capacity-next.zip", Map.of("mods/a.jar", "new-longer", "config/new.txt", "added", "options.txt", "seed"), false);
        SaoData.QuickRelease edge = delta(old, next, "capacity-delta.zip");
        Path instance = temporary.resolve("capacity-instance");
        SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        Files.writeString(instance.resolve("options.txt"), "player settings");
        byte[] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT));
        AtomicBoolean checked = new AtomicBoolean();
        IOException failure = assertThrows(IOException.class, () -> installer.applyQuick(
                List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("capacity-cache"), complete -> {
                    checked.set(true);
                    assertEquals("0.8.1", complete.manifest().version());
                    assertTrue(complete.manifest().files().stream().anyMatch(file -> file.path().equals("config/new.txt")));
                    throw new IOException("capacity fixture refusal");
                }));
        assertEquals("capacity fixture refusal", failure.getMessage()); assertTrue(checked.get());
        assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT)));
        assertEquals("old", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals("player settings", Files.readString(instance.resolve("options.txt")));
        assertFalse(Files.exists(instance.resolve("config/new.txt")));
    }

    /// Both source formats yield only added/changed payloads; unchanged player seeds remain inventory-only.
    @Test void builderSupportsBothFormatsAndOmitsUnchangedPlayerSeeds() throws Exception {
        for (boolean modrinth : List.of(false, true)) {
            String suffix = modrinth ? "mr" : "sao";
            Path old = pack("0.8", "old-" + suffix + ".zip", Map.of("mods/a.jar", "old", "config/remove.txt", "remove", "options.txt", "seed"), modrinth);
            Path next = pack("0.8.1", "new-" + suffix + ".zip", Map.of("mods/a.jar", "new", "config/add.txt", "add", "options.txt", "seed"), modrinth);
            String oldHash = SaoUpdates.sha256(old); String newHash = SaoUpdates.sha256(next);
            SaoData.QuickRelease edge = delta(old, next, "delta-" + suffix + ".zip");
            SaoQuickUpdate update = SaoQuickUpdate.open(temporary.resolve(edge.file()));
            assertEquals(List.of("config/add.txt"), update.added()); assertEquals(List.of("mods/a.jar"), update.changed());
            assertEquals(List.of("config/remove.txt"), update.removed()); assertEquals(3, update.expected().size());
            try (ZipFile zip = new ZipFile(temporary.resolve(edge.file()).toFile())) {
                assertNull(zip.getEntry("overrides/options.txt")); assertNotNull(zip.getEntry("overrides/mods/a.jar")); assertEquals(3, zip.size());
            }
            assertEquals(oldHash, SaoUpdates.sha256(old)); assertEquals(newHash, SaoUpdates.sha256(next));
        }
    }

    /// Drops accumulate by cryptographic identity, including reversed order, duplicates and renamed input files.
    @Test void dropSessionAcceptsTogetherOneAtATimeAndRejectsWrongFiles() throws Exception {
        Path old = pack("0.8", "old.zip", Map.of("mods/a.jar", "old"), false);
        Path middle = pack("0.8.1", "middle.zip", Map.of("mods/a.jar", "mid"), false);
        Path next = pack("0.8.2", "next.zip", Map.of("mods/a.jar", "new"), false);
        var first = delta(old, middle, "first.zip"); var second = delta(middle, next, "second.zip");
        Path wrong = temporary.resolve("wrong.zip"); Files.writeString(wrong, "wrong");
        Path renamed = temporary.resolve("unrelated-name.zip"); Files.copy(temporary.resolve(second.file()), renamed);
        SaoData.VersionMetadata metadata = policy(next, List.of(first, second));
        try (SaoDropSession drops = new SaoDropSession(metadata, "0.8", temporary.resolve("snapshots"))) {
            assertEquals("请把 [0.8.1] 和 [0.8.2] 的快速更新包拖进来", drops.prompt(true, null).message());
            assertFalse(drops.acceptQuick(wrong)); assertTrue(drops.acceptQuick(renamed)); assertTrue(drops.ready().isEmpty());
            assertEquals(List.of("0.8.2"), drops.prompt(true, null).present()); assertEquals(List.of("0.8.1"), drops.prompt(true, null).missing());
            assertTrue(drops.acceptQuick(temporary.resolve(first.file()))); assertTrue(drops.acceptQuick(renamed)); assertEquals(2, drops.ready().size());
            assertEquals("0.8", SaoQuickUpdate.open(drops.ready().get(0)).from());
        }
        try (SaoDropSession drops = new SaoDropSession(metadata, "0.8", temporary.resolve("together"))) {
            for (Path file : List.of(renamed, wrong, temporary.resolve(first.file()))) drops.acceptQuick(file);
            assertEquals(2, drops.ready().size());
        }
        assertTrue(Files.exists(renamed));
    }

    /// A two-hop composition commits once, preserves actual player data and retains pristine repair seeds.
    @Test void applyChainPreservesPlayersAndCachesCompleteRepairSource() throws Exception {
        Path old = pack("0.8", "old.zip", Map.of("mods/a.jar", "old", "config/remove.txt", "remove", "options.txt", "seed"), false);
        Path middle = pack("0.8.1", "middle.zip", Map.of("mods/a.jar", "mid", "options.txt", "seed"), false);
        Path next = pack("0.8.2", "next.zip", Map.of("mods/a.jar", "new", "config/added.txt", "added", "options.txt", "new-seed"), false);
        var first = delta(old, middle, "first.zip"); var second = delta(middle, next, "second.zip");
        Path instance = temporary.resolve("instance"); SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        Files.writeString(instance.resolve("options.txt"), "player options");
        Files.createDirectories(instance.resolve("saves/world")); Files.writeString(instance.resolve("saves/world/level.dat"), "save");
        Files.createDirectories(instance.resolve("screenshots")); Files.writeString(instance.resolve("screenshots/a.png"), "picture");
        Files.writeString(instance.resolve("my-own.txt"), "unmanaged");
        SaoPackArchive retained = installer.applyQuick(List.of(temporary.resolve(first.file()), temporary.resolve(second.file())), SaoPackArchive.open(old), temporary.resolve("cache"));
        assertEquals("0.8.2", Objects.requireNonNull(installer.installed()).version());
        assertEquals("new", Files.readString(instance.resolve("mods/a.jar"))); assertFalse(Files.exists(instance.resolve("config/remove.txt")));
        assertEquals("added", Files.readString(instance.resolve("config/added.txt"))); assertEquals("player options", Files.readString(instance.resolve("options.txt")));
        assertEquals("save", Files.readString(instance.resolve("saves/world/level.dat"))); assertEquals("picture", Files.readString(instance.resolve("screenshots/a.png")));
        assertEquals("unmanaged", Files.readString(instance.resolve("my-own.txt")));
        Files.delete(old); Files.delete(middle); Files.delete(next); Files.delete(temporary.resolve(first.file())); Files.delete(temporary.resolve(second.file()));
        Files.writeString(instance.resolve("mods/a.jar"), "broken"); Files.delete(instance.resolve("options.txt"));
        installer.apply(retained, true);
        assertEquals("new", Files.readString(instance.resolve("mods/a.jar"))); assertEquals("new-seed", Files.readString(instance.resolve("options.txt")));
    }

    /// Corruption in the second hop is detected before the first managed mutation or receipt write.
    @Test void secondHopFailureLeavesWholePreUpdateState() throws Exception {
        Path old = pack("0.8", "old.zip", Map.of("mods/a.jar", "old", "options.txt", "seed"), false);
        Path middle = pack("0.8.1", "middle.zip", Map.of("mods/a.jar", "mid", "options.txt", "seed"), false);
        Path next = pack("0.8.2", "next.zip", Map.of("mods/a.jar", "new", "options.txt", "seed"), false);
        var first = delta(old, middle, "first.zip"); var second = delta(middle, next, "second.zip");
        Path corrupt = temporary.resolve("corrupt.zip");
        try (ZipFile input = new ZipFile(temporary.resolve(second.file()).toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals("overrides/mods/a.jar")) output.write("bad".getBytes(StandardCharsets.UTF_8));
                else try (InputStream stream = input.getInputStream(entry)) { stream.transferTo(output); }
                output.closeEntry();
            }
        }
        Path instance = temporary.resolve("instance"); SaoPackInstaller installer = installer(instance); installer.apply(SaoPackArchive.open(old), false);
        String receipt = Files.readString(instance.resolve(SaoPackInstaller.RECEIPT));
        assertThrows(IOException.class, () -> installer.applyQuick(List.of(temporary.resolve(first.file()), corrupt), SaoPackArchive.open(old), temporary.resolve("cache")));
        assertEquals("old", Files.readString(instance.resolve("mods/a.jar"))); assertEquals("seed", Files.readString(instance.resolve("options.txt")));
        assertEquals(receipt, Files.readString(instance.resolve(SaoPackInstaller.RECEIPT)));
    }

    /// A failing commit callback exercises real rollback after the first replacement, including managed removals.
    @Test void midCommitFailureRestoresFilesAndReceipt() throws Exception {
        Path old = pack("0.8", "old.zip", Map.of("mods/a.jar", "old", "config/remove.txt", "remove"), false);
        Path next = pack("0.8.1", "next.zip", Map.of("mods/a.jar", "new", "config/added.txt", "added"), false);
        var edge = delta(old, next, "delta.zip"); Path instance = temporary.resolve("instance"); installer(instance).apply(SaoPackArchive.open(old), false);
        String receipt = Files.readString(instance.resolve(SaoPackInstaller.RECEIPT)); AtomicBoolean once = new AtomicBoolean();
        SaoPackInstaller failing = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Network forbidden"); }, Map.of(),
                (label, progress) -> { if (label.equals("应用整合包") && once.compareAndSet(false, true)) throw new IllegalStateException("fixture commit failure"); });
        assertThrows(IllegalStateException.class, () -> failing.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("cache")));
        assertEquals(receipt, Files.readString(instance.resolve(SaoPackInstaller.RECEIPT)));
        assertEquals("old", Files.readString(instance.resolve("mods/a.jar"))); assertEquals("remove", Files.readString(instance.resolve("config/remove.txt")));
        assertFalse(Files.exists(instance.resolve("config/added.txt")));
    }

    /// Base edits trigger the service's full-pack fallback while leaving all modified and player files untouched.
    @Test void serviceModifiedBaseFallsBackToFullPack() throws Exception {
        Path old = pack("0.8", "old.zip", Map.of("mods/a.jar", "old", "options.txt", "seed"), false);
        Path next = pack("0.8.1", "next.zip", Map.of("mods/a.jar", "new", "options.txt", "seed"), false);
        var edge = delta(old, next, "delta.zip"); Path root = temporary.resolve("launcher");
        Path instance;
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, SaoPackArchive.open(old), false)) { await(() -> engine.state().get() != SaoPackState.CHECKING); instance = engine.instanceDir(); }
        installer(instance).apply(SaoPackArchive.open(old), false); Files.writeString(instance.resolve("mods/a.jar"), "edited");
        SaoReleaseTestSupport.cacheVersion(root, new Gson().toJson(policy(next, List.of(edge))));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, SaoPackArchive.open(old), false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            assertThrows(ExecutionException.class, () -> engine.acceptDroppedFiles(List.of(temporary.resolve(edge.file()))).get(10, TimeUnit.SECONDS));
            assertTrue(engine.downloadPrompt().get().fullPackRequired());
            assertEquals("请把在夸克里下载的模组文件拖进来", engine.downloadPrompt().get().message());
            assertEquals("edited", Files.readString(instance.resolve("mods/a.jar"))); assertEquals("0.8", Objects.requireNonNull(installer(instance).installed()).version());
        }
    }

    /// The production service processes sequential reversed drops and retains its composed source across restart.
    @Test void serviceDropsAreAccumulatedAndRepairSourceSurvivesRestart() throws Exception {
        Path old = pack("0.8", "old.zip", Map.of("mods/a.jar", "old", "options.txt", "seed"), false);
        Path middle = pack("0.8.1", "middle.zip", Map.of("mods/a.jar", "mid", "options.txt", "seed"), false);
        Path next = pack("0.8.2", "next.zip", Map.of("mods/a.jar", "new", "options.txt", "seed"), false);
        var first = delta(old, middle, "first.zip"); var second = delta(middle, next, "second.zip");
        Path root = temporary.resolve("launcher"); Path instance;
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, SaoPackArchive.open(old), false)) { await(() -> engine.state().get() != SaoPackState.CHECKING); instance = engine.instanceDir(); }
        installer(instance).apply(SaoPackArchive.open(old), false);
        SaoReleaseTestSupport.cacheVersion(root, new Gson().toJson(policy(next, List.of(first, second))));
        Path wrong = temporary.resolve("wrong.zip"); Files.writeString(wrong, "wrong");
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, SaoPackArchive.open(old), false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.acceptDroppedFiles(List.of(temporary.resolve(second.file()))).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("0.8.2"), engine.downloadPrompt().get().present());
            engine.acceptDroppedFiles(List.of(wrong, temporary.resolve(first.file()))).get(10, TimeUnit.SECONDS);
            assertEquals("0.8.2", engine.installedVersion().get()); assertTrue(Objects.requireNonNull(engine.lastError().get()).contains(SaoUpdates.INVALID_ARCHIVE));
        }
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            assertEquals("0.8.2", engine.requireSource().manifest().version()); assertEquals(SaoPackState.READY, engine.state().get());
            Files.writeString(instance.resolve("mods/a.jar"), "broken"); installer(instance).apply(engine.requireSource(), true);
            assertEquals("new", Files.readString(instance.resolve("mods/a.jar")));
            Files.delete(engine.requireSource().zip());
            assertEquals("请把在夸克里下载的模组文件拖进来", assertThrows(IOException.class, engine::requireSource).getMessage());
        }
    }

    /// Waits only for background filesystem initialization; no JavaFX toolkit or game is launched.
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!condition.getAsBoolean()) Thread.sleep(5); });
    }
}
