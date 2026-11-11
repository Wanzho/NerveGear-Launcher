/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/// Preserves all unowned game settings while applying the video keys verified in the OATH 0.8 archive.
/// Files are prepared before the first write; a failed commit restores every file already changed.
@NotNullByDefault
final class SaoGraphicsPresets {
    /// Quality shader declared by the real pack index and selected in its shipped Oculus configuration.
    static final String SHADER = "MakeUp-UltraFast-9.1c.zip";
    /// Small config bound prevents accidental reads of arbitrary large files.
    private static final int MAX_CONFIG = 2 * 1024 * 1024;

    /// Atomic-move seam permits deterministic failure/rollback tests without changing permissions on real files.
    @NotNullByDefault
    @FunctionalInterface
    interface Move {
        /// Replaces one target with the staged source or throws before completing the move.
        void replace(Path source, Path target) throws IOException;
    }

    /// Utility class.
    private SaoGraphicsPresets() { }

    /// Deterministic conservative suggestion; unknown hardware never receives QUALITY automatically.
    static SaoData.GraphicsPreset suggest(long ramMiB, String gpu, boolean discrete) {
        String name = gpu.toLowerCase(Locale.ROOT);
        if (ramMiB <= 0 || ramMiB < 8192 || !discrete && (name.contains("intel") || name.contains("uhd") || name.contains("iris")
                || name.contains("vega")) || name.isBlank() || name.equals("unknown")) return SaoData.GraphicsPreset.FAST;
        return ramMiB >= 16384 && discrete ? SaoData.GraphicsPreset.QUALITY : SaoData.GraphicsPreset.BALANCED;
    }

    /// Exact Minecraft 1.20.1 owned keys; cloud values are JSON strings as required by options.txt.
    static @Unmodifiable Map<String, String> options(SaoData.GraphicsPreset preset) {
        if (preset == SaoData.GraphicsPreset.AUTO) throw new IllegalArgumentException("Resolve AUTO before applying");
        boolean fast = preset == SaoData.GraphicsPreset.FAST, quality = preset == SaoData.GraphicsPreset.QUALITY;
        return Map.of("renderDistance", fast ? "6" : quality ? "16" : "10",
                "simulationDistance", fast ? "4" : quality ? "10" : "6",
                "graphicsMode", fast ? "0" : "1", "particles", fast ? "2" : quality ? "0" : "1",
                "renderClouds", fast ? "\"false\"" : quality ? "\"true\"" : "\"fast\"",
                "entityDistanceScaling", fast ? "0.5" : quality ? "1.0" : "0.75",
                "mipmapLevels", fast ? "0" : quality ? "4" : "2", "ao", fast ? "false" : "true");
    }

    /// Applies only while no game process is running; the caller serializes this with launch and preset selection.
    static void apply(Path instance, SaoData.GraphicsPreset preset, boolean running) throws IOException {
        apply(instance, preset, running, SaoPackFiles::atomicMove);
    }

    /// Supplies an inert/testable commit seam while preserving production validation and staging.
    static void apply(Path instance, SaoData.GraphicsPreset preset, boolean running, Move move) throws IOException {
        if (running) throw new IOException("游戏运行中，画质将在下次启动时应用");
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("options.txt", edit(read(instance, "options.txt"), options(preset), ':'));
        boolean fast = preset == SaoData.GraphicsPreset.FAST, quality = preset == SaoData.GraphicsPreset.QUALITY;
        JsonObject embeddium;
        try {
            String current = read(instance, "config/embeddium-options.json");
            embeddium = current.isBlank() ? new JsonObject() : JsonParser.parseString(current).getAsJsonObject();
            JsonObject video = section(embeddium, "quality"), performance = section(embeddium, "performance");
            video.addProperty("weather_quality", fast ? "FAST" : "FANCY");
            video.addProperty("leaves_quality", fast ? "FAST" : "FANCY");
            video.addProperty("enable_vignette", !fast);
            performance.addProperty("animate_only_visible_textures", !quality);
            replacements.put("config/embeddium-options.json", new GsonBuilder().setPrettyPrinting().create().toJson(embeddium) + "\n");
        } catch (RuntimeException failure) { throw new IOException("Embeddium 配置无效，未修改画质", failure); }
        // A shader pack the player picked stays picked; the preset only seeds the default pack when none is chosen.
        String oculus = read(instance, "config/oculus.properties");
        java.util.Properties current = new java.util.Properties();
        current.load(new java.io.StringReader(oculus));
        String chosen = current.getProperty("shaderPack", "").strip();
        boolean keep = !chosen.isEmpty() && !chosen.equals(SHADER) && chosen.indexOf('/') < 0 && chosen.indexOf('\\') < 0
                && Files.exists(instance.resolve("shaderpacks").resolve(chosen), LinkOption.NOFOLLOW_LINKS);
        Path shader = SaoPackFiles.resolve(instance, "shaderpacks/" + (keep ? chosen : SHADER));
        boolean shaderPresent = Files.exists(shader, LinkOption.NOFOLLOW_LINKS);
        replacements.put("config/oculus.properties", edit(oculus, keep
                ? Map.of("enableShaders", Boolean.toString(quality && shaderPresent))
                : Map.of("enableShaders", Boolean.toString(quality && shaderPresent), "shaderPack", SHADER), '='));
        String dynamic = dynamicLights(read(instance, "config/sodiumdynamiclights-client.toml"), fast ? "OFF" : quality ? "REALTIME" : "FAST");
        if (org.tomlj.Toml.parse(dynamic).hasErrors()) throw new IOException("动态光源配置无效，未修改画质");
        replacements.put("config/sodiumdynamiclights-client.toml", dynamic);
        commit(instance, replacements, move);
    }

