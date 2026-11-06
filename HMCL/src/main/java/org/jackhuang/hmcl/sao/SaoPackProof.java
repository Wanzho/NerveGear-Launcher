/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/// Performs one real headless install, a complete newer-pack update, and an integrity repair in a fresh scratch root.
/// Never creates accounts, launches a game process or initializes the JavaFX toolkit.
@NotNullByDefault
final class SaoPackProof {
    /// Utility class.
    private SaoPackProof() { }

    /// Installs exact HMCL game/loader dependencies and verifies update/removal/preservation/repair with disk evidence.
    static Map<String, Object> run(Path root, SaoPackArchive source, Map<String, Path> baseline) throws Exception {
        SaoGameBridge game = new SaoGameBridge(root, (label, fraction) -> System.err.println(label));
        SaoPackInstaller installer = new SaoPackInstaller(game.instanceDir(), game::download, baseline, (label, fraction) -> { });
        if (installer.installed() != null) throw new IOException("Proof requires a fresh scratch instance");
        game.ensureInstalled(false);
        SaoPackInstaller.Result install = installer.apply(source, false);
        Path instance = game.instanceDir();
        List<String> playerFiles = List.of("options.txt", "optionsshaders.txt", "config/oculus.properties",
                "config/iris.properties", "config/fzzy_config/keybinds.toml", "saves/launcher-proof/sentinel.bin",
                "screenshots/launcher-proof.bin", "shaderpacks/launcher-proof-selection.txt");
        Map<String, String> playerHashes = new LinkedHashMap<>();
        for (String path : playerFiles) {
            Path target = SaoPackFiles.resolve(instance, path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "\n# SAO launcher preservation proof\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            playerHashes.put(path, hash(target));
        }
        Path unmanaged = instance.resolve("launcher-proof-unmanaged.txt");
        Files.writeString(unmanaged, "unmanaged file must survive explicit removal request", StandardCharsets.UTF_8);
        List<SaoPackManifest.PackFile> candidates = source.manifest().files().stream()
                .filter(file -> file.path().startsWith("config/") && file.path().endsWith(".toml")
                        && file.size() < 1024 * 1024 && !SaoPackManifest.isPlayerFile(file.path())).limit(2).toList();
        if (candidates.size() < 2) throw new IOException("Proof requires two managed TOML defaults");
        SaoPackManifest.PackFile changed = candidates.get(0);
        SaoPackManifest.PackFile removed = candidates.get(1);
        byte[] original = Files.readAllBytes(instance.resolve(changed.path()));
        byte[] suffix = "\n# SAO launcher newer release proof\n".getBytes(StandardCharsets.UTF_8);
        byte[] updated = Arrays.copyOf(original, original.length + suffix.length);
        System.arraycopy(suffix, 0, updated, original.length, suffix.length);
        SaoPackManifest.PackFile replacement = new SaoPackManifest.PackFile(changed.path(),
                SaoPackArchive.sha1(new ByteArrayInputStream(updated)), updated.length, "overrides/" + changed.path(), List.of());
        List<SaoPackManifest.PackFile> inventory = new ArrayList<>();
        for (var file : source.manifest().files()) {
            if (file.path().equals(removed.path())) continue;
            inventory.add(file.path().equals(changed.path()) ? replacement : file);
        }
        SaoPackManifest next = new SaoPackManifest(source.manifest().version() + ".1-proof", "1.20.1", "47.1.106",
                source.manifest().server(), inventory, List.of(removed.path(), unmanaged.getFileName().toString()));
        Path updatePath = root.resolve("proof-update.zip");
        writeUpdate(source, next, replacement, updated, removed, updatePath);
        SaoPackArchive update = SaoPackArchive.open(updatePath);
        SaoPackInstaller.Result applied = installer.apply(update, false);
        if (applied.written() != 1 || applied.removed() != 1) throw new IOException("Incremental update wrote unexpected file counts");
        if (Files.exists(instance.resolve(removed.path()))) throw new IOException("Managed removal was not applied");
        if (!Files.exists(unmanaged)) throw new IOException("Unmanaged file was removed");
        SaoPackManifest.PackFile remote = inventory.stream().filter(file -> file.entry() == null).findFirst()
                .orElseThrow(() -> new IOException("Proof requires one external dependency"));
        Files.writeString(instance.resolve(replacement.path()), "corrupt managed content", StandardCharsets.UTF_8);
        Files.delete(instance.resolve(remote.path()));
        SaoPackInstaller.Result repaired = installer.apply(update, true);
        if (repaired.written() != 2) throw new IOException("Repair did not restore exactly the damaged and missing files");
        Map<String, Boolean> preserved = new LinkedHashMap<>();
        for (var entry : playerHashes.entrySet()) {
            boolean equal = entry.getValue().equals(hash(instance.resolve(entry.getKey())));
            preserved.put(entry.getKey(), equal);
            if (!equal) throw new IOException("Player state was modified: " + entry.getKey());
        }
        // Independent final scan confirms actual saved payloads, not just the installer's counters.
        int verified = 0;
        int playerDefaultsDiffer = 0;
        for (var file : next.files()) {
            boolean matches = SaoPackFiles.matches(SaoPackFiles.resolve(instance, file.path()), file);
            if (matches) verified++;
            else if (SaoPackManifest.isPlayerFile(file.path())) playerDefaultsDiffer++;
            else throw new IOException("Final manifest verification failed: " + file.path());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runtimeInstalled", true);
        report.put("install", install);
        report.put("update", applied);
        report.put("repair", repaired);
        report.put("finalFilesMatchingManifest", verified);
        report.put("preservedPlayerDefaultsDiffering", playerDefaultsDiffer);
        report.put("preservationChecks", preserved);
        report.put("managedRemovalApplied", true);
        report.put("unmanagedFilePreserved", true);
        report.put("syntheticUpdate", true);
        report.put("gameLaunched", false);
        report.put("javaFxStarted", false);
        return report;
    }

    /// Writes a complete newer pack, retaining all unchanged archive payloads for subsequent repair.
    private static void writeUpdate(SaoPackArchive source, SaoPackManifest manifest,
                                    SaoPackManifest.PackFile changed, byte[] payload,
                                    SaoPackManifest.PackFile removed, Path destination) throws IOException {
        try (ZipFile archive = new ZipFile(source.zip().toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
            output.setLevel(1);
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().equals(SaoPackManifest.NAME)
                        || entry.getName().equals(changed.entry()) || entry.getName().equals(removed.entry())) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream input = archive.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry(Objects.requireNonNull(changed.entry()))); output.write(payload); output.closeEntry();
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME));
            output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
    }

    /// Computes independent evidence directly from a final file on disk.
    private static String hash(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) { return SaoPackArchive.sha1(input); }
    }
}
