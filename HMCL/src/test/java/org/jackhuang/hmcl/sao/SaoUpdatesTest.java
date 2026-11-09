/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/// Hermetic update-policy, transport, download and service regressions; no browser, game or public network is used.
@NotNullByDefault
class SaoUpdatesTest {
    /// Disposable cache and download roots.
    @TempDir Path temporary;
    /// Canonical one-byte test archive identity, independent of ZIP parsing.
    private static final String SHA = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";

    /// Produces a complete schema document with exact primitive types.
    private static String document(String minimum, String latest) {
        return "{\"schema\":2,\"quark\":\"" + SaoUpdates.QUARK_URL
                + "\",\"pack\":{\"version\":\"0.10\",\"file\":\"pack.zip\",\"size\":1,\"sha256\":\"" + SHA
                + "\"},\"updates\":[],\"launcher\":{\"latest\":\"" + latest + "\",\"min\":\"" + minimum + "\"}}";
    }

    /// Strict parsing preserves the advertised identity and forbids all schema/type/path/link ambiguities.
    @Test void strictSchemaAcceptsOnlyExactTypesAndFields() throws Exception {
        String valid = document("0.1.0", "0.2.0");
        SaoData.VersionMetadata parsed = SaoUpdates.parse(valid);
        assertEquals("0.10", parsed.pack().version()); assertEquals(1, parsed.pack().size());
        for (String invalid : List.of(
                valid.replace("\"schema\":2", "\"schema\":1"), valid.replace("\"schema\":2", "\"schema\":\"1\""),
                valid.replace("\"schema\":2", "\"schema\":1,\"schema\":1"), valid.replace("\"schema\":2", "\"schema\":1,\"extra\":1"),
                valid.replace("\"size\":1", "\"size\":0"), valid.replace("\"size\":1", "\"size\":1.0"),
                valid.replace("\"size\":1", "\"size\":1e0"), valid.replace("\"size\":1", "\"size\":\"1\""),
                valid.replace("\"size\":1", "\"size\":9223372036854775808"), valid.replace("\"size\":1", "\"size\":true"),
                valid.replace("\"size\":1,", ""), valid.replace("pack.zip", "../pack.zip"), valid.replace("pack.zip", "C:pack.zip"),
                valid.replace("pack.zip", "pack.zip.crdownload"), valid.replace("pack.zip", "CON.zip"),
                valid.replace(SHA, SHA.toUpperCase(Locale.ROOT)), valid.replace(SHA, "1234"),
                valid.replace("\"version\":\"0.10\"", "\"version\":0.10"), valid.replace("0.10", "0.10-beta"),
                valid.replace("0.10", "00.10"), valid.replace("0.10", "1.2.3.4.5"),
                valid.replace("https://pan.quark.cn/", "http://pan.quark.cn/"), valid.replace("https://pan.quark.cn/", "https://pan.quark.cn.evil.invalid/"),
                valid.replace("https://pan.quark.cn/", "https://user@pan.quark.cn/"), valid.replace("https://pan.quark.cn/", "https://pan.quark.cn:8443/"),
                valid.replace("\"launcher\":{", "\"launcher\":null,\"unused\":{"), valid + "{}", "// comment\n" + valid,
                document("0.3", "0.2"))) {
            assertThrows(IOException.class, () -> SaoUpdates.parse(invalid), invalid);
        }
        assertThrows(IOException.class, () -> SaoUpdates.parse(" ".repeat(SaoUpdates.MAX_VERSION_BYTES + 1)));
    }

