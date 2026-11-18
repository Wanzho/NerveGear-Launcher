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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/// Ensures lifecycle cleanup cannot disclose the previous social bearer to a changed API origin.
@NotNullByDefault
class SaoFriendsOriginTest {
    /// Isolated nonsecret device/cursor storage.
    @TempDir Path temporary;
    /// An API change during shutdown must not rebind the old session's disconnect request.
    @Test void closeCannotRebindBearerToChangedOrigin()throws Exception {
        var received = new AtomicBoolean();var second = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        second.createContext("/",exchange -> { received.set(exchange.getRequestHeaders().containsKey("Authorization"));exchange.sendResponseHeaders(200,-1);exchange.close(); });second.start();
        try {
            URI original = URI.create("http://127.0.0.1:1/");var selected = new AtomicReference<>(original);var transport = new SaoAccountClient(selected::get,() -> "zh_cn");
            var friends = new SaoFriends(temporary,transport,message -> {});friends.session(new SaoFriends.Session(original,UUID.randomUUID().toString(),UUID.randomUUID().toString().replace("-",""),"OriginFixture"));
            selected.set(URI.create("http://127.0.0.1:" + second.getAddress().getPort() + "/"));friends.close();Thread.sleep(1500);
            assertFalse(received.get(),"Old social bearer must remain bound to its issuing origin");
        } finally { second.stop(0); }
    }
}
