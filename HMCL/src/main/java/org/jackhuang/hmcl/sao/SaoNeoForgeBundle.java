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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// NeoForge 47.1.106 shipped inside the launcher (James, 15 Nov: "先放进去").
///
/// Players stalled or failed at 安装 NeoForge the night 0.8.1 opened: maven.neoforged.net timed out from China and
/// BMCLAPI was cutting connections, so neither source could deliver the installer or the 73 libraries its processors
/// need. The launcher now carries all of it (about 26 MB): the installer is handed to HMCL's install task directly, and
/// the libraries are placed under `minecraft/libraries` before the build, so HMCL finds them present and downloads
/// nothing for NeoForge. Every file is checked against the SHA-1 written in the installer's own profile; a missing or
/// damaged bundle just falls back to the normal download.
@NotNullByDefault
final class SaoNeoForgeBundle {
    /// Classpath resource holding `index.json`, the installer and `libraries/<maven path>`.
    static final String RESOURCE = "/assets/sao-launcher/neoforge/neoforge-1.20.1-47.1.106-offline.zip";

    private SaoNeoForgeBundle() { }

    /// What one unpack placed.
    record Result(@Nullable Path installer, int placed, int present) { }

    /// Unpacks the bundle: missing or damaged libraries into `libraries`, the installer into `cacheDir`.
    /// Returns null when the launcher carries no bundle (development builds) or it cannot be read.
    static @Nullable Result unpack(Path libraries, Path cacheDir) {
        try (@Nullable InputStream in = SaoNeoForgeBundle.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            return unpack(in, libraries, cacheDir);
        } catch (IOException | RuntimeException failure) {
            Logger.LOG.warning("Bundled NeoForge could not be used; downloading it instead", failure);
            return null;
        }
    }

    /// Package-visible for tests: unpacks from any stream laid out like [#RESOURCE].
    static Result unpack(InputStream bundle, Path libraries, Path cacheDir) throws IOException {
        @Nullable JsonObject index = null;
        // index.json comes first in the zip; the expected hashes must be known before any file is written.
        java.util.Map<String, String> sha1 = new java.util.HashMap<>();
        @Nullable String installerName = null, installerSha1 = null;
        @Nullable Path installer = null;
        int placed = 0, present = 0;
        try (ZipInputStream zip = new ZipInputStream(bundle)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.equals("index.json")) {
                    index = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject inst = index.getAsJsonObject("installer");
                    installerName = inst.get("path").getAsString(); installerSha1 = inst.get("sha1").getAsString();
                    JsonArray libs = index.getAsJsonArray("libraries");
                    for (JsonElement l : libs) {
                        JsonObject o = l.getAsJsonObject();
                        sha1.put(o.get("path").getAsString(), o.get("sha1").getAsString());
                    }
                    continue;
                }
                if (index == null) throw new IOException("Bundle index must come first");
                if (name.equals(installerName)) {
                    Files.createDirectories(cacheDir);
                    Path target = cacheDir.resolve(installerName);
                    if (!matches(target, installerSha1)) write(zip, target, installerSha1);
                    installer = target;
                    continue;
                }
                if (!name.startsWith("libraries/")) continue;
                String rel = name.substring("libraries/".length());
                @Nullable String expected = sha1.get(rel);
                if (expected == null || rel.contains("..") || rel.startsWith("/")) continue;
                Path target = libraries.resolve(rel).normalize();
                if (!target.startsWith(libraries.normalize())) continue;
                if (matches(target, expected)) { present++; continue; }
                Files.createDirectories(target.getParent());
                write(zip, target, expected);
                placed++;
            }
        }
        if (index == null) throw new IOException("Bundle has no index");
        Logger.LOG.info("Bundled NeoForge: " + placed + " libraries placed, " + present + " already present, installer "
                + (installer == null ? "missing" : "ready"));
        return new Result(installer, placed, present);
    }

    /// Writes one entry through a temporary file and moves it in only when its SHA-1 matches.
    private static void write(ZipInputStream zip, Path target, @Nullable String expected) throws IOException {
        byte[] bytes = zip.readAllBytes();
        if (expected == null || !expected.equalsIgnoreCase(sha1(bytes))) throw new IOException("Bundled file damaged: " + target.getFileName());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static boolean matches(Path file, @Nullable String expected) {
        if (expected == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try { return expected.equalsIgnoreCase(sha1(Files.readAllBytes(file))); }
        catch (IOException unreadable) { return false; }
    }

    private static String sha1(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