    /// Numeric ordering is independent of decimal/lexical ordering and supports legacy local receipt labels.
    @Test void comparisonsAndLauncherGateAreIndependentOfPackState() throws Exception {
        assertTrue(SaoUpdates.compare("0.10", "0.8") > 0);
        assertEquals(0, SaoUpdates.compare("0.1", "0.1.0"));
        assertTrue(SaoUpdates.compare("1.0.0.1", "1") > 0);
        SaoData.VersionMetadata metadata = SaoUpdates.parse(document("0.1.0", "0.2.0"));
        SaoData.UpdateStatus blocked = SaoUpdates.status(metadata, "0.10", "0.0.9", "0.2.0");
        assertTrue(blocked.launcherBlocked()); assertFalse(blocked.launcherUpdateAvailable()); assertTrue(blocked.message().contains("启动器版本过低"));
        assertTrue(SaoUpdates.status(metadata, "0.8", "0.1.0", null).launcherUpdateAvailable());
        assertFalse(SaoUpdates.status(metadata, "0.8", "0.1.0", "0.2.0").launcherUpdateAvailable());
        assertFalse(SaoUpdates.status(metadata, "0.11", "0.2.0", null).packUpdateAvailable());
        assertFalse(SaoUpdates.status(null, "0.10", "0.0.1", null).launcherBlocked());
        assertDoesNotThrow(() -> SaoUpdates.status(metadata, "0.8-beta", "0.1.0", null));
    }

    /// The single allowed hop is exact HTTPS Gitee to exact HTTPS raw host; all other destinations are refused.
    @Test void redirectPolicyRejectsForeignAndDowngradedLocations() throws Exception {
        URI source = URI.create(SaoUpdates.NEWS_URL);
        assertEquals("raw.giteeusercontent.com", SaoOnlineData.redirected(source, "https://raw.giteeusercontent.com/news?signature=fixture").getHost());
        for (String target : List.of("https://foreign.invalid/news", "http://raw.giteeusercontent.com/news",
                "https://raw.giteeusercontent.com.evil.invalid/news", "https://user@raw.giteeusercontent.com/news",
                "https://raw.giteeusercontent.com:444/news", "/relative", "https://raw.giteeusercontent.com/news#fragment"))
            assertThrows(IOException.class, () -> SaoOnlineData.redirected(source, target));
        assertThrows(IOException.class, () -> SaoOnlineData.redirected(URI.create("https://foreign.invalid/"), "https://raw.giteeusercontent.com/news"));
        assertThrows(IOException.class, () -> SaoOnlineData.redirected(source, null));
    }

