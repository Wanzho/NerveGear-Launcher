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

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.auth.yggdrasil.RemoteAuthenticationException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/// Loopback-only Microsoft device endpoint fixtures; never connects to Microsoft or opens a browser.
@NotNullByDefault
class SaoMicrosoftDeviceTest {
    /// Records public device callbacks and forbids a localhost OAuth redirect server in device mode.
    @NotNullByDefault
    private static final class Callback implements OAuth.Callback {
        /// Number of successful authorizations reported by OAuth.
        final AtomicInteger completed = new AtomicInteger();
        /// Public code delivered to the screen.
        final AtomicReference<String> code = new AtomicReference<>("");
        /// Device mode must not request an authorization-code callback server.
        @Override public OAuth.Session startServer() { throw new AssertionError("Unexpected redirect server"); }

        /// Captures only the user code, not the secret device code.
        @Override public void grantDeviceCode(String userCode, String uri) { code.set(userCode); }

        /// Marks successful device authorization.
        @Override public void loginCompletedDeviceCode() { completed.incrementAndGet(); }

        /// Deliberately does nothing instead of launching a browser.
        @Override public void openBrowser(OAuth.GrantFlow flow, String url) { }

        /// Supplies a non-production fixture id.
        @Override public String getClientId() { return "fixture-client"; }
    }

    /// Checks the actual form body and ensures device denial cannot be mistaken for successful authorization.
    @Test void deviceUsesSameClientAndRejectsDeniedToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> deviceForm = new AtomicReference<>("");
        AtomicReference<String> tokenForm = new AtomicReference<>("");
        server.createContext("/device", exchange -> {
            deviceForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"device_code\":\"fixture-secret\",\"user_code\":\"TEST-CODE\",\"verification_uri\":\"https://example.invalid/device\",\"expires_in\":300,\"interval\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/token", exchange -> {
            tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"error\":\"authorization_declined\",\"error_description\":\"fixture denied\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            OAuth oauth = new OAuth(root + "/unused", root + "/unused", root + "/device", root + "/token");
            Callback callback = new Callback();
            assertThrows(RemoteAuthenticationException.class, () -> oauth.authenticate(OAuth.GrantFlow.DEVICE,
                    new OAuth.Options("XboxLive.signin offline_access", callback)));
            assertTrue(deviceForm.get().contains("client_id=fixture-client"));
            assertTrue(tokenForm.get().contains("client_id=fixture-client"));
            assertTrue(tokenForm.get().contains("device_code=fixture-secret"));
            assertEquals("TEST-CODE", callback.code.get()); assertEquals(0, callback.completed.get());
        } finally { server.stop(0); }
    }
}
