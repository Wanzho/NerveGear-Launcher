/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/// Headless update state-machine coverage with inert JAR fixtures; no fixture code is executed.
@NotNullByDefault
class SaoSelfUpdateTest {
    /// All storage, candidate JARs and bundle fixtures live under one disposable root.
    @TempDir Path temporary;

    /// Creates the actual distribution's manifest/properties layout with inert entries for required classes.
    private Path jar(String filename, String version) throws IOException {
        Path path = temporary.resolve(filename);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "org.jackhuang.hmcl.Main");
        manifest.getMainAttributes().putValue("Implementation-Version", version);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("assets/hmcl.properties"));
            output.write(("hmcl.version=" + version + "\nhmcl.sao.beta=false\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (String entry : List.of("org/jackhuang/hmcl/Main.class", "org/jackhuang/hmcl/Launcher.class",
                    "org/jackhuang/hmcl/sao/SaoLauncherBootstrap.class", "org/jackhuang/hmcl/sao/SaoSelfUpdate.class")) {
                output.putNextEntry(new JarEntry(entry)); output.write(0); output.closeEntry();
            }
        }
        return path;
    }

    /// Creates the flat public launcher identity, exactly as the Gitee document advertises it.
    private static SaoData.VersionMetadata metadata(Path artifact, String latest, String minimum, boolean nativeRequired) throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schema", 2); root.addProperty("sequence", 0); root.addProperty("quark", SaoUpdates.QUARK_URL);
        JsonObject pack = new JsonObject(); pack.addProperty("version", "0.8"); pack.addProperty("file", "pack.zip"); pack.addProperty("size", 1); pack.addProperty("sha256", "0".repeat(64));
        root.add("pack", pack); root.add("updates", new JsonArray());
        JsonObject launcher = new JsonObject(); launcher.addProperty("latest", latest); launcher.addProperty("min", minimum);
        launcher.addProperty("file", artifact.getFileName().toString()); launcher.addProperty("size", Files.size(artifact)); launcher.addProperty("sha256", SaoUpdates.sha256(artifact));
        launcher.addProperty("nativeRequired", nativeRequired); root.add("launcher", launcher);
        return SaoUpdates.parse(root.toString());
    }

    /// Common validated download then native-selection flow.
    private static SaoSelfUpdate.Selection stage(SaoSelfUpdate updater, Path bundled, Path candidate, String version) throws IOException {
        assertTrue(updater.stage(candidate, metadata(candidate, version, "0.1.0", false), "0.1.0").restartRequired());
        SaoSelfUpdate.Selection selection = updater.select(bundled, "0.1.0");
        assertEquals(version, selection.version()); assertNotNull(selection.token()); return selection;
    }

    /// Verified swap changes only the per-user pointer and becomes durable after the exact healthy marker.
    @Test void successfulSwapAndHealthyMarkerRetainSignedBundle() throws Exception {
        Path bundle = jar("bundled.jar", "0.1.0"); Path candidate = jar("update.jar", "0.1.1");
        String bundleHash = SaoUpdates.sha256(bundle); SaoSelfUpdate updater = new SaoSelfUpdate(temporary.resolve("data/self-update"));
        SaoSelfUpdate.Selection selection = stage(updater, bundle, candidate, "0.1.1");
        assertTrue(selection.jar().startsWith(temporary.resolve("data"))); assertEquals("0.1.1", selection.version());
        String token = Objects.requireNonNull(selection.token()); assertFalse(updater.healthy(token));
        updater.acknowledge(token); assertTrue(updater.healthy(token)); updater.finish(token, true);
        assertFalse(updater.status().restartRequired()); assertEquals("启动器更新成功", updater.status().message());
        SaoSelfUpdate.Selection again = updater.select(bundle, "0.1.0"); assertNull(again.token()); assertEquals(selection.jar(), again.jar());
        assertEquals(bundleHash, SaoUpdates.sha256(bundle)); assertTrue(Files.exists(candidate));
        assertThrows(IOException.class, () -> updater.acknowledge(token));
    }

    /// Hash mismatch and manifest/properties version mismatch cannot alter the active selection.
    @Test void invalidDownloadAndWrongVersionLeaveBundleUntouched() throws Exception {
        Path bundle = jar("bundle.jar", "0.1.0"); Path candidate = jar("candidate.jar", "0.1.1");
        SaoSelfUpdate updater = new SaoSelfUpdate(temporary.resolve("data"));
        SaoData.VersionMetadata identity = metadata(candidate, "0.1.1", "0.1.0", false);
        Files.writeString(candidate, "corrupt");
        assertThrows(IOException.class, () -> updater.stage(candidate, identity, "0.1.0"));
        Path wrongVersion = jar("wrong.jar", "0.1.2");
        assertThrows(IOException.class, () -> updater.stage(wrongVersion, metadata(wrongVersion, "0.1.1", "0.1.0", false), "0.1.0"));
        assertEquals(bundle, updater.select(bundle, "0.1.0").jar()); assertFalse(updater.status().restartRequired());
    }

    /// A failed second replacement restores the previously healthy first replacement, not an arbitrary file.
    @Test void failedStartupAndCorruptActiveJarRollBackToPrevious() throws Exception {
        Path bundle = jar("bundle.jar", "0.1.0"); Path first = jar("first.jar", "0.1.1"); Path second = jar("second.jar", "0.1.2");
        SaoSelfUpdate updater = new SaoSelfUpdate(temporary.resolve("data"));
        SaoSelfUpdate.Selection one = stage(updater, bundle, first, "0.1.1"); String token = Objects.requireNonNull(one.token());
        updater.acknowledge(token); updater.finish(token, true);
        updater.stage(second, metadata(second, "0.1.2", "0.1.0", false), "0.1.1");
        SaoSelfUpdate.Selection two = updater.select(bundle, "0.1.0");
        updater.finish(Objects.requireNonNull(two.token()), false);
        assertEquals(one.jar(), updater.select(bundle, "0.1.0").jar()); assertTrue(updater.status().message().contains("已恢复上一版本"));
        updater.stage(second, metadata(second, "0.1.2", "0.1.0", false), "0.1.1");
        SaoSelfUpdate.Selection corrupt = updater.select(bundle, "0.1.0"); String corruptToken = Objects.requireNonNull(corrupt.token());
        updater.acknowledge(corruptToken); updater.finish(corruptToken, true); Files.writeString(corrupt.jar(), "broken");
        assertEquals(one.jar(), updater.select(bundle, "0.1.0").jar());
    }

    /// An unacknowledged dead child or expired attempt rolls back on the next native-shell launch.
    @Test void abandonedOrExpiredStartupRollsBackAndWrongMarkerCannotCommit() throws Exception {
        Path bundle = jar("bundle.jar", "0.1.0"); Path candidate = jar("candidate.jar", "0.1.1"); Path data = temporary.resolve("data");
        SaoSelfUpdate updater = new SaoSelfUpdate(data); SaoSelfUpdate.Selection selection = stage(updater, bundle, candidate, "0.1.1");
        String token = Objects.requireNonNull(selection.token()); updater.attach(token, Long.MAX_VALUE);
        assertEquals(bundle, updater.select(bundle, "0.1.0").jar()); assertFalse(updater.healthy(token));
        selection = stage(updater, bundle, candidate, "0.1.1");
        JsonObject state = JsonParser.parseString(Files.readString(data.resolve("state.json"))).getAsJsonObject(); state.addProperty("deadline", 0); Files.writeString(data.resolve("state.json"), state.toString());
        assertEquals(bundle, updater.select(bundle, "0.1.0").jar());
        assertThrows(IOException.class, () -> updater.acknowledge("../outside"));
    }

    /// Native/runtime changes and below-minimum launchers always request a complete native download.
    @Test void minimumAndNativeMigrationRefuseJarShortcut() throws Exception {
        Path candidate = jar("candidate.jar", "0.1.2"); SaoSelfUpdate updater = new SaoSelfUpdate(temporary.resolve("data"));
        assertEquals(SaoSelfUpdate.FULL_LAUNCHER_REQUIRED, assertThrows(IOException.class,
                () -> updater.stage(candidate, metadata(candidate, "0.1.2", "0.1.1", false), "0.1.0")).getMessage());
        assertEquals(SaoSelfUpdate.FULL_LAUNCHER_REQUIRED, assertThrows(IOException.class,
                () -> updater.stage(candidate, metadata(candidate, "0.1.2", "0.1.0", true), "0.1.0")).getMessage());
        assertEquals("请把 [NerveGear启动器 0.1.2] 的更新包拖进来", SaoUpdates.status(metadata(candidate, "0.1.2", "0.1.0", false), "0.8", "0.1.0", null).message());
        assertTrue(SaoUpdates.status(metadata(candidate, "0.1.2", "0.1.1", false), "0.8", "0.1.0", null).launcherBlocked());
    }

    /// A single safe JAR wrapper is accepted; duplicate/extra or traversal wrapper payloads are rejected.
    @Test void jarZipWrapperHasStrictLayoutAndExpansionLimit() throws Exception {
        Path bundle = jar("bundle.jar", "0.1.0"); Path candidate = jar("candidate.jar", "0.1.1"); Path wrapper = temporary.resolve("update.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(wrapper))) {
            output.putNextEntry(new ZipEntry("launcher.jar")); Files.copy(candidate, output); output.closeEntry();
        }
        SaoSelfUpdate updater = new SaoSelfUpdate(temporary.resolve("data"));
        SaoSelfUpdate.Selection selection = stage(updater, bundle, wrapper, "0.1.1");
        updater.finish(Objects.requireNonNull(selection.token()), false);
        for (String unsafe : List.of("../launcher.jar", "folder/launcher.jar", "launcher.jar/")) {
            Path bad = temporary.resolve("bad-" + Math.abs(unsafe.hashCode()) + ".zip");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(bad))) {
                output.putNextEntry(new ZipEntry(unsafe)); Files.copy(candidate, output); output.closeEntry();
            }
            assertThrows(IOException.class, () -> updater.stage(bad, metadata(bad, "0.1.1", "0.1.0", false), "0.1.0"));
        }
        Path extra = temporary.resolve("extra.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(extra))) {
            output.putNextEntry(new ZipEntry("launcher.jar")); Files.copy(candidate, output); output.closeEntry();
            output.putNextEntry(new ZipEntry("run.exe")); output.write(0); output.closeEntry();
        }
        assertThrows(IOException.class, () -> updater.stage(extra, metadata(extra, "0.1.1", "0.1.0", false), "0.1.0"));
        Path bomb = temporary.resolve("oversized.zip"); byte[] bytes = Files.readAllBytes(wrapper);
        for (int index = 0; index + 28 < bytes.length; index++) {
            if (bytes[index] == 0x50 && bytes[index + 1] == 0x4b && bytes[index + 2] == 0x01 && bytes[index + 3] == 0x02) {
                java.nio.ByteBuffer.wrap(bytes, index + 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt((int) (SaoSelfUpdate.MAX_JAR_BYTES + 1)); break;
            }
        }
        Files.write(bomb, bytes);
        assertThrows(IOException.class, () -> updater.stage(bomb, metadata(bomb, "0.1.1", "0.1.0", false), "0.1.0"));
    }

    /// The same drag entry used by pack imports stages a verified JAR and enforces the minimum gate.
    @Test void engineDropStagesJarAndBelowMinimumRequestsCompleteLauncher() throws Exception {
        String futureVersion = "99.0.0.1";
        Path candidate = jar("candidate.jar", futureVersion);
        for (boolean blocked : List.of(false, true)) {
            Path root = Files.createDirectories(temporary.resolve(blocked ? "blocked" : "optional"));
            SaoData.VersionMetadata metadata = metadata(candidate, futureVersion, blocked ? futureVersion : "0.1.0", false);
            JsonObject document = new Gson().toJsonTree(metadata).getAsJsonObject();
            JsonObject launcher = document.getAsJsonObject("launcher"); launcher.remove("artifact");
            launcher.addProperty("file", candidate.getFileName().toString()); launcher.addProperty("size", Files.size(candidate)); launcher.addProperty("sha256", SaoUpdates.sha256(candidate));
            SaoReleaseTestSupport.cacheVersion(root, document.toString());
            try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
                java.util.concurrent.CompletableFuture<Void> result = engine.acceptDroppedFiles(List.of(candidate));
                if (blocked) {
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(engine.updateStatus().get().launcherBlocked()); assertFalse(engine.launcherPermitted());
                    assertFalse(engine.launcherUpdate().get().restartRequired());
                    assertTrue(Objects.requireNonNull(engine.lastError().get()).contains("完整启动器"));
                } else {
                    result.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(engine.launcherUpdate().get().restartRequired()); assertEquals(futureVersion, engine.launcherUpdate().get().version());
                    assertFalse(engine.updateStatus().get().launcherBlocked());
                }
            }
        }
        assertTrue(Files.exists(candidate));
    }

    /// Opt-in accepts unregistered test launcher updates locally and reuses native restart rollback.
    @Test void betaLauncherDropRequiresOptInAndRollsBack() throws Exception {
        String version = "99.0.0.1";
        Path candidate = jar("NerveGear-Launcher-Beta-" + version + ".jar", version);
        Path bundled = jar("installed.jar", Metadata.VERSION);
        Path root = temporary.resolve("beta-launcher");
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            assertFalse(engine.betaQuickUpdates());
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.acceptDroppedFiles(List.of(candidate)).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(engine.launcherUpdate().get().restartRequired());
            engine.setBetaQuickUpdates(true);
        }
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            assertTrue(engine.betaQuickUpdates());
            engine.acceptDroppedFiles(List.of(candidate)).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(engine.launcherUpdate().get().restartRequired());
            assertEquals(version, engine.launcherUpdate().get().version());
        }
        var updater = new SaoSelfUpdate(root.resolve("self-update"));
        var selection = updater.select(bundled, Metadata.VERSION);
        assertEquals(SaoUpdates.sha256(candidate), SaoUpdates.sha256(selection.jar()));
        updater.finish(Objects.requireNonNull(selection.token()), false);
        assertEquals(bundled, updater.select(bundled, Metadata.VERSION).jar());
    }

    /// A damaged local state pointer is quarantined and exposes a persistent bundled-fallback message.
    @Test void corruptedStateCannotPreventBundledStartup() throws Exception {
        Path bundle = jar("bundle.jar", "0.1.0"); Path root = Files.createDirectories(temporary.resolve("data"));
        Files.writeString(root.resolve("state.json"), "{invalid");
        SaoSelfUpdate updater = new SaoSelfUpdate(root);
        assertEquals(bundle, updater.select(bundle, "0.1.0").jar()); assertTrue(updater.status().message().contains("状态损坏"));
        try (var files = Files.list(root)) { assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("state-corrupt-")).count()); }
    }

    /// Flat launcher identity fields are all-or-none and optional booleans never coerce strings or numbers.
    @Test void launcherMetadataRejectsPartialIdentityAndBooleanCoercions() throws Exception {
        Path jar = jar("candidate.jar", "0.1.1"); SaoData.VersionMetadata metadata = metadata(jar, "0.1.1", "0.1.0", false);
        JsonObject document = new JsonObject(); document.addProperty("schema", 2); document.addProperty("sequence", 0); document.addProperty("quark", metadata.quark());
        document.add("pack", new Gson().toJsonTree(metadata.pack())); document.add("updates", new JsonArray());
        JsonObject launcher = new JsonObject(); launcher.addProperty("latest", "0.1.1"); launcher.addProperty("min", "0.1.0"); launcher.addProperty("file", "candidate.jar"); document.add("launcher", launcher);
        assertThrows(IOException.class, () -> SaoUpdates.parse(document.toString()));
        launcher.remove("file");
        for (JsonElement invalid : List.of(new JsonPrimitive("true"), new JsonPrimitive(1), JsonNull.INSTANCE)) {
            launcher.add("nativeRequired", invalid); assertThrows(IOException.class, () -> SaoUpdates.parse(document.toString()));
        }
    }
}
