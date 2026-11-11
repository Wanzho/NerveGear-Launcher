/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Preset round trips use original pack config fixtures and never touch the supplied archive or a running game.
@NotNullByDefault
class SaoExtrasGraphicsTest {
    /// Disposable instance root.
    @TempDir Path instance;

    /// Copies shipped mod configs and a minimal options fixture into an isolated instance.
    private void fixtures() throws IOException {
        Files.createDirectories(instance.resolve("config"));
        for (String file : List.of("embeddium-options.json", "oculus.properties", "sodiumdynamiclights-client.toml", "options.txt")) {
            try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/sao/extras/" + file))) {
                Files.copy(input, instance.resolve(file.equals("options.txt") ? file : "config/" + file));
            }
        }
    }

    /// All owned options and real mod keys take exact preset values, preserving every unrelated setting.
    @Test void roundTripPreservesPlayerKeys() throws Exception {
        fixtures(); Files.createDirectories(instance.resolve("shaderpacks")); Files.writeString(instance.resolve("shaderpacks/" + SaoGraphicsPresets.SHADER), "fixture");
        JsonObject original = JsonParser.parseString(Files.readString(instance.resolve("config/embeddium-options.json"))).getAsJsonObject();
        for (var preset : List.of(SaoData.GraphicsPreset.FAST, SaoData.GraphicsPreset.BALANCED, SaoData.GraphicsPreset.QUALITY, SaoData.GraphicsPreset.FAST)) {
            SaoGraphicsPresets.apply(instance, preset, false);
            String options = Files.readString(instance.resolve("options.txt"));
            for (var key : SaoGraphicsPresets.options(preset).entrySet()) assertTrue(options.contains(key.getKey() + ":" + key.getValue() + "\r\n"));
            for (String preserved : List.of("lang:zh_cn", "mouseSensitivity:0.5", "key_key.forward:key.keyboard.w", "unknownFutureKey:keep-this", "resourcePacks:[\"file/required.zip\"]"))
                assertTrue(options.contains(preserved + "\r\n"));
            JsonObject now = JsonParser.parseString(Files.readString(instance.resolve("config/embeddium-options.json"))).getAsJsonObject();
            assertEquals(original.get("advanced"), now.get("advanced")); assertEquals(original.get("notifications"), now.get("notifications"));
            assertEquals(original.getAsJsonObject("quality").get("use_quad_normals_for_shading"), now.getAsJsonObject("quality").get("use_quad_normals_for_shading"));
            assertEquals(original.getAsJsonObject("performance").get("chunk_builder_threads"), now.getAsJsonObject("performance").get("chunk_builder_threads"));
            String oculus = Files.readString(instance.resolve("config/oculus.properties"));
            assertTrue(oculus.contains("enableShaders=" + (preset == SaoData.GraphicsPreset.QUALITY)));
            assertTrue(oculus.contains("colorSpace=SRGB\r\n")); assertTrue(oculus.contains("shaderPack=" + SaoGraphicsPresets.SHADER));
            assertTrue(Files.readString(instance.resolve("config/sodiumdynamiclights-client.toml")).contains("entities = true"));
            Map<String, String> snapshot = snapshot(); SaoGraphicsPresets.apply(instance, preset, false); assertEquals(snapshot, snapshot());
        }
    }

    /// A shader pack the player chose survives a preset; quality enables it and the other presets only switch it off.
    @Test void presetKeepsChosenShader() throws Exception {
        Files.createDirectories(instance.resolve("shaderpacks")); Files.writeString(instance.resolve("shaderpacks/Complementary.zip"), "fixture");
        Files.createDirectories(instance.resolve("config"));
        Files.writeString(instance.resolve("config/oculus.properties"), "shaderPack=Complementary.zip\nenableShaders=true\n");
        SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.FAST, false);
        String oculus = Files.readString(instance.resolve("config/oculus.properties"));
        assertTrue(oculus.contains("shaderPack=Complementary.zip")); assertTrue(oculus.contains("enableShaders=false"));
        SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.QUALITY, false);
        assertTrue(Files.readString(instance.resolve("config/oculus.properties")).contains("enableShaders=true"));
    }

        /// Missing configs are created minimally, and quality never enables a nonexistent shader pack.
    @Test void missingFilesAndShaderFallback() throws Exception {
        SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.QUALITY, false);
        assertEquals(8, Files.readAllLines(instance.resolve("options.txt")).size());
        JsonObject embeddium = JsonParser.parseString(Files.readString(instance.resolve("config/embeddium-options.json"))).getAsJsonObject();
        assertEquals(Set.of("quality", "performance"), embeddium.keySet());
        assertFalse(Files.exists(instance.resolve("config/iris.properties")));
        assertFalse(Files.exists(instance.resolve("config/DistantHorizons.toml")));
        assertTrue(Files.readString(instance.resolve("config/oculus.properties")).contains("enableShaders=false"));
        assertEquals("[sodiumdynamiclights]\nmode = \"REALTIME\"\n", Files.readString(instance.resolve("config/sodiumdynamiclights-client.toml")));
    }

    /// A running game or malformed mod config refuses the entire change, before options.txt can be overwritten.
    @Test void runningAndMalformedInputsLeaveEveryFileUntouched() throws Exception {
        fixtures(); Map<String, String> before = snapshot();
        assertThrows(IOException.class, () -> SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.FAST, true)); assertEquals(before, snapshot());
        Files.writeString(instance.resolve("config/embeddium-options.json"), "{broken}"); before = snapshot();
        assertThrows(IOException.class, () -> SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.FAST, false)); assertEquals(before, snapshot());
        Files.delete(instance.resolve("config/embeddium-options.json"));
        Path external = instance.resolveSibling(instance.getFileName() + "-external.json"); Files.writeString(external, "{}");
        Files.createSymbolicLink(instance.resolve("config/embeddium-options.json"), external);
        try { assertThrows(IOException.class, () -> SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.FAST, false)); assertEquals("{}", Files.readString(external)); }
        finally { Files.deleteIfExists(external); }
    }

    /// A failure after the first atomic replacement restores the original bytes and removes every staged file.
    @Test void commitFailureRollsBackAlreadyChangedFiles() throws Exception {
        fixtures(); Map<String, String> before = snapshot();
        var moves = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IOException.class, () -> SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.FAST, false, (source, target) -> {
            if (moves.incrementAndGet() == 2) throw new IOException("Synthetic second-move failure");
            SaoPackFiles.atomicMove(source, target);
        }));
        assertEquals(before, snapshot());
        try (var files = Files.walk(instance)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".preset-"))); }
    }

    /// Invalid unrelated TOML is preserved for recovery rather than silently repaired by a partial preset write.
    @Test void malformedTomlRefusesAllPresetWrites() throws Exception {
        fixtures(); Files.writeString(instance.resolve("config/sodiumdynamiclights-client.toml"), "[broken\nmode = \"unterminated");
        Map<String, String> before = snapshot();
        assertThrows(IOException.class, () -> SaoGraphicsPresets.apply(instance, SaoData.GraphicsPreset.FAST, false));
        assertEquals(before, snapshot());
    }

    /// TOML ownership is scoped to one section, including when another section uses a mode key.
    @Test void sectionScopedTomlAndDuplicateOptions() {
        String original = "# preserved\n[other]\nmode = \"CUSTOM\"\n[sodiumdynamiclights]\nentities = false\n[next]\nmode = \"UNCHANGED\"\n";
        String changed = SaoGraphicsPresets.dynamicLights(original, "OFF");
        assertTrue(changed.contains("[other]\nmode = \"CUSTOM\"")); assertTrue(changed.contains("mode = \"OFF\"\n[next]"));
        assertTrue(changed.contains("mode = \"UNCHANGED\"")); assertTrue(changed.contains("entities = false"));
        String options = SaoGraphicsPresets.edit("renderDistance:20\nunknown:x\nrenderDistance:30\n", Map.of("renderDistance", "6"), ':');
        assertEquals("renderDistance:6\nunknown:x\nrenderDistance:6\n", options);
    }

    /// Automatic recommendations use conservative RAM/GPU gates, including unknown and integrated hardware.
    @Test void automaticSuggestion() {
        assertEquals(SaoData.GraphicsPreset.FAST, SaoGraphicsPresets.suggest(4096, "RTX 4080", true));
        assertEquals(SaoData.GraphicsPreset.FAST, SaoGraphicsPresets.suggest(32768, "Intel Iris Xe", false));
        assertEquals(SaoData.GraphicsPreset.FAST, SaoGraphicsPresets.suggest(32768, "unknown", false));
        assertEquals(SaoData.GraphicsPreset.BALANCED, SaoGraphicsPresets.suggest(8192, "RTX 3060", true));
        assertEquals(SaoData.GraphicsPreset.BALANCED, SaoGraphicsPresets.suggest(16384, "Apple M2", false));
        assertEquals(SaoData.GraphicsPreset.QUALITY, SaoGraphicsPresets.suggest(16384, "RTX 3060", true));
    }

    /// Captures only the four files owned in part by a preset, for meaningful idempotence and refusal assertions.
    private Map<String, String> snapshot() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String file : List.of("options.txt", "config/embeddium-options.json", "config/oculus.properties", "config/sodiumdynamiclights-client.toml"))
            values.put(file, Files.readString(instance.resolve(file)));
        return values;
    }
}
