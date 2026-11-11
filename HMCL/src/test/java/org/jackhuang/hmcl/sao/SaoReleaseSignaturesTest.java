/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Offline release-authentication, rollback, cache, CLI and real service-entry regressions using only throwaway keys.
@NotNullByDefault
class SaoReleaseSignaturesTest {
    /// Disposable document, state and private test-key files.
    @TempDir Path temporary;

    /// Complete minimal schema-2 version policy with an independently supplied sequence.
    private static String version(long sequence) {
        return "{\"schema\":2,\"sequence\":" + sequence + ",\"quark\":\"https://pan.quark.cn/s/fixture\","
                + "\"pack\":{\"version\":\"0.8\",\"file\":\"pack.zip\",\"size\":1,\"sha256\":\"" + "0".repeat(64)
                + "\"},\"updates\":[],\"launcher\":{\"latest\":\"0.1.1\",\"min\":\"0.1.0\"}}";
    }

    /// Minimal schema-2 announcements with a separately supplied sequence and exact title.
    private static String news(long sequence, String title) {
        return "{\"schema\":2,\"sequence\":" + sequence + ",\"news\":[{\"kind\":\"NOTICE\",\"title\":\"" + title + "\"}]}";
    }

    /// Returns exact UTF-8 bytes without normalizing whitespace, Unicode, line endings or object order.
    private static byte @Unmodifiable [] bytes(String document) { return document.getBytes(StandardCharsets.UTF_8); }

    /// Both document kinds accept authentic bytes and reject a wrong key or a one-byte change to either input.
    @Test void authenticWrongKeyAndByteFlipsForBothDocuments() throws Exception {
        for (String document : List.of(version(3), news(3, "Original"))) {
            byte @Unmodifiable [] original = bytes(document), signature = SaoReleaseTestSupport.sign(document);
            assertDoesNotThrow(() -> SaoReleaseTestSupport.TRUST.verify(original, signature));
            assertThrows(IOException.class, () -> SaoReleaseSignatures.PRODUCTION.verify(original, signature));
            assertThrows(IOException.class, () -> SaoReleaseTestSupport.TRUST.verify(original,
                    SaoReleaseTestSupport.sign(original, SaoReleaseTestSupport.key())));
            byte[] changed = original.clone(); changed[changed.length - 1] ^= 1;
            assertThrows(IOException.class, () -> SaoReleaseTestSupport.TRUST.verify(changed, signature));
            byte[] changedSignature = Base64.getDecoder().decode(signature); changedSignature[0] ^= 1;
            assertThrows(IOException.class, () -> SaoReleaseTestSupport.TRUST.verify(original, Base64.getEncoder().encode(changedSignature)));
            assertThrows(IOException.class, () -> SaoReleaseTestSupport.TRUST.verify(bytes(document + "\n"), signature));
            for (byte[] invalid : List.of(new byte[0], bytes("!".repeat(88)), bytes(new String(signature, StandardCharsets.US_ASCII) + "\n"), new byte[129]))
                assertThrows(IOException.class, () -> SaoReleaseTestSupport.TRUST.verify(original, invalid));
        }
    }

    /// Listed rotation keys are interchangeable; a third otherwise valid Ed25519 signature remains untrusted.
    @Test void rotationAcceptsEveryListedKeyAndRejectsUnknownKeys() throws Exception {
        KeyPair first = SaoReleaseTestSupport.key(), second = SaoReleaseTestSupport.key(), third = SaoReleaseTestSupport.key();
        SaoReleaseSignatures rotation = new SaoReleaseSignatures(List.of(first.getPublic(), second.getPublic()));
        for (String document : List.of(version(0), news(0, "Rotation"))) {
            byte @Unmodifiable [] raw = bytes(document);
            rotation.verify(raw, SaoReleaseTestSupport.sign(raw, first)); rotation.verify(raw, SaoReleaseTestSupport.sign(raw, second));
            assertThrows(IOException.class, () -> rotation.verify(raw, SaoReleaseTestSupport.sign(raw, third)));
        }
    }

