/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises real import, accepted inventory planning, local mod transfers and independent trust rejection.
@NotNullByDefault
final class SaoOfflineBundleTest {
    /// Each test has an isolated launcher data root.
    @TempDir Path temporary;

    /// A two-mod pack installs without resolving any network candidate, including after a cache was already used.
    @Test void importsAndInstallsTwoIndexModsWithoutHttp() throws Exception {
        Path root = temporary.resolve("launcher");
        SaoExistingMinecraft.Cache cache = new SaoExistingMinecraft.Cache(root.resolve("dependencies"), List::of);
        assertTrue(cache.checkExistentFile(null, "SHA-1", "0".repeat(40)).isEmpty());
        byte[] a = "first mod".getBytes(), b = "second mod".getBytes(), asset = "sound".getBytes();
        JsonArray remote = new JsonArray();
        remote.add(mod("mods/a.jar", a)); remote.add(mod("mods/b.jar", b));
        JsonObject modrinth = new JsonObject();
        modrinth.addProperty("name", "SAO OATH0.8.1"); modrinth.addProperty("versionId", "0.8.1");
        modrinth.add("dependencies", JsonParser.parseString("{\"minecraft\":\"1.20.1\",\"neoforge\":\"47.1.106\"}"));
        modrinth.add("files", remote);
        Path pack = temporary.resolve("pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) { put(zip, "modrinth.index.json", modrinth.toString().getBytes()); }
        String hash = digest("SHA-1", asset);
        Map<String, byte[]> data = new LinkedHashMap<>();
        data.put("pack/pack.zip", Files.readAllBytes(pack));
        data.put("mods-cache/" + digest("SHA-1", a), a); data.put("mods-cache/" + digest("SHA-1", b), b);
        data.put("assets/objects/" + hash.substring(0, 2) + "/" + hash, asset);
        Path bundle = bundle(data, "");
        assertTrue(SaoOfflineBundle.recognizes(bundle));
        AtomicInteger updates = new AtomicInteger();
        SaoOfflineBundle.Imported imported = SaoOfflineBundle.unpack(bundle, root, (label, fraction) -> updates.incrementAndGet());
        assertTrue(imported.damaged().isEmpty()); assertEquals(4, updates.get());
        assertTrue(cache.checkExistentFile(null, "SHA-1", hash).isPresent());
        SaoPackArchive archive = SaoPackArchive.open(Objects.requireNonNull(imported.pack()));
        assertEquals(2, archive.manifest().files().size());
        assertNotNull(archive.manifest().files().get(0).sha512());
        try (SaoModDownloads downloader = new SaoModDownloads(urls -> { fail("HTTP candidate requested: " + urls); throw new AssertionError(); }, p -> { }, t -> {
            assertEquals(0, t.networkBytes()); assertEquals("offline-bundle", t.finalHost());
        }, Set.of())) {
            downloader.useOfflineRoot(root);
            Path instance = root.resolve("minecraft/versions/sao-oath");
            SaoPackInstaller installer = new SaoPackInstaller(instance, downloader, Map.of(), (label, fraction) -> { });
            SaoPackInstaller.Result result = installer.apply(archive, false);
            assertEquals(2, result.written());
            assertArrayEquals(a, Files.readAllBytes(instance.resolve("mods/a.jar")));
            assertArrayEquals(b, Files.readAllBytes(instance.resolve("mods/b.jar")));
        }
    }

    /// Damaged bytes are skipped, named and never exposed at a final dependency path.
    @Test void skipsDamageAndRejectsSelfAssertedExecutableTrust() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack/pack.zip", "dummy".getBytes()); files.put("mods-cache/bad", "correct".getBytes());
        files.put("versions/sao-oath/sao-oath.json", "{\"mainClass\":\"hostile\"}".getBytes());
        Path root = temporary.resolve("launcher");
        SaoOfflineBundle.Imported imported = SaoOfflineBundle.unpack(bundle(files, "mods-cache/bad"), root, (label, fraction) -> { });
        assertEquals(List.of("mods-cache/bad"), imported.damaged());
        assertFalse(Files.exists(imported.directory().resolve("mods-cache/bad")));
        SaoOfflineBundle.prepare(root);
        assertFalse(Files.exists(root.resolve("minecraft/versions/sao-oath/sao-oath.json")), "Bundle's own hashes cannot authorize a launch manifest");
    }

    /// ZIP names cannot target the parent, a Windows stream/device, a symlink or a duplicate alias.
    @Test void refusesUnsafePathsAndLinkedExtractionRoot() throws Exception {
        for (String name : List.of("../escape", "C:/escape", "libs\\escape", "con.jar", "a./b", "a:b"))
            assertThrows(IOException.class, () -> SaoOfflineBundle.safe(temporary, name));
        Path root = Files.createDirectories(temporary.resolve("launcher/dependencies"));
        Files.createSymbolicLink(root.resolve("offline-bundle"), Files.createDirectory(temporary.resolve("outside")));
        assertThrows(IOException.class, () -> SaoOfflineBundle.unpack(bundle(Map.of("pack/pack.zip", "x".getBytes()), ""), root.getParent(), (l, f) -> { }));
    }

    /// Stronger index hashes remain mandatory even when SHA-1 and size happen to match.
    @Test void verifiesSha512Independently() throws Exception {
        Path file = Files.writeString(temporary.resolve("mod"), "payload");
        SaoPackManifest.PackFile expected = new SaoPackManifest.PackFile("mods/a.jar", DigestUtils.digestToString("SHA-1", file), 7,
                null, List.of("https://invalid.example/a"), "0".repeat(128));
        assertFalse(SaoPackFiles.matches(file, expected));
    }

    /// Creates a Modrinth index member with both published hashes.
    private static JsonObject mod(String path, byte[] data) throws Exception {
        JsonObject file = new JsonObject(); file.addProperty("path", path); file.addProperty("fileSize", data.length);
        JsonObject hashes = new JsonObject(); hashes.addProperty("sha1", digest("SHA-1", data)); hashes.addProperty("sha512", digest("SHA-512", data));
        file.add("hashes", hashes); JsonArray urls = new JsonArray(); urls.add("https://invalid.example/" + path); file.add("downloads", urls);
        return file;
    }

    /// Writes a fixture whose optional damaged member has the original size but different bytes.
    private Path bundle(Map<String, byte[]> files, String damaged) throws Exception {
        JsonObject index = new JsonObject(); index.addProperty("format", 1); index.addProperty("pack", "pack/pack.zip");
        JsonArray entries = new JsonArray();
        for (var file : files.entrySet()) {
            JsonObject entry = new JsonObject(); entry.addProperty("path", file.getKey()); entry.addProperty("size", file.getValue().length);
            entry.addProperty("sha1", digest("SHA-1", file.getValue())); entry.addProperty("sha256", digest("SHA-256", file.getValue()));
            entry.addProperty("source", "synthetic fixture"); entries.add(entry);
        }
        index.add("files", entries);
        Path archive = temporary.resolve(UUID.randomUUID() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, SaoOfflineBundle.INDEX, index.toString().getBytes());
            for (var file : files.entrySet()) put(zip, file.getKey(), file.getKey().equals(damaged) ? new byte[file.getValue().length] : file.getValue());
        }
        return archive;
    }

    /// Computes a fixture digest without any external service.
    private static String digest(String algorithm, byte[] bytes) throws Exception { return DigestUtils.digestToString(algorithm, bytes); }

    /// Adds exactly one named member.
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
}
