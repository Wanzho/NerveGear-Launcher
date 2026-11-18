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
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the production repair pipeline with checksum-bound synthetic payloads and no network or GUI.
@NotNullByDefault
class SaoPrelaunchRepairTest {
    /// Disposable game and archive roots.
    @TempDir Path temporary;

    /// Same-size corruption is repaired for a managed mod and font; a healthy repeat performs no writes.
    @Test void staleModAndCorruptFontRepairThenHealthyDoesNothing() throws Exception {
        Path game = temporary.resolve("game"), assets = temporary.resolve("assets"), zip = temporary.resolve("pack.zip");
        byte @Unmodifiable [] good = "GOOD".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = SaoPackArchive.sha1(new ByteArrayInputStream(good));
        var mod = new SaoPackManifest.PackFile("mods/general-test.jar", hash, good.length, "overrides/mod.jar", List.of());
        var font = new SaoPackManifest.PackFile("minecraft/font/unifont.zip", hash, good.length, null, List.of("https://example.org/font"));
        var manifest = new SaoPackManifest("0.8.1", "1.20.1", "47.1.106", "", "", List.of(mod), List.of());
        Files.createDirectories(game.resolve("mods")); Files.createDirectories(assets.resolve("objects/" + hash.substring(0, 2)));
        Path object = assets.resolve("objects/" + hash.substring(0, 2) + "/" + hash);
        Files.writeString(game.resolve(mod.path()), "STALE"); Files.writeString(object, "BADD");
        Path index = assets.resolve("index.json"); Files.writeString(index, "{\"objects\":{}}");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) { out.putNextEntry(new ZipEntry("overrides/mod.jar")); out.write(good); out.closeEntry(); }
        int[] downloads = {0}; List<String> statuses = new ArrayList<>();
        SaoPackInstaller.Downloader fetch = (file, target) -> { downloads[0]++; Files.write(target, good); };
        assertEquals(2, SaoPrelaunchRepair.repair(game, assets, index, new SaoPackArchive(zip, manifest), fetch, statuses::add, font));
        assertArrayEquals(good, Files.readAllBytes(game.resolve(mod.path()))); assertArrayEquals(good, Files.readAllBytes(object));
        assertEquals(List.of("正在修复 2 个文件"), statuses); assertEquals(1, downloads[0]);
        var modified = Files.getLastModifiedTime(object);
        assertEquals(0, SaoPrelaunchRepair.repair(game, assets, index, new SaoPackArchive(zip, manifest), fetch, statuses::add, font));
        assertEquals(modified, Files.getLastModifiedTime(object)); assertEquals(1, downloads[0]);
        Files.writeString(game.resolve(mod.path()), "BADD");
        assertEquals(1, SaoPrelaunchRepair.repair(game, assets, index, new SaoPackArchive(zip, manifest), fetch, statuses::add, font));
        Path extra = game.resolve("mods/extra.jar");
        Files.writeString(extra, "EXTRA");
        var blocked = assertThrows(IOException.class,
                () -> SaoPrelaunchRepair.repair(game, assets, index, new SaoPackArchive(zip, manifest), fetch, statuses::add, font));
        assertTrue(blocked.getMessage().contains("mods/extra.jar"));
        assertEquals("EXTRA", Files.readString(extra));
    }

    /// Missing and same-size damaged indexes recover before enumeration; a healthy index does no network work.
    @Test void missingAndCorruptIndexRecover() throws Exception {
        Path index = temporary.resolve("indexes/5.json");
        byte @Unmodifiable [] good = "{\"objects\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = SaoPackArchive.sha1(new ByteArrayInputStream(good));
        var descriptor = new SaoPackManifest.PackFile("indexes/5.json", hash, good.length, null, List.of("https://example.org/index"));
        int[] fetched = {0};
        SaoPackInstaller.Downloader fetch = (file, target) -> { fetched[0]++; Files.write(target, good); };
        assertTrue(SaoPrelaunchRepair.repairIndex(index, descriptor, fetch, text -> { }));
        assertArrayEquals(good, Files.readAllBytes(index));
        Files.write(index, new byte[good.length]);
        assertTrue(SaoPrelaunchRepair.repairIndex(index, descriptor, fetch, text -> { }));
        assertFalse(SaoPrelaunchRepair.repairIndex(index, descriptor, fetch, text -> { }));
        assertEquals(2, fetched[0]);
    }

    /// Vanilla placeholder localization leaves ordinary sample names untouched.
    @Test void anonymousMapping() {
        assertEquals("匿名玩家", org.jackhuang.hmcl.ui.sao.SaoPlayerNames.display("Anonymous Player"));
        assertEquals("Player", org.jackhuang.hmcl.ui.sao.SaoPlayerNames.display("Player"));
    }
}