    /// Required sequences have an exact nonnegative integer grammar, even when malformed documents are correctly signed.
    @Test void strictSignedSchemasRejectMissingAndMalformedSequences() throws Exception {
        for (SaoReleaseState.Kind kind : SaoReleaseState.Kind.values()) {
            String valid = kind == SaoReleaseState.Kind.VERSION ? version(0) : news(0, "Strict");
            for (String malformed : List.of(valid.replace("\"sequence\":0,", ""),
                    valid.replace("\"sequence\":0", "\"sequence\":-1"), valid.replace("\"sequence\":0", "\"sequence\":1.0"),
                    valid.replace("\"sequence\":0", "\"sequence\":1e0"), valid.replace("\"sequence\":0", "\"sequence\":\"1\""),
                    valid.replace("\"sequence\":0", "\"sequence\":null"), valid.replace("\"sequence\":0", "\"sequence\":true"),
                    valid.replace("\"sequence\":0", "\"sequence\":9223372036854775808"),
                    valid.replace("\"sequence\":0", "\"sequence\":0,\"sequence\":1"),
                    valid.replace("\"schema\":2", "\"schema\":1"), valid + "{}", "//comment\n" + valid)) {
                SaoReleaseState state = new SaoReleaseState(temporary, SaoReleaseTestSupport.TRUST);
                assertThrows(IOException.class, () -> state.accept(kind, bytes(malformed), SaoReleaseTestSupport.sign(malformed)));
                assertFalse(Files.exists(temporary.resolve(SaoReleaseState.NAME)));
            }
        }
        assertThrows(IOException.class, () -> SaoOnlineData.parseNews("{\"news\":[]}"));
        for (String malformed : List.of(news(0, "Strict").replace("\"Strict\"", "1"), news(0, "Strict").replace("\"title\":", "\"heading\":")))
            assertThrows(IOException.class, () -> SaoOnlineData.parseNews(malformed));
    }

    /// Independent high-water marks survive restart, reject lower sequences, and permit identical equal-sequence refreshes.
    @Test void independentHighWaterRejectsRollbackAndAllowsEqualAfterRestart() throws Exception {
        SaoReleaseState state = new SaoReleaseState(temporary, SaoReleaseTestSupport.TRUST);
        state.accept(SaoReleaseState.Kind.NEWS, bytes(news(50, "First")), SaoReleaseTestSupport.sign(news(50, "First")));
        state.accept(SaoReleaseState.Kind.VERSION, bytes(version(4)), SaoReleaseTestSupport.sign(version(4)));
        SaoReleaseState restarted = new SaoReleaseState(temporary, SaoReleaseTestSupport.TRUST);
        assertThrows(IOException.class, () -> restarted.accept(SaoReleaseState.Kind.VERSION, bytes(version(3)), SaoReleaseTestSupport.sign(version(3))));
        assertThrows(IOException.class, () -> restarted.accept(SaoReleaseState.Kind.NEWS, bytes(news(49, "Old")), SaoReleaseTestSupport.sign(news(49, "Old"))));
        assertEquals(4, restarted.accept(SaoReleaseState.Kind.VERSION, bytes(version(4)), SaoReleaseTestSupport.sign(version(4))));
        // Equal sequences refresh only the identical document; changed content must raise the sequence (B-5).
        assertThrows(IOException.class, () -> restarted.accept(SaoReleaseState.Kind.NEWS, bytes(news(50, "Equal")), SaoReleaseTestSupport.sign(news(50, "Equal"))));
        assertEquals(50, restarted.accept(SaoReleaseState.Kind.NEWS, bytes(news(50, "First")), SaoReleaseTestSupport.sign(news(50, "First"))));
        assertArrayEquals(bytes(news(50, "First")), restarted.cached(SaoReleaseState.Kind.NEWS));
        assertArrayEquals(bytes(version(4)), restarted.cached(SaoReleaseState.Kind.VERSION));
    }

