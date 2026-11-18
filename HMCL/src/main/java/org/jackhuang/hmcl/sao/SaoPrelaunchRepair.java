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

import com.google.gson.*;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/// Repairs only inventory-managed jars and indexed fonts, with verified atomic replacement and no player-file writes.
@NotNullByDefault
final class SaoPrelaunchRepair {
    /// Known 1.20.1 unifont object, enforced even if the local index was truncated or altered.
    static final String UNIFONT = "a661b5622172ea1ce1fa9ec78fad484d118e5689";
    /// Utility class.
    private SaoPrelaunchRepair() { }

    /// Verifies managed mod JARs in release builds and repairs Minecraft fonts in every build.
    static int repair(Path instance, Path assets, Path index, SaoPackArchive pack,
                      SaoPackInstaller.Downloader downloader, Consumer<String> status) throws Exception {
        return repair(instance, assets, index, pack, downloader, status,
                asset("minecraft/font/unifont.zip", UNIFONT, 1546906));
    }

    /// Injects only the fixed font descriptor for small hermetic byte-corruption tests.
    static int repair(Path instance, Path assets, Path index, SaoPackArchive pack,
                      SaoPackInstaller.Downloader downloader, Consumer<String> status, SaoPackManifest.PackFile unifont) throws Exception {
        Map<Path, SaoPackManifest.PackFile> broken = new LinkedHashMap<>();
        if (!Metadata.SAO_BETA) {
            Set<String> managed = new HashSet<>();
            for (var file : pack.manifest().files()) {
                String name = file.path().toLowerCase(Locale.ROOT);
                if (!name.startsWith("mods/") || !name.endsWith(".jar")) continue;
                managed.add(file.path());
                Path target = SaoPackFiles.resolve(instance, file.path());
                if (!SaoPackFiles.matches(target, file)) broken.put(target, file);
            }
            Path mods = SaoPackFiles.resolve(instance, "mods");
            if (Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(mods)) {
                    var extra = paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .map(path -> "mods/" + mods.relativize(path).toString().replace(File.separatorChar, '/'))
                            .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".jar") && !managed.contains(path))
                            .findFirst();
                    if (extra.isPresent()) throw new IOException("发现整合包以外的模组，请先移出再进服：" + extra.get());
                }
            }
        }
        Map<String, SaoPackManifest.PackFile> fonts = new LinkedHashMap<>();
        JsonObject indexDocument;
        if (Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
            try (Reader reader = Files.newBufferedReader(index)) {
                indexDocument = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject objects = indexDocument.getAsJsonObject("objects");
                for (var entry : objects.entrySet()) {
                    if (!entry.getKey().startsWith("minecraft/font/")) continue;
                    JsonObject object = entry.getValue().getAsJsonObject();
                    fonts.put(entry.getKey(), asset(entry.getKey(), object.get("hash").getAsString(), object.get("size").getAsLong()));
                }
            }
        } else throw new IOException("Minecraft asset index is missing; repair the game installation first");
        @Nullable SaoPackManifest.PackFile indexedFont = fonts.get("minecraft/font/unifont.zip");
        boolean indexWrong = indexedFont == null || !indexedFont.sha1().equals(unifont.sha1()) || indexedFont.size() != unifont.size();
        fonts.put("minecraft/font/unifont.zip", unifont);
        for (var file : fonts.values()) {
            Path target = SaoPackFiles.resolve(assets, "objects/" + file.sha1().substring(0, 2) + "/" + file.sha1());
            if (!SaoPackFiles.matches(target, file)) broken.put(target, file);
        }
        if (indexWrong) {
            JsonObject corrected = new JsonObject(); corrected.addProperty("hash", unifont.sha1()); corrected.addProperty("size", unifont.size());
            indexDocument.getAsJsonObject("objects").add("minecraft/font/unifont.zip", corrected);
            Path stagedIndex = Files.createTempFile(index.getParent(), ".sao-index-", ".tmp");
            try { Files.writeString(stagedIndex, indexDocument.toString()); SaoPackFiles.atomicMove(stagedIndex, index); }
            finally { Files.deleteIfExists(stagedIndex); }
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("SAO prelaunch repaired: asset index minecraft/font/unifont.zip");
        }
        if (broken.isEmpty()) return 0;
        status.accept("正在修复 " + broken.size() + " 个文件");
        try (ZipFile zip = new ZipFile(pack.zip().toFile())) {
            for (var item : broken.entrySet()) {
                Path target = item.getKey(); var file = item.getValue();
                Files.createDirectories(target.getParent());
                Path staged = Files.createTempFile(target.getParent(), ".sao-repair-", ".tmp");
                try {
                    if (file.entry() != null) {
                        @Nullable ZipEntry entry = zip.getEntry(file.entry());
                        if (entry == null || entry.getSize() != file.size()) throw new IOException("Missing repair member: " + file.path());
                        try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(staged)) {
                            byte[] buffer = new byte[65536]; long total = 0; int count;
                            while ((count = input.read(buffer)) != -1) {
                                total += count;
                                if (total > file.size()) throw new IOException("Oversized repair member");
                                output.write(buffer, 0, count);
                            }
                        }
                    } else downloader.download(file, staged);
                    if (!SaoPackFiles.matches(staged, file)) throw new IOException("Repair checksum mismatch: " + file.path());
                    SaoPackFiles.atomicMove(staged, target);
                    org.jackhuang.hmcl.util.logging.Logger.LOG.info("SAO prelaunch repaired: " + file.path() + " sha1=" + file.sha1());
                } finally { Files.deleteIfExists(staged); }
            }
        }
        return broken.size();
    }

    /// Restores a missing or corrupt index before font enumeration, using the installed version's trusted descriptor.
    static boolean repairIndex(Path index, SaoPackManifest.PackFile descriptor,
                               SaoPackInstaller.Downloader downloader, Consumer<String> status) throws Exception {
        if (SaoPackFiles.matches(index, descriptor)) return false;
        status.accept("正在修复 1 个文件");
        Files.createDirectories(index.getParent());
        Path staged = Files.createTempFile(index.getParent(), ".sao-index-download-", ".tmp");
        try {
            downloader.download(descriptor, staged);
            if (!SaoPackFiles.matches(staged, descriptor)) throw new IOException("Asset index repair checksum mismatch");
            SaoPackFiles.atomicMove(staged, index);
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("SAO prelaunch repaired: " + descriptor.path() + " sha1=" + descriptor.sha1());
        } finally { Files.deleteIfExists(staged); }
        return true;
    }

    /// Builds a checksum-bound public asset request with official and mirror sources.
    private static SaoPackManifest.PackFile asset(String name, String hash, long size) {
        String location = hash.substring(0, 2) + "/" + hash;
        return new SaoPackManifest.PackFile(name, hash, size, null, List.of(
                "https://resources.download.minecraft.net/" + location, "https://bmclapi2.bangbang93.com/assets/" + location));
    }
}
