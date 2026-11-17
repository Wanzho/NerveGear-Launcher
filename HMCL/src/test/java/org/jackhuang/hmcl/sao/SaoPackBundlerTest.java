/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/// Reopens saved ZIPs to verify content, index identities, attribution and failure cleanup.
@NotNullByDefault
class SaoPackBundlerTest {
    /// Private fixture files and staging.
    @TempDir Path temporary;
    /// Known unchanged jar bytes.
    private static final String PAYLOAD = "abc";
    /// Identity shared by the original index, allow list and generated override.
    private static final String SHA1 = "a9993e364706816aba3e25717850c26c9cd0d89d";

    /// Only the approved index row is removed; all other fields, hashes and original member bytes survive.
    @Test void rewritesOnlyApprovedEntriesAndKeepsHashesAndNotice() throws Exception {
        Path original = archive(); String before = SaoUpdates.sha256(original);
        Path output = temporary.resolve("bundled.zip");
        SaoPackBundler.Result result = SaoPackBundler.bundle(original, approvals("allowed with attribution", "Author copyright; MIT text", SHA1),
                output, (file, path) -> Files.writeString(path, PAYLOAD));
        assertEquals(before, SaoUpdates.sha256(original));
        assertEquals(List.of("mods/allowed.jar"), result.bundledPaths()); assertEquals(1, result.remainingExternalFiles());
        try (ZipFile source = new ZipFile(original.toFile()); ZipFile bundled = new ZipFile(output.toFile())) {
            JsonObject oldIndex = index(source), newIndex = index(bundled);
            assertEquals(oldIndex.get("dependencies"), newIndex.get("dependencies"));
            assertEquals(oldIndex.get("customField"), newIndex.get("customField"));
            assertEquals(oldIndex.getAsJsonArray("files").get(1), newIndex.getAsJsonArray("files").get(0));
            assertArrayEquals(bytes(source, "overrides/config/example.txt"), bytes(bundled, "overrides/config/example.txt"));
            assertArrayEquals(bytes(source, "readme.txt"), bytes(bundled, "readme.txt"));
            byte @Unmodifiable [] jar = bytes(bundled, "overrides/mods/allowed.jar");
            assertEquals(SHA1, SaoPackArchive.sha1(new ByteArrayInputStream(jar)));
            assertTrue(new String(bytes(bundled, SaoPackBundler.NOTICE_ENTRY), StandardCharsets.UTF_8).contains("Author copyright; MIT text"));
        }
        SaoPackManifest manifest = SaoPackArchive.open(output).manifest();
        assertEquals(SHA1, manifest.files().stream().filter(file -> file.path().equals("mods/allowed.jar")).findFirst().orElseThrow().sha1());
        assertClean();
    }

    /// Missing permission, attribution or an exact reviewed identity aborts before any network request.
    @Test void rejectsUnknownPermissionMissingAttributionAndChangedHash() throws Exception {
        Path source = archive(); AtomicInteger requests = new AtomicInteger();
        for (String verdict : List.of("unknown", "not allowed", "allowed with attribution")) {
            Path target = temporary.resolve(UUID.randomUUID() + ".zip");
            assertThrows(IOException.class, () -> SaoPackBundler.bundle(source, approvals(verdict, "", SHA1), target,
                    (file, path) -> requests.incrementAndGet()));
            assertFalse(Files.exists(target));
        }
        assertThrows(IOException.class, () -> SaoPackBundler.bundle(source, approvals("allowed", "", "0".repeat(40)),
                temporary.resolve("bad-hash.zip"), (file, path) -> requests.incrementAndGet()));
        assertEquals(0, requests.get()); assertClean();
    }

