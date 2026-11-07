package org.jackhuang.hmcl.sao;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.game.Arguments;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// End-to-end engine contracts against the local dedicated account server and unchanged released premium router.
/// Enabled only by the explicit proof wrapper; no public account or game endpoint is contacted.
@NotNullByDefault
@EnabledIfSystemProperty(named = "sao.account.proof.root", matches = ".+")
class SaoOathIntegrationTest {
    /// Entire client storage for each test is disposable and isolated from real launcher accounts.
    @TempDir Path temporary;

    /// Exercises durable account mutations, explicit remembered login, ephemeral sessions, skin bytes and injector arguments.
    @Test void localAccountLifecycleAndEngineRestart() throws Exception {
        Path root = temporary.resolve("launcher");
        SaoOathCredentialStore credentials = new SaoOathCredentialStore(root, true);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name = "Oath" + suffix;
        String email = "proof-" + suffix + "@example.test";
        String nextEmail = "next-" + suffix + "@example.test";
        String original = secret();
        String replacement = secret();
        String last = secret();
        String id;
        java.net.URI endpoint = java.net.URI.create("http://127.0.0.1:47281/");
        try (SaoPackEngine engine = engine(root, credentials, endpoint)) {
            awaitIdle(engine);
            assertNull(engine.oathAccount().get());
            char[] buffer = original.toCharArray();
            await(engine.oathRegister(name, email, buffer));
            assertWiped(buffer);
            SaoData.OathAccount registered = requireAccount(engine);
            id = registered.profileId();
            assertEquals(name, registered.name());
            assertFalse(registered.emailVerified());
            String firstCode = code(email, null);
            await(engine.oathResend());
            String verificationCode = code(email, firstCode);
            await(engine.oathVerify(verificationCode));
            assertTrue(requireAccount(engine).emailVerified());
            expectFailure(engine.oathVerify(verificationCode), "验证码不对或已过期");
            assertTrue(requireAccount(engine).emailVerified());
            engine.oathLogout();
            awaitSignedOut(engine);
            expectFailure(engine.oathLogin(name, secret().toCharArray(), false), "密码错误");
            await(engine.oathForgot(name));
            String resetCode = code(email, verificationCode);
            char[] resetPassword = replacement.toCharArray();
            await(engine.oathReset(name, resetCode, resetPassword));
            assertWiped(resetPassword);
            assertNull(engine.oathAccount().get());
            expectFailure(engine.oathLogin(name, original.toCharArray(), false), "密码错误");
            await(engine.oathLogin(name, replacement.toCharArray(), false));
            assertEquals(id, requireAccount(engine).profileId());
            await(engine.oathChangeEmail(nextEmail));
            assertNull(engine.oathAccount().get());
            await(engine.oathLogin(name, replacement.toCharArray(), false));
            assertFalse(requireAccount(engine).emailVerified());
            await(engine.oathVerify(code(nextEmail, null)));
            assertTrue(requireAccount(engine).emailVerified());
            char[] oldBuffer = replacement.toCharArray();
            char[] newBuffer = last.toCharArray();
            await(engine.oathChangePassword(oldBuffer, newBuffer));
            assertWiped(oldBuffer);
            assertWiped(newBuffer);
            assertNull(engine.oathAccount().get());
            await(engine.oathLogin(name, last.toCharArray(), true));
            assertEquals(name, engine.rememberedOathName());
            assertPrivateFiles(root, List.of(name, original, replacement, last));
        }
        try (SaoPackEngine engine = engine(root, new SaoOathCredentialStore(root, true), endpoint)) {
            awaitIdle(engine);
            assertNull(engine.oathAccount().get(), "Sessions must never survive restart");
            assertEquals(name, engine.rememberedOathName());
            expectFailure(engine.oathLogin("AnotherName", null, true), null);
            await(engine.oathLogin(name, null, true));
            assertEquals(id, requireAccount(engine).profileId());
            Path png = temporary.resolve("source.png");
            byte[] originalPng = png();
            Files.write(png, originalPng);
            await(engine.uploadSkin(png, true));
            @Nullable Path cached = engine.oathSkin();
            assertNotNull(cached);
            assertPixelsEqual(png, cached);
            assertTrue(engine.skinSlim());
            SaoData.SavedSkin saved = engine.saveSkin("Fixture skin", originalPng, true);
            assertTrue(Files.isRegularFile(saved.file()));
            assertTrue(saved.slim());
            assertEquals(1, engine.savedSkins().size());
            engine.deleteSavedSkin(saved);
            assertTrue(engine.savedSkins().isEmpty());
            assertFalse(Files.exists(saved.file()));
            assertTrue(Arrays.equals(originalPng, Files.readAllBytes(png)), "Picker source must stay unchanged");
            assertThrows(IllegalArgumentException.class, () -> engine.saveSkin("bad", new byte[]{1, 2, 3}, false));
            AuthlibInjectorAccount account = engine.accounts().prepareLaunchAccount();
            AuthInfo info = account.logIn();
            assertEquals(name, info.getUsername());
            assertEquals(id, info.getUUID().toString().replace("-", ""));
            List<String> arguments = Arguments.parseArguments(info.getLaunchArguments(null).jvm(), Map.of());
            assertTrue(arguments.stream().anyMatch(value -> value.startsWith("-javaagent:") && value.endsWith("=" + endpoint)));
            assertTrue(arguments.contains("-Dauthlibinjector.side=client"));
            assertTrue(arguments.stream().anyMatch(value -> value.startsWith("-Dauthlibinjector.yggdrasil.prefetched=")));
            assertTrue(arguments.stream().noneMatch(value -> value.contains(last) || value.contains(replacement) || value.contains(original)));
            assertThrows(AuthenticationException.class, account::playOffline);
            JsonObject revoke = new JsonObject(); revoke.addProperty("accessToken", info.getAccessToken());
            new SaoAccountClient(() -> endpoint, () -> "zh_cn").json("POST", "authserver/invalidate", revoke, null);
            revoke.remove("accessToken");
            assertThrows(SaoAccountException.class, () -> engine.accounts().prepareLaunchAccount());
            assertNull(engine.oathAccount().get(), "A failed refresh must sign the player out");
            await(engine.oathLogin(name, null, true));
            assertThrows(AuthenticationException.class, account::logIn);
            assertNotNull(engine.oathAccount().get(), "An obsolete prepared account must not clear the new login");
            await(engine.uploadSkin(null, false));
            assertNull(engine.oathSkin());
            engine.oathLogout();
            awaitSignedOut(engine);
            assertNull(engine.rememberedOathName(), "Signout forgets the remembered pair");
            expectFailure(engine.oathLogin(name, null, true), "请输入密码");
            await(engine.oathLogin(name, last.toCharArray(), false));
            assertNull(engine.rememberedOathName());
            assertNull(credentials.read());
        }
    }

