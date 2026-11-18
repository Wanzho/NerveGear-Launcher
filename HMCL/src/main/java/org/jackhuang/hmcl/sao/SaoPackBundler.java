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
import org.jackhuang.hmcl.download.HMCLDownloadProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/// Repackages explicitly reviewed, unmodified dependencies; never infers redistribution permission.
/// The source archive is read-only, and a failed operation publishes no output archive.
@NotNullByDefault
public final class SaoPackBundler {
    /// Attribution is installed alongside the pack as well as travelling in the distribution ZIP.
    static final String NOTICE_ENTRY = "overrides/SAO-BUNDLED-MODS-NOTICE.txt";

    /// Utility class.
    private SaoPackBundler() { }

    /// Usage: --bundle-allowed INPUT_ZIP ALLOW_LIST_JSON NEW_OUTPUT_ZIP.
    /// The allow list has a files array of path, sha1, verdict and notice; optional evidence is retained externally.
    public static void main(String @Unmodifiable [] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length != 4 || !args[0].equals("--bundle-allowed"))
            throw new IllegalArgumentException("Usage: --bundle-allowed <in.zip> <allow-list.json> <out.zip>");
        try (SaoModDownloads downloads = new SaoModDownloads(new HMCLDownloadProvider("https://bmclapi2.bangbang93.com"),
                snapshot -> System.err.println(snapshot.label() + " " + snapshot.bytesDone() + "/" + snapshot.totalBytes()),
                transfer -> { }, Set.of("cdn.modrinth.com", "mediafilez.forgecdn.net", "edge.forgecdn.net"))) {
            Result result = bundle(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), downloads);
            System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        }
    }

    /// Public evidence contains identities only; the actual payload is always obtained through the verified downloader.
    @NotNullByDefault
    record Result(long originalBytes, long outputBytes, int remainingExternalFiles,
                  @Unmodifiable List<String> bundledPaths, @Unmodifiable List<String> extraPaths) {
        /// Prevents callers from modifying the result after publication.
        Result { bundledPaths = List.copyOf(bundledPaths); extraPaths = List.copyOf(extraPaths); }
    }

    /// An explicitly approved source archive or licence text accompanying a redistributed binary.
    @NotNullByDefault
    private record Extra(String path, Path localPath, String sha256) { }

    /// Validates every approval before downloading; preserves all other ZIP member payloads and JSON fields.
    static Result bundle(Path source, Path allowList, Path output, SaoPackInstaller.Downloader downloader) throws Exception {
        Path original = source.toRealPath();
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || target.equals(original))
            throw new IOException("Output must be a new file, distinct from the source ZIP");
        Path parent = Objects.requireNonNull(target.getParent());
        Files.createDirectories(parent);
        if (Files.getFileStore(parent).getUsableSpace() < 10_000_000_000L) throw new IOException("Less than 10 GB free");
        // Reuse production validation for archive paths, duplicates, index checksums and managed-file conflicts.
        SaoPackArchive validated = SaoPackArchive.open(original);
        JsonObject approvals;
        try (Reader reader = Files.newBufferedReader(allowList, StandardCharsets.UTF_8)) {
            approvals = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Path staging = Files.createTempDirectory(parent, ".sao-bundle-");
        Path temporary = Files.createTempFile(parent, ".sao-bundle-", ".part");
        try (ZipFile archive = new ZipFile(original.toFile(), StandardCharsets.UTF_8)) {
            @Nullable ZipEntry indexEntry = archive.getEntry("modrinth.index.json");
            if (indexEntry == null || archive.getEntry(SaoPackManifest.NAME) != null)
                throw new IOException("Expected a Modrinth index without a competing SAO manifest");
            if (archive.getEntry(NOTICE_ENTRY) != null) throw new IOException("Bundled NOTICE already exists");
            JsonObject index;
            try (Reader reader = new InputStreamReader(archive.getInputStream(indexEntry), StandardCharsets.UTF_8)) {
                index = JsonParser.parseReader(reader).getAsJsonObject();
            }
            Map<String, JsonObject> indexed = new LinkedHashMap<>();
            for (JsonElement element : index.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                if (indexed.put(file.get("path").getAsString(), file) != null) throw new IOException("Duplicate index path");
            }
            Map<SaoPackManifest.PackFile, Path> selected = new LinkedHashMap<>();
            Set<String> paths = new LinkedHashSet<>();
            StringBuilder notice = new StringBuilder("Unmodified mod JARs redistributed with this free modpack.\n\n");
            for (JsonElement element : approvals.getAsJsonArray("files")) {
                JsonObject approval = element.getAsJsonObject();
                String path = approval.get("path").getAsString();
                String verdict = approval.get("verdict").getAsString();
                if (!Set.of("allowed", "allowed with attribution").contains(verdict))
                    throw new IOException("No redistribution approval for " + path);
                String text = approval.has("notice") ? approval.get("notice").getAsString() : "";
                if (verdict.equals("allowed with attribution") && text.isBlank())
                    throw new IOException("Missing required attribution for " + path);
                @Nullable JsonObject entry = indexed.get(path);
                if (!path.startsWith("mods/") || !path.endsWith(".jar") || entry == null || !paths.add(path)
                        || archive.getEntry("overrides/" + path) != null)
                    throw new IOException("Not a unique external mod: " + path);
                SaoPackManifest.PackFile file = validated.manifest().files().stream()
                        .filter(item -> item.path().equals(path) && item.entry() == null).findFirst()
                        .orElseThrow(() -> new IOException("Not an external dependency: " + path));
                if (!file.sha1().equals(approval.get("sha1").getAsString())) throw new IOException("Approval SHA-1 mismatch: " + path);
                selected.put(file, SaoPackFiles.resolve(staging, path));
                notice.append(path).append("\nSHA-1: ").append(file.sha1()).append('\n');
                for (String field : List.of("projectUrl", "license"))
                    if (approval.has(field)) notice.append(field).append(": ").append(approval.get(field).getAsString()).append('\n');
                notice.append(text).append("\n\n");
            }
            if (selected.isEmpty()) throw new IOException("Allow list is empty");
            List<Extra> extras = new ArrayList<>();
            Set<String> extraNames = new HashSet<>();
            if (approvals.has("extraFiles")) for (JsonElement element : approvals.getAsJsonArray("extraFiles")) {
                    JsonObject extra = element.getAsJsonObject();
                    String path = extra.get("path").getAsString();
                    SaoPackManifest.validatePath(path);
                    if (!path.startsWith("overrides/licenses/") || !extraNames.add(path) || archive.getEntry(path) != null)
                        throw new IOException("Source/licence artifact must be a new member under overrides/licenses/: " + path);
                    Path local = Path.of(extra.get("localPath").getAsString()).toRealPath();
                    String hash = extra.get("sha256").getAsString();
                    if (!hash.matches("[0-9a-f]{64}") || !Files.isRegularFile(local) || Files.size(local) > SaoPackManifest.MAX_FILE_SIZE
                        || !SaoUpdates.sha256(local).equals(hash)) throw new IOException("Source/licence artifact identity mismatch: " + path);
                    extras.add(new Extra(path, local, hash));
                }
            for (Path destination : selected.values()) Files.createDirectories(Objects.requireNonNull(destination.getParent()));
            downloader.downloadAll(selected);
            for (var item : selected.entrySet())
                if (!SaoPackFiles.matches(item.getValue(), item.getKey())) throw new IOException("Bundle identity mismatch: " + item.getKey().path());
            JsonArray remaining = new JsonArray();
            for (JsonElement element : index.getAsJsonArray("files"))
                if (!paths.contains(element.getAsJsonObject().get("path").getAsString())) remaining.add(element);
            index.add("files", remaining);
            try (ZipOutputStream zipped = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                @Nullable String archiveComment = archive.getComment();
                if (archiveComment != null) zipped.setComment(archiveComment);
                Enumeration<? extends ZipEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copied = new ZipEntry(entry);
                    if (entry.getName().equals("modrinth.index.json")) {
                        copied = new ZipEntry(entry.getName()); copied.setTime(entry.getTime());
                        copied.setComment(entry.getComment()); copied.setExtra(entry.getExtra());
                        zipped.putNextEntry(copied);
                        zipped.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(index).getBytes(StandardCharsets.UTF_8));
                    } else {
                        copied.setCompressedSize(-1); zipped.putNextEntry(copied);
                        try (InputStream input = archive.getInputStream(entry)) { input.transferTo(zipped); }
                    }
                    zipped.closeEntry();
                }
                for (var item : selected.entrySet()) {
                    zipped.putNextEntry(new ZipEntry("overrides/" + item.getKey().path()));
                    Files.copy(item.getValue(), zipped); zipped.closeEntry();
                }
                for (Extra extra : extras) {
                    zipped.putNextEntry(new ZipEntry(extra.path())); Files.copy(extra.localPath(), zipped); zipped.closeEntry();
                }
                zipped.putNextEntry(new ZipEntry(NOTICE_ENTRY));
                zipped.write(notice.toString().getBytes(StandardCharsets.UTF_8)); zipped.closeEntry();
            }
            // Re-read our saved archive through the installer parser before making the new file visible.
            SaoPackArchive.open(temporary);
            try (ZipFile saved = new ZipFile(temporary.toFile(), StandardCharsets.UTF_8)) {
                for (Extra extra : extras) {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (InputStream input = saved.getInputStream(saved.getEntry(extra.path()))) {
                        byte[] buffer = new byte[65536];
                        for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
                    }
                    if (!HexFormat.of().formatHex(digest.digest()).equals(extra.sha256()))
                        throw new IOException("Saved source/licence artifact identity mismatch: " + extra.path());
                }
            }
            Files.move(temporary, target);
            return new Result(Files.size(original), Files.size(target), remaining.size(), List.copyOf(paths), extras.stream().map(Extra::path).toList());
        } finally {
            Files.deleteIfExists(temporary);
            try (var children = Files.walk(staging)) {
                for (Path path : children.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
