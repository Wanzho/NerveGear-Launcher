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
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Headless engine regression contracts for mandatory nickname replacement, using literal loopback only.
@NotNullByDefault
class SaoPremiumNameTest {
    /// Disposable launcher data, never an installed game directory.
    @TempDir Path temporary;

    /// A marked login reaches management, refuses Link Start immediately and clears the mark after normal rename.
    @Test void markedHolderCannotLaunchAndRenameRefreshesState() throws Exception {
        try (Fixture server = new Fixture(true)) {
            AtomicInteger launches = new AtomicInteger();
            AtomicReference<@Nullable String> error = new AtomicReference<>();
            try (SaoOathAccounts accounts = accounts(server, launches, error)) {
                accounts.login("HeldFixture", secret(), false).get(10, TimeUnit.SECONDS);
                assertTrue(Objects.requireNonNull(accounts.account().get()).mustRename());
                assertFalse(Objects.requireNonNull(accounts.account().get()).canJoin());
                accounts.linkStart();
                assertEquals(0, launches.get());
                assertEquals("昵称已被正版玩家使用，请在启动器里更换昵称", error.get());
                SaoAccountException refused = assertThrows(SaoAccountException.class, accounts::prepareLaunchAccount);
                assertEquals("mustRename", refused.code());
                assertNotNull(accounts.account().get());
                assertEquals(0, server.refreshes.get(), "Known mandatory rename must stop before launch preparation");
                accounts.rename("Replacement").get(10, TimeUnit.SECONDS);
                SaoData.OathAccount renamed = Objects.requireNonNull(accounts.account().get());
                assertEquals("Replacement", renamed.name());
                assertEquals(Fixture.ID, renamed.profileId());
                assertFalse(renamed.mustRename());
                assertTrue(renamed.canJoin());
            }
        }
    }

