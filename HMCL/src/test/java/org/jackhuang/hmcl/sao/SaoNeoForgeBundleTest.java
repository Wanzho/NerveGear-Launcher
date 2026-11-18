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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies offline bundle extraction, reuse and repair with a self-contained archive.
final class SaoNeoForgeBundleTest {

    @TempDir Path temporary;

    @Test
    void unpacksVerifiedAndIsIdempotent() throws Exception {
        Path libraries = temporary.resolve("libraries");
        Path cache = temporary.resolve("cache");
        String installerName = "neoforge-installer.jar";
        String libraryName = "example/library/1.0/library-1.0.jar";
        byte[] installerBytes = "fixture installer".getBytes(StandardCharsets.UTF_8);
        byte[] libraryBytes = "fixture library".getBytes(StandardCharsets.UTF_8);

        String index = """
                {"installer":{"path":"%s","sha1":"%s"},"libraries":[{"path":"%s","sha1":"%s"}]}
                """.formatted(installerName, sha1(installerBytes), libraryName, sha1(libraryBytes));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            entry(zip, "index.json", index.getBytes(StandardCharsets.UTF_8));
            entry(zip, installerName, installerBytes);
            entry(zip, "libraries/" + libraryName, libraryBytes);
        }
        byte[] archive = buffer.toByteArray();
        Path library = libraries.resolve(libraryName);

        SaoNeoForgeBundle.Result first = SaoNeoForgeBundle.unpack(new ByteArrayInputStream(archive), libraries, cache);
        assertEquals(1, first.placed());
        assertEquals(cache.resolve(installerName), first.installer());
        assertArrayEquals(installerBytes, Files.readAllBytes(cache.resolve(installerName)));
        assertArrayEquals(libraryBytes, Files.readAllBytes(library));

        SaoNeoForgeBundle.Result second = SaoNeoForgeBundle.unpack(new ByteArrayInputStream(archive), libraries, cache);
        assertEquals(0, second.placed());
        assertEquals(1, second.present());

        Files.write(library, new byte[] { 1, 2, 3 });
        SaoNeoForgeBundle.Result repaired = SaoNeoForgeBundle.unpack(new ByteArrayInputStream(archive), libraries, cache);
        assertEquals(1, repaired.placed());
        assertArrayEquals(libraryBytes, Files.readAllBytes(library));
        try (var leftovers = Files.walk(temporary)) {
            assertTrue(leftovers.noneMatch(path -> path.toString().endsWith(".part")));
        }
    }

    private static String sha1(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
