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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.auth.yggdrasil.GameProfile;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// C-6: the game process gets only the game-scoped token; the launcher session token never reaches the launch plan.
/// Uses a loopback stand-in for the account API; never starts a game, toolkit or public network client.
@NotNullByDefault
class SaoGameTokenTest {
    /// Launcher session token the stand-in accepts; it must never appear in anything handed to the game.
    private static final String FULL = "F".repeat(43);
    /// Game-scoped token the stand-in mints.
    private static final String GAME = "g".repeat(43);
    /// Stable profile of the synthetic account.
    private static final String PROFILE = "0123456789abcdef0123456789abcdef";
    /// Disposable instance directory.
    @TempDir Path temporary;

    /// Loopback account API stand-in: validate succeeds, and /sao/game-token answers with the configured status.
    @NotNullByDefault
    private static final class Api implements AutoCloseable {
        /// The loopback server.
        final HttpServer server;
        /// HTTP status for /sao/game-token: 200 mints {@link #GAME}; anything else returns a JSON error.
        final AtomicInteger gameTokenStatus;
        /// Configurable final-session validation response.
        final AtomicInteger validateStatus = new AtomicInteger(204);
        /// Every request seen, as "METHOD path auth=<bearer or ->", for leak checks.
        final List<String> requests = new CopyOnWriteArrayList<>();

        /// Starts the stand-in on an ephemeral loopback port.
        Api(int status) throws IOException {
            gameTokenStatus = new AtomicInteger(status);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        /// Account API root.
        URI root() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }

        /// Serves validate and game-token; everything else is 404 like an unknown route.
        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            @Nullable String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path + " auth=" + (auth == null ? "-" : auth));
            int status; JsonObject reply = new JsonObject();
            if (path.equals("/authserver/validate")) {
                status = FULL.equals(JsonParser.parseString(body).getAsJsonObject().get("accessToken").getAsString()) ? validateStatus.get() : 403;
            } else if (path.equals("/sao/game-token") && ("Bearer " + FULL).equals(auth)) {
                status = gameTokenStatus.get();
                if (status == 200) {
                    reply.addProperty("accessToken", GAME); reply.addProperty("expiresIn", 86400);
                    JsonObject profile = new JsonObject(); profile.addProperty("id", PROFILE); profile.addProperty("name", "TokenFixture");
                    reply.add("selectedProfile", profile);
                } else { reply.addProperty("error", status == 404 ? "NotFoundException" : "ServiceUnavailableException"); reply.addProperty("errorMessage", "fixture"); }
            } else { status = 404; reply.addProperty("error", "NotFoundException"); reply.addProperty("errorMessage", "Endpoint not found."); }
            byte[] bytes = status == 204 ? new byte[0] : reply.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        /// Stops the stand-in.
        @Override public void close() { server.stop(0); }
    }

    /// Builds the production launch account for the synthetic session against the stand-in.
    private SaoOathLaunchAccount account(Api api, Runnable invalid) {
        URI root = api.root();
        GameProfile profile = new GameProfile(UUID.fromString(PROFILE.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")), "TokenFixture");
        YggdrasilSession session = new YggdrasilSession("fixture-client", FULL, profile, List.of(profile), Map.of());
        AuthlibInjectorArtifactInfo artifact = new AuthlibInjectorArtifactInfo(1, "1.0.0", temporary.resolve("authlib-injector.jar"));
        AuthlibInjectorArtifactProvider provider = new AuthlibInjectorArtifactProvider() {
            @Override public AuthlibInjectorArtifactInfo getArtifactInfo() { return artifact; }

            @Override public Optional<AuthlibInjectorArtifactInfo> getArtifactInfoImmediately() { return Optional.of(artifact); }
        };
        String metadata = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        return new SaoOathLaunchAccount(new AuthlibInjectorServer(root.toString()), provider, "TokenFixture", session,
                new SaoAccountClient(() -> root, () -> "zh_cn"), metadata, artifact, () -> true, invalid,
                (instance, server) -> { throw new AssertionError("No ticket in this test"); });
    }

    /// The real production launch plan, as the process launcher consumes it.
    private SaoGameBridge.LaunchPlan plan(SaoOathLaunchAccount account) throws Exception {
        return SaoGameBridge.launchPlan(account, manifest(), 2048, new SaoData.GameWindow(1280, 720, false),
                "-XX:+UseG1GC", JavaRuntime.CURRENT_JAVA, temporary.resolve("instance"), false);
    }

    /// Every string the launch plan hands to the game process: arguments, agent, environment, commands and identity.
    private static @Unmodifiable List<String> handedToGame(SaoGameBridge.LaunchPlan plan) throws IOException {
        AuthInfo auth = plan.auth(); LaunchOptions options = plan.options();
        List<String> values = new ArrayList<>();
        for (@Nullable Object value : new Object[]{auth.getAccessToken(), auth.getUsername(), auth.getUUID(), auth.getUserType(), auth.getUserProperties(), plan.server()})
            values.add(String.valueOf(value));
        @Nullable Arguments extra = auth.getLaunchArguments(options);
        if (extra != null) {
            if (extra.jvm() != null) values.addAll(Arguments.parseArguments(extra.jvm(), Map.of()));
            if (extra.game() != null) values.addAll(Arguments.parseArguments(extra.game(), Map.of()));
        }
        values.addAll(options.getJavaArguments()); values.addAll(options.getGameArguments()); values.addAll(options.getOverrideJavaArguments());
        options.getJavaAgents().forEach(agent -> values.add(agent.toString()));
        options.getEnvironmentVariables().forEach((key, value) -> { values.add(key); values.add(String.valueOf(value)); });
        if (options.getPreLaunchCommand() != null) values.add(options.getPreLaunchCommand());
        if (options.getPostExitCommand() != null) values.add(options.getPostExitCommand());
        if (options.getQuickPlayOption() != null) values.add(options.getQuickPlayOption().toString());
        return List.copyOf(values);
    }

    /// The game gets the game-scoped token; the session token appears nowhere in the plan and only travels to the API.
    @Test void gameGetsOnlyTheGameScopedToken() throws Exception {
        try (Api api = new Api(200)) {
            SaoGameBridge.LaunchPlan plan = plan(account(api, () -> fail("A minted game token must not sign the player out")));
            assertEquals(GAME, plan.auth().getAccessToken());
            List<String> values = handedToGame(plan);
            assertTrue(values.stream().noneMatch(value -> value.contains(FULL)), "Launcher session token must never reach the game");
            assertTrue(values.stream().anyMatch(value -> value.startsWith("-javaagent:")), "Injector agent still attached");
            assertEquals(List.of("POST /authserver/validate auth=-", "POST /sao/game-token auth=Bearer " + FULL), api.requests,
                    "Session token goes only to the account API: validate body and game-token bearer");
            assertEquals("<access token>", org.jackhuang.hmcl.util.logging.Logger.filterForbiddenToken(GAME), "Game token is redacted in logs");
        }
    }

    /// A server older than sao_auth 1.9.3 has no game-token route (404); the launch keeps today's behaviour.
    @Test void oldServerFallsBackToTheSessionToken() throws Exception {
        for (int missing : new int[]{404, 405, 501}) {
            try (Api api = new Api(missing)) {
                SaoGameBridge.LaunchPlan plan = plan(account(api, () -> fail("Fallback must not sign the player out")));
                assertEquals(FULL, plan.auth().getAccessToken(), "Old server: the session token is passed as before (status " + missing + ")");
            }
        }
    }

    /// Any other game-token failure stops the launch instead of silently handing the session token to the game.
    @Test void otherFailuresNeverFallBack() throws Exception {
        for (int failure : new int[]{403, 500, 503}) {
            try (Api api = new Api(failure)) {
                SaoOathLaunchAccount account = account(api, () -> { });
                assertThrows(AuthenticationException.class, account::logIn, "Status " + failure + " must not fall back");
            }
        }
    }

    /// Transient HTTP failures refuse this launch without revoking a still-valid prepared session.
    @Test void transientFailuresPreserveSessionAndRefuseLaunch() throws Exception {
        for (boolean validate : new boolean[]{true, false}) for (int status : new int[]{429, 500, 503}) {
                try (Api api = new Api(validate ? 200 : status)) {
                    if (validate) api.validateStatus.set(status);
                    AtomicInteger revoked = new AtomicInteger();
                    SaoOathLaunchAccount prepared = account(api, revoked::incrementAndGet);
                    assertThrows(AuthenticationException.class, prepared::logIn);
                    assertEquals(0, revoked.get(), "Temporary response must not clear session");
                    assertEquals(validate ? 1 : 2, api.requests.size(), "No automatic POST retries");
                    api.validateStatus.set(204); api.gameTokenStatus.set(200);
                    assertEquals(GAME, prepared.logIn().getAccessToken(), "Explicit later attempt may recover");
                }
            }
    }

    /// A connection refusal is a real transport failure, not proof that the session expired.
    @Test void transportFailurePreservesSession() throws Exception {
        Api api = new Api(200);
        AtomicInteger revoked = new AtomicInteger();
        SaoOathLaunchAccount prepared = account(api, revoked::incrementAndGet);
        api.close();
        assertThrows(AuthenticationException.class, prepared::logIn);
        assertEquals(0, revoked.get());
    }

    /// Authoritative invalid-session replies must still clear authorization and refuse launch.
    @Test void invalidSessionStillClears() throws Exception {
        try (Api api = new Api(200)) {
            api.validateStatus.set(403);
            AtomicInteger revoked = new AtomicInteger();
            assertThrows(AuthenticationException.class, account(api, revoked::incrementAndGet)::logIn);
            assertEquals(1, revoked.get());
            assertEquals(1, api.requests.size());
        }
    }

    /// The launch manifest used by the plan; its server is the Quick Play target.
    private static SaoPackManifest manifest() {
        return new SaoPackManifest("0.8", "1.20.1", "47.1.106", "server.example:25565", "",
                "https://account.example/", List.of(), List.of(new SaoPackManifest.PackFile("config/offline.txt",
                "da39a3ee5e6b4b0d3255bfef95601890afd80709", 0, "overrides/config/offline.txt", List.of())), List.of());
    }
}