    /// A mark arriving on the final account refresh also refuses launch and retains the management session.
    @Test void finalRefreshCannotBypassMandatoryRename() throws Exception {
        try (Fixture server = new Fixture(false)) {
            AtomicInteger launches = new AtomicInteger();
            AtomicReference<@Nullable String> error = new AtomicReference<>();
            try (SaoOathAccounts accounts = accounts(server, launches, error)) {
                accounts.login("HeldFixture", secret(), false).get(10, TimeUnit.SECONDS);
                assertFalse(Objects.requireNonNull(accounts.account().get()).mustRename());
                server.mustRename = true;
                accounts.linkStart();
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                    while (error.get() == null) Thread.sleep(10);
                });
                assertEquals("昵称已被正版玩家使用，请在启动器里更换昵称", error.get());
                assertNotNull(accounts.account().get(), "The holder must keep access to rename management");
                assertTrue(Objects.requireNonNull(accounts.account().get()).mustRename());
                assertEquals(0, launches.get());
                accounts.rename("Replacement").get(10, TimeUnit.SECONDS);
                assertFalse(Objects.requireNonNull(accounts.account().get()).mustRename());
            }
        }
    }

    /// Unknown response text is replaced with the same curated short refusal used by the engine guard.
    @Test void ticketRefusalUsesCuratedRenameMessage() throws Exception {
        try (Fixture server = new Fixture(true)) {
            SaoAccountClient client = new SaoAccountClient(server::root, () -> "zh_cn");
            SaoAccountException failure = assertThrows(SaoAccountException.class,
                    () -> client.json("POST", "sao/launch", new JsonObject(), null));
            assertEquals("mustRename", failure.code());
            assertEquals("昵称已被正版玩家使用，请在启动器里更换昵称", failure.getMessage());
        }
    }

    /// A cosmetic outage cannot hide the authoritative rename refusal or invalidate a good login.
    @Test void cosmeticOutageDoesNotBlockAccountState() throws Exception {
        try (Fixture server = new Fixture(false)) {
            server.skinFailure = true;
            try (SaoOathAccounts accounts = accounts(server, new AtomicInteger(), new AtomicReference<>())) {
                accounts.login("HeldFixture", secret(), false).get(10, TimeUnit.SECONDS);
                assertTrue(Objects.requireNonNull(accounts.account().get()).canJoin());
                server.mustRename = true;
                SaoAccountException refused = assertThrows(SaoAccountException.class, accounts::prepareLaunchAccount);
                assertEquals("mustRename", refused.code());
                assertNotNull(accounts.account().get());
            }
        }
    }

    /// Creates an account controller whose launch callback can never create a game process.
    private SaoOathAccounts accounts(Fixture server, AtomicInteger launches, AtomicReference<@Nullable String> error) throws Exception {
        return new SaoOathAccounts(temporary, server::root, () -> "zh_cn", new SaoOathCredentialStore(temporary, false),
                () -> { throw new AssertionError("Unexpected Microsoft sign-in"); },
                URI.create("http://127.0.0.1:1/"), account -> launches.incrementAndGet(), error::set);
    }

    /// Creates a random in-memory password that is never printed or retained by the fixture.
    private static char[] secret() { return UUID.randomUUID().toString().toCharArray(); }

    /// A bounded local account-state stub; credentials are consumed without logging or persistent storage.
    @NotNullByDefault
    private static final class Fixture implements AutoCloseable {
        /// Stable synthetic character identity across mandatory rename.
        static final String ID = "b".repeat(32);
        /// Literal-loopback HTTP listener with no public network path.
        final HttpServer http;
        /// Counts refresh calls to prove the eager launch guard prevents preparation.
        final AtomicInteger refreshes = new AtomicInteger();
        /// Authoritative management state, changed by the test or normal rename operation.
        volatile boolean mustRename;
        /// Simulates a failed optional profile service while account verification works.
        volatile boolean skinFailure;
        /// Current unique synthetic nickname.
        volatile String name = "HeldFixture";

        /// Starts only a loopback listener, with no toolkit or Minecraft process.
        Fixture(boolean mark) throws Exception {
            mustRename = mark;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                int status = 200;
                JsonObject reply = new JsonObject();
                switch (path) {
                    case "/sao/launch" -> { status = 403; reply.addProperty("error", "mustRename"); reply.addProperty("errorMessage", "untrusted fixture detail"); }
                    case "/sao/rename" -> { name = "Replacement"; mustRename = false; reply = session(); }
                    case "/sao/login" -> reply = session();
                    case "/authserver/refresh" -> { refreshes.incrementAndGet(); reply = session(); }
                    case "/sao/account" -> reply = state();
                    case "/sao/card" -> { reply.addProperty("id", ID); reply.addProperty("name", name); reply.addProperty("level", -1); reply.addProperty("col", -1); }
                    default -> { reply.addProperty("id", ID); reply.addProperty("name", name); }
                }
                if (skinFailure && path.startsWith("/sessionserver/session/minecraft/profile/")) { status = 503; reply = new JsonObject(); }
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            http.start();
        }

        /// Returns the isolated API origin.
        URI root() { return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/"); }

        /// Produces the private account state used for both login and subsequent authoritative refresh.
        JsonObject state() {
            JsonObject state = new JsonObject();
            state.addProperty("id", ID); state.addProperty("name", name); state.addProperty("state", "ready");
            state.addProperty("maskedEmail", ""); state.addProperty("emailVerified", true);
            state.addProperty("graceSeconds", 0); state.addProperty("mustRename", mustRename);
            return state;
        }

        /// Produces fresh synthetic bearer values without printing them.
        JsonObject session() {
            JsonObject reply = new JsonObject();
            reply.addProperty("accessToken", UUID.randomUUID().toString()); reply.addProperty("clientToken", UUID.randomUUID().toString());
            JsonObject selected = new JsonObject(); selected.addProperty("id", ID); selected.addProperty("name", name);
            reply.add("selectedProfile", selected); reply.add("account", state()); return reply;
        }

        /// Releases the loopback listener even after an assertion fails.
        @Override public void close() { http.stop(0); }
    }
}