    /// A local skin library stays available while signed out, survives an engine restart, and never needs a server call.
    @Test void signedOutLibrarySurvivesRestart() throws Exception {
        Path root = temporary.resolve("library-client");
        java.net.URI endpoint = java.net.URI.create("http://127.0.0.1:1/");
        Path saved;
        try (SaoPackEngine engine = engine(root, new SaoOathCredentialStore(root, false), endpoint)) {
            awaitIdle(engine);
            assertNull(engine.oathAccount().get());
            saved = engine.saveSkin("Kept offline", png(), false).file();
            assertEquals(1, engine.savedSkins().size());
        }
        try (SaoPackEngine engine = engine(root, new SaoOathCredentialStore(root, false), endpoint)) {
            awaitIdle(engine);
            assertNull(engine.oathAccount().get());
            assertEquals(1, engine.savedSkins().size());
            assertEquals(saved, engine.savedSkins().get(0).file());
            engine.deleteSavedSkin(engine.savedSkins().get(0));
            assertFalse(Files.exists(saved));
        }
    }

    /// Real account-server premium proof accepts only the challenge joined through the independent Mojang endpoint.
    @Test void premiumFlowUsesRealReleasedServerAndSeparateMojangEndpoint() throws Exception {
        Path serverJar = Path.of(System.getProperty("sao.account.proof.serverJar"));
        try (SaoLocalPremiumFixture fixture = new SaoLocalPremiumFixture(temporary.resolve("server"), serverJar)) {
            Path client = temporary.resolve("client");
            SaoOathCredentials credentials = new SaoOathCredentialStore(client, false);
            AuthInfo microsoft = new AuthInfo("PremiumFixture", fixture.premiumId(), fixture.microsoftToken(), "msa", "{}");
            try (SaoPackEngine engine = new SaoPackEngine(client, fixture::root, credentials, () -> microsoft, fixture.mojangJoin())) {
                awaitIdle(engine);
                await(engine.oathRegister("PremiumFixture", "premium@example.test", secret().toCharArray()));
                await(engine.oathLinkPremium());
                assertTrue(fixture.joined());
                assertTrue(fixture.tokenStayedLocal());
                assertNull(engine.oathAccount().get(), "Linking revokes the SAO session");
                fixture.assertHealthy();
            }
        }
    }

