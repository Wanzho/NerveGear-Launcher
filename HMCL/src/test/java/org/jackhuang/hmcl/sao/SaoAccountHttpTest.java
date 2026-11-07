package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Loopback transport boundaries: locale, safe public errors, response bounds, and refused credential redirects.
@NotNullByDefault
class SaoAccountHttpTest {
    /// Every locale is normalized and submitted in both JSON and Accept-Language without changing endpoint origin.
    @Test void localeBodyAndHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> language = new AtomicReference<>("zh-Hant");
        AtomicReference<String> seenHeader = new AtomicReference<>("");
        AtomicReference<String> seenBody = new AtomicReference<>("");
        server.createContext("/", exchange -> {
            seenHeader.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            seenBody.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().get("language").getAsString());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            SaoAccountClient client = new SaoAccountClient(() -> root(server), language::get);
            client.json("POST", "sao/forgot", new JsonObject(), null);
            assertEquals("zh_cn", seenHeader.get());
            assertEquals("zh_cn", seenBody.get());
            language.set("de_CH");
            client.json("POST", "sao/forgot", new JsonObject(), null);
            assertEquals("en_us", seenHeader.get());
            assertEquals("en_us", seenBody.get());
        } finally { server.stop(0); }
    }

    /// Redirect targets receive no request, and arbitrary provider/error bodies never become screen messages.
    @Test void redirectsRefusedAndErrorsCurated() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(302);
        String sentinel = UUID.randomUUID().toString();
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/sao/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", root(server).resolve("target").toString());
            JsonObject error = new JsonObject(); error.addProperty("error", "ProviderRejected"); error.addProperty("errorMessage", sentinel);
            byte[] response = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            SaoAccountClient client = new SaoAccountClient(() -> root(server), () -> "zh_cn");
            JsonObject request = new JsonObject(); request.addProperty("password", UUID.randomUUID().toString());
            for (int response : new int[]{302, 400, 401, 403, 404, 413, 429, 500, 503}) {
                status.set(response);
                SaoAccountException failure = assertThrows(SaoAccountException.class, () -> client.json("POST", "sao/login", request, null));
                assertFalse(failure.getMessage().contains(sentinel));
                assertTrue(failure.getMessage().length() < 40);
                if (response == 429) assertEquals("请求太频繁，稍后再试", failure.getMessage());
            }
            assertEquals(0, targetCalls.get());
        } finally { server.stop(0); }
    }

    /// Oversize successful responses fail before parsing and cannot allocate an unbounded body.
    @Test void boundedResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = new byte[1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        try {
            SaoAccountClient client = new SaoAccountClient(() -> root(server), () -> "en_us");
            SaoAccountException failure = assertThrows(SaoAccountException.class, () -> client.json("GET", "", null, null));
            assertEquals("invalidResponse", failure.code());
        } finally { server.stop(0); }
    }

    /// Explicit business codes override HTTP categories, including the rename quota's special 429 wording.
    @Test void argusErrorsHaveShortCuratedVoice() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> code = new AtomicReference<>("renameLimit");
        server.createContext("/", exchange -> {
            JsonObject response = new JsonObject(); response.addProperty("error", code.get());
            response.addProperty("errorMessage", UUID.randomUUID().toString());
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get().equals("renameLimit") ? 429 : 409, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            SaoAccountClient client = new SaoAccountClient(() -> root(server), () -> "zh_cn");
            for (String item : java.util.List.of("renameLimit", "nameTaken", "reservedName", "invalidName", "accountOnline", "useRename", "invalidPremiumProof", "premiumLinked", "launchRequired", "invalidTicket")) {
                code.set(item);
                SaoAccountException error = assertThrows(SaoAccountException.class, () -> client.json("POST", "sao/rename", new JsonObject(), null));
                assertEquals(item, error.code()); assertTrue(error.getMessage().length() < 40);
                if (item.equals("renameLimit")) assertEquals("今天已经改了 3 次，明天再来", error.getMessage());
            }
        } finally { server.stop(0); }
    }

    /// Resolves only an ephemeral local test socket.
    private static URI root(HttpServer server) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
}
