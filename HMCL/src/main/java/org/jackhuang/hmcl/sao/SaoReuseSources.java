/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/// User-approved read-only Minecraft sources; no account, save or launcher settings are imported.
@NotNullByDefault
final class SaoReuseSources {
    /// Utility class.
    private SaoReuseSources() { }

    /// Preference lives with this game root, outside every selected source.
    private static Path preference(Path root) { return root.resolve("reuse-sources.json"); }

    /// Accepts a game root or a portable launcher folder containing .minecraft.
    static Path validate(Path selected, Path own) throws IOException {
        Path folder = selected.toRealPath();
        if (Files.isDirectory(folder.resolve(".minecraft"), LinkOption.NOFOLLOW_LINKS)) folder = folder.resolve(".minecraft").toRealPath();
        Path ours = own.toAbsolutePath().normalize();
        if (Files.exists(ours)) ours = ours.toRealPath();
        if (folder.startsWith(ours) || ours.startsWith(folder)) throw new IOException("请选择其他启动器的游戏目录");
        if (!Files.isDirectory(folder) || !(Files.isDirectory(folder.resolve("assets"), LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(folder.resolve("libraries"), LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(folder.resolve("versions"), LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("未找到 assets、libraries 或 versions，请选择游戏文件夹");
        return folder;
    }

    /// Loads only bounded, still-available selections; a disconnected drive is not an error.
    static @Unmodifiable List<Path> read(Path root) {
        List<Path> paths = new ArrayList<>();
        Path file = preference(root);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) return List.of();
            var values = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            if (values.size() > 32) return List.of();
            for (var value : values) {
                try { paths.add(validate(Path.of(value.getAsString()), root)); }
                catch (IOException | RuntimeException unavailable) { /* Keep other usable selections. */ }
            }
        } catch (IOException | RuntimeException invalid) { return List.of(); }
        return List.copyOf(paths);
    }

    /// Saves an explicit selection atomically. Empty clears approval without touching source data.
    static void save(Path root, List<Path> selected) throws IOException {
        if (selected.size() > 32) throw new IOException("最多选择 32 个游戏目录");
        List<String> paths = new ArrayList<>();
        for (Path path : selected) {
            String valid = validate(path, root).toString();
            if (!paths.contains(valid)) paths.add(valid);
        }
        Files.createDirectories(root);
        Path temp = Files.createTempFile(root, ".reuse-sources-", ".tmp");
        try {
            Files.writeString(temp, new Gson().toJson(paths));
            SaoPackFiles.atomicMove(temp, preference(root));
        } finally { Files.deleteIfExists(temp); }
    }
}