    /// Corresponding Source and licence files are included only with explicit paths and exact SHA-256 approval.
    @Test void bundlesVerifiedSourceAndRejectsChangedSource() throws Exception {
        Path source = archive(); Path approval = approvals("allowed with attribution", "Source: licenses/example/source.zip", SHA1);
        Path sources = temporary.resolve("source.zip"); Files.writeString(sources, "exact matching source archive fixture");
        JsonObject root = JsonParser.parseString(Files.readString(approval)).getAsJsonObject();
        JsonObject extra = new JsonObject(); extra.addProperty("path", "overrides/licenses/example/source.zip");
        extra.addProperty("localPath", sources.toString()); extra.addProperty("sha256", SaoUpdates.sha256(sources));
        JsonArray extras = new JsonArray(); extras.add(extra); root.add("extraFiles", extras); Files.writeString(approval, root.toString());
        Path output = temporary.resolve("with-source.zip");
        SaoPackBundler.bundle(source, approval, output, (file, path) -> Files.writeString(path, PAYLOAD));
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertArrayEquals(Files.readAllBytes(sources), bytes(zip, "overrides/licenses/example/source.zip"));
        }
        Files.writeString(sources, "changed source");
        assertThrows(IOException.class, () -> SaoPackBundler.bundle(source, approval, temporary.resolve("changed.zip"),
                (file, path) -> fail("Reject changed source before download")));
        assertFalse(Files.exists(temporary.resolve("changed.zip"))); assertClean();
    }

    /// A corrupt download cannot escape into an output archive; existing output/source files are protected.
    @Test void corruptPayloadLeavesNoOutputOrPartialsAndRefusesOverwrite() throws Exception {
        Path source = archive(); Path target = temporary.resolve("bad.zip");
        Path allow = approvals("allowed", "permission", SHA1);
        assertThrows(IOException.class, () -> SaoPackBundler.bundle(source, allow, target, (file, path) -> Files.writeString(path, "BAD")));
        assertFalse(Files.exists(target)); assertClean();
        Files.writeString(target, "existing output");
        assertThrows(IOException.class, () -> SaoPackBundler.bundle(source, allow, target, (file, path) -> fail("No download expected")));
        assertEquals("existing output", Files.readString(target));
        assertThrows(IOException.class, () -> SaoPackBundler.bundle(source, allow, source, (file, path) -> fail("No download expected")));
    }

    /// Writes a valid two-row index, an override and an untouched root member.
    private Path archive() throws IOException {
        JsonObject index = new JsonObject(); index.addProperty("name", "SAO OATH0.8"); index.addProperty("versionId", "1.0.0");
        index.addProperty("customField", "kept");
        index.add("dependencies", JsonParser.parseString("{\"minecraft\":\"1.20.1\",\"neoforge\":\"47.1.106\"}"));
        JsonArray files = new JsonArray();
        for (String name : List.of("allowed", "remote")) {
            JsonObject file = new JsonObject(); file.addProperty("path", "mods/" + name + ".jar"); file.addProperty("fileSize", 3);
            JsonObject hashes = new JsonObject(); hashes.addProperty("sha1", SHA1); hashes.addProperty("sha512", "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"); file.add("hashes", hashes);
            JsonArray urls = new JsonArray(); urls.add("https://mediafilez.forgecdn.net/files/1/2/" + name + ".jar"); file.add("downloads", urls);
            files.add(file);
        }
        index.add("files", files);
        Path path = Files.createTempFile(temporary, "source-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            member(zip, "modrinth.index.json", index.toString()); member(zip, "overrides/config/example.txt", "unchanged config");
            member(zip, "readme.txt", "unchanged root data");
        }
        return path;
    }

    /// Approval requires a verdict and exact identity; attribution text may be empty only for unconditional permission.
    private Path approvals(String verdict, String notice, String sha1) throws IOException {
        JsonObject approval = new JsonObject(); approval.addProperty("path", "mods/allowed.jar"); approval.addProperty("sha1", sha1);
        approval.addProperty("verdict", verdict); approval.addProperty("notice", notice);
        JsonArray files = new JsonArray(); files.add(approval); JsonObject root = new JsonObject(); root.add("files", files);
        Path path = Files.createTempFile(temporary, "allow-", ".json"); Files.writeString(path, root.toString()); return path;
    }

    /// Adds a UTF-8 member to a fixture archive.
    private static void member(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    /// Reads the index from the actual saved archive.
    private static JsonObject index(ZipFile zip) throws IOException {
        return JsonParser.parseString(new String(bytes(zip, "modrinth.index.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /// Reads one named member and fails clearly if it disappeared during repackaging.
    private static byte @Unmodifiable [] bytes(ZipFile zip, String name) throws IOException {
        @Nullable ZipEntry entry = zip.getEntry(name); assertNotNull(entry);
        try (InputStream input = zip.getInputStream(entry)) { return input.readAllBytes(); }
    }

    /// Both successful and failed runs must remove private ZIP and payload staging.
    private void assertClean() throws IOException {
        try (var paths = Files.walk(temporary)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".sao-bundle-")));
        }
    }
}
