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
    /// Opens an update manifest, or derives the complete inventory of an original Modrinth pack.
    public static SaoPackArchive open(Path zip) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            @Nullable ZipEntry explicit = archive.getEntry(SaoPackManifest.NAME);
            if (explicit != null) {
                try (Reader reader = new InputStreamReader(archive.getInputStream(explicit), StandardCharsets.UTF_8)) {
                    return new SaoPackArchive(zip, SaoPackManifest.read(reader));
                }
            }
            return new SaoPackArchive(zip, deriveManifest(archive));
        }
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
        try (Reader reader = new InputStreamReader(archive.getInputStream(indexEntry), StandardCharsets.UTF_8)) {
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
            for (JsonElement element : index.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                // Modrinth marks some files server-only; this launcher builds a client instance.
                if (file.has("env") && file.getAsJsonObject("env").has("client")
                        && "unsupported".equals(file.getAsJsonObject("env").get("client").getAsString())) continue;
                String path = file.get("path").getAsString();
                List<String> downloads = new ArrayList<>();
                for (JsonElement url : file.getAsJsonArray("downloads")) downloads.add(url.getAsString());
                if (files.put(path, new SaoPackManifest.PackFile(path, file.getAsJsonObject("hashes").get("sha1").getAsString(),
                        file.get("fileSize").getAsLong(), null, downloads)) != null)
                    throw new IOException("Duplicate dependency path: " + path);
            }
            Set<String> entries = new HashSet<>();
            Enumeration<? extends ZipEntry> enumeration = archive.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entries.add(entry.getName())) throw new IOException("Duplicate archive member: " + entry.getName());
                if (entry.isDirectory() || !entry.getName().startsWith("overrides/")) continue;
                String path = entry.getName().substring("overrides/".length());
                if (entry.getSize() < 0 || entry.getSize() > SaoPackManifest.MAX_FILE_SIZE)
                    throw new IOException("Invalid archive member size: " + path);
                String hash;
                try (InputStream input = archive.getInputStream(entry)) { hash = sha1(input); }
                // Overrides intentionally take precedence over Modrinth dependency entries.
                files.put(path, new SaoPackManifest.PackFile(path, hash, entry.getSize(), entry.getName(), List.of()));
            }
            String name = index.get("name").getAsString();
            java.util.regex.Matcher namedVersion = java.util.regex.Pattern.compile("(?i)OATH\\s*(\\d+(?:\\.\\d+)+)").matcher(name);
            String version = namedVersion.find() ? namedVersion.group(1) : index.get("versionId").getAsString();
            return new SaoPackManifest(version, minecraft, neoforge, "", List.copyOf(files.values()), List.of());
        } catch (RuntimeException e) {
            throw new IOException("Invalid pack metadata: " + e.getMessage(), e);
        }
    }

    /// Computes a streaming SHA-1 without retaining file payloads in memory; caller closes the stream.
    public static String sha1(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime has no SHA-1", e);
        }
    }
}
