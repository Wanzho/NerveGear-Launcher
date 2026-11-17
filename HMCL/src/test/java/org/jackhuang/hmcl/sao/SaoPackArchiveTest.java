/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Checks original Modrinth archive normalization independently of preprocessed release manifests.
@NotNullByDefault
class SaoPackArchiveTest {
    /// Disposable original archive fixtures.
    @TempDir Path temporary;

    /// The supplied release's display name wins over its generic 1.0.0 export ID; newer numeric versions stay distinct.
    @Test void parsesOathVersionWithoutCollapsingFutureUpdates() throws Exception {
        assertEquals("0.8", SaoPackArchive.open(archive("SAO OATH0.8", "1.0.0")).manifest().version());
        assertEquals("0.8.1", SaoPackArchive.open(archive("SAO OATH0.8.1", "0.8.1")).manifest().version());
        assertEquals("0.80", SaoPackArchive.open(archive("SAO OATH0.80", "0.80")).manifest().version());
    }

    /// Override bytes are hashed from the original source while remote checksums/sizes and URLs remain exact.
    @Test void inventoriesLocalAndExternalFiles() throws Exception {
        SaoPackManifest manifest = SaoPackArchive.open(archive("SAO OATH0.8", "1.0.0")).manifest();
        assertEquals(2, manifest.files().size());
        assertEquals("", manifest.server());
        SaoPackManifest.PackFile local = manifest.files().stream().filter(file -> file.entry() != null).findFirst().orElseThrow();
        assertEquals("config/example.txt", local.path());
        assertEquals(3, local.size());
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", local.sha1());
        SaoPackManifest.PackFile external = manifest.files().stream().filter(file -> file.entry() == null).findFirst().orElseThrow();
        assertEquals("mods/remote.jar", external.path());
        assertEquals(3, external.size());
        assertEquals("https://example.invalid/remote.jar", external.downloads().get(0));
    }

    /// Oversized metadata is refused before parsing, including when the compressed file is small.
    @Test void rejectsOversizedIndex() throws Exception {
        Path zip = temporary.resolve("oversized.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("modrinth.index.json"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 33; i++) output.write(block);
        }
        var failure = assertThrows(java.io.IOException.class, () -> SaoPackArchive.open(zip));
        assertEquals("Pack index exceeds size limit", failure.getMessage());
    }

    /// Writes the original overrides + modrinth.index layout with distinct export and display versions.
    private Path archive(String name, String version) throws Exception {
        String index = "{\"name\":\"" + name + "\",\"versionId\":\"" + version + "\","
                + "\"dependencies\":{\"minecraft\":\"1.20.1\",\"neoforge\":\"47.1.106\"},"
                + "\"files\":[{\"path\":\"mods/remote.jar\",\"fileSize\":3,\"hashes\":{\"sha1\":\"a9993e364706816aba3e25717850c26c9cd0d89d\"},"
                + "\"downloads\":[\"https://example.invalid/remote.jar\"]}]}";
        Path zip = Files.createTempFile(temporary, "original-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("modrinth.index.json")); output.write(index.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            output.putNextEntry(new ZipEntry("overrides/config/example.txt")); output.write("abc".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        return zip;
    }
}
