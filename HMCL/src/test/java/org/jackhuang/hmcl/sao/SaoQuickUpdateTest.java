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
import java.util.concurrent.atomic.AtomicInteger;
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

    /// Normal game rewrites and absent unchanged settings do not block a quick update or lose player choices.
    @Test void playedSettingsArePreservedAcrossQuickUpdate() throws Exception {
        @Unmodifiable Map<String, String> settings = Map.ofEntries(Map.entry("mods/a.jar", "old"), Map.entry("config/forge-client.toml", "seed"),
                Map.entry("config/jei/jei-client.ini", "seed"), Map.entry("config/ftbxmodcompat.snbt", "seed"),
                Map.entry("config/imblocker.json", "seed"), Map.entry("config/packetfixer.properties", "seed"),
                Map.entry("config/graveyard/the-graveyard-3.0-config.json5", "seed"), Map.entry("tacz/.export-state.json", "seed"),
                Map.entry("tacz/tacz-pre.toml", "seed"), Map.entry("config/artifacts/client.json5", "seed"),
                Map.entry("config/badoptimizations.txt", "seed"), Map.entry("config/fancymenu/options.txt", "seed"),
                Map.entry("defaultconfigs/ftbquests/client-config.snbt", "seed"), Map.entry("kubejs/config/client.properties", "seed"));
        Path old = pack("0.8", "played-old.zip", settings, false);
        Map<String, String> nextSettings = new TreeMap<>(settings); nextSettings.put("mods/a.jar", "new");
        Path next = pack("0.8.1", "played-next.zip", nextSettings, false);
        var edge = delta(old, next, "played-delta.zip");
        Path instance = temporary.resolve("played-instance"); SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        for (String path : settings.keySet()) if (!path.endsWith(".jar")) Files.writeString(instance.resolve(path), "game wrote " + path);
        Files.delete(instance.resolve("config/jei/jei-client.ini"));
        installer.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("played-cache"));
        assertEquals("new", Files.readString(instance.resolve("mods/a.jar")));
        for (String path : settings.keySet()) if (!path.endsWith(".jar") && !path.equals("config/jei/jei-client.ini"))
            assertEquals("game wrote " + path, Files.readString(instance.resolve(path)));
        assertFalse(Files.exists(instance.resolve("config/jei/jei-client.ini")));
        assertEquals("0.8.1", Objects.requireNonNull(installer.installed()).version());
    }

    /// Replaced and removed settings use author intent and retain the exact game-written bytes after successful commit.
    @Test void changedAndRemovedSettingsKeepDurableTransactionBackups() throws Exception {
        Path old = pack("0.8", "settings-old.zip", Map.of("mods/a.jar", "jar", "config/change.toml", "seed", "config/remove.toml", "seed"), false);
        Path next = pack("0.8.1", "settings-next.zip", Map.of("mods/a.jar", "jar", "config/change.toml", "author"), false);
        var edge = delta(old, next, "settings-delta.zip");
        Path instance = temporary.resolve("settings-instance"); SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        Files.writeString(instance.resolve("config/change.toml"), "played change");
        Files.writeString(instance.resolve("config/remove.toml"), "played remove");
        installer.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("settings-cache"));
        assertEquals("author", Files.readString(instance.resolve("config/change.toml")));
        assertFalse(Files.exists(instance.resolve("config/remove.toml")));
        try (var transactions = Files.list(instance.resolve(".sao-config-backups"))) {
            @Unmodifiable List<Path> backups = transactions.toList(); assertEquals(1, backups.size());
            assertEquals("played change", Files.readString(backups.get(0).resolve("config/change.toml")));
            assertEquals("played remove", Files.readString(backups.get(0).resolve("config/remove.toml")));
        }
    }

    /// Every hop validates canonical settings while the final commit honors author changes that return to the original seed.
    @Test void twoHopsRetainUntouchedSettingsAndApplyRevertedAuthorSettings() throws Exception {
        Path old = pack("0.8", "revert-old.zip", Map.of("mods/a.jar", "old", "config/change.toml", "seed", "config/keep.toml", "seed", "config/readd.toml", "seed"), false);
        Path middle = pack("0.8.1", "revert-middle.zip", Map.of("mods/a.jar", "mid", "config/change.toml", "middle", "config/keep.toml", "seed"), false);
        Path next = pack("0.8.2", "revert-next.zip", Map.of("mods/a.jar", "new", "config/change.toml", "seed", "config/keep.toml", "seed", "config/readd.toml", "seed"), false);
        var first = delta(old, middle, "revert-first.zip"); var second = delta(middle, next, "revert-second.zip");
        Path instance = temporary.resolve("revert-instance"); SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        Files.writeString(instance.resolve("config/change.toml"), "played change"); Files.writeString(instance.resolve("config/keep.toml"), "played keep");
        Files.writeString(instance.resolve("config/readd.toml"), "played readded setting");
        installer.applyQuick(List.of(temporary.resolve(first.file()), temporary.resolve(second.file())), SaoPackArchive.open(old), temporary.resolve("revert-cache"));
        assertEquals("seed", Files.readString(instance.resolve("config/change.toml")));
        assertEquals("seed", Files.readString(instance.resolve("config/readd.toml")));
        assertEquals("played keep", Files.readString(instance.resolve("config/keep.toml")));
        assertEquals("new", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals("0.8.2", Objects.requireNonNull(installer.installed()).version());
    }

    /// A mod-generated setting newly managed in either hop is replaced by author content and backed up durably.
    @Test void newlyManagedSettingsMayAlreadyExistAfterPlay() throws Exception {
        Path old = pack("0.8", "generated-old.zip", Map.of("mods/a.jar", "old"), false);
        Path middle = pack("0.8.1", "generated-middle.zip", Map.of("mods/a.jar", "mid", "config/new.toml", "first author"), false);
        Path next = pack("0.8.2", "generated-next.zip", Map.of("mods/a.jar", "new", "config/new.toml", "first author", "kubejs/config/new.properties", "second author"), false);
        var first = delta(old, middle, "generated-first.zip"); var second = delta(middle, next, "generated-second.zip");
        Path instance = temporary.resolve("generated-instance"); SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        Files.createDirectories(instance.resolve("config")); Files.writeString(instance.resolve("config/new.toml"), "game first");
        Files.createDirectories(instance.resolve("kubejs/config")); Files.writeString(instance.resolve("kubejs/config/new.properties"), "game second");
        installer.applyQuick(List.of(temporary.resolve(first.file()), temporary.resolve(second.file())), SaoPackArchive.open(old), temporary.resolve("generated-cache"));
        assertEquals("first author", Files.readString(instance.resolve("config/new.toml")));
        assertEquals("second author", Files.readString(instance.resolve("kubejs/config/new.properties")));
        try (var transactions = Files.list(instance.resolve(".sao-config-backups"))) {
            @Unmodifiable List<Path> backups = transactions.toList(); assertEquals(1, backups.size());
            assertEquals("game first", Files.readString(backups.get(0).resolve("config/new.toml")));
            assertEquals("game second", Files.readString(backups.get(0).resolve("kubejs/config/new.properties")));
        }
    }

    /// Matching external shader seeds can seed the verified chain locally; edited seeds without embedded originals refuse safely.
    @Test void externalPlayerSeedsUseMatchingInstalledBytesWithoutDownloads() throws Exception {
        Path source = pack("0.8", "external-source.zip", Map.of("mods/a.jar", "old", "shaderpacks/external.zip", "shader seed"), false);
        SaoPackManifest original = SaoPackArchive.open(source).manifest();
        List<SaoPackManifest.PackFile> files = new ArrayList<>();
        String shaderHash = "";
        for (var file : original.files()) {
            if (file.path().equals("shaderpacks/external.zip")) {
                shaderHash = file.sha1();
                files.add(new SaoPackManifest.PackFile(file.path(), file.sha1(), file.size(), null, List.of("https://example.invalid/shader.zip")));
            } else files.add(file);
        }
        Path old = temporary.resolve("external-old.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(old))) {
            member(output, SaoPackManifest.NAME, SaoQuickUpdate.relabel(original, "0.8", files).toJson());
            member(output, "overrides/mods/a.jar", "old");
        }
        Path shader = temporary.resolve("external-shader.zip"); Files.writeString(shader, "shader seed");
        @Unmodifiable Map<String, Path> baseline = Map.of(shaderHash, shader);
        Path next = pack("0.8.1", "external-next.zip", Map.of("mods/a.jar", "new", "shaderpacks/external.zip", "shader seed"), false);
        Path delta = temporary.resolve("external-delta.zip"); SaoUpdateBuilder.build(old, next, delta, baseline, null, null);
        for (boolean edited : List.of(false, true)) {
            Path instance = temporary.resolve("external-instance-" + edited);
            SaoPackInstaller installer = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Network forbidden"); }, baseline, (label, progress) -> { });
            installer.apply(SaoPackArchive.open(old), false);
            if (edited) Files.writeString(instance.resolve("shaderpacks/external.zip"), "player shader");
            byte[] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT));
            if (edited) {
                IOException failure = assertThrows(IOException.class, () -> installer.applyQuick(List.of(delta), SaoPackArchive.open(old), temporary.resolve("external-cache-true")));
                assertEquals(SaoQuickUpdate.FULL_REQUIRED, failure.getMessage());
                assertEquals("player shader", Files.readString(instance.resolve("shaderpacks/external.zip")));
                assertEquals("old", Files.readString(instance.resolve("mods/a.jar")));
                assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT)));
            } else {
                installer.applyQuick(List.of(delta), SaoPackArchive.open(old), temporary.resolve("external-cache-false"));
                assertEquals("shader seed", Files.readString(instance.resolve("shaderpacks/external.zip")));
                assertEquals("new", Files.readString(instance.resolve("mods/a.jar")));
                assertEquals("0.8.1", Objects.requireNonNull(installer.installed()).version());
            }
        }
    }

    /// Configuration-root JARs and authored data/scripts remain strict even when settings are otherwise mutable.
    @Test void executableAndAuthoredDataChangesRequireFullPack() throws Exception {
        for (String path : List.of("mods/keybind-helper.jar", "config/keybind-helper.jar", "kubejs/server_scripts/main.js",
                "kubejs/data/test/recipes/example.json", "kubejs/assets/test/models/example.json", "kubejs/config/sao_balance.json",
                "config/ftbquests/quests/data.snbt", "config/roadweaver/presets/test.json", "config/.entrance-render-defaults")) {
            String id = Integer.toString(path.hashCode());
            Path old = pack("0.8", "strict-old-" + id + ".zip", Map.of(path, "old"), false);
            Path next = pack("0.8.1", "strict-next-" + id + ".zip", Map.of(path, "author"), false);
            var edge = delta(old, next, "strict-delta-" + id + ".zip");
            Path instance = temporary.resolve("strict-" + id); SaoPackInstaller installer = installer(instance);
            installer.apply(SaoPackArchive.open(old), false); Files.writeString(instance.resolve(path), "edited");
            byte[] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT));
            IOException failure = assertThrows(IOException.class, () -> installer.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("strict-cache-" + id)), path);
            assertEquals(SaoQuickUpdate.FULL_REQUIRED, failure.getMessage(), path);
            assertEquals("edited", Files.readString(instance.resolve(path)), path);
            assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT)), path);
        }
    }

    /// Strict original data must match even when the author leaves it unchanged or removes it in the new release.
    @Test void unchangedAndRemovedAuthoredDataStillRequireOriginalBase() throws Exception {
        for (boolean removed : List.of(false, true)) {
            Path old = pack("0.8", "data-old-" + removed + ".zip", Map.of("kubejs/data/test/recipes/example.json", "original"), false);
            Map<String, String> nextFiles = new TreeMap<>(); nextFiles.put("config/new.toml", "new");
            if (!removed) nextFiles.put("kubejs/data/test/recipes/example.json", "original");
            Path next = pack("0.8.1", "data-next-" + removed + ".zip", nextFiles, false);
            var edge = delta(old, next, "data-delta-" + removed + ".zip");
            Path instance = temporary.resolve("data-instance-" + removed); SaoPackInstaller installer = installer(instance);
            installer.apply(SaoPackArchive.open(old), false);
            Files.writeString(instance.resolve("kubejs/data/test/recipes/example.json"), "edited original data");
            IOException failure = assertThrows(IOException.class, () -> installer.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("data-cache-" + removed)));
            assertEquals(SaoQuickUpdate.FULL_REQUIRED, failure.getMessage());
            assertEquals("edited original data", Files.readString(instance.resolve("kubejs/data/test/recipes/example.json")));
            assertFalse(Files.exists(instance.resolve("config/new.toml")));
            assertEquals("0.8", Objects.requireNonNull(installer.installed()).version());
        }
    }

    /// If durable backup storage is unavailable after mutation, the entire transaction rolls back before the receipt changes.
    @Test void backupStorageFailureRollsBackReplacedAndRemovedSettings() throws Exception {
        Path old = pack("0.8", "backup-old.zip", Map.of("mods/a.jar", "old", "config/change.toml", "seed", "config/remove.toml", "seed"), false);
        Path next = pack("0.8.1", "backup-next.zip", Map.of("mods/a.jar", "new", "config/change.toml", "author"), false);
        var edge = delta(old, next, "backup-delta.zip");
        Path instance = temporary.resolve("backup-instance"); SaoPackInstaller installer = installer(instance);
        installer.apply(SaoPackArchive.open(old), false);
        Files.writeString(instance.resolve("config/change.toml"), "played change"); Files.writeString(instance.resolve("config/remove.toml"), "played remove");
        Files.writeString(instance.resolve(".sao-config-backups"), "occupied backup location");
        byte[] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT));
        assertThrows(IOException.class, () -> installer.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("backup-cache")));
        assertEquals("old", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals("played change", Files.readString(instance.resolve("config/change.toml")));
        assertEquals("played remove", Files.readString(instance.resolve("config/remove.toml")));
        assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT)));
        assertEquals("occupied backup location", Files.readString(instance.resolve(".sao-config-backups")));
    }

    /// A failing mutation restores rewritten, removed and newly added settings, code, player data and receipt together.
    @Test void rollbackRestoresPlayedSettingsAndAllOtherState() throws Exception {
        Path old = pack("0.8", "rollback-old.zip", Map.of("mods/a.jar", "old", "config/change.toml", "seed", "config/remove.toml", "seed", "options.txt", "seed"), false);
        Path next = pack("0.8.1", "rollback-next.zip", Map.of("mods/a.jar", "new", "config/change.toml", "author", "config/add.toml", "added", "options.txt", "author"), false);
        var edge = delta(old, next, "rollback-delta.zip");
        Path instance = temporary.resolve("rollback-instance"); installer(instance).apply(SaoPackArchive.open(old), false);
        Files.writeString(instance.resolve("config/change.toml"), "played change"); Files.writeString(instance.resolve("config/remove.toml"), "played remove");
        Files.writeString(instance.resolve("options.txt"), "player");
        byte[] receipt = Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT));
        AtomicInteger mutations = new AtomicInteger();
        SaoPackInstaller failing = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Network forbidden"); }, Map.of(),
                (label, progress) -> { if (label.equals("应用整合包") && mutations.incrementAndGet() == 4) throw new IllegalStateException("rollback fixture"); });
        assertThrows(IllegalStateException.class, () -> failing.applyQuick(List.of(temporary.resolve(edge.file())), SaoPackArchive.open(old), temporary.resolve("rollback-cache")));
        assertEquals(4, mutations.get());
        assertEquals("old", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals("played change", Files.readString(instance.resolve("config/change.toml")));
        assertEquals("played remove", Files.readString(instance.resolve("config/remove.toml")));
        assertEquals("player", Files.readString(instance.resolve("options.txt")));
        assertFalse(Files.exists(instance.resolve("config/add.toml")));
        assertArrayEquals(receipt, Files.readAllBytes(instance.resolve(SaoPackInstaller.RECEIPT)));
        assertFalse(Files.exists(instance.resolve(".sao-config-backups")));
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
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(old), false)) { await(() -> engine.state().get() != SaoPackState.CHECKING); instance = engine.instanceDir(); }
        installer(instance).apply(SaoPackArchive.open(old), false); Files.writeString(instance.resolve("mods/a.jar"), "edited");
        Files.writeString(root.resolve("online-version.json"), new Gson().toJson(policy(next, List.of(edge))));
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(old), false)) {
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
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(old), false)) { await(() -> engine.state().get() != SaoPackState.CHECKING); instance = engine.instanceDir(); }
        installer(instance).apply(SaoPackArchive.open(old), false);
        Files.writeString(root.resolve("online-version.json"), new Gson().toJson(policy(next, List.of(first, second))));
        Path wrong = temporary.resolve("wrong.zip"); Files.writeString(wrong, "wrong");
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(old), false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.acceptDroppedFiles(List.of(temporary.resolve(second.file()))).get(10, TimeUnit.SECONDS);
            assertEquals(List.of("0.8.2"), engine.downloadPrompt().get().present());
            engine.acceptDroppedFiles(List.of(wrong, temporary.resolve(first.file()))).get(10, TimeUnit.SECONDS);
            assertEquals("0.8.2", engine.installedVersion().get()); assertTrue(Objects.requireNonNull(engine.lastError().get()).contains(SaoUpdates.INVALID_ARCHIVE));
        }
        try (SaoPackEngine engine = new SaoPackEngine(root, null, false)) {
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
