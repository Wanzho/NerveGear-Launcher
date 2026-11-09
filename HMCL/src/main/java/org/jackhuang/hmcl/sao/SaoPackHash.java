/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/// Headless team utility reached before launcher storage, logging, AWT or JavaFX initialization.
@NotNullByDefault
public final class SaoPackHash {
    /// Utility class.
    private SaoPackHash() { }

    /// Prints a complete pasteable pack block; an optional explicit version supports otherwise unlabelled ZIPs.
    /// Arguments are --pack-hash ZIP [--pack-version NUMERIC_VERSION].
    public static void main(String[] args) throws IOException {
        if ((args.length != 2 && args.length != 4) || !args[0].equals("--pack-hash")
                || args.length == 4 && !args[2].equals("--pack-version"))
            throw new IOException("用法：--pack-hash <zip> [--pack-version <version>]");
        Path zip = Path.of(args[1]);
        String version = args.length == 4 ? SaoUpdates.version(args[3]) : archiveVersion(zip);
        System.out.println(block(zip, version));
    }

    /// Hashes only the supplied regular file and returns a schema-compatible pack object wrapper.
    static String block(Path zip, String version) throws IOException {
        String filename = SaoUpdates.filename(zip.getFileName().toString());
        java.nio.file.attribute.BasicFileAttributes before = Files.readAttributes(zip, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String hash = SaoUpdates.sha256(zip);
        java.nio.file.attribute.BasicFileAttributes after = Files.readAttributes(zip, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("文件仍在下载或发生变化，请下载完成后重试");
        long size = after.size();
        if (size <= 0) throw new IOException("整合包文件为空");
        JsonObject pack = new JsonObject();
        pack.addProperty("version", SaoUpdates.version(version));
        pack.addProperty("file", filename); pack.addProperty("size", size); pack.addProperty("sha256", hash);
        JsonObject result = new JsonObject(); result.add("pack", pack);
        return new GsonBuilder().setPrettyPrinting().create().toJson(result);
    }

    /// Reads only the bounded version manifest; never inventories, extracts, installs or resolves dependencies.
    private static String archiveVersion(Path zip) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            @Nullable ZipEntry entry = archive.getEntry("sao-manifest.json");
            boolean sao = entry != null;
            if (entry == null) entry = archive.getEntry("modrinth.index.json");
            if (entry == null || entry.getSize() > 1048576) throw new IOException("无法识别整合包版本，请加 --pack-version <version>");
            try (InputStream input = archive.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(1048577);
                if (bytes.length > 1048576) throw new IOException("整合包版本信息过大");
                JsonObject metadata = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (sao) return SaoUpdates.version(metadata.get("version").getAsString());
                String name = metadata.has("name") ? metadata.get("name").getAsString() : "";
                var namedVersion = java.util.regex.Pattern.compile("(?i)OATH\\s*(\\d+(?:\\.\\d+)+)").matcher(name);
                return SaoUpdates.version(namedVersion.find() ? namedVersion.group(1) : metadata.get("versionId").getAsString());
            }
        } catch (RuntimeException failure) { throw new IOException("无法识别整合包版本，请加 --pack-version <version>", failure); }
    }
}
