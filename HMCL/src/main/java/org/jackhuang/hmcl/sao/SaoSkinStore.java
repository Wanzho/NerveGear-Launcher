/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/// Stores only standard-size PNG skins and a public Classic/Slim model choice in the fixed instance.
@NotNullByDefault
final class SaoSkinStore {
    /// Standard PNG signature.
    private static final byte @Unmodifiable [] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    /// Maximum encoded bytes; ordinary 64-pixel skins fit comfortably inside this bound.
    private static final long MAX_BYTES = 1024 * 1024;
    /// Fixed instance run directory.
    private final Path instance;
    /// Creates a skin store without reading or writing data.
    SaoSkinStore(Path instance) { this.instance = instance; }

    /// Validates signature and IHDR before decoding, then writes skin.png and model JSON atomically per file.
    /// Null removes both files and restores normal Mojang/CustomSkinLoader lookup.
    synchronized void set(@Nullable Path png, boolean slim) throws IOException {
        Path directory = checkedDirectory();
        if (png == null) {
            Files.deleteIfExists(directory.resolve("skin.png"));
            Files.deleteIfExists(directory.resolve("skin.json"));
            return;
        }
        long length = Files.size(png);
        if (length < 33 || length > MAX_BYTES) throw new IllegalArgumentException("Skin must be a 64×64 or 64×32 PNG");
        byte[] bytes = Files.readAllBytes(png);
        ByteBuffer header = ByteBuffer.wrap(bytes);
        int chunkLength = header.getInt(8);
        int chunkType = header.getInt(12);
        int width = header.getInt(16);
        int height = header.getInt(20);
        if (!Arrays.equals(Arrays.copyOf(bytes, 8), PNG_SIGNATURE) || chunkLength != 13 || chunkType != 0x49484452
                || width != 64 || height != 64 && height != 32)
            throw new IllegalArgumentException("Skin must be a 64×64 or 64×32 PNG; HD skins are unsupported");
        @Nullable BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
            throw new IllegalArgumentException("Skin PNG is invalid");
        Files.createDirectories(directory);
        Path imageTemporary = Files.createTempFile(directory, "skin-", ".png.tmp");
        Path modelTemporary = Files.createTempFile(directory, "skin-", ".json.tmp");
        try {
            Files.write(imageTemporary, bytes);
            Files.writeString(modelTemporary, "{\"model\":\"" + (slim ? "slim" : "classic") + "\"}\n", StandardCharsets.UTF_8);
            SaoPackFiles.atomicMove(imageTemporary, directory.resolve("skin.png"));
            SaoPackFiles.atomicMove(modelTemporary, directory.resolve("skin.json"));
        } finally {
            Files.deleteIfExists(imageTemporary);
            Files.deleteIfExists(modelTemporary);
        }
    }

    /// Returns a stored regular skin file, or null when no skin was selected.
    synchronized @Nullable Path skin() {
        try {
            Path file = checkedDirectory().resolve("skin.png");
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? file : null;
        } catch (IOException e) { return null; }
    }

    /// Reads the saved model choice; absent or invalid JSON uses Classic.
    synchronized boolean slim() {
        try {
            Path model = checkedDirectory().resolve("skin.json");
            if (!Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS) || Files.size(model) > 1024) return false;
            try (Reader reader = Files.newBufferedReader(model, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return json.has("model") && "slim".equals(json.get("model").getAsString());
            }
        } catch (IOException | RuntimeException ignored) { return false; }
    }

    /// Rejects symlink ancestors and stored files before any skin writes or removals.
    private Path checkedDirectory() throws IOException {
        Path directory = SaoPackFiles.resolve(instance, "sao");
        if (Files.isSymbolicLink(directory.resolve("skin.png")) || Files.isSymbolicLink(directory.resolve("skin.json")))
            throw new IOException("Skin storage contains a symbolic link");
        return directory;
    }
}
