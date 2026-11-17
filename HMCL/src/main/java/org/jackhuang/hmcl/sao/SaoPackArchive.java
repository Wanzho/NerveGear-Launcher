/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Opens a read-only pack zip and its trusted, validated inventory.
///
/// @param zip source archive, never modified
/// @param manifest validated inventory used for every extraction and download
@NotNullByDefault
public record SaoPackArchive(Path zip, SaoPackManifest manifest) {
    private static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_PACK_BYTES = 8L * 1024 * 1024 * 1024;

    /// Opens an update manifest, or derives the complete inventory of an original Modrinth pack.
    public static SaoPackArchive open(Path zip) throws IOException {
        return open(zip, false);
    }

    /// Verifies the embedded bytes of a locally imported full pack before it can become a repair source.
    static SaoPackArchive openForImport(Path zip) throws IOException {
        return open(zip, true);
    }

    /// Explicit inventories are validated against their payload when the input is a local import.
    private static SaoPackArchive open(Path zip, boolean verifyPayload) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            if (archive.size() > MAX_ENTRIES) throw new IOException("Pack contains too many archive members");
            @Nullable ZipEntry explicit = archive.getEntry(SaoPackManifest.NAME);
            if (explicit != null) {
                SaoPackManifest manifest = SaoPackManifest.read(readIndex(archive, explicit));
                checkInventorySize(manifest.files());
                if (verifyPayload) verifyPayload(archive, manifest);
                return new SaoPackArchive(zip, manifest);
            }
            return new SaoPackArchive(zip, deriveManifest(archive));
        }
    }

    /// Checks embedded entries even when the current installation would skip copying unchanged files.
    private static void verifyPayload(ZipFile archive, SaoPackManifest manifest) throws IOException {
        for (var file : manifest.files()) {
            @Nullable String member = file.entry();
            if (member == null) continue;
            @Nullable ZipEntry entry = archive.getEntry(member);
            if (entry == null || entry.isDirectory() || entry.getSize() != file.size())
                throw new IOException("Pack member missing or wrong size: " + file.path());
            try (var input = archive.getInputStream(entry)) {
                if (!file.sha1().equals(sha1(input, file.size())))
                    throw new IOException("Pack checksum mismatch: " + file.path());
            }
            if (file.sha512() != null) {
                try (var input = archive.getInputStream(entry)) {
                    if (!file.sha512().equals(digest(input, file.size(), "SHA-512")))
                        throw new IOException("Pack checksum mismatch: " + file.path());
                }
            }
        }
    }

    /// Validates both declared and actual index size before handing the text to the parser.
    private static Reader readIndex(ZipFile archive, ZipEntry entry) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > MAX_INDEX_BYTES)
            throw new IOException("Pack index exceeds size limit");
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length != entry.getSize()) throw new IOException("Invalid pack index size");
            return new StringReader(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static void checkInventorySize(Collection<SaoPackManifest.PackFile> files) throws IOException {
        long size = 0;
        for (var file : files) size += file.size();
        if (size > MAX_PACK_BYTES) throw new IOException("Pack content exceeds size limit");
    }

    /// Uses a preprocessed embedded inventory and materializes its archive under the launcher's own cache.
    /// Returns null for development builds that deliberately have no embedded pack.
    public static @Nullable SaoPackArchive embedded(Path cache) throws IOException {
        @Nullable InputStream source = SaoPackArchive.class.getResourceAsStream("/assets/sao/" + SaoPackManifest.NAME);
        if (source == null) return null;
        SaoPackManifest manifest;
        try (Reader reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            manifest = SaoPackManifest.read(reader);
        }
        String key = sha1(new ByteArrayInputStream(manifest.toJson().getBytes(StandardCharsets.UTF_8)));
        SaoPackFiles.resolve(cache, "cache-path-check");
        Files.createDirectories(cache);
        Path zip = cache.resolve(key + ".zip");
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
            @Nullable InputStream payload = SaoPackArchive.class.getResourceAsStream("/assets/sao/pack.zip");
            if (payload == null) throw new IOException("Embedded SAO pack payload is missing");
            Path temporary = Files.createTempFile(cache, "pack-", ".tmp");
            try (InputStream in = payload) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                SaoPackFiles.atomicMove(temporary, zip);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return new SaoPackArchive(zip, manifest);
    }

    /// Derives hashes of all overrides and retains exact upstream SHA-1/size for external dependencies.
    private static SaoPackManifest deriveManifest(ZipFile archive) throws IOException {
        @Nullable ZipEntry indexEntry = archive.getEntry("modrinth.index.json");
        if (indexEntry == null) throw new IOException("Pack requires sao-manifest.json or modrinth.index.json");
        JsonObject index;
        try (Reader reader = readIndex(archive, indexEntry)) {
            index = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Modrinth index", e);
        }
        try {
            JsonObject dependencies = index.getAsJsonObject("dependencies");
            String minecraft = dependencies.get("minecraft").getAsString();
            String neoforge = dependencies.get("neoforge").getAsString();
            if (dependencies.has("forge") || !SaoPackManifest.MINECRAFT.equals(minecraft)
                    || !SaoPackManifest.NEOFORGE.equals(neoforge))
                throw new IOException("Pack requires exactly Minecraft 1.20.1 + NeoForge 47.1.106");
            Map<String, SaoPackManifest.PackFile> files = new TreeMap<>();
            if (index.getAsJsonArray("files").size() > MAX_ENTRIES)
                throw new IOException("Pack lists too many dependencies");
            for (JsonElement element : index.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                // Modrinth marks some files server-only; this launcher builds a client instance.
                if (file.has("env") && file.getAsJsonObject("env").has("client")
                        && "unsupported".equals(file.getAsJsonObject("env").get("client").getAsString())) continue;
                String path = file.get("path").getAsString();
                List<String> downloads = new ArrayList<>();
                for (JsonElement url : file.getAsJsonArray("downloads")) downloads.add(url.getAsString());
                if (files.put(path, new SaoPackManifest.PackFile(path, file.getAsJsonObject("hashes").get("sha1").getAsString(),
                        file.get("fileSize").getAsLong(), null, downloads,
                        file.getAsJsonObject("hashes").has("sha512") ? file.getAsJsonObject("hashes").get("sha512").getAsString() : null)) != null)
                    throw new IOException("Duplicate dependency path: " + path);
            }
            Set<String> entries = new HashSet<>();
            long expanded = 0;
            Enumeration<? extends ZipEntry> enumeration = archive.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entries.add(entry.getName())) throw new IOException("Duplicate archive member: " + entry.getName());
                if (entry.isDirectory() || !entry.getName().startsWith("overrides/")) continue;
                String path = entry.getName().substring("overrides/".length());
                if (entry.getSize() < 0 || entry.getSize() > SaoPackManifest.MAX_FILE_SIZE)
                    throw new IOException("Invalid archive member size: " + path);
                expanded += entry.getSize();
                if (expanded > MAX_PACK_BYTES) throw new IOException("Pack content exceeds size limit");
                String hash;
                try (InputStream input = archive.getInputStream(entry)) { hash = sha1(input, entry.getSize()); }
                // Overrides intentionally take precedence over Modrinth dependency entries.
                files.put(path, new SaoPackManifest.PackFile(path, hash, entry.getSize(), entry.getName(), List.of()));
            }
            String name = index.get("name").getAsString();
            java.util.regex.Matcher namedVersion = java.util.regex.Pattern.compile("(?i)OATH\\s*(\\d+(?:\\.\\d+)+)").matcher(name);
            String version = namedVersion.find() ? namedVersion.group(1) : index.get("versionId").getAsString();
            checkInventorySize(files.values());
            return new SaoPackManifest(version, minecraft, neoforge, "", List.copyOf(files.values()), List.of());
        } catch (RuntimeException e) {
            throw new IOException("Invalid pack metadata: " + e.getMessage(), e);
        }
    }

    /// Computes a streaming SHA-1 without retaining file payloads in memory; caller closes the stream.
    public static String sha1(InputStream input) throws IOException {
        return sha1(input, -1);
    }

    private static String sha1(InputStream input, long expectedSize) throws IOException {
        return digest(input, expectedSize, "SHA-1");
    }

    /// Computes an archive member's digest and checks its actual expanded byte count.
    private static String digest(InputStream input, long expectedSize, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536];
            int count;
            long size = 0;
            while ((count = input.read(buffer)) >= 0) {
                size += count;
                if (expectedSize >= 0 && size > expectedSize) throw new IOException("Pack member exceeds its declared size");
                digest.update(buffer, 0, count);
            }
            if (expectedSize >= 0 && size != expectedSize) throw new IOException("Pack member has the wrong size");
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime has no " + algorithm, e);
        }
    }
}
