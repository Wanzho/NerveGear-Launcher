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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.hc.client5.http.DnsResolver;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Accepts publicly routable system answers for the production ARGUS host and recovers bad answers over HTTPS.
@NotNullByDefault
final class SaoArgusDns implements DnsResolver {
    /// The only account hostname entitled to use the public fallback.
    static final String HOST = "argus.saooath.com";
    /// Literal HTTPS resolver endpoints work even when the local resolver returns wrong addresses.
    private static final @Unmodifiable List<URI> RESOLVERS = List.of(
            URI.create("https://223.5.5.5/resolve?name=" + HOST + "&type=A"),
            URI.create("https://1.1.1.1/dns-query?name=" + HOST + "&type=A"));
    /// Bounds every public resolver attempt, including consumption of the response.
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);
    /// The public JSON response consists of a small DNS answer, never account data.
    private static final int MAX_REPLY = 8192;

    /// Injects a DNS or fixture lookup without changing the JVM resolver.
    @FunctionalInterface
    @NotNullByDefault
    interface SystemLookup {
        /// Returns the addresses the local system supplied.
        InetAddress @Unmodifiable [] resolve(String host) throws UnknownHostException;
    }

    /// Supplies a decoded encrypted answer, with an empty list representing an unusable response.
    @FunctionalInterface
    @NotNullByDefault
    interface PublicLookup {
        /// Resolves the production account hostname only.
        @Nullable Answer resolve() throws IOException, InterruptedException;
    }

    /// Stores one positive fallback answer until the lower of its record TTLs or five minutes.
    @NotNullByDefault
    record Answer(@Unmodifiable List<InetAddress> addresses, int ttl) {
        /// Snapshots addresses shared by concurrent connections.
        Answer {
            addresses = List.copyOf(addresses);
        }
    }

    /// Reads the operating system's answer for the relevant hostname.
    private final SystemLookup system;
    /// Consults the fixed encrypted providers on demand.
    private final PublicLookup fallback;
    /// The last successful encrypted answer, shared across newly opened ARGUS connections.
    private @Nullable Answer cached;
    /// The monotonic expiry of the cached answer.
    private long expires;

    /// Creates a resolver for production account requests.
    SaoArgusDns(HttpClient http) {
        this(InetAddress::getAllByName, () -> encryptedLookup(http));
    }

    /// Gives local regression tests control of both DNS inputs.
    SaoArgusDns(SystemLookup system, PublicLookup fallback) {
        this.system = system;
        this.fallback = fallback;
    }

    /// Ignores loopback, private and other unusable answers, then checks encrypted DNS if needed.
    @Override
    public InetAddress @Unmodifiable [] resolve(String host) throws UnknownHostException {
        if (!host.equalsIgnoreCase(HOST)) throw new UnknownHostException("Unexpected ARGUS hostname");
        try {
            InetAddress @Unmodifiable [] addresses = Arrays.stream(system.resolve(HOST))
                    .filter(SaoArgusDns::publicAddress).toArray(InetAddress[]::new);
            if (addresses.length != 0) return addresses;
        } catch (UnknownHostException ignored) {
            // A negative system lookup is eligible for the same recovery as a loopback-only answer.
        }
        return encryptedAddresses();
    }

    /// Keeps the original hostname for HTTP host headers, TLS SNI and certificate verification.
    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        if (!host.equalsIgnoreCase(HOST)) throw new UnknownHostException("Unexpected ARGUS hostname");
        return HOST;
    }

    /// Serializes the rarely used fallback and refreshes it after its advertised lifetime.
    private synchronized InetAddress @Unmodifiable [] encryptedAddresses() throws UnknownHostException {
        if (cached != null && System.nanoTime() - expires < 0) {
            return cached.addresses().toArray(InetAddress[]::new);
        }
        try {
            @Nullable Answer answer = fallback.resolve();
            if (answer != null && !answer.addresses().isEmpty()) {
                cached = answer;
                expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(answer.ttl());
                org.jackhuang.hmcl.util.logging.Logger.LOG.info("ARGUS secure DNS fallback succeeded");
                return answer.addresses().toArray(InetAddress[]::new);
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (IOException unavailable) {
            // Credential-free DNS failures are summarized by the account client's DNS category.
        }
        throw new UnknownHostException("ARGUS public address unavailable");
    }

    /// Tries fixed TLS-verified DNS services without sending passwords, session tokens or account details.
    private static @Nullable Answer encryptedLookup(HttpClient http) throws InterruptedException {
        for (URI uri : RESOLVERS) {
            try {
                long started = System.nanoTime();
                var request = HttpRequest.newBuilder(uri).timeout(QUERY_TIMEOUT).header("Accept", "application/dns-json").GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body()) {
                    if (response.statusCode() != 200) continue;
                    byte[] bytes = SaoArgusHttp.readBounded(input, started, QUERY_TIMEOUT, MAX_REPLY);
                    if (bytes.length <= MAX_REPLY) {
                        Answer answer = parse(new String(bytes, StandardCharsets.UTF_8));
                        if (!answer.addresses().isEmpty()) return answer;
                    }
                }
            } catch (IOException | RuntimeException invalid) {
                // A failed, truncated or malformed answer can be recovered using the other provider.
            }
        }
        return null;
    }

    /// Decodes public IPv4 records; the original ARGUS hostname still has to pass TLS verification.
    static Answer parse(String json) throws IOException {
        var reply = JsonParser.parseString(json).getAsJsonObject();
        List<InetAddress> addresses = new ArrayList<>();
        int ttl = 300;
        if (reply.get("Status").getAsInt() == 0 && (!reply.has("TC") || !reply.get("TC").getAsBoolean()) && reply.has("Answer")) {
            for (JsonElement record : reply.getAsJsonArray("Answer")) {
                var entry = record.getAsJsonObject();
                if (entry.get("type").getAsInt() != 1) continue;
                String value = entry.get("data").getAsString();
                if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) continue;
                InetAddress address = InetAddress.getByName(value);
                if (publicAddress(address)) {
                    addresses.add(address);
                    ttl = Math.min(ttl, Math.max(0, entry.get("TTL").getAsInt()));
                }
            }
        }
        return new Answer(addresses, ttl);
    }

    /// Rejects addresses inappropriate for the public production service, including IPv6 ULA and shared IPv4.
    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte @Unmodifiable [] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xfe) != 0xfc;
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first != 0 && first < 224 && (first != 100 || (second & 0xc0) != 64);
    }
}
