/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.game.QuickPlayOption;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/// Offline production-policy regressions with throwaway signatures, inert exchanges and a monotonic fake clock.
@NotNullByDefault
class SaoProductionPolicyTest {
    /// Disposable launcher/cache/key fixtures; no real credentials or release documents are used.
    @TempDir Path temporary;

    /// Complete policy with optional fixture fields and an independently chosen signed sequence.
    private static String document(long sequence, String optional) {
        return "{\"schema\":2,\"sequence\":" + sequence + ",\"quark\":\"https://pan.quark.cn/s/fixture\","
                + "\"pack\":{\"version\":\"0.81\",\"file\":\"pack.zip\",\"size\":1,\"sha256\":\"" + "0".repeat(64)
                + "\"},\"updates\":[],\"launcher\":{\"latest\":\"0.1.0\",\"min\":\"0.1.0\"}" + optional + "}";
    }

    /// Produces exact optional settings used by verified fetch and restart tests.
    private static String production(long sequence, boolean maintenance) {
        return document(sequence, ",\"server\":\"play.example:25566\",\"accountApi\":\"https://api.example/argus/\""
                + (maintenance ? ",\"maintenance\":{\"message\":\"正式服维护中，安装新的登录验证\",\"until\":\"今晚 20:00\"}" : ""));
    }

    /// Optional values retain exact strings and old schema-2 documents remain valid without any settings.
    @Test void optionalSettingsAndLegacyDefaultsParse() throws Exception {
        SaoData.VersionMetadata old = SaoUpdates.parse(document(0, ""));
        assertNull(old.server()); assertNull(old.accountApi()); assertNull(old.maintenance());
        SaoData.VersionMetadata parsed = SaoUpdates.parse(production(1, true));
        assertEquals("play.example:25566", parsed.server()); assertEquals("https://api.example/argus/", parsed.accountApi());
        assertEquals(new SaoData.Maintenance("正式服维护中，安装新的登录验证", "今晚 20:00"), parsed.maintenance());
        assertEquals(parsed.maintenance(), SaoUpdates.status(parsed, null, "0.1.0", null).maintenance());
        assertNull(SaoUpdates.status(null, null, "0.1.0", null).maintenance());
        assertEquals("mc.saooath.com:65535", SaoUpdates.parse(document(0, ",\"server\":\"mc.saooath.com:65535\"")).server());
        assertEquals("a:1", SaoUpdates.server("a:1"));
        assertEquals("https://api.example:65535/v1/", SaoUpdates.accountApi("https://api.example:65535/v1/"));
    }

