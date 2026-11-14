/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/// Read-only official-library importer and launcher-owned premium cache; stores no credentials.
@NotNullByDefault
final class SaoPremiumSkins {
    /// Bounds aggregate processing and texture downloads.
    static final int MAX_ENTRIES = 128;
    /// Bounds the entire official JSON, including unused model previews.
    static final int FILE_LIMIT = 16 * 1024 * 1024;
    /// Validated image with its public name and arm model.
    @NotNullByDefault
    record Entry(String name, byte @Unmodifiable [] png, boolean slim) { }
    /// Utility class.
    private SaoPremiumSkins() { }
    /// Resolves only the official default directory; never creates it.
    static Path officialFile(String os, Path home, @Nullable String appData) {
        Path directory = os.toLowerCase(Locale.ROOT).startsWith("windows")
                ? (appData == null ? home.resolve("AppData/Roaming") : Path.of(appData)).resolve(".minecraft")
                : os.toLowerCase(Locale.ROOT).contains("mac") ? home.resolve("Library/Application Support/minecraft") : home.resolve(".minecraft");
        return directory.resolve("launcher_custom_skins.json");
    }
    /// Reads a bounded object-keyed customSkins library without writing anywhere in the official directory.
    static @Unmodifiable List<Entry> local(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > FILE_LIMIT) throw new IOException("Invalid skin library");
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(FILE_LIMIT + 1);
            if (bytes.length > FILE_LIMIT) throw new IOException("Skin library too large");
            @Nullable JsonObject skins = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("customSkins");
            if (skins == null || skins.size() > MAX_ENTRIES) throw new IOException("Invalid skin library");
            List<Entry> result = new ArrayList<>();
            for (JsonElement value : skins.asMap().values()) {
                JsonObject skin = value.getAsJsonObject();
                String name = skin.get("name").getAsString();
                String data = skin.get("skinImage").getAsString();
                if (name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)
                        || !data.startsWith("data:image/png;base64,") || data.length() > 22 + 4 * ((SaoMicrosoftSkin.LIMIT + 2) / 3)
                        || !skin.get("slim").isJsonPrimitive() || !skin.getAsJsonPrimitive("slim").isBoolean()) throw new IOException("Invalid skin entry");
                result.add(new Entry(name, SaoMicrosoftSkin.normalize(Base64.getDecoder().decode(data.substring(22))), skin.get("slim").getAsBoolean()));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) { throw new IOException("Invalid skin library"); }
    }
    /// Independently tries both sources, emitting at most one sanitized line per refresh.
    static @Unmodifiable List<Entry> load(@Nullable String token, Path file, SaoMicrosoftSkin.Fetch fetch) {
        List<Entry> result = new ArrayList<>(); boolean failed = false;
        if (token != null) try { result.addAll(SaoMicrosoftSkin.library(token, fetch)); } catch (Exception ignored) { failed = true; }
        try { result.addAll(local(file)); } catch (Exception ignored) { failed = true; }
        if (failed) System.getLogger(SaoPremiumSkins.class.getName()).log(System.Logger.Level.INFO, "Premium skin library source unavailable");
        return List.copyOf(result);
    }
    /// Caches normalized images by content hash inside launcher data, deduplicating both sources.
    static @Unmodifiable List<SaoData.SavedSkin> cache(Path root, List<Entry> entries) throws IOException {
        Path directory = root.resolve("oath-skins/premium-library");
        SaoUninstaller.rejectLinksBelowRoot(directory); Files.createDirectories(directory);
        Map<String, SaoData.SavedSkin> result = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byte[] png = SaoMicrosoftSkin.normalize(entry.png()); String hash = hash(png);
            if (result.containsKey(hash)) continue;
            Path file = directory.resolve(hash + ".png"); SaoUninstaller.rejectLinksBelowRoot(file);
            Path temp = Files.createTempFile(directory, "skin-", ".tmp");
            try { Files.write(temp, png); SaoPackFiles.atomicMove(temp, file); } finally { Files.deleteIfExists(temp); }
            result.put(hash, new SaoData.SavedSkin(entry.name(), file, entry.slim(), 0, true));
        }
        return List.copyOf(result.values());
    }
    /// Combines local and premium entries by normalized pixels, preferring the protected premium provenance.
    static @Unmodifiable List<SaoData.SavedSkin> merge(List<SaoData.SavedSkin> local, List<SaoData.SavedSkin> premium) {
        if (premium.isEmpty()) return List.copyOf(local);
        Map<String, SaoData.SavedSkin> result = new LinkedHashMap<>();
        List<SaoData.SavedSkin> all = new ArrayList<>(premium); all.addAll(local);
        for (SaoData.SavedSkin entry : all) try {
            if (Files.size(entry.file()) > SaoMicrosoftSkin.LIMIT) continue;
            try (InputStream input = Files.newInputStream(entry.file())) {
                result.putIfAbsent(hash(SaoMicrosoftSkin.normalize(input.readNBytes(SaoMicrosoftSkin.LIMIT + 1))), entry);
            }
        } catch (IOException | RuntimeException ignored) { }
        return List.copyOf(result.values());
    }
    /// Computes a stable image content key without source identifiers.
    private static String hash(byte[] png) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