    /// An expired verification grace still permits account management, while Link Start never reaches the launch callback.
    @Test void lockedManagementSessionCannotReachLaunch() throws Exception {
        Path serverJar = Path.of(System.getProperty("sao.account.proof.serverJar"));
        try (SaoLocalPremiumFixture fixture = new SaoLocalPremiumFixture(temporary.resolve("locked-server"), serverJar, 0)) {
            Path root = temporary.resolve("locked-client");
            java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
            try (SaoOathAccounts accounts = new SaoOathAccounts(root, fixture::root, () -> "zh_cn",
                    new SaoOathCredentialStore(root, false), () -> { throw new AssertionError("Unexpected Microsoft proof"); },
                    fixture.mojangJoin(), account -> launches.incrementAndGet(), message -> fail("Unexpected launch error"))) {
                accounts.linkStart();
                assertEquals(0, launches.get());
                await(accounts.register("LockedFixture", "locked@example.test", secret().toCharArray()));
                assertTrue(java.util.Objects.requireNonNull(accounts.account().get()).locked());
                accounts.linkStart();
                await(accounts.resend());
                assertEquals(0, launches.get());
                assertTrue(java.util.Objects.requireNonNull(accounts.account().get()).locked());
                fixture.assertHealthy();
            }
        }
    }

    /// Constructs a headless engine whose Microsoft hook fails if an ordinary test unexpectedly asks for it.
    private static SaoPackEngine engine(Path root, SaoOathCredentials credentials, java.net.URI endpoint) {
        return new SaoPackEngine(root, () -> endpoint, credentials,
                () -> { throw new AssertionError("Unexpected Microsoft session request"); },
                java.net.URI.create("http://127.0.0.1:1/session/minecraft/join"));
    }

    /// Resolves the explicit private mail fixture without ever printing a code or message.
    private static String code(String recipient, @Nullable String previous) {
        AtomicReference<@Nullable String> result = new AtomicReference<>();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Path mail = Path.of(System.getProperty("sao.account.proof.root"), "server-1", "private-fixtures", "mail-fixture.json");
            while (result.get() == null) {
                if (Files.isRegularFile(mail)) {
                    JsonArray messages = JsonParser.parseString(Files.readString(mail)).getAsJsonArray();
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        JsonObject object = messages.get(i).getAsJsonObject();
                        if (!object.get("to").getAsString().equals(recipient)) continue;
                        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(object.get("body").getAsString());
                        if (match.find() && !match.group().equals(previous)) result.set(match.group());
                        break;
                    }
                }
                if (result.get() == null) Thread.sleep(25);
            }
        });
        return java.util.Objects.requireNonNull(result.get());
    }

    /// Generates an in-memory test secret, avoiding committed password literals.
    private static String secret() { return "Proof" + UUID.randomUUID().toString().replace("-", ""); }

    /// Awaits a public operation without logging its request, account, or exception payload.
    private static void await(CompletableFuture<Void> operation) throws Exception { operation.get(30, TimeUnit.SECONDS); }

    /// Confirms a curated public failure label while avoiding assertion serialization of secrets.
    private static void expectFailure(CompletableFuture<Void> operation, @Nullable String message) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(30, TimeUnit.SECONDS));
        assertInstanceOf(SaoAccountException.class, failure.getCause());
        if (message != null) assertEquals(message, failure.getCause().getMessage());
    }

    /// Returns the already-published authenticated state after the corresponding future has completed.
    private static SaoData.OathAccount requireAccount(SaoPackEngine engine) { return java.util.Objects.requireNonNull(engine.oathAccount().get()); }

    /// Waits for background pack inspection without starting a game or JavaFX toolkit.
    private static void awaitIdle(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(10);
        });
    }

    /// Waits for asynchronous token invalidation and signed-out publication.
    private static void awaitSignedOut(SaoPackEngine engine) {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            while (engine.oathAccount().get() != null) Thread.sleep(10);
        });
    }

    /// Verifies ownership of every password array was consumed and erased.
    private static void assertWiped(char[] values) { for (char value : values) assertTrue(value == '\0', "Password buffer must be wiped"); }

    /// Scans client files only for plaintext name/password sentinels, without rendering their contents on failure.
    private static void assertPrivateFiles(Path root, List<String> secrets) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                for (String secret : secrets) assertFalse(text.contains(secret), "Client disk contains an unprotected account field");
            }
        }
    }

    /// Creates distinct actual PNG pixels; a separate decoder verifies uploads independent of encoding differences.
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(17, 31, 0xff3377bb);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /// Compares all decoded pixels because both server and launcher sanitize PNG metadata by re-encoding.
    private static void assertPixelsEqual(Path expected, Path actual) throws Exception {
        BufferedImage first = ImageIO.read(expected.toFile());
        BufferedImage second = ImageIO.read(actual.toFile());
        assertEquals(64, second.getWidth());
        assertEquals(64, second.getHeight());
        assertTrue(Arrays.equals(first.getRGB(0, 0, 64, 64, null, 0, 64), second.getRGB(0, 0, 64, 64, null, 0, 64)));
    }
}
