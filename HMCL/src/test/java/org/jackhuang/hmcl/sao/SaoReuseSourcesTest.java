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

import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Explicit source approval and read-only, hash-verified reuse in isolated fixtures.
@NotNullByDefault
final class SaoReuseSourcesTest {
    /// Isolated preference, source and destination fixture root.
    @TempDir Path temporary;

    /// Only valid foreign directories may be persisted; invalid changes leave the previous selection intact.
    @Test void validatesAndPersistsExplicitChoices() throws Exception {
        Path own = Files.createDirectories(temporary.resolve("our-game"));
        Path portable = Files.createDirectories(temporary.resolve("portable"));
        Path minecraft = Files.createDirectories(portable.resolve(".minecraft"));
        Files.createDirectories(minecraft.resolve("assets"));
        Path invalid = Files.createDirectories(temporary.resolve("unrelated"));
        assertEquals(minecraft.toRealPath(), SaoReuseSources.validate(portable, own));
        assertThrows(IOException.class, () -> SaoReuseSources.validate(own, own));
        assertThrows(IOException.class, () -> SaoReuseSources.validate(temporary, own));
        assertThrows(IOException.class, () -> SaoReuseSources.validate(invalid, own));
        SaoReuseSources.save(own, List.of(portable, minecraft));
        assertEquals(List.of(minecraft.toRealPath()), SaoReuseSources.read(own));
        assertThrows(IOException.class, () -> SaoReuseSources.save(own, List.of(invalid)));
        assertEquals(List.of(minecraft.toRealPath()), SaoReuseSources.read(own));
        SaoReuseSources.save(own, List.of());
        assertTrue(SaoReuseSources.read(own).isEmpty());
        assertTrue(Files.isDirectory(minecraft.resolve("assets")), "clearing approval must not touch source");
    }

    /// A change in approval affects the already-created cache and renamed client files are copied only on hash match.
    @Test void refreshesApprovedSourcesAndCopiesRenamedClientWithoutMutatingSource() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("pcl/.minecraft"));
        Path client = source.resolve("versions/custom-1.20.1/custom-1.20.1.jar");
        Files.createDirectories(client.getParent());
        byte[] bytes = "verified renamed client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(client, bytes);
        long modified = Files.getLastModifiedTime(client).toMillis();
        String hash = DigestUtils.digestToString(CacheRepository.SHA1, bytes);
        AtomicReference<List<Path>> approved = new AtomicReference<>(List.of());
        var cache = new SaoExistingMinecraft.Cache(temporary.resolve("game/dependencies"), approved::get);
        // GameDownloadTask -> CacheFileTask supplies null for its source path.
        @Nullable Path requested = null;
        assertTrue(cache.checkExistentFile(requested, CacheRepository.SHA1, hash).isEmpty());
        approved.set(List.of(source));
        cache.refreshSources();
        Path copy = cache.checkExistentFile(requested, CacheRepository.SHA1, hash).orElseThrow();
        assertFalse(copy.startsWith(source));
        assertArrayEquals(bytes, Files.readAllBytes(copy));
        assertArrayEquals(bytes, Files.readAllBytes(client));
        assertEquals(modified, Files.getLastModifiedTime(client).toMillis());
        assertTrue(cache.checkExistentFile(requested, CacheRepository.SHA1,
                DigestUtils.digestToString(CacheRepository.SHA1, "wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEmpty());
    }

    /// Portable launcher directories are discoverable, while a cancelled manual chooser does not alter preferences.
    @Test void findsPortableDirectoriesWithoutApprovingThem() throws Exception {
        Path own = Files.createDirectories(temporary.resolve("own"));
        Path minecraft = Files.createDirectories(temporary.resolve("Desktop/PCL/.minecraft/versions"));
        assertTrue(SaoExistingMinecraft.portableFolders(List.of(temporary.resolve("Desktop"))).contains(minecraft.getParent()));
        assertTrue(SaoReuseSources.read(own).isEmpty());
    }

    /// Current HMCL directory stores resolve relative paths against the launcher root, not its config subfolder.
    @Test void readsModernHmclDirectoryStores() throws Exception {
        Path launcher = Files.createDirectories(temporary.resolve("hmcl"));
        Path config = Files.createDirectories(launcher.resolve("config"));
        Path absolute = temporary.resolve("absolute-game").toAbsolutePath();
        String json = new com.google.gson.Gson().toJson(java.util.Map.of("directories", List.of(
                java.util.Map.of("path", "relative-game"), java.util.Map.of("path", absolute.toString()))));
        for (String name : List.of("game-directories.json", "user-game-directories.json")) {
            Path store = config.resolve(name); Files.writeString(store, json);
            assertEquals(List.of(launcher.resolve("relative-game"), absolute), SaoExistingMinecraft.hmclFolders(store));
        }
    }

}
