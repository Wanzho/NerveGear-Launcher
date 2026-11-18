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

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/// Headless, local-only quick-update builder. Source archives and optional baseline files are never modified.
@NotNullByDefault
public final class SaoUpdateBuilder {
    /// Utility class.
    private SaoUpdateBuilder() { }

    /// Builds --make-update OLD.zip NEW.zip OUTPUT.zip with optional --baseline RECEIPT and explicit version labels.
    /// Overrides label only the generated quick update; they do not change source archives or prove a historical release.
    public static void main(String[] args) throws IOException {
        if (args.length < 4 || !args[0].equals("--make-update") || (args.length - 4) % 2 != 0)
            throw new IOException("用法：--make-update <old.zip> <new.zip> <output.zip> [--baseline <receipt.json>] [--from-version <version>] [--to-version <version>]");
        @Nullable Path baseline = null; @Nullable String from = null; @Nullable String to = null;
        Set<String> options = new HashSet<>();
        for (int index = 4; index < args.length; index += 2) {
            if (!options.add(args[index])) throw new IOException("Duplicate builder option");
            switch (args[index]) {
                case "--baseline" -> baseline = Path.of(args[index + 1]);
                case "--from-version" -> from = SaoUpdates.version(args[index + 1]);
                case "--to-version" -> to = SaoUpdates.version(args[index + 1]);
                default -> throw new IOException("Unknown update builder option: " + args[index]);
            }
        }
        SaoData.QuickRelease result = build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                baseline == null ? Map.of() : SaoPackInstaller.readBaseline(baseline), from, to);
        JsonObject entry = new JsonObject(); entry.addProperty("from", result.from()); entry.addProperty("to", result.to());
        entry.addProperty("file", result.file()); entry.addProperty("size", result.size()); entry.addProperty("sha256", result.sha256());
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(entry));
    }

    /// Validates both pack inventories, resolves every SHA-256 from verified local bytes, and atomically saves a delta.
    /// Fails if external dependency bytes are unavailable; it never accesses dependency URLs.
    public static SaoData.QuickRelease build(Path oldZip, Path newZip, Path output, Map<String, Path> baseline,
                                             @Nullable String fromOverride, @Nullable String toOverride) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Update output already exists; choose a new path");
        if (target.equals(oldZip.toAbsolutePath().normalize()) || target.equals(newZip.toAbsolutePath().normalize()))
            throw new IOException("Update output must not replace a source pack");
        String filename = SaoUpdates.filename(target.getFileName().toString());
        BasicFileAttributes oldBefore = attributes(oldZip); BasicFileAttributes newBefore = attributes(newZip);
        SaoPackArchive oldArchive = SaoPackArchive.open(oldZip); SaoPackArchive newArchive = SaoPackArchive.open(newZip);
        String from = SaoUpdates.version(fromOverride == null ? oldArchive.manifest().version() : fromOverride);
        String to = SaoUpdates.version(toOverride == null ? newArchive.manifest().version() : toOverride);
        if (SaoUpdates.compare(from, to) >= 0) throw new IOException("Quick update requires a newer target version; explicit labels may be supplied for local builder validation");
        SaoPackManifest before = SaoQuickUpdate.relabel(oldArchive.manifest(), from, oldArchive.manifest().files());
        // Canonical entries allow the installer to cache a complete reconstructable source after a quick chain.
        List<SaoPackManifest.PackFile> targetFiles = new ArrayList<>();
        for (var file : newArchive.manifest().files()) targetFiles.add(new SaoPackManifest.PackFile(file.path(), file.sha1(), file.size(), "overrides/" + file.path(), List.of()));
        SaoPackManifest after = SaoQuickUpdate.relabel(newArchive.manifest(), to, targetFiles);
        Map<String, String> base = new TreeMap<>(); Map<String, String> expected = new TreeMap<>();
        try (ZipFile oldSource = new ZipFile(oldZip.toFile(), StandardCharsets.UTF_8);
             ZipFile newSource = new ZipFile(newZip.toFile(), StandardCharsets.UTF_8)) {
            rejectDuplicateEntries(oldSource); rejectDuplicateEntries(newSource);
            for (var file : oldArchive.manifest().files()) base.put(file.path(), hash(file, oldSource, baseline));
            for (var file : newArchive.manifest().files()) expected.put(file.path(), hash(file, newSource, baseline));
            @Unmodifiable Map<String, SaoPackManifest.PackFile> oldFiles = SaoQuickUpdate.inventory(before);
            @Unmodifiable Map<String, SaoPackManifest.PackFile> newFiles = SaoQuickUpdate.inventory(after);
            List<String> added = new ArrayList<>(); List<String> changed = new ArrayList<>(); List<String> removed = new ArrayList<>();
            for (var file : after.files()) {
                @Nullable SaoPackManifest.PackFile old = oldFiles.get(file.path());
                if (old == null) added.add(file.path());
                else if (old.size() != file.size() || !old.sha1().equals(file.sha1()) || !base.get(file.path()).equals(expected.get(file.path()))) changed.add(file.path());
            }
            // Only the previous managed inventory can authorize a removal; player paths are retained by apply.
            for (String path : oldFiles.keySet()) if (!newFiles.containsKey(path)) removed.add(path);
            SaoQuickUpdate update = new SaoQuickUpdate(from, to, before, after, base, expected, added, changed, removed);
            Path parent = Objects.requireNonNull(target.getParent()); Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".sao-update-", ".tmp");
            boolean committed = false;
            try {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    write(zip, SaoQuickUpdate.NAME, new ByteArrayInputStream(update.toJson().getBytes(StandardCharsets.UTF_8)), -1);
                    Set<String> payloads = new TreeSet<>(added); payloads.addAll(changed);
                    @Unmodifiable Map<String, SaoPackManifest.PackFile> originalFiles = SaoQuickUpdate.inventory(newArchive.manifest());
                    for (String path : payloads) {
                        SaoPackManifest.PackFile file = originalFiles.get(path);
                        try (InputStream input = payload(file, newSource, baseline)) { write(zip, "overrides/" + path, input, file.size()); }
                    }
                }
                // Detect source mutations throughout inventory and payload streaming, including changes after the first hash pass.
                unchanged(oldZip, oldBefore); unchanged(newZip, newBefore);
                verifyPayloads(temporary, update);
                Files.move(temporary, target); committed = true;
            } finally { if (!committed) Files.deleteIfExists(temporary); }
        }
        return new SaoData.QuickRelease(from, to, filename, Files.size(target), SaoUpdates.sha256(target));
    }

    /// Rejects ambiguous duplicate members before archive.getEntry could select an arbitrary copy.
    private static void rejectDuplicateEntries(ZipFile zip) throws IOException {
        Set<String> names = new HashSet<>(); Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) if (!names.add(entries.nextElement().getName())) throw new IOException("Duplicate pack ZIP member");
    }

    /// Captures source identity without following symlinks.
    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Pack source must be a regular file");
        return attributes;
    }

    /// Refuses changing source archives instead of publishing an inconsistent release identity.
    private static void unchanged(Path path, BasicFileAttributes before) throws IOException {
        BasicFileAttributes after = attributes(path);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("Source pack changed while building update");
    }

    /// Opens a bounded embedded member or a verified local content-addressed external dependency.
    private static InputStream payload(SaoPackManifest.PackFile file, ZipFile zip, Map<String, Path> baseline) throws IOException {
        if (file.entry() != null) {
            @Nullable ZipEntry entry = zip.getEntry(file.entry());
            if (entry == null || entry.isDirectory() || entry.getSize() != file.size()) throw new IOException("Missing or wrong-sized pack member: " + file.path());
            return zip.getInputStream(entry);
        }
        @Nullable Path source = baseline.get(file.sha1());
        if (source == null || !SaoPackFiles.matches(source, file)) throw new IOException("Missing verified local dependency bytes for " + file.path() + "; supply --baseline <receipt.json>");
        return Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
    }

    /// Verifies the source SHA-1 and size while deriving the required SHA-256 of every managed file.
    private static String hash(SaoPackManifest.PackFile file, ZipFile zip, Map<String, Path> baseline) throws IOException {
        try (InputStream input = payload(file, zip, baseline)) {
            return hash(input, file);
        }
    }

    /// Derives both hashes in one bounded pass and refuses malformed base or target content.
    private static String hash(InputStream input, SaoPackManifest.PackFile file) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; long count = 0; int read;
            while ((read = input.read(buffer)) != -1) {
                count += read; if (count > file.size()) throw new IOException("Pack member exceeds declared size: " + file.path());
                sha1.update(buffer, 0, read); sha256.update(buffer, 0, read);
            }
            if (count != file.size() || !HexFormat.of().formatHex(sha1.digest()).equals(file.sha1())) throw new IOException("Pack checksum mismatch: " + file.path());
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /// Writes deterministic timestamps and bounded member content without holding payload bytes in memory.
    private static void write(ZipOutputStream zip, String name, InputStream input, long size) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry);
        if (size < 0) input.transferTo(zip); else SaoQuickUpdate.copy(input, zip, size);
        zip.closeEntry();
    }

    /// Reopens the generated delta and verifies every physical payload against its final SHA-1 and SHA-256 identities.
    private static void verifyPayloads(Path path, SaoQuickUpdate update) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            @Unmodifiable Map<String, SaoPackManifest.PackFile> files = SaoQuickUpdate.inventory(update.after());
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); if (!entry.getName().startsWith("overrides/")) continue;
                String destination = entry.getName().substring("overrides/".length());
                @Nullable SaoPackManifest.PackFile file = files.get(destination);
                if (file == null) throw new IOException("Unexpected update payload");
                try (InputStream input = zip.getInputStream(entry)) {
                    if (!hash(input, file).equals(update.expected().get(destination))) throw new IOException("Update payload changed while building");
                }
            }
        }
    }
}
