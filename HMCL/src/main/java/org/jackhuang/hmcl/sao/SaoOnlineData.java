/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/// Bounded news and Minecraft 1.20.1 status requests, with a last-good public news cache.
@NotNullByDefault
final class SaoOnlineData implements AutoCloseable {
    /// Maximum decoded news response bytes.
    private static final int MAX_NEWS_BYTES = 262144;
    /// Closes a status socket at the total deadline even if a peer keeps trickling bytes.
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sao-status-deadline"); thread.setDaemon(true); return thread;
    });
    /// Reused client does not create a new selector/executor for every refresh.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /// Cache file for the last valid remote document.
    private final Path cache;
    /// Last-good fully validated release policy, separate from announcements.
    private final Path versionCache;
    /// Injectable request exchange; tests never contact external hosts.
    private final Exchange exchange;
    /// DNS has a separate daemon so the complete ping has a three-second deadline.
    private final ThreadPoolExecutor resolver = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> { Thread thread = new Thread(task, "sao-status-dns"); thread.setDaemon(true); return thread; });

    /// Keeps all cache files in the launcher's own data root.
    SaoOnlineData(Path root) {
        cache = root.resolve("online-news.json"); versionCache = root.resolve("online-version.json");
        exchange = (request, limit) -> {
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new NewsSubscriber(limit));
            try {
                HttpResponse<byte[]> response = pending.get(request.timeout().orElseThrow().toNanos(), TimeUnit.NANOSECONDS);
                return new Response(response.statusCode(), response.headers().firstValue("location").orElse(null), response.body());
            } finally { pending.cancel(true); }
        };
    }

    /// Supplies an entirely inert exchange for redirect/cache contract tests.
    SaoOnlineData(Path root, Exchange exchange) {
        cache = root.resolve("online-news.json"); versionCache = root.resolve("online-version.json"); this.exchange = exchange;
    }

    /// Bounded HTTP exchange result; redirect locations are never followed by the HTTP client itself.
    /// @param status HTTP response status
    /// @param location optional Location header
    /// @param body bounded response bytes
    @NotNullByDefault
    record Response(int status, @Nullable String location, byte[] body) { }

    /// Single-request transport seam, with redirect validation performed by the caller.
    @NotNullByDefault
    @FunctionalInterface
    interface Exchange {
        /// Executes one request with the caller's timeout and decoded-byte limit.
        Response send(HttpRequest request, int limit) throws Exception;
    }

    /// Validates a small news document, preserving source order, with no HTML rendering or synthetic entries.
    static @Unmodifiable List<SaoData.News> parseNews(String document) throws IOException {
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_NEWS_BYTES) throw new IOException("News document is too large");
        try {
            @Nullable JsonArray entries = JsonParser.parseString(document).getAsJsonObject().getAsJsonArray("news");
            if (entries == null || entries.size() > 100) throw new IOException("News must contain at most 100 entries");
            List<SaoData.News> result = new ArrayList<>();
            for (JsonElement item : entries) {
                JsonObject entry = item.getAsJsonObject();
                SaoData.NewsKind kind = SaoData.NewsKind.valueOf(entry.get("kind").getAsString());
                String title = entry.get("title").getAsString();
                String date = entry.has("date") ? entry.get("date").getAsString() : "";
                String body = entry.has("body") ? entry.get("body").getAsString() : "";
                if (title.isBlank() || title.length() > 200 || date.length() > 40 || body.length() > 16000)
                    throw new IOException("Invalid news entry length");
                result.add(new SaoData.News(kind, title, date, body));
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) { throw new IOException("Invalid news document", failure); }
    }

    /// Reads valid cached news only; a damaged cache does not prevent the launcher from starting.
    @Unmodifiable List<SaoData.News> cached() {
        try { return Files.size(cache) <= MAX_NEWS_BYTES ? parseNews(Files.readString(cache)) : List.of(); }
        catch (IOException | RuntimeException ignored) { return List.of(); }
    }

    /// Fetches an explicitly configured HTTPS document and commits it only after full validation.
    @Unmodifiable List<SaoData.News> fetch(String endpoint) throws Exception {
        String document = fetchDocument(endpoint, MAX_NEWS_BYTES);
        @Unmodifiable List<SaoData.News> news = parseNews(document);
        save(cache, document);
        return news;
    }

    /// Reads only fully validated cached links; an absent/damaged cache uses the built-in defaults.
    @Unmodifiable List<SaoData.CommunityLink> cachedLinks() {
        Path file = cache.resolveSibling("online-links.json");
        try { return Files.size(file) <= SaoCommunityLinks.MAX_BYTES ? SaoCommunityLinks.parse(Files.readString(file)) : SaoCommunityLinks.DEFAULTS; }
        catch (IOException | RuntimeException ignored) { return SaoCommunityLinks.DEFAULTS; }
    }

    /// Uses one ten-second total deadline and the same one-hop Gitee redirect and bounded-body transport as news.
    @Unmodifiable List<SaoData.CommunityLink> fetchLinks() throws Exception {
        String document = fetchDocument(SaoCommunityLinks.URL, SaoCommunityLinks.MAX_BYTES, 10);
        @Unmodifiable List<SaoData.CommunityLink> result = SaoCommunityLinks.parse(document);
        save(cache.resolveSibling("online-links.json"), document);
        return result;
    }

    /// Loads only a valid last-good release policy; unavailable metadata does not disable play.
    @Nullable SaoData.VersionMetadata cachedVersion() {
        try { return Files.size(versionCache) <= SaoUpdates.MAX_VERSION_BYTES ? SaoUpdates.parse(Files.readString(versionCache)) : null; }
        catch (IOException | RuntimeException ignored) { return null; }
    }

    /// Commits version metadata only after complete schema and value validation.
    SaoData.VersionMetadata fetchVersion(String endpoint) throws Exception {
        String document = fetchDocument(endpoint, SaoUpdates.MAX_VERSION_BYTES);
        SaoData.VersionMetadata metadata = SaoUpdates.parse(document);
        save(versionCache, document); return metadata;
    }

    /// Atomically replaces one last-good public cache after validation.
    private static void save(Path cache, String document) throws IOException {
        Path parent = Objects.requireNonNull(cache.getParent()); Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "online-", ".tmp");
        try { Files.writeString(temporary, document); SaoPackFiles.atomicMove(temporary, cache); }
        finally { Files.deleteIfExists(temporary); }
    }

    /// Fetches a bounded document under one ten-second deadline, permitting only Gitee's one raw-host redirect.
    private String fetchDocument(String endpoint, int limit) throws Exception {
        return fetchDocument(endpoint, limit, 10);
    }

    /// Shares redirect/body validation while allowing a caller-specific whole-operation deadline.
    private String fetchDocument(String endpoint, int limit, int seconds) throws Exception {
        URI uri = checkedEndpoint(URI.create(endpoint));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Response response = exchange.send(request(uri, deadline), limit);
        if (redirect(response.status())) {
            uri = redirected(uri, response.location());
            response = exchange.send(request(uri, deadline), limit);
        }
        if (response.status() != 200) throw new IOException("Metadata HTTP " + response.status());
        if (System.nanoTime() >= deadline) throw new IOException("Metadata request timed out");
        if (response.body().length > limit) throw new IOException("Metadata document is too large");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(response.body())).toString();
    }

    /// Builds each hop from the remaining whole-operation timeout.
    private static HttpRequest request(URI uri, long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("Metadata request timed out");
        return HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(nanos)).GET().build();
    }

    /// Recognizes HTTP redirect responses without enabling HttpClient's broad redirect behavior.
    private static boolean redirect(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }

    /// Rejects user-info, downgrade URLs and nonstandard ports before creating a request.
    private static URI checkedEndpoint(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || uri.getFragment() != null) throw new IOException("Metadata requires an HTTPS endpoint");
        return uri;
    }

    /// Allows exactly the trusted source/destination host pair; a second redirect is rejected by the fetcher.
    static URI redirected(URI source, @Nullable String location) throws IOException {
        checkedEndpoint(source);
        if (!"gitee.com".equals(source.getHost()) || location == null) throw new IOException("Metadata redirect refused");
        try {
            URI target = checkedEndpoint(URI.create(location));
            if (!"raw.giteeusercontent.com".equals(target.getHost())) throw new IOException("Metadata redirect refused");
            return target;
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid metadata redirect", failure); }
    }

    /// Builds the mandatory embedded-version update entry from the archive's actual changelog, when present.
    static SaoData.News embeddedNews(@Nullable SaoPackArchive pack, String embeddedVersion) {
        String body = "";
        if (pack != null) {
            try (ZipFile zip = new ZipFile(pack.zip().toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName().toLowerCase(Locale.ROOT);
                    String basename = name.substring(name.lastIndexOf('/') + 1);
                    if (entry.isDirectory() || entry.getSize() > 65536 || !(basename.matches("(?:changelog|changes|更新日志|更新说明|变更)(?:\\.[a-z]+)?"))) continue;
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] bytes = input.readNBytes(65537);
                        if (bytes.length <= 65536) body = new String(bytes, StandardCharsets.UTF_8);
                    }
                    break;
                }
            } catch (IOException ignored) { /* The release title remains available even without notes. */ }
        }
        return new SaoData.News(SaoData.NewsKind.UPDATE, "v" + embeddedVersion, "", body);
    }

    /// Performs modern status handshake, JSON request and ping/pong under one three-second deadline.
    SaoData.ServerStatus ping(String address) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        ServerAddress parsed = ServerAddress.parse(address);
        Future<InetAddress[]> lookup = resolver.submit(() -> InetAddress.getAllByName(parsed.host()));
        InetAddress[] addresses;
        try { addresses = lookup.get(remaining(deadline), TimeUnit.MILLISECONDS); }
        finally { lookup.cancel(true); resolver.purge(); }
        int port = parsed.port() < 0 ? 25565 : parsed.port();
        try (Socket socket = new Socket()) {
            ScheduledFuture<?> timeout = deadlines.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, remaining(deadline), TimeUnit.MILLISECONDS);
            try {
            socket.connect(new InetSocketAddress(addresses[0], port), remaining(deadline));
            socket.setSoTimeout(remaining(deadline));
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0); writeVarInt(handshake, 763);
            byte[] host = parsed.host().getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, host.length); handshake.write(host); handshake.writeShort(port); writeVarInt(handshake, 1);
            writeVarInt(output, handshakeBytes.size()); output.write(handshakeBytes.toByteArray());
            output.write(new byte[]{1, 0}); output.flush();
            DataInputStream packet = packet(input);
            if (readVarInt(packet) != 0) throw new IOException("Unexpected status packet");
            int length = readVarInt(packet);
            if (length < 0 || length > 1048576 || length > packet.available()) throw new IOException("Invalid status JSON length");
            JsonObject status = JsonParser.parseString(new String(packet.readNBytes(length), StandardCharsets.UTF_8)).getAsJsonObject();
            long start = System.nanoTime();
            output.write(new byte[]{9, 1}); output.writeLong(start); output.flush();
            socket.setSoTimeout(remaining(deadline));
            DataInputStream pong = packet(input);
            if (readVarInt(pong) != 1 || pong.readLong() != start) throw new IOException("Invalid pong");
            int milliseconds = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return status(status, milliseconds);
            } finally { timeout.cancel(false); }
        }
    }

    /// Converts public status JSON into immutable screen values, tolerating an absent player sample.
    static SaoData.ServerStatus status(JsonObject status, int pingMs) {
        JsonObject players = status.has("players") ? status.getAsJsonObject("players") : new JsonObject();
        List<String> names = new ArrayList<>();
        if (players.has("sample")) for (JsonElement sample : players.getAsJsonArray("sample")) {
            if (names.size() == 100) break;
            names.add(sample.getAsJsonObject().get("name").getAsString());
        }
        return new SaoData.ServerStatus(true, players.has("online") ? players.get("online").getAsInt() : 0,
                players.has("max") ? players.get("max").getAsInt() : 0, pingMs,
                status.has("description") ? plainText(status.get("description")) : "", List.copyOf(names));
    }

    /// Flattens Minecraft text components, including nested extras, and removes legacy color codes.
    private static String plainText(JsonElement element) {
        StringBuilder text = new StringBuilder();
        if (element.isJsonPrimitive()) text.append(element.getAsString());
        else if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) text.append(plainText(child));
        else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("text")) text.append(object.get("text").getAsString());
            if (object.has("extra")) text.append(plainText(object.get("extra")));
        }
        return text.toString().replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    /// Returns the remaining whole-operation deadline as a socket-compatible timeout.
    private static int remaining(long deadline) throws SocketTimeoutException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new SocketTimeoutException("Server status timed out");
        return (int) Math.max(1, remaining);
    }

    /// Reads one bounded modern protocol packet without exposing the socket stream to JSON parsing.
    private static DataInputStream packet(DataInputStream input) throws IOException {
        int size = readVarInt(input);
        if (size < 1 || size > 1048576) throw new IOException("Invalid status packet length");
        byte[] packet = input.readNBytes(size);
        if (packet.length != size) throw new EOFException("Truncated status packet");
        return new DataInputStream(new ByteArrayInputStream(packet));
    }

    /// Encodes one Minecraft VarInt.
    static void writeVarInt(DataOutputStream output, int value) throws IOException {
        do { int bits = value & 127; value >>>= 7; output.writeByte(value == 0 ? bits : bits | 128); } while (value != 0);
    }

    /// Decodes a Minecraft VarInt, rejecting oversized values.
    static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte(); value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Invalid VarInt");
    }

    /// A demand-driven response accumulator that rejects oversized news before buffering the whole body.
    @NotNullByDefault
    static final class NewsSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        /// Per-document decoded response byte limit.
        private final int limit;
        /// At most MAX_NEWS_BYTES accepted response bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /// Completes with a validated-size response or the first transport/size error.
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        /// Current publisher subscription, null before onSubscribe.
        private @Nullable java.util.concurrent.Flow.Subscription subscription;
        /// Retains the historical news limit for existing callers and regression tests.
        NewsSubscriber() { this(MAX_NEWS_BYTES); }
        /// Uses the smaller version-document limit when fetching release metadata.
        NewsSubscriber(int limit) { this.limit = limit; }
        /// Returns the completion observed by HttpClient.send.
        @Override public CompletionStage<byte[]> getBody() { return result; }
        /// Requests one chunk at a time, bounding buffering independently of upstream chunk count.
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        /// Cancels immediately when a chunk would exceed the document limit.
        @Override public void onNext(List<java.nio.ByteBuffer> chunks) {
            for (java.nio.ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    if (subscription != null) subscription.cancel();
                    result.completeExceptionally(new IOException("News document is too large")); return;
                }
                byte[] copy = new byte[chunk.remaining()]; chunk.get(copy); bytes.writeBytes(copy);
            }
            if (subscription != null) subscription.request(1);
        }
        /// Propagates a network error without replacing the last-good cache.
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        /// Completes the bounded response after the publisher sends every accepted chunk.
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    /// Cancels pending resolution on launcher shutdown.
    @Override public void close() { resolver.shutdownNow(); deadlines.shutdownNow(); }
}