    /// Returns or minimally creates a known JSON object, refusing to replace malformed player data.
    private static JsonObject section(JsonObject root, String name) {
        if (!root.has(name)) root.add(name, new JsonObject());
        return root.getAsJsonObject(name);
    }

    /// Resolves without symlink traversal and reads only a bounded ordinary UTF-8 configuration file.
    private static String read(Path root, String name) throws IOException {
        Path file = SaoPackFiles.resolve(root, name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "";
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_CONFIG) throw new IOException("配置文件不可读");
        return Files.readString(file);
    }

    /// Replaces all occurrences of owned keys while retaining comments, unknown lines, order and line endings.
    static String edit(String text, Map<String, String> owned, char separator) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i); int split = line.indexOf(separator);
            if (split < 0 || line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!")) continue;
            String key = line.substring(0, split).strip();
            if (owned.containsKey(key)) { lines.set(i, key + separator + owned.get(key)); seen.add(key); }
        }
        owned.keySet().stream().sorted().filter(key -> !seen.contains(key)).forEach(key -> lines.add(key + separator + owned.get(key)));
        return String.join(newline, lines) + newline;
    }

    /// Edits mode only inside the actual sodiumdynamiclights TOML section, preserving similarly named other keys.
    static String dynamicLights(String text, String mode) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        boolean section = false, foundSection = false, foundMode = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("[")) {
                if (section && !foundMode) { lines.add(i++, "mode = \"" + mode + "\""); foundMode = true; }
                section = line.equals("[sodiumdynamiclights]"); foundSection |= section;
            } else if (section && line.matches("mode\\s*=.*")) {
                lines.set(i, "mode = \"" + mode + "\""); foundMode = true;
            }
        }
        if (!foundSection) lines.add("[sodiumdynamiclights]");
        if (!foundMode) lines.add("mode = \"" + mode + "\"");
        return String.join(newline, lines) + newline;
    }

    /// Stages all files, then commits each atomically; rollback restores originals if any move fails.
    private static void commit(Path root, Map<String, String> replacements, Move move) throws IOException {
        Map<Path, byte @Nullable @Unmodifiable []> originals = new LinkedHashMap<>();
        Map<Path, Path> staged = new LinkedHashMap<>(); List<Path> committed = new ArrayList<>();
        try {
            for (var entry : replacements.entrySet()) {
                Path target = SaoPackFiles.resolve(root, entry.getKey());
                originals.put(target, Files.exists(target) ? Files.readAllBytes(target) : null);
                Files.createDirectories(Objects.requireNonNull(target.getParent()));
                Path temporary = Files.createTempFile(target.getParent(), ".preset-", ".tmp"); staged.put(target, temporary);
                Files.writeString(temporary, entry.getValue(), StandardCharsets.UTF_8);
            }
            for (var entry : staged.entrySet()) { move.replace(entry.getValue(), entry.getKey()); committed.add(entry.getKey()); }
        } catch (IOException failure) {
            for (Path target : committed) {
                try {
                    byte @Nullable @Unmodifiable [] original = originals.get(target);
                    if (original == null) Files.deleteIfExists(target);
                    else {
                        Path restore = Files.createTempFile(target.getParent(), ".preset-restore-", ".tmp");
                        try { Files.write(restore, original); move.replace(restore, target); }
                        finally { Files.deleteIfExists(restore); }
                    }
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        } finally { for (Path temporary : staged.values()) Files.deleteIfExists(temporary); }
    }
}