    /// Concurrent store objects cannot let an older accepted document overwrite a newer high-water transaction.
    @Test void concurrentWritersSerializeHighWaterAndPreserveBothKinds() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int sequence = 0; sequence < 20; sequence++) {
                int value = sequence;
                futures.add(workers.submit(() -> {
                    try {
                        SaoReleaseState state = new SaoReleaseState(temporary, SaoReleaseTestSupport.TRUST);
                        state.accept(SaoReleaseState.Kind.VERSION, bytes(version(value)), SaoReleaseTestSupport.sign(version(value)));
                    } catch (IOException rollback) { /* Racing lower sequences are explicitly rejected. */ }
                }));
            }
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            SaoReleaseState restarted = new SaoReleaseState(temporary, SaoReleaseTestSupport.TRUST);
            assertEquals(19, SaoUpdates.parse(SaoReleaseSignatures.utf8(Objects.requireNonNull(restarted.cached(SaoReleaseState.Kind.VERSION)))).sequence());
            restarted.accept(SaoReleaseState.Kind.NEWS, bytes(news(0, "Independent")), SaoReleaseTestSupport.sign(news(0, "Independent")));
            assertThrows(IOException.class, () -> restarted.accept(SaoReleaseState.Kind.VERSION, bytes(version(18)), SaoReleaseTestSupport.sign(version(18))));
        } finally { workers.shutdownNow(); }
    }

    /// A cache entry that no longer verifies is dropped but keeps its sequence floor: older documents stay refused, the
    /// same or newer ones are accepted again. An unreadable transaction is set aside, keeping only legible high-water marks.
    @Test void alteredCachesAndCorruptStateRecoverWithoutLoweringTheFloor() throws Exception {
        for (SaoReleaseState.Kind kind : SaoReleaseState.Kind.values()) {
            Path root = temporary.resolve(kind.field); String original = kind == SaoReleaseState.Kind.VERSION ? version(8) : news(8, "Good");
            String older = kind == SaoReleaseState.Kind.VERSION ? version(7) : news(7, "Old");
            SaoReleaseState state = new SaoReleaseState(root, SaoReleaseTestSupport.TRUST);
            state.accept(kind, bytes(original), SaoReleaseTestSupport.sign(original));
            Path file = root.resolve(SaoReleaseState.NAME);
            String good = Files.readString(file);
            JsonObject damaged = JsonParser.parseString(good).getAsJsonObject();
            damaged.getAsJsonObject(kind.field).addProperty("document", Base64.getEncoder().encodeToString(bytes(original + " ")));
            Files.writeString(file, damaged.toString());
            try (SaoOnlineData online = new SaoOnlineData(root, SaoReleaseTestSupport.TRUST)) {
                assertNull(online.cachedVersion()); assertTrue(online.cached().isEmpty());
            }
            assertNull(state.cached(kind));
            assertThrows(IOException.class, () -> state.accept(kind, bytes(older), SaoReleaseTestSupport.sign(older)));
            assertEquals(8, state.accept(kind, bytes(original), SaoReleaseTestSupport.sign(original)));
            damaged = JsonParser.parseString(good).getAsJsonObject(); damaged.getAsJsonObject(kind.field).addProperty("highWater", 0);
            Files.writeString(file, damaged.toString());
            assertNull(state.cached(kind));
            assertThrows(IOException.class, () -> state.accept(kind, bytes(older), SaoReleaseTestSupport.sign(older)));
            // A torn write keeps the high-water still legible in its text.
            Files.writeString(file, good.substring(0, good.indexOf("\"document\"") + 20));
            assertNull(state.cached(kind));
            assertThrows(IOException.class, () -> state.accept(kind, bytes(older), SaoReleaseTestSupport.sign(older)));
            Files.writeString(file, "{corrupt");
            assertNull(state.cached(kind));
            assertEquals(8, state.accept(kind, bytes(original), SaoReleaseTestSupport.sign(original)));
            assertArrayEquals(bytes(original), state.cached(kind));
        }
    }

    /// Missing, foreign-document and unavailable signatures leave exact accepted bytes and their high-water untouched.
    @Test void unavailableMissingOrDifferentDocumentSignatureRetainsLastGood() throws Exception {
        for (SaoReleaseState.Kind kind : SaoReleaseState.Kind.values()) {
            Path root = temporary.resolve(kind.field); String good = kind == SaoReleaseState.Kind.VERSION ? version(7) : news(7, "Good");
            SaoReleaseState state = new SaoReleaseState(root, SaoReleaseTestSupport.TRUST); state.accept(kind, bytes(good), SaoReleaseTestSupport.sign(good));
            byte @Unmodifiable [] prior = Files.readAllBytes(root.resolve(SaoReleaseState.NAME));
            for (int mode = 0; mode < 4; mode++) {
                int failure = mode;
                try (SaoOnlineData online = SaoReleaseTestSupport.online(root, (request, limit) -> {
                    if (!request.uri().getPath().endsWith(".sig")) return new SaoOnlineData.Response(200, null, bytes(good));
                    if (failure == 0) return new SaoOnlineData.Response(404, null, new byte[0]);
                    if (failure == 1) throw new IOException("Offline signature");
                    return new SaoOnlineData.Response(200, null, failure == 2 ? SaoReleaseTestSupport.sign(news(7, "Another document")) : bytes("invalid"));
                })) {
                    assertThrows(Exception.class, () -> {
                        if (kind == SaoReleaseState.Kind.VERSION) online.fetchVersion(SaoUpdates.VERSION_URL); else online.fetch(SaoUpdates.NEWS_URL);
                    });
                    if (kind == SaoReleaseState.Kind.VERSION) assertEquals(7, Objects.requireNonNull(online.cachedVersion()).sequence());
                    else assertEquals("Good", online.cached().get(0).title());
                }
                assertArrayEquals(prior, Files.readAllBytes(root.resolve(SaoReleaseState.NAME)));
            }
        }
    }

    /// JSON and its original-endpoint sibling signature each get one allowed redirect within the same shrinking deadline.
    @Test void fetchPairUsesOriginalSiblingAndOneWholeDeadline() throws Exception {
        String document = version(6); List<URI> visited = new ArrayList<>(); List<Duration> remaining = new ArrayList<>();
        try (SaoOnlineData online = SaoReleaseTestSupport.online(temporary, (request, limit) -> {
            visited.add(request.uri()); remaining.add(request.timeout().orElseThrow());
            boolean signature = request.uri().getPath().endsWith(".sig");
            assertEquals(signature ? SaoReleaseSignatures.MAX_SIGNATURE_BYTES : SaoUpdates.MAX_VERSION_BYTES, limit);
            if (request.uri().getHost().equals("gitee.com")) return new SaoOnlineData.Response(302,
                    "https://raw.giteeusercontent.com/release/version.json" + (signature ? ".sig" : ""), new byte[0]);
            return new SaoOnlineData.Response(200, null, signature ? SaoReleaseTestSupport.sign(document) : bytes(document));
        })) {
            assertEquals(6, online.fetchVersion("https://gitee.com/release/version.json?fixture=1").sequence());
        }
        assertEquals(List.of("/release/version.json", "/release/version.json", "/release/version.json.sig", "/release/version.json.sig"), visited.stream().map(URI::getPath).toList());
        assertEquals("fixture=1", visited.get(2).getQuery());
        assertTrue(remaining.get(0).compareTo(Duration.ofSeconds(10)) <= 0);
        for (int index = 1; index < remaining.size(); index++) assertTrue(remaining.get(index).compareTo(remaining.get(index - 1)) < 0);
    }

    /// Fully valid pinned JAR and quick candidates remain unusable from unsigned metadata; signed controls accept both.
    @Test void unsignedMetadataNeverAuthorizesSelfUpdateOrQuickDrops() throws Exception {
        Path jar = launcherJar(), old = pack("old.zip", "0.8", "old"), next = pack("next.zip", "0.8.1", "new");
        Path quick = temporary.resolve("quick.zip"), root = temporary.resolve("launcher");
        SaoData.QuickRelease edge = SaoUpdateBuilder.build(old, next, quick, Map.of(), null, null);
        JsonObject policy = JsonParser.parseString(version(100)).getAsJsonObject();
        JsonObject pack = policy.getAsJsonObject("pack"); pack.addProperty("version", "0.8.1");
        pack.addProperty("file", "next.zip"); pack.addProperty("size", Files.size(next)); pack.addProperty("sha256", SaoUpdates.sha256(next));
        policy.getAsJsonArray("updates").add(new Gson().toJsonTree(edge));
        JsonObject launcher = policy.getAsJsonObject("launcher"); launcher.addProperty("file", "update.jar");
        launcher.addProperty("size", Files.size(jar)); launcher.addProperty("sha256", SaoUpdates.sha256(jar));
        String document = policy.toString(); SaoUpdates.parse(document);
        Path instance;
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(old), false)) {
            await(engine); instance = engine.instanceDir();
        }
        new SaoPackInstaller(instance, (file, target) -> { throw new AssertionError("No network"); }, Map.of(), (label, progress) -> { })
                .apply(SaoPackArchive.open(old), false);
        Files.writeString(root.resolve("online-version.json"), document);
        Files.writeString(root.resolve("online-news.json"), news(100, "Unsigned"));
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoPackArchive.open(old), false)) {
            await(engine);
            assertEquals(SaoPackState.READY, engine.state().get());
            assertNull(engine.updateStatus().get().metadata()); assertFalse(engine.updateStatus().get().launcherBlocked());
            assertEquals(new SaoData.ReleaseVerification(null, null), engine.releaseVerification().get());
            assertTrue(engine.links().stream().noneMatch(link -> link.id().equals("quark")));
            for (Path drop : List.of(jar, quick)) assertThrows(ExecutionException.class, () -> engine.acceptDroppedFiles(List.of(drop)).get(10, TimeUnit.SECONDS));
            assertFalse(engine.launcherUpdate().get().restartRequired()); assertEquals(List.of(), engine.downloadPrompt().get().present());
            assertEquals("old", Files.readString(instance.resolve("mods/fixture.jar")));
            assertFalse(Files.exists(root.resolve("self-update/state.json")));
        }
        SaoReleaseTestSupport.cacheVersion(root, document);
        SaoReleaseTestSupport.cacheNews(root, news(4, "Authenticated"));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, SaoPackArchive.open(old), false)) {
            await(engine);
            assertEquals(new SaoData.ReleaseVerification(100L, 4L), engine.releaseVerification().get());
            engine.acceptDroppedFiles(List.of(jar)).get(10, TimeUnit.SECONDS);
            assertTrue(engine.launcherUpdate().get().restartRequired());
            engine.acceptDroppedFiles(List.of(quick)).get(10, TimeUnit.SECONDS);
            assertEquals("0.8.1", engine.installedVersion().get()); assertEquals("new", Files.readString(instance.resolve("mods/fixture.jar")));
        }
    }

    /// Creates a bounded inert launcher with every entry required by the real JAR validator; no class is executed.
    private Path launcherJar() throws IOException {
        Path path = temporary.resolve("update.jar"); Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "org.jackhuang.hmcl.Main");
        manifest.getMainAttributes().putValue("Implementation-Version", "0.1.1");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            member(output, "assets/hmcl.properties", "hmcl.version=0.1.1\n");
            for (String entry : List.of("org/jackhuang/hmcl/Main.class", "org/jackhuang/hmcl/Launcher.class",
                    "org/jackhuang/hmcl/sao/SaoLauncherBootstrap.class", "org/jackhuang/hmcl/sao/SaoSelfUpdate.class")) member(output, entry, "inert");
        }
        return path;
    }

    /// Creates a fully embedded one-file pack accepted by the real delta builder and preserving installer.
    private Path pack(String name, String version, String content) throws IOException {
        Path path = temporary.resolve(name); byte @Unmodifiable [] payload = bytes(content);
        SaoPackManifest.PackFile file = new SaoPackManifest.PackFile("mods/fixture.jar", SaoPackArchive.sha1(new ByteArrayInputStream(payload)),
                payload.length, "overrides/mods/fixture.jar", List.of());
        SaoPackManifest manifest = new SaoPackManifest(version, "1.20.1", "47.1.106", "", List.of(file), List.of());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            member(output, SaoPackManifest.NAME, manifest.toJson()); member(output, "overrides/mods/fixture.jar", content);
        }
        return path;
    }

    /// Writes one bounded UTF-8 fixture entry.
    private static void member(ZipOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new ZipEntry(name)); output.write(bytes(content)); output.closeEntry();
    }

    /// Waits only for isolated background filesystem discovery and property publication.
    private static void await(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(5); });
    }

    /// CLI signs exact bytes once, refuses overwrite, supports force and never writes a signature from an unknown key.
    @Test void cliSignsVerifiesAndRefusesUnknownOrOverwrittenSignatures() throws Exception {
        Path document = temporary.resolve("version.json"), key = temporary.resolve("throwaway.pem"), unknown = temporary.resolve("unknown.pem");
        Files.writeString(document, version(11)); writeKey(key, SaoReleaseTestSupport.KEY); writeKey(unknown, SaoReleaseTestSupport.key());
        ByteArrayOutputStream result = new ByteArrayOutputStream(); PrintStream output = new PrintStream(result, true, StandardCharsets.UTF_8);
        String[] sign = {"--sign-release", document.toString(), "--key", key.toString()};
        assertEquals(0, SaoReleaseTool.run(sign, output, SaoReleaseTestSupport.TRUST));
        assertTrue(result.toString(StandardCharsets.UTF_8).contains("OK sequence=11"));
        Path signature = temporary.resolve("version.json.sig"); byte @Unmodifiable [] previous = Files.readAllBytes(signature);
        assertEquals(88, previous.length); assertEquals(1, SaoReleaseTool.run(sign, output, SaoReleaseTestSupport.TRUST));
        assertArrayEquals(previous, Files.readAllBytes(signature));
        assertEquals(0, SaoReleaseTool.run(new String[]{"--verify-release", document.toString()}, output, SaoReleaseTestSupport.TRUST));
        assertEquals(0, SaoReleaseTool.run(new String[]{"--sign-release", document.toString(), "--key", key.toString(), "--force"}, output, SaoReleaseTestSupport.TRUST));
        assertEquals(1, SaoReleaseTool.run(new String[]{"--sign-release", document.toString(), "--key", unknown.toString(), "--force"}, output, SaoReleaseTestSupport.TRUST));
        assertArrayEquals(previous, Files.readAllBytes(signature));
        Files.writeString(document, version(12)); assertEquals(1, SaoReleaseTool.run(new String[]{"--verify-release", document.toString()}, output, SaoReleaseTestSupport.TRUST));
        Path news = temporary.resolve("news.json"); Files.writeString(news, news(2, "CLI"));
        assertEquals(0, SaoReleaseTool.run(new String[]{"--sign-release", news.toString(), "--key", key.toString()}, output, SaoReleaseTestSupport.TRUST));
        assertEquals(0, SaoReleaseTool.run(new String[]{"--verify-release", news.toString()}, output, SaoReleaseTestSupport.TRUST));
        Files.delete(signature); Files.writeString(document, version(11));
        assertEquals(1, SaoReleaseTool.run(sign, output, SaoReleaseSignatures.PRODUCTION)); assertFalse(Files.exists(signature));
    }

    /// Writes only ephemeral test private keys, never reading the protected production key location.
    private static void writeKey(Path path, KeyPair key) throws IOException {
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(key.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
    }

    /// Unsigned community documents cannot expose a Quark action under an alternate ID or omit the signed canonical action.
    @Test void communityQuarkAlwaysComesFromSignedPolicy() {
        List<SaoData.CommunityLink> untrusted = List.of(new SaoData.CommunityLink("download", "下载", "https://pan.quark.cn/s/untrusted"),
                new SaoData.CommunityLink("quark", "伪造", "https://example.org/unknown"), new SaoData.CommunityLink("site", "网站", "https://example.org"));
        assertEquals(List.of("site"), SaoCommunityLinks.withQuark(untrusted, null).stream().map(SaoData.CommunityLink::id).toList());
        var trusted = SaoCommunityLinks.withQuark(untrusted, "https://pan.quark.cn/s/signed");
        assertEquals(List.of("site", "quark"), trusted.stream().map(SaoData.CommunityLink::id).toList());
        assertEquals("https://pan.quark.cn/s/signed", trusted.get(1).url());
    }
}
