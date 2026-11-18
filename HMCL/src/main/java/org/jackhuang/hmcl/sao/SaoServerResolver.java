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

import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/// Resolves optional Minecraft SRV destinations on the status DNS worker, caching outcomes for one minute.
@NotNullByDefault
final class SaoServerResolver {
    /// Positive, absent and failed SRV lookups share this short retry interval.
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(60);
    /// Bounds cache growth when successive signed policies select different hosts.
    private static final int MAX_CACHE_ENTRIES = 64;
    /// Injectable SRV transport; production uses the same JNDI DNS provider as vanilla Minecraft.
    private final SrvLookup lookup;
    /// Monotonic time source shared by deadline and cache checks.
    private final LongSupplier clock;
    /// Worker-confined destination cache; synchronized access also supports independent callers.
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    /// Creates the production resolver without performing DNS during construction.
    SaoServerResolver() { this(SaoServerResolver::lookupSrv, System::nanoTime); }

    /// Supplies offline DNS fixtures and a controllable monotonic clock.
    SaoServerResolver(SrvLookup lookup, LongSupplier clock) { this.lookup = lookup; this.clock = clock; }

    /// One immutable selected destination and its monotonic cache insertion time.
    /// @param address selected SRV endpoint or direct-host fallback
    /// @param storedAt monotonic cache insertion time
    @NotNullByDefault
    private record Cached(ServerAddress address, long storedAt) { }

    /// Returns an SRV destination or null for an absent record; failures fall back to the logical host.
    @NotNullByDefault
    @FunctionalInterface
    interface SrvLookup {
        /// Queries within the supplied remaining milliseconds; the outer ping also enforces its total deadline.
        @Nullable ServerAddress lookup(String host, int timeoutMillis) throws Exception;
    }

    /// Explicit ports always bypass SRV, including an explicitly written default port.
    synchronized ServerAddress resolve(ServerAddress original, long deadline) throws IOException {
        remaining(deadline);
        if (original.port() >= 0) return original;
        String key = original.host().toLowerCase(Locale.ROOT);
        @Nullable Cached cached = cache.get(key);
        if (cached != null && clock.getAsLong() - cached.storedAt() < CACHE_NANOS) return cached.address();
        ServerAddress selected = new ServerAddress(original.host(), 25565);
        try {
            @Nullable ServerAddress advertised = lookup.lookup(original.host(), remaining(deadline));
            if (advertised != null && advertised.port() > 0 && advertised.port() <= 65535 && !advertised.host().isEmpty())
                selected = advertised;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Server status resolution interrupted", interrupted);
        } catch (Exception | LinkageError ignored) {
            // Missing records/providers and DNS failures preserve Minecraft's direct-host fallback.
        }
        remaining(deadline);
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new Cached(selected, clock.getAsLong()));
        return selected;
    }

    /// Rejects an expired ping before returning a cached destination or starting another DNS operation.
    private int remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - clock.getAsLong();
        if (nanos <= 0) throw new SocketTimeoutException("Server status timed out");
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    /// Defers loading naming classes until inside the caller's missing-provider fallback boundary.
    private static @Nullable ServerAddress lookupSrv(String host, int timeoutMillis) throws Exception {
        return Jndi.lookup(host, timeoutMillis);
    }

    /// Isolates optional runtime naming modules so their absence cannot prevent resolver or launcher construction.
    @NotNullByDefault
    private static final class Jndi {
        /// This holder supplies only the lazily linked static DNS operation.
        private Jndi() { }

        /// Uses a fresh bounded context and always closes its resources after reading vanilla's first SRV record.
        static @Nullable ServerAddress lookup(String host, int timeoutMillis) throws Exception {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            environment.put(Context.PROVIDER_URL, "dns:");
            environment.put("com.sun.jndi.dns.timeout.initial", Integer.toString(timeoutMillis));
            environment.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext context = new InitialDirContext(environment);
            try {
                String @Unmodifiable [] attributes = {"SRV"};
                @Nullable Attribute records = context.getAttributes("_minecraft._tcp." + host, attributes).get("SRV");
                if (records == null || records.size() == 0) return null;
                @Nullable Object record = records.get();
                return record == null ? null : parseSrv(record.toString());
            } finally { context.close(); }
        }
    }

    /// Reads vanilla's first SRV record, accepting a trailing DNS root dot and rejecting unusable targets or ports.
    static @Nullable ServerAddress parseSrv(String record) {
        String @Unmodifiable [] fields = record.trim().split("\\s+");
        if (fields.length != 4) return null;
        try {
            int priority = Integer.parseInt(fields[0]), weight = Integer.parseInt(fields[1]), port = Integer.parseInt(fields[2]);
            if (priority < 0 || priority > 65535 || weight < 0 || weight > 65535 || port < 1 || port > 65535) return null;
            String host = fields[3];
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            return ServerAddress.parse(SaoUpdates.server(host.toLowerCase(Locale.ROOT) + ":" + port));
        } catch (IOException | IllegalArgumentException failure) { return null; }
    }
}