    /// The fetch path follows exactly once, preserves order and refuses to request a forbidden third/foreign hop.
    @Test void fetchFollowsOneTrustedRedirectAndRetainsLastGoodNews() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<URI> visited = new ArrayList<>();
        String news = "{\"news\":[{\"kind\":\"NOTICE\",\"title\":\"真实格式 fixture\"}]}";
        try (SaoOnlineData online = new SaoOnlineData(temporary, (request, limit) -> {
            visited.add(request.uri()); assertTrue(request.timeout().orElseThrow().compareTo(Duration.ofSeconds(10)) <= 0);
            return requests.incrementAndGet() == 1 ? new SaoOnlineData.Response(302, "https://raw.giteeusercontent.com/news?signature=fixture", new byte[0])
                    : new SaoOnlineData.Response(200, null, news.getBytes(StandardCharsets.UTF_8));
        })) {
            assertEquals("真实格式 fixture", online.fetch(SaoUpdates.NEWS_URL).get(0).title());
            assertEquals(List.of("gitee.com", "raw.giteeusercontent.com"), visited.stream().map(URI::getHost).toList());
        }
        AtomicInteger refused = new AtomicInteger();
        try (SaoOnlineData online = new SaoOnlineData(temporary, (request, limit) -> {
            refused.incrementAndGet(); return new SaoOnlineData.Response(302, "https://foreign.invalid/news", new byte[0]);
        })) {
            assertThrows(IOException.class, () -> online.fetch(SaoUpdates.NEWS_URL)); assertEquals(1, refused.get());
            assertEquals("真实格式 fixture", online.cached().get(0).title());
        }
        AtomicInteger loop = new AtomicInteger();
        try (SaoOnlineData online = new SaoOnlineData(temporary, (request, limit) -> {
            loop.incrementAndGet(); return new SaoOnlineData.Response(302, "https://raw.giteeusercontent.com/again", new byte[0]);
        })) {
            assertThrows(IOException.class, () -> online.fetch(SaoUpdates.NEWS_URL)); assertEquals(2, loop.get());
            assertEquals("真实格式 fixture", online.cached().get(0).title());
        }
    }

    /// Failed schema and oversized responses cannot replace a valid version cache.
    @Test void versionCacheSurvivesInvalidAndOversizedReplacement() throws Exception {
        String valid = document("0.1.0", "0.2.0");
        AtomicReference<String> response = new AtomicReference<>(valid);
        try (SaoOnlineData online = new SaoOnlineData(temporary, (request, limit) -> {
            assertEquals(SaoUpdates.MAX_VERSION_BYTES, limit);
            return new SaoOnlineData.Response(200, null, response.get().getBytes(StandardCharsets.UTF_8));
        })) {
            assertEquals("0.10", online.fetchVersion(SaoUpdates.VERSION_URL).pack().version());
            response.set(valid.replace("\"size\":1", "\"size\":0"));
            assertThrows(IOException.class, () -> online.fetchVersion(SaoUpdates.VERSION_URL));
            response.set("x".repeat(SaoUpdates.MAX_VERSION_BYTES + 1));
            assertThrows(IOException.class, () -> online.fetchVersion(SaoUpdates.VERSION_URL));
            assertEquals("0.10", Objects.requireNonNull(online.cachedVersion()).pack().version());
            assertEquals(valid, Files.readString(temporary.resolve("online-version.json")));
        }
    }

    /// Invalid UTF-8 cannot be silently repaired into a replacement public cache document.
    @Test void malformedUtf8RetainsTheLastGoodVersion() throws Exception {
        String valid = document("0.1", "0.1");
        Files.writeString(temporary.resolve("online-version.json"), valid);
        try (SaoOnlineData online = new SaoOnlineData(temporary, (request, limit) ->
                new SaoOnlineData.Response(200, null, new byte[]{'{', (byte) 0xc3, '(', '}'}))) {
            assertThrows(IOException.class, () -> online.fetchVersion(SaoUpdates.VERSION_URL));
            assertEquals(valid, Files.readString(temporary.resolve("online-version.json")));
        }
    }

    /// The response subscriber actively cancels before buffering beyond the smaller version cap.
    @Test void versionSubscriberCancelsOversizedBodies() {
        AtomicBoolean cancelled = new AtomicBoolean();
        SaoOnlineData.NewsSubscriber subscriber = new SaoOnlineData.NewsSubscriber(SaoUpdates.MAX_VERSION_BYTES);
        subscriber.onSubscribe(new Flow.Subscription() {
            /// Chunks are delivered directly by the fixture.
            @Override public void request(long count) { }
            /// Records cancellation without any network publisher.
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[SaoUpdates.MAX_VERSION_BYTES + 1])));
        assertTrue(cancelled.get()); assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    }

    /// Exact size/name filtering ignores incomplete suffixes, same-sized renamed files and symlinks.
    @Test void downloadsFinderHandlesTemporaryAndLocalizedFolders() throws Exception {
        Path downloads = Files.createDirectories(temporary.resolve("下载"));
        SaoData.PackRelease release = SaoUpdates.parse(document("0.1", "0.1")).pack();
        Files.writeString(downloads.resolve("pack.zip.crdownload"), "x"); Files.writeString(downloads.resolve("other.zip"), "x");
        assertNull(SaoDownloads.find(List.of(downloads), release));
        Path zip = downloads.resolve("pack.zip"); Files.writeString(zip, "xx"); assertNull(SaoDownloads.find(List.of(downloads), release));
        Files.writeString(zip, "x"); assertEquals(zip, SaoDownloads.find(List.of(temporary.resolve("missing"), downloads), release));
        Files.delete(zip); Files.createSymbolicLink(zip, downloads.resolve("other.zip")); assertNull(SaoDownloads.find(List.of(downloads), release));
        Path config = temporary.resolve("user-dirs.dirs"); Files.writeString(config, "XDG_DOWNLOAD_DIR=\"$HOME/下载\"\n");
        assertEquals(List.of(downloads), SaoDownloads.xdg(config, temporary));
        Files.writeString(config, "XDG_DOWNLOAD_DIR=\"$(touch /tmp/forbidden)\"\n"); assertTrue(SaoDownloads.xdg(config, temporary).isEmpty());
    }

    /// Digest and size checks reject both truncation and same-size substitution with the required Chinese message.
    @Test void hashAcceptsExactBytesAndRejectsWrongSizeOrDigest() throws Exception {
        Path zip = temporary.resolve("pack.zip"); Files.writeString(zip, "x");
        SaoData.PackRelease release = SaoUpdates.parse(document("0.1", "0.1")).pack();
        assertEquals(SHA, SaoUpdates.sha256(zip)); assertDoesNotThrow(() -> SaoUpdates.verify(zip, release));
        JsonObject block = JsonParser.parseString(SaoPackHash.block(zip, "0.10")).getAsJsonObject().getAsJsonObject("pack");
        assertEquals("pack.zip", block.get("file").getAsString()); assertEquals(1, block.get("size").getAsLong()); assertEquals(SHA, block.get("sha256").getAsString());
        Files.writeString(zip, "y"); assertEquals(SaoUpdates.INVALID_ARCHIVE, assertThrows(IOException.class, () -> SaoUpdates.verify(zip, release)).getMessage());
        Files.writeString(zip, "xx"); assertEquals(SaoUpdates.INVALID_ARCHIVE, assertThrows(IOException.class, () -> SaoUpdates.verify(zip, release)).getMessage());
    }

    /// Cached mandatory policy blocks every Link Start entry before any installer, account ticket or game action.
    @Test void serviceGateUsesCachedPolicyAndQuarkOpenerIsStubbable() throws Exception {
        Files.writeString(temporary.resolve("online-version.json"), document("9.0", "9.1"));
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            assertTrue(engine.updateStatus().get().launcherBlocked());
            assertFalse(engine.launcherPermitted());
            engine.linkStartOffline("Fixture"); engine.linkStartOath();
            assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get()); assertTrue(Objects.requireNonNull(engine.lastError().get()).contains("启动器版本过低"));
            engine.dismissLauncherUpdate(); assertTrue(engine.updateStatus().get().launcherBlocked());
            List<String> opened = new ArrayList<>(); engine.setLinkOpener(opened::add);
            engine.openPackDownload(); engine.openLauncherDownload(); assertEquals(List.of(SaoUpdates.QUARK_URL, SaoUpdates.QUARK_URL), opened);
            engine.refreshUpdates(); // Explicit fixtures must remain network-inert even on UI refresh.
            assertTrue(engine.updateStatus().get().launcherBlocked());
        }
    }

    /// Prompt polling exposes a candidate, stops on cancel, and a mismatched manual file fails before dependency work.
    @Test void promptCancellationAndManualPathVerificationAreHeadless() throws Exception {
        Files.writeString(temporary.resolve("online-version.json"), document("0.1", "0.1"));
        Path downloads = Files.createDirectories(temporary.resolve("Downloads")); Path zip = downloads.resolve("pack.zip"); Files.writeString(zip, "y");
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.setDownloadDirectories(() -> List.of(downloads)); engine.watchPackDownload();
            await(() -> zip.equals(engine.downloadPrompt().get().candidate()));
            engine.cancelPackDownload(); assertFalse(engine.downloadPrompt().get().watching()); assertNull(engine.downloadPrompt().get().candidate());
            engine.applyUpdateZip(zip); await(() -> engine.state().get() == SaoPackState.FAILED);
            assertTrue(Objects.requireNonNull(engine.lastError().get()).contains(SaoUpdates.INVALID_ARCHIVE));
            assertFalse(Files.exists(engine.instanceDir().resolve(SaoPackInstaller.RECEIPT)));
        }
    }

    /// The accepted snapshot survives replacement of Downloads and uses the existing preserving installer unchanged.
    @Test void verifiedPrivateSnapshotInstallsAndPreservesPlayerFiles() throws Exception {
        byte[] bytes = "managed-new".getBytes(StandardCharsets.UTF_8);
        SaoPackManifest.PackFile managed = new SaoPackManifest.PackFile("config/managed.txt", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, "overrides/config/managed.txt", List.of());
        SaoPackManifest manifest = new SaoPackManifest("0.10", "1.20.1", "47.1.106", "", List.of(managed), List.of());
        Path download = temporary.resolve("release.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(download))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            output.putNextEntry(new ZipEntry(managed.entry())); output.write(bytes); output.closeEntry();
        }
        SaoData.PackRelease release = new SaoData.PackRelease("0.10", "release.zip", Files.size(download), SaoUpdates.sha256(download));
        SaoPackArchive snapshot = SaoUpdates.snapshot(download, release, temporary.resolve("private-cache"));
        try {
            Files.writeString(download, "replaced by a browser after acceptance");
            Path instance = Files.createDirectories(temporary.resolve("instance"));
            Files.createDirectories(instance.resolve("saves/world")); Files.writeString(instance.resolve("saves/world/level.dat"), "player save");
            Files.writeString(instance.resolve("options.txt"), "player options"); Files.writeString(instance.resolve("my-own.txt"), "player file");
            SaoPackInstaller installer = new SaoPackInstaller(instance, (file, destination) -> { throw new AssertionError("Unexpected dependency request"); }, Map.of(), (label, progress) -> { });
            installer.apply(snapshot, false);
            assertEquals("managed-new", Files.readString(instance.resolve("config/managed.txt")));
            assertEquals("player options", Files.readString(instance.resolve("options.txt")));
            assertEquals("player save", Files.readString(instance.resolve("saves/world/level.dat")));
            assertEquals("player file", Files.readString(instance.resolve("my-own.txt")));
            assertEquals("0.10", Objects.requireNonNull(installer.installed()).version());
        } finally { Files.deleteIfExists(snapshot.zip()); }
    }

    /// Missing or malformed public policy cannot block an already installed current pack during an outage.
    @Test void installedCurrentPackRemainsReadyWithoutValidMetadata() throws Exception {
        byte[] bytes = "installed".getBytes(StandardCharsets.UTF_8);
        SaoPackManifest.PackFile file = new SaoPackManifest.PackFile("config/managed.txt", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, "overrides/config/managed.txt", List.of());
        SaoPackManifest manifest = new SaoPackManifest("0.10", "1.20.1", "47.1.106", "", List.of(file), List.of());
        Path zip = temporary.resolve("current.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); output.write(manifest.toJson().getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            output.putNextEntry(new ZipEntry(file.entry())); output.write(bytes); output.closeEntry();
        }
        SaoPackArchive pack = SaoPackArchive.open(zip);
        Path root = temporary.resolve("launcher"); Path instance;
        try (SaoPackEngine engine = new SaoPackEngine(root, pack, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING); instance = engine.instanceDir();
        }
        new SaoPackInstaller(instance, (remote, destination) -> { throw new AssertionError("Unexpected network"); }, Map.of(), (label, progress) -> { }).apply(pack, false);
        Files.writeString(root.resolve("online-version.json"), "{invalid outage cache");
        try (SaoPackEngine engine = new SaoPackEngine(root, pack, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            assertEquals(SaoPackState.READY, engine.state().get()); assertEquals("0.10", engine.installedVersion().get());
            assertNull(engine.updateStatus().get().metadata()); assertTrue(engine.launcherPermitted());
            engine.refreshUpdates(); assertEquals(SaoPackState.READY, engine.state().get());
            assertEquals("0.10", engine.requireSource().manifest().version());
        }
    }

    /// A downloaded ZIP cannot bypass verification when Gitee metadata has never been available.
    @Test void missingMetadataRejectsManualInstallWithoutBreakingLocalState() throws Exception {
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.applyUpdateZip(temporary.resolve("unknown.zip"));
            assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
            assertTrue(Objects.requireNonNull(engine.lastError().get()).contains("暂无有效版本信息"));
            assertTrue(engine.launcherPermitted());
        }
    }

    /// Waits only for background filesystem/publication work, never a toolkit or launched process.
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!condition.getAsBoolean()) Thread.sleep(5); });
    }
}
