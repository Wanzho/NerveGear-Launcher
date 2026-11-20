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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.apache.hc.client5.http.DnsResolver;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises the production hostname's invalid-resolution recovery and the connector's TLS authority.
@NotNullByDefault
class SaoArgusDnsTest {
    /// Production request origin from the signed policy.
    private static final URI ORIGIN = URI.create(SaoAccountClient.DEFAULT_API);

    /// Keeps public system answers, filters local ones and keeps all alternate origins on the existing client.
    @Test
    void healthyDnsAndProxiesKeepTheirRouting() throws Exception {
        InetAddress address = InetAddress.getByName("1.2.3.4");
        var dns = new SaoArgusDns(host -> new InetAddress[]{InetAddress.getLoopbackAddress(), address},
                () -> fail("Public system answers do not need encrypted DNS"));
        assertArrayEquals(new InetAddress[]{address}, dns.resolve(SaoArgusDns.HOST));
        assertThrows(UnknownHostException.class, () -> dns.resolve("sessionserver.mojang.com"));
        assertTrue(SaoArgusHttp.usesArgusDns(ORIGIN, null));
        assertFalse(SaoArgusHttp.usesArgusDns(ORIGIN, ProxySelector.of(InetSocketAddress.createUnresolved("proxy.example", 8080))));
        assertFalse(SaoArgusHttp.usesArgusDns(URI.create("https://argus.saooath.com.example/"), null));
        assertFalse(SaoArgusHttp.usesArgusDns(URI.create("http://127.0.0.1/"), null));
    }

    /// Resolves the exact IPv4/IPv6 loopback failure and briefly reuses a successful provider answer.
    @Test
    void loopbackOnlyUsesEncryptedDnsAndCachesAnswer() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        var dns = new SaoArgusDns(host -> new InetAddress[]{
                InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")}, () -> {
            queries.incrementAndGet();
            return SaoArgusDns.parse("""
                    {"Status":0,"Answer":[{"type":1,"TTL":60,"data":"1.2.3.4"}]}
                    """);
        });
        assertEquals("1.2.3.4", dns.resolve(SaoArgusDns.HOST)[0].getHostAddress());
        assertEquals("1.2.3.4", dns.resolve(SaoArgusDns.HOST)[0].getHostAddress());
        assertEquals(1, queries.get());
    }

    /// Leaves local and private destinations unusable after recovery and respects a zero TTL.
    @Test
    void excludesUnusablePublicAnswersAndRefreshesExpiredRecords() throws Exception {
        assertTrue(SaoArgusDns.parse("""
                {"Status":0,"Answer":[{"type":1,"TTL":60,"data":"127.0.0.1"},
                                      {"type":1,"TTL":60,"data":"192.168.1.2"},
                                      {"type":1,"TTL":60,"data":"100.74.2.3"}]}
                """).addresses().isEmpty());
        assertTrue(SaoArgusDns.parse("""
                {"Status":3,"Answer":[{"type":1,"TTL":60,"data":"1.2.3.4"}]}
                """).addresses().isEmpty());
        var unavailable = new SaoArgusDns(host -> { throw new UnknownHostException(); }, () -> null);
        assertThrows(UnknownHostException.class, () -> unavailable.resolve(SaoArgusDns.HOST));
        AtomicInteger lookups = new AtomicInteger();
        var rotating = new SaoArgusDns(host -> new InetAddress[]{InetAddress.getLoopbackAddress()},
                () -> new SaoArgusDns.Answer(List.of(InetAddress.getByName("1.2.3." + lookups.incrementAndGet())), 0));
        assertEquals("1.2.3.1", rotating.resolve(SaoArgusDns.HOST)[0].getHostAddress());
        assertEquals("1.2.3.2", rotating.resolve(SaoArgusDns.HOST)[0].getHostAddress());
    }

    /// Sends one request across a forced-address TLS connection and refuses a hostname mismatch before any HTTP request.
    @Test
    void forcedAddressPreservesTlsHostnameAndNeverReplaysOrRedirects(@TempDir Path temporary) throws Exception {
        SSLContext tls = fixtureTls(temporary);
        var loopback = InetAddress.getByName("127.0.0.1");
        var server = HttpsServer.create(new InetSocketAddress(loopback, 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        AtomicInteger requests = new AtomicInteger();
        var received = new AtomicReference<>("");
        server.createContext("/proof", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                var https = (com.sun.net.httpserver.HttpsExchange) exchange;
                var requestedNames = ((ExtendedSSLSession) https.getSSLSession()).getRequestedServerNames();
                String sni = requestedNames.isEmpty() ? "" : ((SNIHostName)requestedNames.get(0)).getAsciiName();
                String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.set(sni + " " + exchange.getRequestHeaders().getFirst("Host") + " " + payload);
                exchange.getResponseHeaders().set("Location", "/proof");
                exchange.sendResponseHeaders(307, -1);
            }
        });
        server.start();
        try (var client = SaoArgusHttp.direct(new DnsResolver() {
            /// Supplies the listener address while leaving the caller's hostname unchanged.
            @Override
            public InetAddress @Unmodifiable [] resolve(String host) {
                return new InetAddress[]{loopback};
            }

            /// Keeps the expected certificate subject and SNI on this socket.
            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        }, tls)) {
            int port = server.getAddress().getPort();
            byte[] payload = "fixture-body".getBytes(StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create("https://" + SaoArgusDns.HOST + ":" + port + "/proof"))
                    .timeout(Duration.ofSeconds(4)).POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var response = SaoArgusHttp.sendDirect(client, request, payload);
            try (var body = response.body()) {
                assertEquals(307, response.statusCode());
                assertEquals(-1, body.read());
            }
            assertEquals(SaoArgusDns.HOST + " " + SaoArgusDns.HOST + ":" + port + " fixture-body", received.get());
            var wrongHost = HttpRequest.newBuilder(URI.create("https://wrong.example:" + port + "/proof"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            assertThrows(SSLException.class, () -> SaoArgusHttp.sendDirect(client, wrongHost, null));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Creates a throwaway key and trusts only its certificate for the local HTTPS test.
    private static SSLContext fixtureTls(Path temporary) throws Exception {
        Path storePath = temporary.resolve("argus.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture",
                "-keyalg", "EC", "-dname", "CN=" + SaoArgusDns.HOST, "-ext", "san=dns:" + SaoArgusDns.HOST,
                "-validity", "2", "-storetype", "PKCS12", "-storepass", "fixture-test",
                "-keystore", storePath.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out creating the local TLS fixture");
        }
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        var store = KeyStore.getInstance("PKCS12");
        char[] password = "fixture-test".toCharArray();
        try (var in = Files.newInputStream(storePath)) {
            store.load(in, password);
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return tls;
    }
}
