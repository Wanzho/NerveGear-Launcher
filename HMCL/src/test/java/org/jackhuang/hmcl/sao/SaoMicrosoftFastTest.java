/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/// Headless production-chain tests with literal-loopback transport and no real credentials or game launch.
@NotNullByDefault
class SaoMicrosoftFastTest {
    /// Disposable account storage.
    @TempDir Path root;

    /// Resolves platform temp aliases before exercising production path hardening.
    @org.junit.jupiter.api.BeforeEach void canonicalStorageRoot() throws Exception { root = root.toRealPath(); }

    /// Identity precedes proof, all four refresh reads overlap, and repeated login shares one attempt.
    @Test void identityFirstAndParallelRefresh() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            fixture.holdChallenge = true;
            CompletableFuture<Void> login = accounts.premiumLogin();
            assertTrue(fixture.challenge.await(3, TimeUnit.SECONDS));
            assertEquals("Fixture", Objects.requireNonNull(accounts.microsoftIdentity().get()).name());
            assertEquals(SaoOathLinking.Kind.LINKING, accounts.oathLinking().get().kind());
            assertNull(accounts.account().get());
            assertSame(login, accounts.premiumLogin());
            fixture.release.countDown();
            login.get(5, TimeUnit.SECONDS);
            awaitState(accounts, SaoOathLinking.Kind.LINKED);
            assertEquals(1, fixture.sessions.get());
            assertEquals(8, fixture.chainRequests.get(), "Four proof/login requests and four independent refresh requests");
            assertEquals(4, fixture.maxReads.get());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(fixture.lastRead.get() - fixture.firstRead.get());
            assertTrue(elapsed < 650, "Four 200ms branches must finish near one branch, not 800ms");
            SaoAccountClient transport = new SaoAccountClient(fixture::uri, () -> "zh_cn");
            long sequentialStart = System.nanoTime();
            for (String route : List.of("sao/account", "sao/card", "sao/textures", "sessionserver/session/minecraft/profile/11111111111111111111111111111111"))
                transport.json("GET", route, null, "fixture");
            long sequential = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sequentialStart);
            assertTrue(sequential >= 800);
            assertTrue(elapsed * 2 < sequential);
            System.out.println("MSFAST_SEQUENTIAL refreshReads=4 elapsedMs=" + sequential);
            System.out.println("MSFAST_FIXTURE steps=10 httpRoundTrips=8 refreshBranches=4 maxConcurrent=4 refreshElapsedMs=" + elapsed);
        }
    }

    /// Remembered profile publication does not wait for credential refresh.
    @Test void rememberedIdentityBeforeSessionRefresh() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.holdSession = true;
            try (SaoOathAccounts accounts = accounts(fixture)) {
                accounts.restoreMicrosoft();
                assertTrue(fixture.sessionEntered.await(3, TimeUnit.SECONDS));
                assertNotNull(accounts.microsoftIdentity().get());
                assertEquals(0, fixture.chainRequests.get());
                fixture.sessionRelease.countDown();
                awaitState(accounts, SaoOathLinking.Kind.LINKED);
            }
        }
    }

    /// Logout mid-proof clears identity and blocks all subsequent admission requests and queued launch.
    @Test void logoutMidLinkFencesIdentityAndLaunch() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            fixture.holdChallenge = true;
            CompletableFuture<Void> login = accounts.premiumLogin();
            assertTrue(fixture.challenge.await(3, TimeUnit.SECONDS));
            CompletableFuture<Void> launch = accounts.linkStart();
            assertFalse(launch.isDone());
            assertTrue(accounts.oathLinkWaiting().get());
            accounts.logout(); fixture.release.countDown();
            assertThrows(ExecutionException.class, () -> login.get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> launch.get(5, TimeUnit.SECONDS));
            assertNull(accounts.microsoftIdentity().get());
            assertNull(accounts.account().get());
            assertEquals(SaoOathLinking.Kind.IDLE, accounts.oathLinking().get().kind());
            assertEquals(1, fixture.chainRequests.get());
            assertFalse(accounts.oathLinkWaiting().get());
        }
    }

    /// Explicit cancellation has the same generation fence as logout without waiting for an HTTP timeout.
    @Test void cancelMidLink() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            fixture.holdChallenge = true;
            CompletableFuture<Void> login = accounts.premiumLogin();
            assertTrue(fixture.challenge.await(3, TimeUnit.SECONDS));
            accounts.cancelMicrosoftLink(); fixture.release.countDown();
            assertThrows(ExecutionException.class, () -> login.get(5, TimeUnit.SECONDS));
            assertNull(accounts.microsoftIdentity().get());
            assertEquals(SaoOathLinking.Kind.IDLE, accounts.oathLinking().get().kind());
            assertEquals(1, fixture.chainRequests.get());
        }
    }

    /// Business and network failures retain their existing mapping in both the future and observable state.
    @Test void failureMappingAndWaitingLaunch() throws Exception {
        for (String code : List.of("premiumLinked", "invalidPremiumProof", "unavailable")) {
            try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
                fixture.failure = code; fixture.holdChallenge = true;
                CompletableFuture<Void> login = accounts.premiumLogin();
                assertTrue(fixture.challenge.await(3, TimeUnit.SECONDS));
                CompletableFuture<Void> launch = accounts.linkStart();
                assertFalse(launch.isDone()); fixture.release.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> login.get(5, TimeUnit.SECONDS));
                SaoAccountException safe = assertInstanceOf(SaoAccountException.class, failure.getCause());
                assertEquals(code, safe.code());
                awaitState(accounts, SaoOathLinking.Kind.FAILED);
                assertEquals(safe.getMessage(), accounts.oathLinking().get().reason());
                ExecutionException launchFailure = assertThrows(ExecutionException.class, () -> launch.get(5, TimeUnit.SECONDS));
                assertEquals(safe.getMessage(), launchFailure.getCause().getMessage());
                assertNull(accounts.account().get());
            }
        }
    }

    /// An absent remembered identity does not open Microsoft login or contact ARGUS at startup.
    @Test void noRememberedIdentityIsIdle() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = new SaoOathAccounts(root, fixture::uri,
                () -> "zh_cn", new SaoOathCredentialStore(root, false), () -> { throw new AssertionError("Unexpected sign-in"); },
                fixture.uri().resolve("join"), account -> fail("No game"), error -> { })) {
            accounts.restoreMicrosoft();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (accounts.busy() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(accounts.busy());
            assertNull(accounts.microsoftIdentity().get());
            assertEquals(0, fixture.chainRequests.get());
            assertEquals(SaoOathLinking.Kind.IDLE, accounts.oathLinking().get().kind());
        }
    }

    /// The Microsoft skin property updates while the first ARGUS request is still blocked.
    @Test void skinAppearsDuringLinking() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            fixture.skin = new SaoMicrosoftSkin.Skin(bytes.toByteArray(), true, null);
            fixture.holdChallenge = true;
            CompletableFuture<Void> login = accounts.premiumLogin();
            assertTrue(fixture.challenge.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Objects.requireNonNull(accounts.microsoftIdentity().get()).skin() == null && System.nanoTime() < deadline) Thread.sleep(5);
            SaoMicrosoftIdentity identity = Objects.requireNonNull(accounts.microsoftIdentity().get());
            assertNotNull(identity.skin()); assertTrue(identity.slim());
            assertTrue(java.nio.file.Files.isRegularFile(identity.skin()));
            assertEquals(SaoOathLinking.Kind.LINKING, accounts.oathLinking().get().kind());
            accounts.cancelMicrosoftLink(); fixture.release.countDown();
            assertThrows(ExecutionException.class, () -> login.get(5, TimeUnit.SECONDS));
            assertNull(accounts.microsoftIdentity().get());
        }
    }

    /// Separate account transport instances reuse the same keep-alive pool and TCP connection.
    @Test void sharedKeepAliveClient() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        server.createContext("/", exchange -> {
            ports.add(exchange.getRemoteAddress().getPort());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            for (int i = 0; i < 3; i++) new SaoAccountClient(() -> uri, () -> "zh_cn").json("GET", "", null, null);
            assertEquals(1, ports.size());
        } finally { server.stop(0); }
    }

    /// A valid freshly installed or restored Microsoft session performs zero validation/refresh calls.
    @Test void freshSessionIsReusedUntilProviderExpiry() throws Exception {
        org.jackhuang.hmcl.auth.OAuth.Callback callback = (org.jackhuang.hmcl.auth.OAuth.Callback)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{org.jackhuang.hmcl.auth.OAuth.Callback.class},
                        (proxy, method, arguments) -> { throw new AssertionError("No OAuth allowed"); });
        AtomicInteger refreshes = new AtomicInteger();
        var service = new org.jackhuang.hmcl.auth.microsoft.MicrosoftService(callback) {
            /// Never connects to Microsoft during this test.
            @Override public boolean validate(long expiry, String type, String token) { return false; }
            /// Produces one renewed synthetic session after expiry.
            @Override public org.jackhuang.hmcl.auth.microsoft.MicrosoftSession refresh(org.jackhuang.hmcl.auth.microsoft.MicrosoftSession old) {
                refreshes.incrementAndGet();
                return new org.jackhuang.hmcl.auth.microsoft.MicrosoftSession(old.tokenType(), old.accessToken(),
                        System.currentTimeMillis() + 60000, old.refreshToken(), old.user(), old.profile());
            }
            /// Suppresses any incidental profile-property lookup.
            @Override public Optional<org.jackhuang.hmcl.auth.yggdrasil.CompleteGameProfile> getCompleteGameProfile(UUID id) { return Optional.empty(); }
        };
        for (boolean expired : new boolean[]{false, true}) {
            var metadata = new com.google.gson.JsonObject();
            metadata.addProperty("accountID", "account:22222222-2222-2222-2222-222222222222");
            metadata.addProperty("profileID", "11111111-1111-1111-1111-111111111111");
            var data = new com.google.gson.JsonObject();
            data.addProperty("profileName", "Fixture"); data.addProperty("tokenType", "Bearer");
            data.addProperty("accessToken", "fixture"); data.addProperty("refreshToken", "fixture");
            data.addProperty("userid", "fixture"); data.addProperty("notAfter", expired ? 0 : System.currentTimeMillis() + 60000);
            var account = new org.jackhuang.hmcl.auth.microsoft.MicrosoftAccountFactory(service).fromStorage(metadata, data);
            assertEquals("Fixture", SaoMicrosoftLogin.reuse(account).getUsername());
            SaoMicrosoftLogin.reuse(account);
            assertEquals(expired ? 1 : 0, refreshes.get());
        }
    }

    /// Explicit sync prefers premium and publishes fresh pixels before the first ARGUS read.
    @Test void manualSyncPremium() throws Exception { manualSuccess(true, 0); }

    /// Explicit sync overwrites an occupied own slot, then selects it and requests cape inheritance.
    @Test void manualSyncReplacesOwn() throws Exception { manualSuccess(false, 0); }

    /// A known cooldown suppresses only inheritance refresh, not the requested skin replacement.
    @Test void manualSyncDuringCooldown() throws Exception { manualSuccess(false, 90); }

    /// Both successful branches retain the provider's model and perform no additional session acquisition.
    private void manualSuccess(boolean premiumSlot, long cooldown) throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            accounts.premiumLogin().get(5, TimeUnit.SECONDS);
            assertEquals("Signed in", accounts.savedSkins().get(0).name());
            fixture.premiumSlot = premiumSlot; fixture.cooldown = cooldown;
            fixture.manual = true; fixture.skin = sampleSkin();
            fixture.previewCheck = () -> {
                assertNotNull(accounts.skin());
                assertTrue(accounts.skinSlim());
                assertNotNull(Objects.requireNonNull(accounts.microsoftIdentity().get()).skin());
            };
            accounts.syncMicrosoftSkin().get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.sessions.get());
            assertEquals(premiumSlot ? 0 : 1, fixture.uploads.get());
            assertEquals(premiumSlot ? "premium" : "own", fixture.choice);
            assertEquals(cooldown == 0 ? 1 : 0, fixture.refreshes.get());
            assertTrue(fixture.previewObserved.get());
            assertEquals("Refreshed", accounts.savedSkins().get(0).name());
        }
    }

    /// Missing stored sessions cannot open the injected interactive login path.
    @Test void manualSyncNoSession() throws Exception { manualFailure("noMicrosoftAccount", "请先用 Microsoft 登录"); }

    /// Provider connectivity errors expose only a short curated message.
    @Test void manualSyncOffline() throws Exception { manualFailure("network", "网络连接失败，请联网后重试"); }

    /// An unavailable ARGUS endpoint retains the freshly published preview and never uploads.
    @Test void manualSyncArgusOffline() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            accounts.premiumLogin().get(5, TimeUnit.SECONDS);
            fixture.manual = true; fixture.argusOffline = true; fixture.skin = sampleSkin();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accounts.syncMicrosoftSkin().get(5, TimeUnit.SECONDS));
            assertEquals("网络连接失败，请联网后重试", failure.getCause().getMessage());
            assertNotNull(accounts.skin()); assertEquals(0, fixture.uploads.get());
        }
    }

    /// Logout during the provider read prevents late preview publication and all server writes.
    @Test void manualSyncLogoutFence() throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            accounts.premiumLogin().get(5, TimeUnit.SECONDS);
            fixture.manual = true; fixture.holdSkin = true; fixture.skin = sampleSkin();
            CompletableFuture<Void> sync = accounts.syncMicrosoftSkin();
            assertTrue(fixture.skinEntered.await(3, TimeUnit.SECONDS));
            accounts.logout(); fixture.skinRelease.countDown();
            assertThrows(ExecutionException.class, () -> sync.get(5, TimeUnit.SECONDS));
            assertNull(accounts.skin()); assertNull(accounts.microsoftIdentity().get());
            assertTrue(accounts.savedSkins().isEmpty());
            assertEquals(0, fixture.uploads.get()); assertEquals(0, fixture.refreshes.get());
        }
    }

    /// An account with no ACTIVE custom skin cannot change server cosmetics.
    @Test void manualSyncNoCustomSkin() throws Exception { manualFailure("noMicrosoftSkin", "这个微软账号没有自定义皮肤"); }

    /// Server cooldown races retain the account client's friendly Chinese mapping.
    @Test void manualSyncCooldownRace() throws Exception { manualFailure("textureRefreshLimited", "操作太频繁，请 2 分钟后再试"); }

    /// Exercises exceptional futures without real identity providers or remote services.
    private void manualFailure(String code, String message) throws Exception {
        try (Fixture fixture = new Fixture(); SaoOathAccounts accounts = accounts(fixture)) {
            if (!code.equals("noMicrosoftAccount")) accounts.premiumLogin().get(5, TimeUnit.SECONDS);
            fixture.manual = true;
            fixture.stored = !code.equals("noMicrosoftAccount");
            fixture.skinOffline = code.equals("network");
            fixture.refreshLimited = code.equals("textureRefreshLimited");
            if (fixture.refreshLimited) fixture.skin = sampleSkin();
            int sessions = fixture.sessions.get();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accounts.syncMicrosoftSkin().get(5, TimeUnit.SECONDS));
            SaoAccountException safe = assertInstanceOf(SaoAccountException.class, failure.getCause());
            assertEquals(code, safe.code()); assertEquals(message, safe.getMessage());
            assertEquals(sessions, fixture.sessions.get());
            if (!fixture.refreshLimited) assertEquals(0, fixture.uploads.get());
        }
    }

    /// Creates valid synthetic SLIM pixels; no real account or public image is used.
    private static SaoMicrosoftSkin.Skin sampleSkin() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return new SaoMicrosoftSkin.Skin(bytes.toByteArray(), true, null);
    }

    /// Uses the production controller with an offline identity/skin provider and no launch permission.
    private SaoOathAccounts accounts(Fixture fixture) throws Exception {
        return new SaoOathAccounts(root, fixture::uri, () -> "zh_cn", new SaoOathCredentialStore(root, false),
                new SaoOathAccounts.PremiumProof() {
                    /// Identity is available without authentication.
                    public AuthInfo known() { return fixture.auth; }
                    /// Fake latency can hold credential renewal independently of identity publication.
                    public AuthInfo session() throws Exception {
                        fixture.sessions.incrementAndGet(); fixture.sessionEntered.countDown();
                        if (fixture.holdSession) assertTrue(fixture.sessionRelease.await(3, TimeUnit.SECONDS));
                        return fixture.auth;
                    }
                    /// No public provider request is allowed in these transport fixtures.
                    public SaoMicrosoftSkin.@Nullable Skin skin(AuthInfo auth) throws Exception {
                        if (fixture.holdSkin) {
                            fixture.skinEntered.countDown();
                            assertTrue(fixture.skinRelease.await(3, TimeUnit.SECONDS));
                        }
                        if (fixture.skinOffline) throw new java.io.IOException("Fixture offline");
                        return fixture.skin;
                    }
                    /// Supplies premium library pixels without filesystem or network source reads.
                    public @Unmodifiable List<SaoPremiumSkins.Entry> library(@Nullable String name) throws Exception {
                        return List.of(new SaoPremiumSkins.Entry(fixture.manual ? "Refreshed" : "Signed in", sampleSkin().png(), true));
                    }
                    /// Manual actions use only this stored fixture session, never session().
                    public @Nullable AuthInfo stored(@Nullable String name) { return fixture.stored ? fixture.auth : null; }
                }, fixture.uri().resolve("join"), account -> fail("No game"), error -> { });
    }

    /// Waits only for the final observable callback, which follows future completion.
    private static void awaitState(SaoOathAccounts accounts, SaoOathLinking.Kind expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (accounts.oathLinking().get().kind() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, accounts.oathLinking().get().kind());
    }

    /// Minimal concurrent account endpoint with counted chain routes and separately ignored social traffic.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Synthetic profile identity used only in memory.
        final AuthInfo auth = new AuthInfo("Fixture", UUID.fromString("11111111-1111-1111-1111-111111111111"), "fixture", "msa", "{}");
        /// Loopback listener, never a production endpoint.
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        /// Independent server workers expose accidental client serialization.
        final ExecutorService executor = Executors.newCachedThreadPool();
        /// Counts actual session acquisition calls.
        final AtomicInteger sessions = new AtomicInteger();
        /// Counts only the eight deterministic chain requests.
        final AtomicInteger chainRequests = new AtomicInteger();
        /// Active refresh requests.
        final AtomicInteger reading = new AtomicInteger();
        /// Peak overlapping refresh reads.
        final AtomicInteger maxReads = new AtomicInteger();
        /// Start of the first refresh request.
        final AtomicLong firstRead = new AtomicLong(Long.MAX_VALUE);
        /// Completion of the last refresh request.
        final AtomicLong lastRead = new AtomicLong();
        /// Proof-entry signal.
        final CountDownLatch challenge = new CountDownLatch(1);
        /// Explicit proof release.
        final CountDownLatch release = new CountDownLatch(1);
        /// Authentication-entry signal.
        final CountDownLatch sessionEntered = new CountDownLatch(1);
        /// Explicit authentication release.
        final CountDownLatch sessionRelease = new CountDownLatch(1);
        /// Holds proof for cancellation and ordering tests.
        volatile boolean holdChallenge;
        /// Holds session refresh for startup ordering tests.
        volatile boolean holdSession;
        /// Optional public preview for the ordering test.
        volatile SaoMicrosoftSkin.@Nullable Skin skin;
        /// Optional safe failure code returned by premium login.
        volatile String failure = "";

        /// Enables manual-action route observations after initial admission.
        volatile boolean manual;
        /// Stored-session availability independent of the login provider.
        volatile boolean stored = true;
        /// Simulates a provider transport failure.
        volatile boolean skinOffline;
        /// Simulates ARGUS service unavailability independently of Microsoft.
        volatile boolean argusOffline;
        /// Holds the manual provider read for the logout fence test.
        volatile boolean holdSkin;
        /// Signals provider read entry.
        final CountDownLatch skinEntered = new CountDownLatch(1);
        /// Releases the held provider read.
        final CountDownLatch skinRelease = new CountDownLatch(1);
        /// Whether inherited premium pixels are offered by ARGUS.
        volatile boolean premiumSlot;
        /// Remaining inheritance cooldown in seconds.
        volatile long cooldown;
        /// Simulates a server-side cooldown racing the state read.
        volatile boolean refreshLimited;
        /// Counts replacement multipart uploads.
        final AtomicInteger uploads = new AtomicInteger();
        /// Counts inheritance requests.
        final AtomicInteger refreshes = new AtomicInteger();
        /// Records the selected source from the actual JSON request.
        volatile String choice = "";
        /// Verifies preview visibility at the first subsequent server request.
        volatile Runnable previewCheck = () -> { };
        /// Records a successful preview assertion on the server worker.
        final AtomicBoolean previewObserved = new AtomicBoolean();

        /// Produces schema-valid occupied slots and cooldown state without any image downloads.
        String textures() {
            String slot = premiumSlot ? "premiumSkin" : "ownSkin";
            String hash = "a".repeat(64);
            return "{\"linked\":true,\"skinSource\":\"own\",\"capeSource\":\"none\",\"effectiveSkinSource\":\"own\",\"effectiveCapeSource\":\"none\",\"images\":{\""
                    + slot + "\":{\"hash\":\"" + hash + "\",\"model\":\"slim\",\"url\":\"" + uri() + "textures/" + hash
                    + "\"}},\"lastRefresh\":1,\"refreshAfter\":" + cooldown + ",\"status\":\"synced\"}";
        }

        /// Serves deterministic responses, adding 200ms latency to each independent refresh read.
        Fixture() throws Exception {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    String path = exchange.getRequestURI().getPath();
                    String body;
                    int status = 200;
                    boolean refresh = path.equals("/sao/account") || path.equals("/sao/card")
                            || path.equals("/sao/textures") || path.contains("/session/minecraft/profile/");
                    if (refresh) {
                        firstRead.accumulateAndGet(System.nanoTime(), Math::min);
                        maxReads.accumulateAndGet(reading.incrementAndGet(), Math::max);
                        Thread.sleep(200); reading.decrementAndGet(); lastRead.accumulateAndGet(System.nanoTime(), Math::max);
                    }
                    if (manual && path.equals("/sao/textures")) {
                        previewCheck.run(); previewObserved.set(true); body = textures();
                        if (argusOffline) { status = 503; body = "{}"; }
                    } else if (manual && path.endsWith("/skin")) {
                        uploads.incrementAndGet();
                        assertEquals("PUT", exchange.getRequestMethod());
                        assertTrue(new String(request, StandardCharsets.ISO_8859_1).contains("slim"));
                        body = "{}";
                    } else if (manual && path.equals("/sao/textures/choices")) {
                        choice = com.google.gson.JsonParser.parseString(new String(request, StandardCharsets.UTF_8))
                                .getAsJsonObject().get("skinSource").getAsString();
                        body = textures();
                    } else if (manual && path.equals("/sao/textures/refresh")) {
                        refreshes.incrementAndGet(); body = textures();
                        if (refreshLimited) { status = 429; body = "{\"error\":\"textureRefreshLimited\",\"waitSeconds\":90}"; }
                    } else if (path.equals("/sao/premium/challenge")) {
                        chainRequests.incrementAndGet(); challenge.countDown();
                        if (holdChallenge) release.await(3, TimeUnit.SECONDS);
                        body = "{\"serverId\":\"fixture\",\"challengeToken\":\"fixture\"}";
                    } else if (path.equals("/join")) { chainRequests.incrementAndGet(); body = "{}"; }
                    else if (path.equals("/sao/premium/complete")) { chainRequests.incrementAndGet(); body = "{\"premiumToken\":\"fixture\"}"; }
                    else if (path.equals("/sao/premium/login")) {
                        chainRequests.incrementAndGet();
                        body = "{\"accessToken\":\"fixture\",\"clientToken\":\"fixture\",\"selectedProfile\":{\"id\":\"11111111111111111111111111111111\",\"name\":\"Fixture\"}}";
                        if (!failure.isEmpty()) { status = failure.equals("unavailable") ? 503 : 409; body = "{\"error\":\"" + failure + "\"}"; }
                    } else if (path.equals("/sao/account")) { chainRequests.incrementAndGet(); body = "{\"id\":\"11111111111111111111111111111111\",\"name\":\"Fixture\",\"state\":\"ready\",\"emailVerified\":true,\"graceSeconds\":0,\"premiumName\":\"Fixture\"}"; }
                    else if (path.equals("/sao/card")) { chainRequests.incrementAndGet(); body = "{\"id\":\"11111111111111111111111111111111\",\"level\":1,\"col\":0,\"friends\":[]}"; }
                    else if (path.equals("/sao/textures")) { chainRequests.incrementAndGet(); body = "{\"linked\":true,\"skinSource\":\"own\",\"capeSource\":\"none\",\"effectiveSkinSource\":\"own\",\"effectiveCapeSource\":\"none\",\"images\":{},\"lastRefresh\":1,\"refreshAfter\":0,\"status\":\"synced\"}"; }
                    else if (refresh) { chainRequests.incrementAndGet(); body = "{}"; }
                    else { status = 404; body = "{}"; }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        /// Captured literal-loopback origin.
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
        /// Releases held handlers before stopping only fixture-owned resources.
        public void close() { skinRelease.countDown(); release.countDown(); sessionRelease.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