    /// Scheme/path/port/DNS ambiguity and non-string coercions cannot become game destinations.
    @Test void rejectsMalformedServers() {
        for (String server : List.of("", "MC.saooath.com", "https://mc.saooath.com", "mc.saooath.com/", "host:0",
                "host:65536", "host:-1", "host:", "host:1:2", "host:1.0", "host:abc", "host:000001", "host?x", "host#x",
                "user@host", "-host", "host-", "a..b", ".host", "host.", "a_b", "[::1]", "host name",
                "a".repeat(64) + ".example", ("a".repeat(63) + ".").repeat(4) + "a"))
            assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"server\":\"" + server + "\"")), server);
        for (String value : List.of("null", "true", "1", "{}", "[]"))
            assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"server\":" + value)));
        assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"server\":\"a\",\"server\":\"b\"")));
    }

    /// Signed account roots require HTTPS, a real host, an explicit trailing slash and a valid optional TCP port.
    @Test void rejectsMalformedAccountApis() {
        for (String api : List.of("", "http://api.example/", "https:///", "https://api.example", "https://api.example/v1",
                "https://api.example:0/", "https://api.example:65536/", "https://api.example:-1/", "https://api.example:/",
                "https://user:pass@api.example/", "https://api.example/?query=1", "https://api.example/?", "https://api.example/#",
                "https://api.example/#fragment", "https://api.example/" + "x".repeat(2048) + "/"))
            assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"accountApi\":\"" + api + "\"")), api);
        for (String value : List.of("null", "false", "1", "{}", "[]"))
            assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"accountApi\":" + value)));
        assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"accountApi\":\"https://a/\",\"accountApi\":\"https://b/\"")));
    }

    /// Maintenance has an exact field set, Unicode character bounds and no C0/C1 controls in either display string.
    @Test void maintenanceTextBoundsAndRejectCases() throws Exception {
        String max = "😀".repeat(200);
        SaoData.Maintenance notice = SaoUpdates.parse(document(0, ",\"maintenance\":{\"message\":\"" + max + "\"}")).maintenance();
        assertNotNull(notice); assertEquals(max, notice.message()); assertNull(notice.until());
        assertNotNull(SaoUpdates.parse(document(0, ",\"maintenance\":{\"message\":\"m\",\"until\":\"" + "时".repeat(40) + "\"}")).maintenance());
        for (String value : List.of("null", "false", "1", "\"on\"", "[]", "{}", "{\"message\":\"\"}",
                "{\"message\":\"" + "a".repeat(201) + "\"}", "{\"message\":1}", "{\"message\":null}",
                "{\"message\":\"m\",\"until\":null}", "{\"message\":\"m\",\"until\":1}",
                "{\"message\":\"m\",\"until\":\"" + "a".repeat(41) + "\"}", "{\"message\":\"m\",\"message\":\"n\"}",
                "{\"message\":\"m\\nline\"}", "{\"message\":\"m\\u0000\"}", "{\"message\":\"m\\u0085\"}",
                "{\"message\":\"m\",\"until\":\"now\\t\"}"))
            assertThrows(IOException.class, () -> SaoUpdates.parse(document(0, ",\"maintenance\":" + value)), value);
        // Fields unknown to this launcher (B-2: a newer release's additions) are ignored rather than rejected.
        assertNull(SaoUpdates.parse(document(0, ",\"maintenence\":{}")).maintenance());
        assertEquals("m", Objects.requireNonNull(SaoUpdates.parse(document(0, ",\"maintenance\":{\"message\":\"m\",\"extra\":\"x\"}")).maintenance()).message());
    }

    /// Authentic legacy docs, maintenance enable/disable and rollback rejection all survive full service restart.
    @Test void signedCacheRestartsAndTurnsMaintenanceOffAcrossSequences() throws Exception {
        Path root = temporary.resolve("restart");
        SaoReleaseTestSupport.cacheVersion(root, document(1, ""));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            awaitEngine(engine); assertNull(engine.updateStatus().get().maintenance()); assertEquals("mc.saooath.com", engine.serverAddress());
        }
        SaoReleaseTestSupport.cacheVersion(root, production(2, true));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            awaitEngine(engine); assertNotNull(engine.updateStatus().get().maintenance());
            assertEquals("play.example:25566", engine.serverAddress());
            assertEquals(URI.create("https://api.example/argus/"), engine.accounts().accountEndpoint());
        }
        SaoReleaseTestSupport.cacheVersion(root, production(3, false));
        assertThrows(IOException.class, () -> SaoReleaseTestSupport.cacheVersion(root, production(2, true)));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            awaitEngine(engine); assertNull(engine.updateStatus().get().maintenance());
            assertEquals(3L, engine.releaseVerification().get().versionSequence());
        }
    }

    /// QuickPlay uses signed targets, preserves explicit pack targets, and suppresses every target for offline play.
    @Test void quickPlayPolicyAndPackPriority() throws Exception {
        SaoPackManifest empty = manifest("");
        SaoGameBridge.LaunchPlan signed = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("FixturePlayer"), empty,
                2048, new SaoData.GameWindow(1280, 720, false), "", JavaRuntime.CURRENT_JAVA, temporary, false, "signed.example:25566");
        assertEquals("signed.example:25566", signed.server());
        assertEquals(new QuickPlayOption.MultiPlayer(signed.server()), signed.options().getQuickPlayOption());
        SaoGameBridge.LaunchPlan pack = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("FixturePlayer"), manifest("fixture.example:25567"),
                2048, new SaoData.GameWindow(1280, 720, false), "", JavaRuntime.CURRENT_JAVA, temporary, false, "signed.example:25566");
        assertEquals("fixture.example:25567", pack.server());
        SaoGameBridge.LaunchPlan offline = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("FixturePlayer"), empty,
                2048, new SaoData.GameWindow(1280, 720, false), "", JavaRuntime.CURRENT_JAVA, temporary, true, "signed.example:25566");
        assertEquals("", offline.server()); assertNull(offline.options().getQuickPlayOption());
        Path root = temporary.resolve("pack-priority"); SaoReleaseTestSupport.cacheVersion(root, production(1, false));
        SaoPackArchive archive = new SaoPackArchive(temporary.resolve("unused.zip"), manifest("fixture.example:25567"));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, archive, false)) {
            awaitEngine(engine); assertEquals("fixture.example:25567", engine.serverAddress());
        }
    }

    /// Both policy failure and success consume attempt slots; the fake clock includes nanoTime's negative range.
    @Test void fakeClockLimitsTimerToThreeMinutesAndButtonToThirtySeconds() {
        AtomicLong clock = new AtomicLong(-TimeUnit.MINUTES.toNanos(10)); SaoPolicyRefresh limiter = new SaoPolicyRefresh(clock::get);
        assertTrue(limiter.acquire(false)); assertFalse(limiter.acquire(true));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(29)); assertFalse(limiter.acquire(true));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); assertTrue(limiter.acquire(true));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(179)); assertFalse(limiter.acquire(false));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1)); assertTrue(limiter.acquire(false));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30)); assertTrue(limiter.acquire(true));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(180)); assertTrue(limiter.acquire(false));
    }

    /// Real refresh entry points share the limiter, authenticate each new sequence, and publish notice removal.
    @Test void refreshEntryPointsApplyPolicyAndRespectBothCadences() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicReference<String> policy = new AtomicReference<>(production(1, true));
        AtomicInteger versions = new AtomicInteger(), news = new AtomicInteger();
        try (SaoPackEngine engine = onlineEngine(clock, policy, versions, news)) {
            awaitEngine(engine); engine.refreshOnline(); awaitIdle(engine); assertEquals(1, versions.get());
            assertNotNull(engine.updateStatus().get().maintenance());
            assertEquals(URI.create("https://api.example/argus/"), engine.accounts().accountEndpoint());
            clock.set(TimeUnit.SECONDS.toNanos(60)); engine.refreshOnline(); awaitIdle(engine); assertEquals(1, versions.get());
            clock.set(TimeUnit.SECONDS.toNanos(179)); engine.refreshOnline(); awaitIdle(engine); assertEquals(1, versions.get());
            policy.set(production(2, false)); clock.set(TimeUnit.SECONDS.toNanos(180)); engine.refreshOnline(); awaitIdle(engine);
            assertEquals(2, versions.get()); assertNull(engine.updateStatus().get().maintenance());
            clock.set(TimeUnit.SECONDS.toNanos(209)); engine.refreshOnline(true); awaitIdle(engine); assertEquals(2, versions.get());
            clock.set(TimeUnit.SECONDS.toNanos(210)); engine.refreshOnline(true); awaitIdle(engine); assertEquals(3, versions.get());
            clock.set(TimeUnit.SECONDS.toNanos(239)); engine.refreshUpdates(); awaitIdle(engine); assertEquals(3, versions.get());
            clock.set(TimeUnit.SECONDS.toNanos(240)); engine.refreshUpdates(); awaitIdle(engine); assertEquals(4, versions.get());
            assertEquals(8, news.get());
        }
    }

    /// A failed attempt is rate-limited and retains cached maintenance, game target and account API.
    @Test void failedRefreshRetainsLastGoodAndConsumesRateLimit() throws Exception {
        Path root = temporary.resolve("failed"); SaoReleaseTestSupport.cacheVersion(root, production(1, true));
        AtomicLong clock = new AtomicLong(); AtomicInteger attempts = new AtomicInteger();
        try (SaoPackEngine engine = new SaoPackEngine(root, SaoReleaseTestSupport.TRUST, (request, limit) -> {
            if (request.uri().getPath().contains("version")) attempts.incrementAndGet();
            throw new IOException("Fixture unavailable");
        }, clock::get)) {
            awaitEngine(engine); engine.refreshOnline(); awaitIdle(engine); assertEquals(1, attempts.get());
            clock.set(TimeUnit.SECONDS.toNanos(20)); engine.refreshOnline(true); awaitIdle(engine); assertEquals(1, attempts.get());
            assertNotNull(engine.updateStatus().get().maintenance()); assertEquals("play.example:25566", engine.serverAddress());
            assertEquals(URI.create("https://api.example/argus/"), engine.accounts().accountEndpoint());
        }
    }

    /// In-memory synthetic session state exercises the real idle/session guard without sending any account request.
    @Test void accountApiDoesNotChangeDuringSignedInSessionAndAppliesAfterRestart() throws Exception {
        Path root = temporary.resolve("account"); SaoReleaseTestSupport.cacheVersion(root, production(1, false));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            awaitEngine(engine); SaoOathAccounts accounts = engine.accounts();
            syntheticSession(accounts, URI.create("https://api.example/argus/"));
            assertFalse(accounts.applyAccountApi("https://next.example/")); assertFalse(accounts.applyAccountApi(null));
            assertEquals(URI.create("https://api.example/argus/"), accounts.accountEndpoint());
            // Remove only the synthetic session; logout would intentionally try token revocation over HTTP.
            clearSyntheticSession(accounts);
            assertTrue(accounts.applyAccountApi("https://next.example/")); assertEquals(URI.create("https://next.example/"), accounts.accountEndpoint());
        }
        SaoReleaseTestSupport.cacheVersion(root, document(2, ",\"accountApi\":\"https://next.example/\""));
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
            awaitEngine(engine); assertEquals(URI.create("https://next.example/"), engine.accounts().accountEndpoint());
        }
    }

    /// A newly verified API is cached immediately but cannot rebind a live session; the next idle refresh adopts it.
    @Test void verifiedApiChangeIsDeferredForSessionThenAdoptedWhileSignedOut() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicReference<String> policy = new AtomicReference<>(production(1, true));
        AtomicInteger versions = new AtomicInteger(), news = new AtomicInteger();
        try (SaoPackEngine engine = onlineEngine(clock, policy, versions, news)) {
            awaitEngine(engine); engine.refreshOnline(); awaitIdle(engine);
            syntheticSession(engine.accounts(), URI.create("https://api.example/argus/"));
            policy.set(document(2, ",\"accountApi\":\"https://next.example/v2/\""));
            clock.set(TimeUnit.SECONDS.toNanos(30)); engine.refreshOnline(true); awaitIdle(engine);
            assertEquals("https://next.example/v2/", Objects.requireNonNull(engine.updateStatus().get().metadata()).accountApi());
            assertEquals(URI.create("https://api.example/argus/"), engine.accounts().accountEndpoint());
            assertFalse(engine.accounts().busy());
            clearSyntheticSession(engine.accounts());
            clock.set(TimeUnit.SECONDS.toNanos(60)); engine.refreshOnline(true); awaitIdle(engine);
            assertEquals(URI.create("https://next.example/v2/"), engine.accounts().accountEndpoint());
            policy.set(document(3, "")); clock.set(TimeUnit.SECONDS.toNanos(90)); engine.refreshOnline(true); awaitIdle(engine);
            assertEquals(URI.create(SaoAccountClient.DEFAULT_API), engine.accounts().accountEndpoint());
            assertEquals("mc.saooath.com", engine.serverAddress());
        }
    }

    /// Pending login/account operations reserve their API before transport starts, closing the signed-out login race.
    @Test void pendingAccountOperationDefersApiChanges() throws Exception {
        try (SaoPackEngine engine = SaoReleaseTestSupport.engine(temporary.resolve("pending"), null, false)) {
            awaitEngine(engine); SaoOathAccounts accounts = engine.accounts();
            assertTrue(accounts.applyAccountApi("https://original.example/"));
            var field = SaoOathAccounts.class.getDeclaredField("pending"); field.setAccessible(true);
            AtomicInteger pending = (AtomicInteger) read(field, accounts);
            pending.incrementAndGet();
            try {
                assertFalse(accounts.applyAccountApi("https://next.example/"));
                assertEquals(URI.create("https://original.example/"), accounts.accountEndpoint());
            } finally { pending.decrementAndGet(); }
            assertTrue(accounts.applyAccountApi("https://next.example/"));
        }
    }

    /// Multiple timer/button calls share one in-flight refresh even when the policy interval has elapsed.
    @Test void concurrentRefreshRequestsRemainCoalesced() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger versions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        String policy = production(1, true), news = "{\"schema\":2,\"sequence\":0,\"news\":[]}";
        try (SaoPackEngine engine = new SaoPackEngine(temporary.resolve("coalesced"), SaoReleaseTestSupport.TRUST, (request, limit) -> {
            boolean version = request.uri().getPath().contains("version");
            if (!version) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
            else if (!request.uri().getPath().endsWith(".sig")) versions.incrementAndGet();
            String document = version ? policy : news;
            return new SaoOnlineData.Response(200, null, request.uri().getPath().endsWith(".sig")
                    ? SaoReleaseTestSupport.sign(document) : document.getBytes(StandardCharsets.UTF_8));
        }, clock::get)) {
            awaitEngine(engine); engine.refreshOnline(); assertTrue(entered.await(10, TimeUnit.SECONDS));
            clock.set(TimeUnit.MINUTES.toNanos(10));
            for (int i = 0; i < 20; i++) { engine.refreshOnline(true); engine.refreshUpdates(); }
            release.countDown(); awaitIdle(engine); assertEquals(1, versions.get());
        } finally { release.countDown(); }
    }

    /// Existing explicit sao.account.api fixtures override signed account settings, including literal-loopback HTTP.
    @Test void explicitAccountOverrideAndDefaultRemainAvailable() throws Exception {
        @Nullable String previous = System.getProperty("sao.account.api");
        try {
            System.setProperty("sao.account.api", "http://127.0.0.1:20002/");
            Path root = temporary.resolve("override"); SaoReleaseTestSupport.cacheVersion(root, production(1, false));
            try (SaoPackEngine engine = SaoReleaseTestSupport.engine(root, null, false)) {
                awaitEngine(engine); assertEquals(URI.create("http://127.0.0.1:20002/"), engine.accounts().accountEndpoint());
            }
            System.clearProperty("sao.account.api");
            try (SaoPackEngine engine = SaoReleaseTestSupport.engine(temporary.resolve("default"), null, false)) {
                awaitEngine(engine); assertEquals(URI.create(SaoAccountClient.DEFAULT_API), engine.accounts().accountEndpoint());
            }
        } finally { if (previous == null) System.clearProperty("sao.account.api"); else System.setProperty("sao.account.api", previous); }
    }

    /// Both CLI operations validate the new fields and print target/maintenance summaries using only a throwaway key.
    @Test void cliSummaryIncludesProductionSettingsAndOffDefaults() throws Exception {
        Path file = temporary.resolve("version.json"), key = temporary.resolve("fixture-key.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(SaoReleaseTestSupport.KEY.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        for (String policy : List.of(production(1, true), document(2, ""))) {
            Files.writeString(file, policy); Files.deleteIfExists(file.resolveSibling("version.json.sig"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            assertEquals(0, SaoReleaseTool.run(new String[]{"--sign-release", file.toString(), "--key", key.toString()}, output, SaoReleaseTestSupport.TRUST));
            assertEquals(0, SaoReleaseTool.run(new String[]{"--verify-release", file.toString()}, output, SaoReleaseTestSupport.TRUST));
            String summary = policy.equals(production(1, true)) ? "maintenance=on server=play.example:25566 accountApi=https://api.example/argus/"
                    : "maintenance=off server=mc.saooath.com accountApi=" + SaoAccountClient.DEFAULT_API;
            assertEquals(2, bytes.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains(summary)).count());
        }
    }

    /// Builds a file-only manifest; neither its unused file identity nor source path is downloaded or opened.
    private static SaoPackManifest manifest(String server) {
        return new SaoPackManifest("0.81", "1.20.1", "47.1.106", server,
                List.of(new SaoPackManifest.PackFile("config/fixture.txt", "0".repeat(40), 1, "overrides/config/fixture.txt", List.of())), List.of());
    }

    /// Supplies exact signed policy/news bytes through the production verifier and persistence implementation.
    private SaoPackEngine onlineEngine(AtomicLong clock, AtomicReference<String> policy, AtomicInteger versions, AtomicInteger news) {
        return new SaoPackEngine(temporary.resolve("online"), SaoReleaseTestSupport.TRUST, (request, limit) -> {
            boolean version = request.uri().getPath().contains("version");
            boolean signature = request.uri().getPath().endsWith(".sig");
            if (!signature) { if (version) versions.incrementAndGet(); else news.incrementAndGet(); }
            String document = version ? policy.get() : "{\"schema\":2,\"sequence\":0,\"news\":[]}";
            return new SaoOnlineData.Response(200, null, signature ? SaoReleaseTestSupport.sign(document) : document.getBytes(StandardCharsets.UTF_8));
        }, clock::get);
    }

    /// Waits for file-only startup inspection, never a toolkit, DNS resolver or account HTTP request.
    private static void awaitEngine(SaoPackEngine engine) {
        await(() -> engine.state().get() != SaoPackState.CHECKING); assertNull(engine.lastError().get());
    }

    /// Joins the actual private coalescing fences; reflection avoids adding a production UI synchronization API.
    private static void awaitIdle(SaoPackEngine engine) throws Exception {
        var updates = SaoPackEngine.class.getDeclaredField("updatesRefreshing"); updates.setAccessible(true);
        var online = SaoPackEngine.class.getDeclaredField("onlineRefreshing"); online.setAccessible(true);
        await(() -> !((AtomicBoolean) read(updates, engine)).get() && !((AtomicBoolean) read(online, engine)).get());
    }

    /// Reads one known non-null private fixture field, translating reflection failures into test assertion failures.
    private static Object read(java.lang.reflect.Field field, Object instance) {
        try { return Objects.requireNonNull(field.get(instance)); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /// Installs only the memory-only session guard; no token is accepted by a server or published to friends.
    private static void syntheticSession(SaoOathAccounts accounts, URI root) throws Exception {
        Class<?> type = Class.forName(SaoOathAccounts.class.getName() + "$Session");
        var constructor = type.getDeclaredConstructor(URI.class, String.class, String.class, String.class, String.class); constructor.setAccessible(true);
        var field = SaoOathAccounts.class.getDeclaredField("session"); field.setAccessible(true);
        field.set(accounts, constructor.newInstance(root, "synthetic-not-a-token", "synthetic-client", "FixturePlayer", "0".repeat(32)));
    }

    /// Clears the synthetic memory-only guard without invoking logout's network revocation path.
    private static void clearSyntheticSession(SaoOathAccounts accounts) throws Exception {
        var field = SaoOathAccounts.class.getDeclaredField("session"); field.setAccessible(true); field.set(accounts, null);
    }

    /// Bounded asynchronous condition wait with a useful test timeout rather than long sleeps.
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (!condition.getAsBoolean()) Thread.sleep(5); });
    }
}
