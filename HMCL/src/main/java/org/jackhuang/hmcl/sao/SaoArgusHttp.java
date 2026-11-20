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

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/// Routes direct production ARGUS traffic through a hostname-preserving, per-client DNS resolver.
@NotNullByDefault
final class SaoArgusHttp {
    /// The process-wide account pool serves the API and the long-lived social stream.
    static final SaoArgusHttp INSTANCE = new SaoArgusHttp();
    /// Reuses sockets for all other origins and any explicitly configured proxy route.
    private final HttpClient standard = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /// Uses the same JDK certificate trust for the independent DNS HTTPS request and account HTTPS transport.
    private final CloseableHttpClient argus = direct(new SaoArgusDns(standard), null);
    /// Daemon timers cancel stalled reads and remove completed tasks immediately.
    private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlines();

    /// Keeps connection pools private to the request routing layer.
    private SaoArgusHttp() {
    }

    /// Builds a bounded direct connection pool with regular JSSE hostname and chain verification.
    static CloseableHttpClient direct(DnsResolver resolver, @Nullable SSLContext context) {
        var tls = ClientTlsStrategyBuilder.create().useSystemProperties();
        if (context != null) tls.setSslContext(context);
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setTlsSocketStrategy(tls.buildClassic()).setMaxConnPerRoute(8).setMaxConnTotal(8)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(8)).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3)).build())
                .build();
    }

    /// Adapts the small response surface used by ARGUS account requests and event streams.
    @NotNullByDefault
    record Response(int statusCode, @Nullable String date, String contentType, InputStream body) {
    }

    /// Sends a single request, using the scoped resolver only for direct HTTPS to the production ARGUS host.
    Response send(HttpRequest request, byte @Nullable [] body) throws IOException, InterruptedException {
        if (usesArgusDns(request.uri(), ProxySelector.getDefault())) return sendDirect(argus, request, body);
        var result = standard.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new Response(result.statusCode(), result.headers().firstValue("Date").orElse(null),
                result.headers().firstValue("Content-Type").orElse(""), result.body());
    }

    /// Chooses the DNS-aware client for the official host without breaking a proxy's own resolution.
    static boolean usesArgusDns(URI uri, @Nullable ProxySelector proxy) {
        return "https".equalsIgnoreCase(uri.getScheme()) && SaoArgusDns.HOST.equalsIgnoreCase(uri.getHost())
                && (proxy == null || proxy.select(uri).stream().findFirst().orElse(Proxy.NO_PROXY).type() == Proxy.Type.DIRECT);
    }

    /// Preserves URI, authority, headers and method. Cancels a stalled connection without replaying a POST.
    static Response sendDirect(CloseableHttpClient client, HttpRequest request, byte @Nullable [] bytes)
            throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        var call = new HttpUriRequestBase(request.method(), request.uri());
        request.headers().map().forEach((name, values) -> values.forEach(value -> call.addHeader(name, value)));
        if (bytes != null) call.setEntity(new ByteArrayEntity(bytes, null));
        var deadline = DEADLINES.schedule(call::cancel, request.timeout().orElse(Duration.ofSeconds(20)).toNanos(), TimeUnit.NANOSECONDS);
        try {
            var reply = client.executeOpen(null, call, null);
            try {
                var entity = reply.getEntity();
                InputStream body = entity == null ? InputStream.nullInputStream() : entity.getContent();
                return new Response(reply.getCode(), header(reply, "Date").orElse(null),
                        header(reply, "Content-Type").orElse(""), new FilterInputStream(body) {
                            /// Releases an unfinished body promptly on deadline, logout or explicit stream close.
                            @Override
                            public void close() throws IOException {
                                call.cancel();
                                reply.close();
                            }
                        });
            } catch (IOException | RuntimeException invalid) {
                call.cancel();
                reply.close();
                throw invalid;
            }
        } catch (IOException failure) {
            if (Thread.interrupted()) throw new InterruptedException();
            if (call.isCancelled()) throw new HttpTimeoutException("ARGUS response deadline");
            throw failure;
        } finally {
            deadline.cancel(false);
        }
    }

    /// Returns one response header without requiring the caller to know the HTTP implementation.
    private static Optional<String> header(ClassicHttpResponse reply, String name) {
        var header = reply.getFirstHeader(name);
        return header == null ? Optional.empty() : Optional.ofNullable(header.getValue());
    }

    /// Reads at most limit plus one bytes and covers the streaming body with the remaining total budget.
    static byte[] readBounded(InputStream input, long started, Duration timeout, int limit) throws IOException {
        long remaining = timeout.toNanos() - (System.nanoTime() - started);
        if (remaining <= 0) throw new HttpTimeoutException("ARGUS response deadline");
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        var deadline = DEADLINES.schedule(() -> {
            expired.set(true);
            try { input.close(); } catch (IOException ignored) { }
        }, remaining, TimeUnit.NANOSECONDS);
        try {
            byte[] bytes = input.readNBytes(limit + 1);
            if (expired.get() || System.nanoTime() - started >= timeout.toNanos()) {
                Arrays.fill(bytes, (byte) 0);
                throw new HttpTimeoutException("ARGUS response deadline");
            }
            return bytes;
        } catch (IOException failure) {
            if (expired.get()) throw new HttpTimeoutException("ARGUS response deadline");
            throw failure;
        } finally {
            deadline.cancel(false);
        }
    }

    /// Creates one daemon scheduler for account and resolver response deadlines.
    private static ScheduledThreadPoolExecutor createDeadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "SAO account HTTP deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
