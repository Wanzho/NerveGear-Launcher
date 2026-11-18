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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/// Profile-scoped, sanitized PNG cache and local skin library owned entirely by the launcher data root.
@NotNullByDefault
final class SaoOathSkins {
    /// Account-scoped storage root; neither account names nor mailboxes appear in file paths.
    private final Path root;

    /// Creates the per-user local library, independent of account sign-in or server profile.
    SaoOathSkins(Path dataRoot) { root = dataRoot.resolve("oath-skins").resolve("library"); }

    /// Creates a profile cache for an already validated API/profile namespace.
    SaoOathSkins(Path dataRoot, String namespace, String profile) {
        if (!namespace.matches("[a-f0-9]{64}") || !profile.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("皮肤目录无效");
        root = dataRoot.resolve("oath-skins").resolve(namespace).resolve(profile.toLowerCase(Locale.ROOT));
    }

    /// Checks dimensions before decoding and re-encodes a fresh ARGB bitmap to discard ancillary metadata.
    static byte[] sanitized(byte[] png, boolean allowLegacy) throws IOException {
        if (png.length < 33 || png.length > 1024 * 1024) throw new IllegalArgumentException("皮肤必须是 64×64 PNG");
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getLong(0) != 0x89504e470d0a1a0aL || header.getInt(8) != 13 || header.getInt(12) != 0x49484452
                || header.getInt(16) != 64 || header.getInt(20) != 64 && !(allowLegacy && header.getInt(20) == 32))
            throw new IllegalArgumentException("皮肤必须是 64×64 PNG");
        @Nullable BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        if (decoded == null || decoded.getWidth() != 64 || decoded.getHeight() != header.getInt(20))
            throw new IllegalArgumentException("皮肤 PNG 无效");
        BufferedImage clean = new BufferedImage(64, decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
        clean.setRGB(0, 0, 64, clean.getHeight(), decoded.getRGB(0, 0, 64, clean.getHeight(), null, 0, 64), 0, 64);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(clean, "png", output)) throw new IOException("无法编码皮肤 PNG");
        return output.toByteArray();
    }

    /// Returns the cached current skin, or null after the association is removed.
    @Nullable Path current() throws IOException {
        checked();
        Path file = root.resolve("current.png");
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? file : null;
    }

    /// Replaces the server-profile cache only after complete validation; null removes its association.
    void current(@Nullable byte[] bytes) throws IOException {
        checked();
        if (bytes == null) { Files.deleteIfExists(root.resolve("current.png")); return; }
        write(root.resolve("current.png"), sanitized(bytes, true));
    }

    /// Saves a 64×64 PNG and public library metadata, never source metadata or filesystem paths.
    SaoData.SavedSkin save(String name, byte[] png, boolean slim) throws IOException {
        String clean = name.strip();
        if (clean.isEmpty() || clean.length() > 80 || clean.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请给皮肤填写简短名称");
        byte[] bytes = sanitized(png, false);
        checked();
        String id = UUID.randomUUID().toString();
        Path file = root.resolve(id + ".png");
        long saved = System.currentTimeMillis();
        JsonObject metadata = new JsonObject(); metadata.addProperty("name", clean); metadata.addProperty("slim", slim); metadata.addProperty("saved", saved);
        write(file, bytes);
        try { write(root.resolve(id + ".json"), metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (IOException failure) { Files.deleteIfExists(file); throw failure; }
        return new SaoData.SavedSkin(clean, file, slim, saved);
    }

    /// Lists only complete, ordinary profile-owned entries, newest first, while ignoring malformed metadata.
    @Unmodifiable List<SaoData.SavedSkin> list() throws IOException {
        checked();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<SaoData.SavedSkin> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.json")) {
            for (Path metadata : entries) {
                if (result.size() >= 10000) break;
                String id = metadata.getFileName().toString().replaceFirst("\\.json$", "");
                if (!id.matches("[a-f0-9-]{36}") || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 2048) continue;
                Path png = root.resolve(id + ".png");
                if (!Files.isRegularFile(png, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
                    String name = json.get("name").getAsString();
                    if (name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) continue;
                    result.add(new SaoData.SavedSkin(name, png, json.get("slim").getAsBoolean(), json.get("saved").getAsLong()));
                } catch (RuntimeException ignored) { }
            }
        }
        result.sort(Comparator.comparingLong(SaoData.SavedSkin::saved).reversed());
        return List.copyOf(result);
    }

    /// Removes only an entry currently in this profile's library; externally supplied paths are refused.
    void delete(SaoData.SavedSkin skin) throws IOException {
        checked();
        Path file = skin.file().toAbsolutePath().normalize();
        if (!Objects.equals(file.getParent(), root.toAbsolutePath().normalize()) || !file.getFileName().toString().matches("[a-f0-9-]{36}\\.png"))
            throw new IOException("皮肤不属于当前账号的皮肤库");
        SaoUninstaller.rejectLinksBelowRoot(file);
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName().toString().replaceFirst("\\.png$", ".json")));
    }

    /// Refuses a symbolic-link storage root or existing target file before reading or writing.
    private void checked() throws IOException { SaoUninstaller.rejectLinksBelowRoot(root); }

    /// Atomically replaces a complete sanitized image or bounded metadata file.
    private void write(Path file, byte[] bytes) throws IOException {
        checked(); SaoUninstaller.rejectLinksBelowRoot(file); Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "skin-", ".tmp");
        try { Files.write(temporary, bytes); SaoPackFiles.atomicMove(temporary, file); }
        finally { Files.deleteIfExists(temporary); }
    }
}
