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
    @Test void closeCannotRebindBearerToChangedOrigin()throws Exception{
        var received=new AtomicBoolean();var second=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        second.createContext("/",exchange->{received.set(exchange.getRequestHeaders().containsKey("Authorization"));exchange.sendResponseHeaders(200,-1);exchange.close();});second.start();
        try{
            URI original=URI.create("http://127.0.0.1:1/");var selected=new AtomicReference<>(original);var transport=new SaoAccountClient(selected::get,()->"zh_cn");
            var friends=new SaoFriends(temporary,transport,message->{});friends.session(new SaoFriends.Session(original,UUID.randomUUID().toString(),UUID.randomUUID().toString().replace("-",""),"OriginFixture"));
            selected.set(URI.create("http://127.0.0.1:"+second.getAddress().getPort()+"/"));friends.close();Thread.sleep(1500);
            assertFalse(received.get(),"Old social bearer must remain bound to its issuing origin");
        }finally{second.stop(0);}
    }
}
