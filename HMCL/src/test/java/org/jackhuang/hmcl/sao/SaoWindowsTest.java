/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonParser;
import javafx.collections.ObservableList;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/// Headless per-window regressions using tiny local worlds, archives and loopback status servers.
/// These tests never initialize a JavaFX toolkit, start a game, install a runtime or download a package.
@NotNullByDefault
class SaoWindowsTest {
    /// Disposable launcher roots and fixture exports.
    @TempDir Path temporary;

    /// Every added default seam method must be backed by the production engine.
    @Test void everyWindowMethodIsImplementedAndViewsRemainStable() throws Exception {
        try (SaoPackEngine engine = engine()) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            for (var method : SaoLauncherService.class.getMethods()) {
                if (method.isDefault()) assertEquals(SaoPackEngine.class,
                        SaoPackEngine.class.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(), method.getName());
            }
            assertSame(engine.news(), engine.news()); assertSame(engine.worlds(), engine.worlds());
            assertSame(engine.backups(), engine.backups()); assertSame(engine.shaderPacks(), engine.shaderPacks());
            assertSame(engine.resourcePacks(), engine.resourcePacks()); assertSame(engine.screenshots(), engine.screenshots());
            assertSame(engine.gameLog(), engine.gameLog()); assertSame(engine.javas(), engine.javas()); assertSame(engine.tasks(), engine.tasks());
            assertThrows(UnsupportedOperationException.class, () -> engine.gameLog().add("forged"));
            assertNull(engine.serverStatus().get());
            engine.refreshOnline(); engine.refreshOnline();
            assertNull(engine.serverStatus().get());
            assertEquals(1, engine.news().size());
            assertEquals(SaoData.NewsKind.UPDATE, engine.news().get(0).kind());
            assertEquals("", engine.news().get(0).body());
        }
    }

    /// Settings round-trip through actual engine recreation rather than remaining in screen memory.
    @Test void settingsPersistAndRejectInvalidValuesWithoutBreakingPackState() throws Exception {
        Path root = temporary.resolve("settings-launcher");
        try (SaoPackEngine engine = new SaoPackEngine(root, null, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.setMemoryMiB(2048); engine.setAutoMemory(true);
            engine.setGameWindow(new SaoData.GameWindow(1600, 900, true));
            engine.setJvmArgs("-Dfixture='two words' -XX:+UseG1GC");
            engine.setDownloadSource(SaoData.Source.OFFICIAL); engine.setDownloadThreads(7);
            engine.setPreference("sound", "helmet");
            engine.setDownloadThreads(0);
            assertEquals(7, engine.downloadThreads()); assertNotNull(engine.lastError().get());
            assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
            engine.setMemoryMiB(1); assertEquals(2048, engine.memoryMiB());
            assertTrue(engine.systemMemoryMiB() >= 2048);
        }
        try (SaoPackEngine engine = new SaoPackEngine(root, null, false)) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            assertEquals(2048, engine.memoryMiB()); assertTrue(engine.autoMemory());
            assertEquals(new SaoData.GameWindow(1600, 900, true), engine.gameWindow());
            assertEquals("-Dfixture='two words' -XX:+UseG1GC", engine.jvmArgs());
            assertEquals(SaoData.Source.OFFICIAL, engine.downloadSource()); assertEquals(7, engine.downloadThreads());
            assertEquals("helmet", engine.preference("sound", "none"));
            assertEquals("fallback", engine.preference("unset", "fallback"));
        }
    }

    /// Java selection verifies the executable instead of trusting a path label, and persists a valid Java 17.
    @Test void javaWindowOnlyLists17AndValidatesSelection() throws Exception {
        try (SaoPackEngine engine = engine()) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            await(() -> !engine.javas().isEmpty());
            assertTrue(engine.javas().stream().allMatch(runtime -> runtime.version().startsWith("17.")));
            Path executable = org.jackhuang.hmcl.java.JavaRuntime.CURRENT_JAVA.getBinary().toRealPath();
            engine.setJava(executable);
            await(() -> executable.equals(engine.java()));
            engine.setJava(temporary.resolve("missing-java"));
            await(() -> engine.lastError().get() != null);
            assertEquals(executable, engine.java());
            engine.setJava(null); await(() -> engine.java() == null);
        }
    }

    /// The source selector reaches HMCL's actual payload-candidate order, including an explicit official choice.
    @Test void downloadSourceChangesRealProviderCandidates() throws Exception {
        SaoGameBridge bridge = bridge();
        var field = SaoGameBridge.class.getDeclaredField("provider"); field.setAccessible(true);
        org.jackhuang.hmcl.download.HMCLDownloadProvider provider = (org.jackhuang.hmcl.download.HMCLDownloadProvider) field.get(bridge);
        bridge.setDownloadSource(SaoData.Source.BMCLAPI);
        assertTrue(provider.getDownloadCandidates(List.of("https://piston-data.mojang.com/fixture"))
                .getPrimaryCandidate().url().toString().startsWith("https://bmclapi2.bangbang93.com"));
        bridge.setDownloadSource(SaoData.Source.OFFICIAL);
        assertTrue(provider.getDownloadCandidates(List.of("https://piston-data.mojang.com/fixture"))
                .getPrimaryCandidate().url().toString().startsWith("https://piston-data.mojang.com"));
    }

    /// A last-good cached document survives malformed replacement data and an unconfigured endpoint.
    @Test void newsCacheAndEmbeddedChangelogUseActualSources() throws Exception {
        String valid = "{\"schema\":2,\"sequence\":0,\"news\":[{\"kind\":\"NOTICE\",\"title\":\"Fixture\",\"date\":\"2026-11-06\",\"body\":\"Plain text\"}]}";
        SaoReleaseTestSupport.cacheNews(temporary, valid);
        try (SaoOnlineData online = new SaoOnlineData(temporary, SaoReleaseTestSupport.TRUST)) {
            assertEquals("Fixture", online.cached().get(0).title());
            assertThrows(IOException.class, () -> SaoOnlineData.parseNews("{\"news\":[{\"kind\":\"UNKNOWN\"}]}"));
            assertEquals("Fixture", online.cached().get(0).title());
        }
        SaoPackArchive archive = release(Map.of("config/fixture.txt", "x"), Map.of("CHANGELOG.md", "Actual fixture release notes"));
        SaoData.News update = SaoOnlineData.embeddedNews(archive, "0.8");
        assertEquals("v0.8", update.title()); assertEquals("Actual fixture release notes", update.body());
        assertEquals("", SaoOnlineData.embeddedNews(null, "0.8").body());
        SaoPackManifest manifest = new SaoPackManifest("0.8", "1.20.1", "47.1.106", "", "https://example.invalid/news.json",
                archive.manifest().files(), List.of());
        assertEquals(manifest.newsUrl(), SaoPackManifest.read(new StringReader(manifest.toJson())).newsUrl());
        assertThrows(IllegalArgumentException.class, () -> new SaoPackManifest("0.8", "1.20.1", "47.1.106", "", "http://example.invalid/news.json", archive.manifest().files(), List.of()));
    }

    /// Public node metadata survives JSON round trips and absent fields preserve the seam defaults.
    @Test void argusServerAddressesUseManifestAndDefaults() throws Exception {
        SaoPackArchive archive = release(Map.of("config/fixture.txt", "x"), Map.of());
        SaoPackManifest manifest = new SaoPackManifest("0.8", "1.20.1", "47.1.106", "local.example:25567", "", "",
                List.of("one.example", "two.example:25568"), archive.manifest().files(), List.of());
        SaoPackManifest read = SaoPackManifest.read(new StringReader(manifest.toJson()));
        assertEquals(manifest.serverNodes(), read.serverNodes());
        assertThrows(UnsupportedOperationException.class, () -> read.serverNodes().add("three.example"));
        assertThrows(IllegalArgumentException.class, () -> new SaoPackManifest("0.8", "1.20.1", "47.1.106", "", "", "",
                List.of("https://bad.example/path"), archive.manifest().files(), List.of()));
        try (SaoPackEngine empty = new SaoPackEngine(temporary.resolve("empty-addresses"), null, false)) {
            assertEquals("mc.saooath.com", empty.serverAddress());
            assertEquals(List.of("zz.saooath.com", "tz.saooath.com", "sq.saooath.com"), empty.serverNodes());
        }
    }

    /// The response subscriber cancels oversized bodies before retaining their bytes.
    @Test void oversizedNewsCancelsAtTheBound() {
        SaoOnlineData.NewsSubscriber subscriber = new SaoOnlineData.NewsSubscriber();
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            /// The fixture supplies its chunks directly.
            @Override public void request(long count) { }
            /// Records the real subscriber cancellation fence.
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[262145])));
        assertTrue(cancelled.get());
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    }

    /// A loopback server verifies protocol 763 and echoes the ping rather than supplying invented player numbers.
    @Test void statusUsesMinecraft1201HandshakeAndImmutableSample() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); SaoOnlineData online = new SaoOnlineData(temporary)) {
            CompletableFuture<Void> served = serve(server, false, new AtomicInteger());
            SaoData.ServerStatus status = online.ping("127.0.0.1:" + server.getLocalPort());
            served.get(5, TimeUnit.SECONDS);
            assertTrue(status.online()); assertEquals(2, status.players()); assertEquals(20, status.max());
            assertEquals("Fixture MOTD!", status.motd()); assertEquals(List.of("FixturePlayer"), status.names());
            assertTrue(status.pingMs() >= 0); assertThrows(UnsupportedOperationException.class, () -> status.names().add("forged"));
        }
    }

    /// A peer that continually trickles bytes still cannot keep a ping alive beyond its total deadline.
    @Test void slowStatusPeerCannotExtendThreeSecondDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); SaoOnlineData online = new SaoOnlineData(temporary)) {
            CompletableFuture<Void> served = serve(server, true, new AtomicInteger());
            long start = System.nanoTime();
            assertThrows(Exception.class, () -> online.ping("127.0.0.1:" + server.getLocalPort()));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 2700 && elapsed < 4200, "Total ping time: " + elapsed);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    /// Repeated refresh actions while a ping is active produce only one socket connection.
    @Test void onlineRefreshDoesNotPileUpPings() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            SaoPackArchive release = release(Map.of("config/fixture.txt", "x"), Map.of());
            SaoPackManifest configured = new SaoPackManifest("0.8", "1.20.1", "47.1.106", "127.0.0.1:" + server.getLocalPort(), release.manifest().files(), List.of());
            AtomicInteger connections = new AtomicInteger();
            CompletableFuture<Void> served = serve(server, true, connections);
            try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("status-engine"), new SaoPackArchive(release.zip(), configured), false)) {
                await(() -> engine.state().get() != SaoPackState.CHECKING);
                for (int i = 0; i < 20; i++) engine.refreshOnline();
                await(() -> connections.get() == 1);
                await(() -> engine.serverStatus().get() != null);
                assertFalse(Objects.requireNonNull(engine.serverStatus().get()).online());
                assertEquals(1, connections.get());
            }
            served.get(5, TimeUnit.SECONDS);
        }
    }

    /// Saves come from NBT, and restore makes a fresh current-world backup before replacing any player data.
    @Test void worldBackupExportAndRestorePreserveCurrentWorldFirst() throws Exception {
        SaoGameBridge bridge = bridge();
        SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path world = bridge.instanceDir().resolve("saves/practice");
        world(world, "Fixture world", 123456789L, 1);
        Files.writeString(world.resolve("marker.txt"), "original");
        @Unmodifiable List<SaoData.World> worlds = files.worlds();
        assertEquals("Fixture world", worlds.get(0).name()); assertEquals("创造", worlds.get(0).mode());
        assertEquals(123456789L, worlds.get(0).lastPlayed()); assertTrue(worlds.get(0).sizeBytes() > 0);
        Path backup = files.backup(worlds.get(0));
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            assertNotNull(zip.getEntry("practice/level.dat")); assertNull(zip.getEntry("practice/session.lock"));
        }
        Files.writeString(world.resolve("marker.txt"), "current before restore");
        SaoData.Backup selected = files.backups().stream().filter(item -> item.file().equals(backup)).findFirst().orElseThrow();
        files.restore(selected);
        assertEquals("original", Files.readString(world.resolve("marker.txt")));
        assertEquals("Fixture world", files.worlds().get(0).name());
        assertEquals(2, files.backups().size());
        boolean preserved = false;
        for (SaoData.Backup item : files.backups()) try (ZipFile zip = new ZipFile(item.file().toFile())) {
            try (InputStream input = zip.getInputStream(zip.getEntry("practice/marker.txt"))) {
                preserved |= new String(input.readAllBytes(), StandardCharsets.UTF_8).equals("current before restore");
            }
        }
        assertTrue(preserved);
        Path exported = temporary.resolve("world-export.zip");
        files.export(files.worlds().get(0), exported);
        assertEquals("Fixture world", new org.jackhuang.hmcl.game.World(exported).getWorldName());
        assertThrows(IOException.class, () -> files.export(files.worlds().get(0), world.resolve("recursive.zip")));
    }

    /// A legitimate folder named previous or with a dot prefix must not collide with internal restore staging.
    @Test void restoreSupportsWorldFolderNamesThatResembleInternalNames() throws Exception {
        for (String name : List.of("previous", ".sao-world")) {
            SaoGameBridge bridge = bridge(); SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
            Path directory = bridge.instanceDir().resolve("saves").resolve(name); world(directory, "Fixture", 1, 0);
            Files.writeString(directory.resolve("marker.txt"), "archived");
            files.backup(files.worlds().get(0));
            assertEquals(1, files.backups().size());
            Files.writeString(directory.resolve("marker.txt"), "changed");
            files.restore(files.backups().get(0));
            assertEquals("archived", Files.readString(directory.resolve("marker.txt")));
        }
    }

    /// Even when commit and rollback both fail, recovery staging must retain the displaced current world.
    @Test void restoreRollbackFailureNeverDeletesThePreviousWorld() throws Exception {
        SaoGameBridge bridge = bridge(); SaoInstanceFiles normal = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path directory = bridge.instanceDir().resolve("saves/practice"); world(directory, "Fixture", 1, 0);
        Files.writeString(directory.resolve("marker.txt"), "archived");
        Path archive = normal.backup(normal.worlds().get(0));
        Files.writeString(directory.resolve("marker.txt"), "must survive failed rollback");
        AtomicInteger moves = new AtomicInteger();
        SaoInstanceFiles faulted = new SaoInstanceFiles(bridge.instanceDir(), bridge, (source, destination) -> {
            if (moves.incrementAndGet() == 2) {
                Files.createDirectories(destination);
                Files.writeString(destination.resolve("unexpected.txt"), "collision");
                throw new IOException("Fixture commit collision");
            }
            if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
            Files.move(source, destination);
        });
        SaoData.Backup selected = normal.backups().stream().filter(item -> item.file().equals(archive)).findFirst().orElseThrow();
        IOException failure = assertThrows(IOException.class, () -> faulted.restore(selected));
        assertTrue(failure.getMessage().contains("recovery data retained"));
        Path saves = bridge.instanceDir().resolve("saves");
        try (var folders = Files.list(saves)) {
            Path retained = folders.filter(path -> path.getFileName().toString().startsWith(".sao-restore-")).findFirst().orElseThrow();
            assertEquals("must survive failed rollback", Files.readString(retained.resolve("previous/marker.txt")));
        }
        assertEquals("collision", Files.readString(directory.resolve("unexpected.txt")));
    }

    /// A record forged outside the fixed instance cannot export or restore another installation's data.
    @Test void worldMutationsRejectOutsideRecordsAndTraversalBackups() throws Exception {
        SaoGameBridge bridge = bridge();
        SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path outside = temporary.resolve("outside"); world(outside, "Outside", 1, 0);
        SaoData.World record = new SaoData.World("Outside", outside, 1, 0, null, "");
        assertThrows(IOException.class, () -> files.backup(record));
        Path backups = bridge.instanceDir().resolve("backups"); Files.createDirectories(backups);
        zip(backups.resolve("traversal.zip"), Map.of("../outside/level.dat", "bad"));
        assertTrue(files.backups().isEmpty());
    }

    /// Shader detection reads the fork's own mod declaration and ignores dependency-only Oculus references.
    @Test void shaderWindowSupportsRenamedOculusForkAndOwnConfigOnly() throws Exception {
        SaoGameBridge bridge = bridge();
        SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path instance = bridge.instanceDir();
        Files.createDirectories(instance.resolve("shaderpacks"));
        zip(instance.resolve("shaderpacks/Fixture.zip"), Map.of("shaders/fixture.fsh", "shader"));
        Files.createDirectories(instance.resolve("config"));
        Files.writeString(instance.resolve("config/oculus.properties"), "shaderPack=Fixture.zip\nenableShaders=false\nother=kept\n");
        assertEquals(1, files.packs(true).size()); assertFalse(files.packs(true).get(0).enabled());
        assertThrows(IOException.class, () -> files.setShader(files.packs(true).get(0)));
        Files.createDirectories(instance.resolve("mods"));
        zip(instance.resolve("mods/embeddium.jar"), Map.of("META-INF/mods.toml", "[[mods]]\nmodId=\"embeddium\"\n[[dependencies.embeddium]]\nmodId=\"oculus\"\n"));
        assertNull(files.shaderConfig());
        zip(instance.resolve("mods/renamed-fork.jar"), Map.of("META-INF/mods.toml", "[[mods]]\nmodId=\"oculus\"\ndisplayName=\"Mekalus (Oculus)\"\n"));
        assertEquals(instance.resolve("config/oculus.properties"), files.shaderConfig());
        files.setShader(files.packs(true).get(0));
        assertTrue(files.packs(true).get(0).enabled());
        assertTrue(Files.readString(instance.resolve("config/oculus.properties")).contains("other=kept"));
        files.setShader(null); assertFalse(files.packs(true).get(0).enabled());
        assertFalse(Files.exists(instance.resolve("config/iris.properties")));
    }

    /// Resource toggles preserve required defaults, vanilla IDs, application order and unrelated option lines.
    @Test void resourceWindowPreservesRequiredPackOrderAndPlayerOptions() throws Exception {
        SaoGameBridge bridge = bridge();
        SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path instance = bridge.instanceDir(); Files.createDirectories(instance.resolve("resourcepacks"));
        for (String name : List.of("Required.zip", "Existing.zip", "New.zip")) zip(instance.resolve("resourcepacks/" + name), Map.of("pack.mcmeta", "{\"pack\":{\"description\":\"Fixture\",\"pack_format\":15}}"));
        files.requiredResources(release(Map.of("options.txt", "resourcePacks:[\"file/Required.zip\"]\n"), Map.of()));
        Files.writeString(instance.resolve("options.txt"), "music:0.2\nresourcePacks:[\"vanilla\",\"file/Required.zip\",\"file/Existing.zip\"]\nincompatibleResourcePacks:[]\nkey_key.forward:key.keyboard.w\n");
        SaoData.Pack required = pack(files, "Required");
        assertThrows(IOException.class, () -> files.setResource(required, false));
        files.setResource(pack(files, "New"), true);
        assertTrue(Files.readString(instance.resolve("options.txt")).contains("[\"vanilla\",\"file/Required.zip\",\"file/Existing.zip\",\"file/New.zip\"]"));
        files.setResource(pack(files, "Existing"), false);
        String options = Files.readString(instance.resolve("options.txt"));
        assertTrue(options.contains("music:0.2")); assertTrue(options.contains("key_key.forward:key.keyboard.w"));
        assertTrue(options.contains("[\"vanilla\",\"file/Required.zip\",\"file/New.zip\"]"));
        assertEquals(List.of("Required", "New", "Existing"), files.packs(false).stream().map(SaoData.Pack::name).toList());
        Files.writeString(instance.resolve("options.txt"), "music:0.2\nresourcePacks:[\"vanilla\",\"file/New.zip\"]\n");
        files.enforceRequiredResources();
        assertTrue(pack(files, "Required").enabled());
        assertTrue(Files.readString(instance.resolve("options.txt")).contains("[\"vanilla\",\"file/New.zip\",\"file/Required.zip\"]"));
        files.requiredResources(null);
        files.setResource(required, false);
        assertFalse(pack(files, "Required").enabled());
    }

    /// Linked config/options paths cannot redirect a pack setting into another instance or outside file.
    @Test void packSettingsRejectSymlinkTargetsWithoutOutsideWrites() throws Exception {
        SaoGameBridge bridge = bridge(); SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path instance = bridge.instanceDir(); Files.createDirectories(instance.resolve("mods"));
        Files.createDirectories(instance.resolve("shaderpacks")); Files.createDirectories(instance.resolve("resourcepacks"));
        zip(instance.resolve("mods/oculus.jar"), Map.of("META-INF/mods.toml", "[[mods]]\nmodId=\"oculus\"\n"));
        Path shader = instance.resolve("shaderpacks/Fixture.zip"); zip(shader, Map.of("shaders/main.fsh", "fixture"));
        Path outside = temporary.resolve("outside-config"); Files.createDirectories(outside);
        Files.writeString(outside.resolve("oculus.properties"), "outside=unchanged\n");
        Files.createSymbolicLink(instance.resolve("config"), outside);
        assertThrows(IOException.class, () -> files.setShader(new SaoData.Pack("Fixture", shader, "", false, null)));
        assertEquals("outside=unchanged\n", Files.readString(outside.resolve("oculus.properties")));
        Files.delete(instance.resolve("config"));
        Path externalOptions = Files.writeString(outside.resolve("options.txt"), "outside options");
        Files.createSymbolicLink(instance.resolve("options.txt"), externalOptions);
        Path resource = instance.resolve("resourcepacks/Fixture.zip"); zip(resource, Map.of("pack.mcmeta", "{}"));
        assertThrows(IOException.class, () -> files.setResource(new SaoData.Pack("Fixture", resource, "", false, null), true));
        assertEquals("outside options", Files.readString(externalOptions));
    }

    /// Dropped zips copy into the requested instance folder and cannot overwrite an existing pack.
    @Test void addingPacksPreservesSourceAndExistingCopies() throws Exception {
        SaoGameBridge bridge = bridge();
        SaoInstanceFiles files = new SaoInstanceFiles(bridge.instanceDir(), bridge);
        Path input = temporary.resolve("Dropped.zip"); zip(input, Map.of("pack.mcmeta", "{}"));
        byte[] original = Files.readAllBytes(input);
        files.addPacks(List.of(input), false);
        assertArrayEquals(original, Files.readAllBytes(input));
        assertArrayEquals(original, Files.readAllBytes(bridge.instanceDir().resolve("resourcepacks/Dropped.zip")));
        assertThrows(FileAlreadyExistsException.class, () -> files.addPacks(List.of(input), false));
        files.addPacks(List.of(input), true);
        assertTrue(Files.exists(bridge.instanceDir().resolve("shaderpacks/Dropped.zip")));
    }

    /// Screenshot discovery is newest first and only includes actual image files, not folders or pointer text.
    @Test void screenshotWindowRefreshesNewImagesInOrder() throws Exception {
        try (SaoPackEngine engine = engine()) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            Path shots = engine.instanceDir().resolve("screenshots"); Files.createDirectories(shots);
            Path old = Files.writeString(shots.resolve("old.png"), "fixture");
            Path newest = Files.writeString(shots.resolve("new.png"), "fixture");
            Files.setLastModifiedTime(old, FileTime.fromMillis(100)); Files.setLastModifiedTime(newest, FileTime.fromMillis(200));
            Files.writeString(shots.resolve("pointer.png.txt"), "not an image");
            await(() -> engine.screenshots().size() == 2);
            assertEquals(List.of(newest, old), engine.screenshots());
        }
    }

    /// Live output is capped and sanitized before publication; support exports never include authentication files.
    @Test void logWindowCapsOutputAndExportRedactsHandoffIncludingGzipCollision() throws Exception {
        SaoGameBridge bridge = bridge(); Path instance = bridge.instanceDir();
        SaoGameLogs logs = new SaoGameLogs(instance);
        for (int i = 0; i < 5010; i++) logs.append("line " + i);
        assertEquals(5000, logs.snapshot().size()); assertEquals("line 10", logs.snapshot().get(0));
        String secret = "fixture-private-value";
        logs.append(secret + "x".repeat(33000) + " sao_auth password=redacted");
        assertFalse(String.join("\n", logs.snapshot()).contains(secret));
        Files.createDirectories(instance.resolve("logs")); Files.createDirectories(instance.resolve("sao"));
        Files.writeString(instance.resolve("sao/password.txt"), secret);
        Files.writeString(instance.resolve("logs/sao_auth-private.txt"), secret);
        String encoded = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        Files.writeString(instance.resolve("logs/same.log"), "SAO-AUTH-V1 " + encoded + "\nsafe plain log\n");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(instance.resolve("logs/same.log.gz")))) {
            gzip.write(("sao_auth password=" + secret + "\nsafe archived log").getBytes(StandardCharsets.UTF_8));
        }
        Path destination = temporary.resolve("support.zip"); logs.export(destination);
        try (ZipFile zip = new ZipFile(destination.toFile())) {
            assertNotNull(zip.getEntry("logs/same.log")); assertNotNull(zip.getEntry("logs/same.log.gz.txt"));
            assertNull(zip.getEntry("sao/password.txt")); assertNull(zip.getEntry("logs/sao_auth-private.txt"));
            var entries = zip.entries();
            while (entries.hasMoreElements()) try (InputStream input = zip.getInputStream(entries.nextElement())) {
                String exported = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(exported.contains(secret)); assertFalse(exported.contains(encoded)); assertFalse(exported.contains("SAO-AUTH-V1"));
            }
        }
        try (SaoPackEngine engine = engine()) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            engine.appendGameLog("live fixture line");
            await(() -> engine.gameLog().contains("live fixture line"));
        }
    }

    /// HMCL's real rule matcher supplies a one-line analysis and never associates an old report with a clean new run.
    @Test void crashWindowUsesHmclRuleAndCurrentRunReport() throws Exception {
        SaoGameBridge bridge = bridge(); Path instance = bridge.instanceDir();
        SaoGameLogs logs = new SaoGameLogs(instance);
        Files.createDirectories(instance.resolve("crash-reports"));
        Path report = Files.writeString(instance.resolve("crash-reports/crash-fixture.txt"), "java.lang.OutOfMemoryError: Java heap space");
        SaoData.Crash result = Objects.requireNonNull(logs.analyze(true, 0));
        assertEquals(report, result.report()); assertTrue(result.detail().contains("OutOfMemoryError"));
        assertFalse(result.reason().contains("\n"));
        assertNull(logs.analyze(false, System.currentTimeMillis() + 5000));
    }

    /// Real HMCL TaskExecutor callbacks drive progress and completion records without making a network request.
    @Test void downloadWindowTracksTaskExecutorLifecycle() throws Exception {
        try (SaoPackEngine engine = engine()) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            var field = SaoPackEngine.class.getDeclaredField("game"); field.setAccessible(true);
            SaoGameBridge bridge = (SaoGameBridge) field.get(engine);
            CountDownLatch running = new CountDownLatch(1); CountDownLatch finish = new CountDownLatch(1);
            org.jackhuang.hmcl.task.FileDownloadTask task = new org.jackhuang.hmcl.task.FileDownloadTask(
                    "https://example.invalid/never-requested", temporary.resolve("fixture-runtime.zip")) {
                /// Replaces network transfer with a controlled local task, retaining HMCL's actual graph/listener path.
                @Override public void execute() throws Exception {
                    updateProgressImmediately(0.25); running.countDown();
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture was not released");
                    updateProgressImmediately(1);
                }
            };
            CompletableFuture<Void> completed = CompletableFuture.runAsync(() -> {
                try { bridge.runTask(task); } catch (Exception failure) { throw new CompletionException(failure); }
            });
            try {
                assertTrue(running.await(5, TimeUnit.SECONDS));
                await(() -> !engine.tasks().isEmpty() && engine.tasks().get(0).progress() == 0.25);
                assertEquals("fixture-runtime.zip", engine.tasks().get(0).name()); assertFalse(engine.tasks().get(0).done());
            } finally { finish.countDown(); }
            completed.get(5, TimeUnit.SECONDS);
            await(() -> engine.tasks().get(0).done()); assertEquals(1, engine.tasks().get(0).progress());
        }
    }

    /// World/pack/skin changes are rejected with a visible error while the game owns the instance files.
    @Test void runningGameRefusesMutationsAndRetainsRunningState() throws Exception {
        try (SaoPackEngine engine = engine()) {
            await(() -> engine.state().get() != SaoPackState.CHECKING);
            var field = SaoPackEngine.class.getDeclaredField("gameRunning"); field.setAccessible(true);
            ((AtomicBoolean) field.get(engine)).set(true);
            SaoData.World selected = new SaoData.World("Fixture", engine.instanceDir().resolve("saves/fixture"), 0, 0, null, "");
            Path export = temporary.resolve("refused-export.zip");
            engine.backupWorld(selected);
            assertTrue(Objects.requireNonNull(engine.lastError().get()).contains("游戏运行中"));
            engine.exportWorld(selected, export);
            assertFalse(Files.exists(export));
            engine.restoreBackup(new SaoData.Backup(engine.instanceDir().resolve("backups/fixture.zip"), selected.folder(), 0, 0));
            assertFalse(Files.exists(selected.folder()));
            engine.setShader(null);
            assertNotNull(engine.lastError().get());
            assertTrue(engine.lastError().get().contains("游戏运行中"));
            engine.setSkin(null, false);
            assertEquals(SaoPackState.NOT_INSTALLED, engine.state().get());
            assertFalse(Files.exists(engine.instanceDir().resolve("config/oculus.properties")));
        }
    }

    /// Creates a no-distribution service so tests cannot accidentally materialize the full embedded archive.
    private SaoPackEngine engine() { return new SaoPackEngine(temporary.resolve("engine-" + UUID.randomUUID()), null, false); }
    /// Creates a toolkit-free bridge without installing or launching anything.
    private SaoGameBridge bridge() { return new SaoGameBridge(temporary.resolve("bridge-" + UUID.randomUUID()), (label, fraction) -> { }); }
    /// Finds a resource pack by its actual file-derived label.
    private static SaoData.Pack pack(SaoInstanceFiles files, String name) throws IOException {
        return files.packs(false).stream().filter(pack -> pack.name().equals(name)).findFirst().orElseThrow();
    }
    /// Polls asynchronous service publication with a fixed short deadline and no GUI event loop.
    private static void await(BooleanSupplier ready) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!ready.getAsBoolean()) Thread.sleep(10); });
    }
    /// Writes a valid compressed Minecraft level.dat without needing a running game.
    private static void world(Path directory, String name, long lastPlayed, int mode) throws IOException {
        Files.createDirectories(directory);
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(directory.resolve("level.dat"))))) {
            output.writeByte(10); output.writeUTF(""); output.writeByte(10); output.writeUTF("Data");
            output.writeByte(8); output.writeUTF("LevelName"); output.writeUTF(name);
            output.writeByte(4); output.writeUTF("LastPlayed"); output.writeLong(lastPlayed);
            output.writeByte(3); output.writeUTF("GameType"); output.writeInt(mode);
            output.writeByte(0); output.writeByte(0);
        }
    }
    /// Writes a tiny zip for shader mods, resource packs and invalid-backup fixtures.
    private static void zip(Path destination, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
    }
    /// Builds a complete small release whose options can act as required-resource defaults.
    private SaoPackArchive release(Map<String, String> overrides, Map<String, String> extra) throws Exception {
        List<SaoPackManifest.PackFile> files = new ArrayList<>(); Map<String, String> entries = new LinkedHashMap<>(extra);
        for (var entry : overrides.entrySet()) {
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            files.add(new SaoPackManifest.PackFile(entry.getKey(), SaoPackArchive.sha1(new ByteArrayInputStream(bytes)), bytes.length,
                    "overrides/" + entry.getKey(), List.of())); entries.put("overrides/" + entry.getKey(), entry.getValue());
        }
        SaoPackManifest manifest = new SaoPackManifest("0.8", "1.20.1", "47.1.106", "", files, List.of());
        entries.put(SaoPackManifest.NAME, manifest.toJson());
        Path archive = Files.createTempFile(temporary, "release-", ".zip"); zip(archive, entries);
        return SaoPackArchive.open(archive);
    }
    /// Hosts one minimal real status exchange, optionally trickling a response until the client closes its deadline.
    private static CompletableFuture<Void> serve(ServerSocket server, boolean slow, AtomicInteger connections) {
        return CompletableFuture.runAsync(() -> {
            try (Socket socket = server.accept()) {
                connections.incrementAndGet(); socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                int handshakeSize = SaoOnlineData.readVarInt(input);
                DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(input.readNBytes(handshakeSize)));
                assertEquals(0, SaoOnlineData.readVarInt(handshake)); assertEquals(763, SaoOnlineData.readVarInt(handshake));
                int hostSize = SaoOnlineData.readVarInt(handshake); handshake.readNBytes(hostSize); handshake.readUnsignedShort();
                assertEquals(1, SaoOnlineData.readVarInt(handshake));
                assertEquals(1, SaoOnlineData.readVarInt(input)); assertEquals(0, SaoOnlineData.readVarInt(input));
                String json = "{\"players\":{\"online\":2,\"max\":20,\"sample\":[{\"name\":\"FixturePlayer\"}]},\"description\":{\"text\":\"§aFixture MOTD\",\"extra\":[{\"text\":\"!\"}]}}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream packet = new ByteArrayOutputStream(); DataOutputStream body = new DataOutputStream(packet);
                SaoOnlineData.writeVarInt(body, 0); SaoOnlineData.writeVarInt(body, bytes.length); body.write(bytes);
                SaoOnlineData.writeVarInt(output, packet.size());
                if (slow) {
                    try { for (byte value : packet.toByteArray()) { output.writeByte(value); output.flush(); Thread.sleep(100); } }
                    catch (SocketException expected) { return; }
                } else { output.write(packet.toByteArray()); output.flush(); }
                assertEquals(9, SaoOnlineData.readVarInt(input)); assertEquals(1, SaoOnlineData.readVarInt(input));
                long timestamp = input.readLong(); output.write(new byte[]{9, 1}); output.writeLong(timestamp); output.flush();
            } catch (Exception failure) { throw new CompletionException(failure); }
        });
    }
}
