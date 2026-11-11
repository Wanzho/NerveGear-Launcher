/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Headless integration coverage of staged installation, incremental updates, player preservation and repair.
@NotNullByDefault
class SaoPackInstallerTest {
    /// Isolated disposable filesystem, independent of the real pack and launcher profile.
    @TempDir Path temporary;

    /// Installs archive and external members, updates only changed content and repairs managed corruption.
    @Test void installUpdateRepairPreservesPlayerState() throws Exception {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("mods/a.jar", "a-v1");
        original.put("mods/obsolete.jar", "old");
        original.put("config/managed.toml", "managed-v1");
        for (String path : playerPaths()) original.put(path, "pack-default");
        Path instance = temporary.resolve("instance");
        AtomicInteger downloads = new AtomicInteger();
        SaoPackInstaller installer = installer(instance, downloads);
        SaoPackArchive pack = fixture("0.8", original, List.of(), true);
        SaoPackInstaller.Result first = installer.apply(pack, false);
        assertEquals(original.size() + 1, first.written());
        assertEquals(1, downloads.get());
        assertEquals("remote-payload", Files.readString(instance.resolve("mods/remote.jar")));
        for (String path : playerPaths()) Files.writeString(instance.resolve(path), "player-owned:" + path);
        Path unchanged = instance.resolve("config/managed.toml");
        FileTime timestamp = FileTime.fromMillis(123456000L);
        Files.setLastModifiedTime(unchanged, timestamp);
        Files.writeString(instance.resolve("unmanaged.txt"), "keep-me");
        Map<String, String> next = new LinkedHashMap<>(original);
        next.put("mods/a.jar", "a-v2");
        next.remove("mods/obsolete.jar");
        next.put("mods/new.jar", "new");
        for (String path : playerPaths()) next.put(path, "new-default");
        SaoPackArchive update = fixture("0.9", next, List.of("mods/obsolete.jar", "unmanaged.txt"), true);
        SaoPackInstaller.Result second = installer.apply(update, false);
        assertEquals(2, second.written());
        assertEquals(1, second.removed());
        assertEquals(timestamp, Files.getLastModifiedTime(unchanged));
        assertFalse(Files.exists(instance.resolve("mods/obsolete.jar")));
        assertEquals("keep-me", Files.readString(instance.resolve("unmanaged.txt")));
        assertEquals(1, downloads.get(), "Unchanged remote files must not be downloaded again");
        Files.writeString(instance.resolve("mods/a.jar"), "damage");
        Files.delete(instance.resolve("mods/new.jar"));
        SaoPackInstaller.Result repaired = installer.apply(update, true);
        assertEquals(2, repaired.written());
        assertEquals("a-v2", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals("new", Files.readString(instance.resolve("mods/new.jar")));
        for (String path : playerPaths()) assertEquals("player-owned:" + path, Files.readString(instance.resolve(path)));
        assertEquals(playerPaths().size(), repaired.preserved());
        assertEquals("0.9", Objects.requireNonNull(installer.installed()).version());
    }

    /// A damaged external dependency aborts staging before any installed file or receipt is changed.
    @Test void checksumFailureLeavesInstalledInventoryIntact() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("mods/a.jar", "old"), List.of(), false), false);
        SaoPackInstaller broken = new SaoPackInstaller(instance, (file, path) -> Files.writeString(path, "wrong"), Map.of(), (label, progress) -> { });
        assertThrows(IOException.class, () -> broken.apply(fixture("0.9", Map.of("mods/a.jar", "new"), List.of(), true), false));
        assertEquals("old", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals("0.8", Objects.requireNonNull(installer.installed()).version());
    }

    /// A player-linked shaderpacks/ folder is left alone instead of failing the install.
    @Test void linkedPlayerFolderDoesNotBlockInstall() throws Exception {
        Path instance = temporary.resolve("instance");
        Path shared = Files.createDirectories(temporary.resolve("shared-shaders"));
        Files.createDirectories(instance);
        Files.createSymbolicLink(instance.resolve("shaderpacks"), shared);
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        SaoPackInstaller.Result result = installer.apply(fixture("0.8", Map.of("mods/a.jar", "a", "shaderpacks/MakeUp.zip", "shader"), List.of(), false), false);
        assertEquals("a", Files.readString(instance.resolve("mods/a.jar")));
        assertEquals(1, result.preserved());
        assertFalse(Files.exists(shared.resolve("MakeUp.zip")));
    }

        /// A receipt from another loader version keeps its inventory; a damaged receipt reads as not installed.
    @Test void unreadableReceiptDoesNotLockOutRepair() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("mods/a.jar", "a"), List.of(), false), false);
        Path receipt = instance.resolve(SaoPackInstaller.RECEIPT);
        Files.writeString(receipt, Files.readString(receipt).replace(SaoPackManifest.NEOFORGE, "47.9.9"));
        assertEquals(List.of("mods/a.jar"), Objects.requireNonNull(installer.installed()).files().stream().map(SaoPackManifest.PackFile::path).toList());
        Files.writeString(receipt, "{broken");
        assertNull(installer.installed());
        installer.apply(fixture("0.8", Map.of("mods/a.jar", "a"), List.of(), false), true);
        assertEquals("0.8", Objects.requireNonNull(installer.installed()).version());
    }

        /// A commit cut off by a crash is rolled back on the next start; a commit whose receipt landed is kept.
    @Test void interruptedCommitIsRolledBack() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("mods/x-1.0.jar", "old"), List.of(), false), false);
        Path stage = Files.createDirectories(instance.resolve(".sao-stage-crashed"));
        Files.createDirectories(stage.resolve("backup/mods"));
        Files.move(instance.resolve("mods/x-1.0.jar"), stage.resolve("backup/mods/x-1.0.jar"));
        Files.writeString(instance.resolve("mods/x-1.1.jar"), "new");
        Files.writeString(stage.resolve(SaoPackInstaller.JOURNAL),
                "{\"manifest\":\"0000000000000000000000000000000000000000\",\"paths\":[\"mods/x-1.0.jar\",\"mods/x-1.1.jar\"],\"changed\":[\"mods/x-1.1.jar\"]}");
        installer.recover();
        assertEquals("old", Files.readString(instance.resolve("mods/x-1.0.jar")));
        assertFalse(Files.exists(instance.resolve("mods/x-1.1.jar")));
        assertFalse(Files.exists(stage));
        Path finished = Files.createDirectories(instance.resolve(".sao-stage-finished"));
        String receipt;
        try (InputStream input = Files.newInputStream(instance.resolve(SaoPackInstaller.RECEIPT))) { receipt = SaoPackArchive.sha1(input); }
        Files.writeString(finished.resolve(SaoPackInstaller.JOURNAL), "{\"manifest\":\"" + receipt + "\",\"previous\":\"1111111111111111111111111111111111111111\",\"paths\":[\"mods/x-1.0.jar\"],\"changed\":[\"mods/x-1.0.jar\"]}");
        installer.recover();
        assertEquals("old", Files.readString(instance.resolve("mods/x-1.0.jar")));
        assertFalse(Files.exists(finished));
        // An interrupted Repair (same manifest before and after) is undone, and a replaced file is restored, not deleted.
        Path repair = Files.createDirectories(instance.resolve(".sao-stage-repair/backup/mods")).getParent().getParent();
        Files.move(instance.resolve("mods/x-1.0.jar"), repair.resolve("backup/mods/x-1.0.jar"));
        Files.writeString(instance.resolve("mods/x-1.0.jar"), "repaired");
        Files.writeString(repair.resolve(SaoPackInstaller.JOURNAL), "{\"manifest\":\"" + receipt + "\",\"previous\":\"" + receipt + "\",\"paths\":[\"mods/x-1.0.jar\"],\"changed\":[]}");
        installer.recover();
        assertEquals("old", Files.readString(instance.resolve("mods/x-1.0.jar")));
        assertFalse(Files.exists(repair));
        // A torn journal never blocks startup.
        Path torn = Files.createDirectories(instance.resolve(".sao-stage-torn"));
        Files.writeString(torn.resolve(SaoPackInstaller.JOURNAL), "{\"manifest\":\"00");
        installer.recover();
        assertFalse(Files.exists(torn));
        assertEquals("old", Files.readString(instance.resolve("mods/x-1.0.jar")));
    }

    /// A completed same-version repair leaves no journal, so a later recovery keeps the repaired files.
    @Test void completedRepairLeavesNoJournal() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        SaoPackArchive pack = fixture("0.8", Map.of("mods/x-1.0.jar", "old"), List.of(), false);
        installer.apply(pack, false);
        Files.writeString(instance.resolve("mods/x-1.0.jar"), "broken");
        installer.apply(pack, true);
        installer.recover();
        assertEquals("old", Files.readString(instance.resolve("mods/x-1.0.jar")));
    }

        /// A dropped full pack keeps player-edited settings it does not change and backs up the ones it replaces.
    @Test void fullPackUpdateKeepsPlayerSettings() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("config/a.toml", "default-a", "config/b.toml", "default-b"), List.of(), false), false);
        Files.writeString(instance.resolve("config/a.toml"), "player-a");
        Files.writeString(instance.resolve("config/b.toml"), "player-b");
        installer.applyUpdate(fixture("0.9", Map.of("config/a.toml", "default-a", "config/b.toml", "new-b"), List.of(), false));
        assertEquals("player-a", Files.readString(instance.resolve("config/a.toml")));
        assertEquals("new-b", Files.readString(instance.resolve("config/b.toml")));
        try (var backups = Files.walk(instance.resolve(".sao-config-backups"))) {
            assertTrue(backups.anyMatch(path -> path.endsWith("config/b.toml") && readQuietly(path).equals("player-b")));
        }
    }

    /// Reads a small fixture file inside a stream predicate.
    private static String readQuietly(Path path) {
        try { return Files.readString(path); } catch (IOException failure) { return ""; }
    }

        /// A release that only changes a file name's case keeps exactly one copy, on case-insensitive filesystems too.
    @Test void caseOnlyRenameKeepsTheMod() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("mods/Foo.jar", "foo-bytes"), List.of(), false), false);
        installer.apply(fixture("0.9", Map.of("mods/foo.jar", "foo-bytes"), List.of(), false), false);
        assertEquals("foo-bytes", Files.readString(instance.resolve("mods/foo.jar")));
        try (var listing = Files.list(instance.resolve("mods"))) { assertEquals(1, listing.count()); }
    }

        /// A failed download batch keeps its verified files; the retry downloads only what is still missing.
    @Test void failedBatchKeepsVerifiedDownloadsForRetry() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackManifest.PackFile good = remote("mods/good.jar", "good-payload"), bad = remote("mods/bad.jar", "bad-payload");
        SaoPackArchive pack = archive("0.8", List.of(good, bad));
        Map<String, String> payloads = Map.of(good.path(), "good-payload", bad.path(), "bad-payload");
        List<String> fetched = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger badFailures = new AtomicInteger(1);
        SaoPackInstaller installer = new SaoPackInstaller(instance, (file, path) -> {
            fetched.add(file.path());
            if (file.path().equals(bad.path()) && badFailures.getAndDecrement() > 0) throw new IOException("network dropped");
            Files.writeString(path, payloads.get(file.path()));
        }, Map.of(), (label, progress) -> { });
        assertThrows(IOException.class, () -> installer.apply(pack, false));
        assertEquals(Set.of(good.path(), bad.path()), new HashSet<>(fetched));
        fetched.clear();
        installer.apply(pack, false);
        assertEquals(List.of(bad.path()), fetched);
        assertEquals("good-payload", Files.readString(instance.resolve(good.path())));
        assertFalse(Files.exists(instance.resolve(SaoPackInstaller.DOWNLOAD_CACHE)));
    }

        /// A filesystem failure after the first replacement restores the preceding file and original receipt.
    @Test void commitFailureRollsBackEarlierWrites() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("a.txt", "old-a", "z.txt", "old-z"), List.of(), false), false);
        Files.delete(instance.resolve("z.txt"));
        Files.createDirectory(instance.resolve("z.txt"));
        assertThrows(IOException.class, () -> installer.apply(fixture("0.9", Map.of("a.txt", "new-a", "z.txt", "new-z"), List.of(), false), false));
        assertEquals("old-a", Files.readString(instance.resolve("a.txt")));
        assertTrue(Files.isDirectory(instance.resolve("z.txt")));
        assertEquals("0.8", Objects.requireNonNull(installer.installed()).version());
    }

    /// Repair must refuse an older release, including after an engine restart with an older embedded pack.
    @Test void repairNeverDowngradesNewerInstalledRelease() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoPackInstaller installer = installer(instance, new AtomicInteger());
        installer.apply(fixture("0.9", Map.of("mods/a.jar", "new"), List.of(), false), false);
        assertThrows(IOException.class, () -> installer.apply(fixture("0.8", Map.of("mods/a.jar", "old"), List.of(), false), true));
        assertEquals("new", Files.readString(instance.resolve("mods/a.jar")));
    }

    /// A symlink in a managed path cannot make extraction write outside the fixed instance.
    @Test void rejectsSymlinkAncestor() throws Exception {
        Path instance = temporary.resolve("instance");
        Path outside = temporary.resolve("outside");
        Files.createDirectories(instance);
        Files.createDirectories(outside);
        Files.createSymbolicLink(instance.resolve("mods"), outside);
        assertThrows(IOException.class, () -> installer(instance, new AtomicInteger()).apply(fixture("0.8", Map.of("mods/a.jar", "x"), List.of(), false), false));
        assertFalse(Files.exists(outside.resolve("a.jar")));
    }

    /// An alias outside the selected root is legitimate, including macOS temporary paths below /var.
    @Test void allowsAncestorAliasesOutsideManagedRoot() throws Exception {
        Path physicalParent = temporary.resolve("physical-parent");
        Files.createDirectory(physicalParent);
        Path alias = temporary.resolve("parent-alias");
        Files.createSymbolicLink(alias, physicalParent);
        Path selectedRoot = alias.resolve("instance");
        SaoPackInstaller installer = installer(selectedRoot, new AtomicInteger());
        installer.apply(fixture("0.8", Map.of("config/file.txt", "safe"), List.of(), false), false);
        assertEquals("safe", Files.readString(physicalParent.resolve("instance/config/file.txt")));
        if (System.getProperty("os.name").startsWith("Mac")) {
            Path physicalTemporary = temporary.toRealPath();
            Path aliasTemporary = physicalTemporary.startsWith("/private/var")
                    ? Path.of(physicalTemporary.toString().substring("/private".length())) : temporary;
            Path macRoot = Files.createTempDirectory(aliasTemporary, "sao-alias-proof-");
            try {
                SaoPackInstaller macInstaller = installer(macRoot, new AtomicInteger());
                macInstaller.apply(fixture("0.8", Map.of("config/file.txt", "mac-safe"), List.of(), false), false);
                assertEquals("mac-safe", Files.readString(macRoot.resolve("config/file.txt")));
            } finally { SaoPackFiles.deleteTree(macRoot); }
        }
    }

    /// Incompatible dependencies, traversal and case-colliding destinations fail validation.
    @Test void rejectsUnsafeOrIncompatibleManifest() {
        assertThrows(IllegalArgumentException.class, () -> new SaoPackManifest("0.8", "1.21", "47.1.106", "", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> file("../escape", "x"));
        assertThrows(IllegalArgumentException.class, () -> file("config/CON.txt", "x"));
        assertThrows(IllegalArgumentException.class, () -> new SaoPackManifest("0.8", "1.20.1", "47.1.106", "",
                List.of(file("mods/A.jar", "a"), file("mods/a.jar", "b")), List.of()));
    }

    /// Builds a deterministic test downloader; remote payloads are never fetched from a real network.
    private SaoPackInstaller installer(Path instance, AtomicInteger downloads) {
        return new SaoPackInstaller(instance, (file, path) -> { downloads.incrementAndGet(); Files.writeString(path, "remote-payload"); }, Map.of(), (label, progress) -> { });
    }

    /// Player state whose content must survive both updates and repair.
    private static @Unmodifiable List<String> playerPaths() {
        return List.of("options.txt", "optionsshaders.txt", "saves/world/level.dat", "screenshots/picture.png",
                "config/oculus.properties", "config/iris.properties", "config/fzzy_config/keybinds.toml", "shaderpacks/custom.zip");
    }

    /// Creates an explicit manifest archive with deterministic local content and an optional external dependency.
    private SaoPackArchive fixture(String version, Map<String, String> content, List<String> removals, boolean remote) throws Exception {
        List<SaoPackManifest.PackFile> files = new ArrayList<>();
        for (var entry : new TreeMap<>(content).entrySet()) files.add(file(entry.getKey(), entry.getValue()));
        if (remote) {
            SaoPackManifest.PackFile local = file("mods/remote.jar", "remote-payload");
            files.add(new SaoPackManifest.PackFile(local.path(), local.sha1(), local.size(), null, List.of("https://example.invalid/remote.jar")));
        }
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", "", files, removals);
        Path zip = Files.createTempFile(temporary, "pack-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME));
            output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            for (var entry : content.entrySet()) {
                output.putNextEntry(new ZipEntry("overrides/" + entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
        return SaoPackArchive.open(zip);
    }

    /// Remote-only fixture identity with no archive member.
    private static SaoPackManifest.PackFile remote(String path, String value) {
        SaoPackManifest.PackFile local = file(path, value);
        return new SaoPackManifest.PackFile(path, local.sha1(), local.size(), null, List.of("https://example.invalid/" + path));
    }

    /// Writes a manifest-only archive for the given files.
    private SaoPackArchive archive(String version, List<SaoPackManifest.PackFile> files) throws Exception {
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", "", files, List.of());
        Path zip = Files.createTempFile(temporary, "pack-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME));
            output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        return SaoPackArchive.open(zip);
    }

        /// Computes one deterministic fixture identity from its UTF-8 payload.
    private static SaoPackManifest.PackFile file(String path, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try { return new SaoPackManifest.PackFile(path, SaoPackArchive.sha1(new ByteArrayInputStream(bytes)), bytes.length, "overrides/" + path, List.of()); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
    }
}
