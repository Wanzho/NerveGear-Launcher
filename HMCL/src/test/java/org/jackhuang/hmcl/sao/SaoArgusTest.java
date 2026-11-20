/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.game.Arguments;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// Real server-core API contracts and local process/file proofs; never opens JavaFX or a Minecraft client.
@NotNullByDefault
@EnabledIfSystemProperty(named = "sao.account.proof.root", matches = ".+")
class SaoArgusTest {
    /// Disposable per-test user roots, never real launcher data.
    @TempDir Path temporary;

    /// The first independently proven Microsoft login creates ARGUS ID; later proof logs into the same stable profile.
    @Test void premiumCreatesThenLogsIntoLinkedAccount() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("premium")) {
            Path root = temporary.resolve("client");
            String id;
            try (SaoPackEngine engine = engine(root, server)) {
                await(engine.oathPremiumLogin());
                SaoData.OathAccount account = account(engine); id = account.profileId();
                assertEquals("PremiumFixture", account.name()); assertEquals("PremiumFixture", account.premiumName());
                assertEquals("", account.maskedEmail()); assertFalse(account.emailVerified());
                assertTrue(server.joined()); assertTrue(server.tokenStayedLocal());
                engine.oathLogout(); assertNull(engine.oathAccount().get());
                await(engine.oathPremiumLogin()); assertEquals(id, account(engine).profileId());
                assertEquals("PremiumFixture", account(engine).premiumName());
                await(engine.oathRename("LinkedNickname", new char[0])); engine.oathLogout();
                await(engine.oathPremiumLogin());
                assertEquals("LinkedNickname", account(engine).name()); assertEquals(id, account(engine).profileId());
                assertEquals("PremiumFixture", account(engine).premiumName()); server.assertHealthy();
            }
        }
    }

    /// Independently proven ownership takes the name while the prior holder retains safe management access.
    @Test void premiumTakesNameAndHolderMustRename() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("taken"); SaoPackEngine engine = engine(temporary.resolve("taken-client"), server)) {
            String password = new String(secret());
            await(engine.oathRegister("PremiumFixture", "taken@example.test", password.toCharArray()));
            String holderId = account(engine).profileId();
            engine.oathLogout();
            await(engine.oathPremiumLogin());
            String premiumId = account(engine).profileId();
            assertEquals("PremiumFixture", account(engine).name()); assertNotEquals(holderId, premiumId);
            assertFalse(account(engine).mustRename());
            engine.oathLogout();
            await(engine.oathPremiumLogin()); assertEquals(premiumId, account(engine).profileId());
            engine.oathLogout();
            await(engine.oathLogin("PREMIUMFIXTURE", password.toCharArray(), false));
            assertEquals(holderId, account(engine).profileId());
            assertTrue(account(engine).mustRename()); assertFalse(account(engine).canJoin());
            assertNotEquals("premiumfixture", account(engine).name().toLowerCase(java.util.Locale.ROOT));
            engine.state.set(SaoPackState.READY);
            engine.linkStartOath();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (engine.lastError().get() == null) Thread.sleep(10);
            });
            assertEquals("昵称已被正版玩家使用，请在启动器里更换昵称", engine.lastError().get());
            SaoAccountException refusal = assertThrows(SaoAccountException.class, () -> engine.accounts().prepareLaunchAccount());
            assertEquals("mustRename", refusal.code()); assertNotNull(engine.oathAccount().get());
            await(engine.oathRename("HolderNewName", password.toCharArray()));
            assertEquals(holderId, account(engine).profileId()); assertFalse(account(engine).mustRename());
            engine.oathLogout();
            failure(engine.oathLogin("PremiumFixture", password.toCharArray(), false), "invalidCredentials");
            await(engine.oathLogin("HolderNewName", password.toCharArray(), false));
            assertEquals(holderId, account(engine).profileId());
            assertTrue(server.tokenStayedLocal()); server.assertHealthy();
        }
    }

    /// Rename is case-insensitively unique and limited by the server, retains profile ID, and preserves protected auto-login.
    @Test void renameReuseQuotaAndRememberedRestart() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("rename")) {
            Path root = temporary.resolve("rename-client");
            char[] password = secret(); String privatePassword = new String(password);
            String id;
            try (SaoPackEngine engine = engine(root, server)) {
                await(engine.oathRegister("ReservedName", "reserved@example.test", secret())); engine.oathLogout();
                await(engine.oathRegister("RenameStart", "rename@example.test", password));
                id = account(engine).profileId();
                await(engine.oathLogin("RenameStart", privatePassword.toCharArray(), true));
                assertFalse(engine.oathNameAvailable("reservedname").get(10, TimeUnit.SECONDS));
                failure(engine.oathRename("rEsErVeDnAmE", privatePassword.toCharArray()), "nameTaken");
                assertTrue(engine.oathNameAvailable("RenameOne").get(10, TimeUnit.SECONDS));
                await(engine.oathRename("RenameOne", privatePassword.toCharArray())); assertEquals(id, account(engine).profileId());
                assertTrue(engine.oathNameAvailable("renamestart").get(10, TimeUnit.SECONDS));
                await(engine.oathRename("RenameTwo", privatePassword.toCharArray())); await(engine.oathRename("RenameThree", privatePassword.toCharArray()));
                failure(engine.oathRename("RenameFour", privatePassword.toCharArray()), "renameLimit");
                assertEquals("RenameThree", account(engine).name()); assertEquals(id, account(engine).profileId());
                assertEquals("RenameThree", engine.rememberedOathName());
            }
            try (SaoPackEngine reopened = engine(root, server)) {
                assertNull(reopened.oathAccount().get());
                await(reopened.oathLogin(Objects.requireNonNull(reopened.rememberedOathName()), null, true));
                assertEquals(id, account(reopened).profileId());
                reopened.oathLogout(); assertNull(reopened.rememberedOathName());
                assertNull(new SaoOathCredentialStore(root, false).read());
            }
            try (SaoPackEngine signedOut = engine(root, server)) {
                assertNull(signedOut.rememberedOathName()); failure(signedOut.oathLogin("RenameThree", null, true), "missingPassword");
            }
        }
    }

    /// HMCL's actual-process hooks count a controlled fake game, retain totals across restart, and ignore later account signout.
    @Test void cardMergesFakeProcessLifetimeAndPersistsByStableId() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("card")) {
            Path root = temporary.resolve("card-client"); String privatePassword = new String(secret());
            try (SaoPackEngine engine = engine(root, server)) {
                await(engine.oathRegister("CardFixture", "card@example.test", privatePassword.toCharArray()));
                SaoData.PlayerCard first = Objects.requireNonNull(engine.playerCard().get());
                assertEquals(-1, first.level()); assertEquals(-1, first.floor()); assertEquals(-1, first.col());
                assertTrue(first.friendsOnline().isEmpty()); assertEquals(0, first.playSeconds());
                SaoOathLaunchAccount prepared = (SaoOathLaunchAccount) engine.accounts().prepareLaunchAccount();
                Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 1.2").start();
                engine.accounts().gameStarted(prepared);
                assertTrue(process.waitFor(10, TimeUnit.SECONDS)); engine.accounts().gameExited();
                await(engine.oathLogin("CardFixture", privatePassword.toCharArray(), false));
                SaoData.PlayerCard played = Objects.requireNonNull(engine.playerCard().get());
                assertTrue(played.lastPlayed() > 0); assertTrue(played.playSeconds() >= 1 && played.playSeconds() < 10);
            }
            try (SaoPackEngine reopened = engine(root, server)) {
                await(reopened.oathLogin("CardFixture", privatePassword.toCharArray(), false));
                assertTrue(Objects.requireNonNull(reopened.playerCard().get()).playSeconds() >= 1);
            }
        }
    }

    /// Final launch preparation mints a private file, while HMCL arguments contain only standard authentication and public metadata.
    @Test void launchFileIsPrivateAndNeverInArguments() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("ticket"); SaoPackEngine engine = engine(temporary.resolve("ticket-client"), server)) {
            await(engine.oathRegister("TicketFixture", "ticket@example.test", secret()));
            SaoOathLaunchAccount prepared = (SaoOathLaunchAccount) engine.accounts().prepareLaunchAccount();
            AuthInfo info = prepared.logIn(); Path instance = temporary.resolve("instance");
            try (SaoOathLaunchTicket handoff = prepared.ticket(instance, "127.0.0.1:25565")) {
                Path path = instance.resolve(SaoOathLaunchTicket.FILE_NAME);
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                assertEquals(account(engine).profileId(), json.get("profileId").getAsString());
                String sensitive = json.get("ticket").getAsString();
                List<String> arguments = Arguments.parseArguments(info.getLaunchArguments(null).jvm(), Map.of());
                assertTrue(arguments.stream().noneMatch(arg -> arg.contains(sensitive)));
                assertTrue(arguments.stream().noneMatch(arg -> arg.contains(SaoOathLaunchTicket.FILE_NAME)));
                assertEquals("[authentication data omitted]", SaoGameLogs.sanitize(json.toString()));
            }
            assertFalse(Files.exists(instance.resolve(SaoOathLaunchTicket.FILE_NAME)));
        }
    }

    /// C-6 against the real sao_auth router: the token handed to the game joins, but cannot read or change the account;
    /// the email change needs the current password.
    @Test void realServerGameTokenJoinsButCannotTouchTheAccount() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("game-token"); SaoPackEngine engine = engine(temporary.resolve("game-token-client"), server)) {
            char[] password = secret(); String kept = new String(password);
            await(engine.oathRegister("GameTokenFixture", "game@example.test", password));
            SaoOathLaunchAccount prepared = (SaoOathLaunchAccount) engine.accounts().prepareLaunchAccount();
            AuthInfo info = prepared.logIn();
            SaoAccountClient client = new SaoAccountClient(server::root, () -> "zh_cn");
            JsonObject join = new JsonObject(); join.addProperty("accessToken", info.getAccessToken());
            join.addProperty("selectedProfile", account(engine).profileId()); join.addProperty("serverId", "c6-fixture");
            client.json("POST", "sessionserver/session/minecraft/join", join, null);
            assertThrows(SaoAccountException.class, () -> client.json("GET", "sao/account", null, info.getAccessToken()));
            JsonObject steal = new JsonObject(); steal.addProperty("email", "thief@example.test"); steal.addProperty("password", kept);
            assertThrows(SaoAccountException.class, () -> client.json("POST", "sao/email", steal, info.getAccessToken()));
            assertThrows(SaoAccountException.class, () -> client.json("POST", "sao/launch", new JsonObject(), info.getAccessToken()));
            JsonObject refresh = new JsonObject(); refresh.addProperty("accessToken", info.getAccessToken());
            assertThrows(SaoAccountException.class, () -> client.json("POST", "authserver/refresh", refresh, null));
            assertNotNull(engine.oathAccount().get(), "Refused game-token calls leave the launcher session");
            failure(engine.oathChangeEmail("next@example.test", secret()), "wrongPassword");
            assertNotNull(engine.oathAccount().get(), "A wrong current password keeps the session");
            failure(engine.oathChangeEmail("next@example.test", new char[0]), "missingPassword");
            await(engine.oathChangeEmail("next@example.test", kept.toCharArray()));
            assertNull(engine.oathAccount().get(), "The email change revokes the session as before");
        }
    }

    /// Expired handoffs fail before writing and valid files are removed automatically when their bounded lifetime ends.
    @Test void handoffExpiryAndSymlinkRefusal() throws Exception {
        JsonObject reply = ticket(System.currentTimeMillis() - 2 * SaoOathLaunchTicket.MAX_CLOCK_SKEW);
        assertThrows(SaoAccountException.class, () -> new SaoOathLaunchTicket(temporary.resolve("expired"), "http://127.0.0.1/", "a".repeat(32), "localhost", reply));
        // A PC clock a few minutes fast sees a fresh ticket as already expired; the server decides, so it is written.
        // The game mod checks the written expiry against the PC clock (now, now + 10 min], so it is written on that clock.
        for (long offset : new long[]{-300_000, 3_600_000, 120_000}) {
            Path skewed = Files.createDirectory(temporary.resolve("skewed" + offset));
            try (SaoOathLaunchTicket ignored = new SaoOathLaunchTicket(skewed, "http://127.0.0.1/", "a".repeat(32), "localhost", ticket(System.currentTimeMillis() + offset))) {
                assertTrue(Files.exists(skewed.resolve(SaoOathLaunchTicket.FILE_NAME)));
                long written = JsonParser.parseString(Files.readString(skewed.resolve(SaoOathLaunchTicket.FILE_NAME))).getAsJsonObject().get("expiresAtMillis").getAsLong();
                long now = System.currentTimeMillis();
                assertTrue(written > now && written - now <= 600_000, "Expiry on the PC clock for server offset " + offset);
            }
        }
        Path instance = Files.createDirectory(temporary.resolve("expiry"));
        try (SaoOathLaunchTicket ignored = new SaoOathLaunchTicket(instance, "http://127.0.0.1/", "a".repeat(32), "localhost", ticket(System.currentTimeMillis() + 500))) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> { while (Files.exists(instance.resolve(SaoOathLaunchTicket.FILE_NAME))) Thread.sleep(10); });
        }
        Path outside = Files.writeString(temporary.resolve("outside"), "preserve");
        Files.createSymbolicLink(instance.resolve(SaoOathLaunchTicket.FILE_NAME), outside);
        assertThrows(java.io.IOException.class, () -> new SaoOathLaunchTicket(instance, "http://127.0.0.1/", "a".repeat(32), "localhost", ticket(System.currentTimeMillis() + 10_000)));
        assertEquals("preserve", Files.readString(outside));
    }

    /// Monotonic duration survives backward wall-clock changes and repeated exit callbacks without double counting.
    @Test void elapsedUsesMonotonicClockAndExitIsIdempotent() throws Exception {
        AtomicLong wall = new AtomicLong(1000), nano = new AtomicLong(100_000_000);
        SaoOathPlayTime time = new SaoOathPlayTime(temporary, wall::get, nano::get);
        URI api = URI.create("http://127.0.0.1/"); String id = "b".repeat(32);
        SaoOathPlayTime.Run run = time.start(api, id); wall.set(0); nano.addAndGet(2_750_000_000L);
        run.close(); nano.addAndGet(9_000_000_000L); run.close();
        assertEquals(1000, time.read(api, id).lastPlayed()); assertEquals(2750, time.read(api, id).playMillis());
        assertEquals(0, time.read(URI.create("http://127.0.0.2/"), id).playMillis());
    }

    /// Signing out during a live HTTP login prevents its late response from restoring the protected pair or account.
    @Test void signoutFencesLateRememberedLogin() throws Exception {
        try (SaoLocalPremiumFixture server = fixture("signout-race")) {
            Path root = temporary.resolve("race-client"); String privatePassword = new String(secret());
            try (SaoPackEngine engine = engine(root, server)) {
                await(engine.oathRegister("RaceFixture", "race@example.test", privatePassword.toCharArray()));
                await(engine.oathLogin("RaceFixture", privatePassword.toCharArray(), true));
                server.delayLogin();
                CompletableFuture<Void> login = engine.oathLogin("RaceFixture", null, true);
                try { server.awaitDelayedLogin(); engine.oathLogout(); }
                finally { server.releaseLogin(); }
                failure(login, "cancelled");
                assertNull(engine.oathAccount().get()); assertNull(engine.rememberedOathName());
                assertNull(new SaoOathCredentialStore(root, false).read());
                assertThrows(SaoAccountException.class, () -> engine.accounts().prepareLaunchAccount());
            }
            try (SaoPackEngine reopened = engine(root, server)) { assertNull(reopened.rememberedOathName()); }
        }
    }

    /// Creates an isolated real account router with a separate loopback Mojang endpoint.
    private SaoLocalPremiumFixture fixture(String name) throws Exception {
        return new SaoLocalPremiumFixture(temporary.resolve(name), Path.of(System.getProperty("sao.account.proof.serverJar")));
    }

    /// Injects Microsoft proof and encrypted fallback storage; never calls the native/UI Microsoft sign-in bridge.
    private SaoPackEngine engine(Path root, SaoLocalPremiumFixture server) throws Exception {
        AuthInfo microsoft = new AuthInfo("PremiumFixture", server.premiumId(), server.microsoftToken(), "msa", "{}");
        SaoPackEngine engine = new SaoPackEngine(root, server::root, new SaoOathCredentialStore(root, false), () -> microsoft, server.mojangJoin());
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(10); });
        return engine;
    }

    /// Produces private test credentials in memory only.
    private static char[] secret() { return ("Proof" + UUID.randomUUID().toString().replace("-", "")).toCharArray(); }

    /// Returns the account published before its corresponding future completed.
    private static SaoData.OathAccount account(SaoPackEngine engine) { return Objects.requireNonNull(engine.oathAccount().get()); }

    /// Awaits a safe public future without serializing request data.
    private static void await(CompletableFuture<Void> operation) throws Exception { operation.get(30, TimeUnit.SECONDS); }

    /// Checks curated error codes without printing any server response or credential.
    private static void failure(CompletableFuture<Void> operation, String code) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> operation.get(30, TimeUnit.SECONDS));
        assertInstanceOf(SaoAccountException.class, error.getCause()); assertEquals(code, ((SaoAccountException) Objects.requireNonNull(error.getCause())).code());
    }

    /// Synthesizes a private handoff response without committing any ticket literal.
    private static JsonObject ticket(long expiry) {
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        JsonObject reply = new JsonObject(); reply.addProperty("launchTicket", Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        reply.addProperty("expiresAtMillis", expiry); reply.addProperty("id", "a".repeat(32)); return reply;
    }
}
