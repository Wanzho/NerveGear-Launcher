/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.download.DownloadCandidate;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.HMCLDownloadProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/// Local HTTP integration tests exercise the production HMCL transfer, without public network or JavaFX.
@NotNullByDefault
class SaoModDownloadsTest {
    /// Disposable staging and destination files.
    @TempDir Path temporary;

    /// All three CDN hosts prefer MCIM on every locale; mediafilez/edge share one deduplicated mirror.
    @Test void mirrorFirstOrderingPreservesOriginalsAndEncodedPaths() {
        HMCLDownloadProvider provider = new HMCLDownloadProvider("https://bmclapi2.bangbang93.com");
        String path = "/files/1234/567/a%20b+1.jar";
        assertEquals(List.of("https://mod.mcimirror.top" + path, "https://mediafilez.forgecdn.net" + path),
                urls(provider.getPackDownloadCandidates(List.of("https://mediafilez.forgecdn.net" + path))));
        assertEquals(List.of("https://mod.mcimirror.top" + path, "https://edge.forgecdn.net" + path),
                urls(provider.getPackDownloadCandidates(List.of("https://edge.forgecdn.net" + path))));
        String modrinth = "/data/abc/versions/def/mod.jar";
        assertEquals(List.of("https://mod.mcimirror.top" + modrinth, "https://cdn.modrinth.com" + modrinth),
                urls(provider.getPackDownloadCandidates(List.of("https://cdn.modrinth.com" + modrinth))));
        assertEquals(List.of("https://mod.mcimirror.top" + path, "https://mediafilez.forgecdn.net" + path, "https://edge.forgecdn.net" + path),
                urls(provider.getPackDownloadCandidates(List.of("https://mediafilez.forgecdn.net" + path, "https://edge.forgecdn.net" + path))));
    }

    /// A mirror with the right size but wrong SHA-1 is rejected once, then the original succeeds.
    @Test void wrongHashFallsBackAndUserAgentSurvivesRedirects() throws Exception {
        try (LocalServer server = new LocalServer()) {
            List<String> seen = new CopyOnWriteArrayList<>();
            List<String> agents = new CopyOnWriteArrayList<>();
            server.server.createContext("/mirror", exchange -> {
                seen.add("mirror"); agents.add(Objects.requireNonNull(exchange.getRequestHeaders().getFirst("User-Agent"))); send(exchange, 200, "BAD");
            });
            server.server.createContext("/original", exchange -> {
                seen.add("original"); agents.add(Objects.requireNonNull(exchange.getRequestHeaders().getFirst("User-Agent")));
                exchange.getResponseHeaders().set("Location", server.url("/payload")); send(exchange, 302, "");
            });
            server.server.createContext("/payload", exchange -> {
                seen.add("payload"); agents.add(Objects.requireNonNull(exchange.getRequestHeaders().getFirst("User-Agent"))); send(exchange, 200, "jar");
            });
            List<SaoModDownloads.Transfer> results = new CopyOnWriteArrayList<>();
            List<SaoInstallProgress> snapshots = new CopyOnWriteArrayList<>();
            try (SaoModDownloads downloads = downloader(server, List.of("/mirror", "/original"), snapshots, results, 2)) {
                Path destination = temporary.resolve("mods/a.jar");
                downloads.downloadAll(Map.of(file("a", "jar"), destination));
                assertEquals("jar", Files.readString(destination));
            }
            assertEquals(List.of("mirror", "original", "payload"), seen);
            assertTrue(agents.stream().allMatch(SaoModDownloads.USER_AGENT::equals));
            assertTrue(SaoModDownloads.USER_AGENT.startsWith("NerveGear-Launcher/"));
            assertEquals(List.of(200, 302, 200), results.get(0).attempts().stream().map(SaoInstallProgress.Attempt::status).toList());
            assertTrue(results.get(0).verified());
            SaoInstallProgress last = snapshots.get(snapshots.size() - 1);
            assertEquals(1, last.completedFiles()); assertEquals(1, last.totalFiles());
            assertEquals(3, last.bytesDone()); assertEquals(3, last.totalBytes());
            assertEquals(6, last.networkBytes(), "Retransfers are counted as network traffic, not verified bytes");
            assertEquals(0, last.bytesPerSecond());
            assertNoTemporaryFiles();
        }
    }

    /// Every failed file and actual host is reported; no partial destination jar or temporary sibling survives.
    @Test void allHostsFailReportsEveryFileAndLeavesNoPartialJar() throws Exception {
        try (LocalServer server = new LocalServer()) {
            server.server.createContext("/mirror", exchange -> send(exchange, 404, "missing"));
            server.server.createContext("/original", exchange -> send(exchange, 404, "missing"));
            List<SaoInstallProgress> snapshots = new CopyOnWriteArrayList<>();
            List<SaoModDownloads.Transfer> results = new CopyOnWriteArrayList<>();
            try (SaoModDownloads downloads = downloader(server, List.of("/mirror", "/original"), snapshots, results, 0)) {
                Map<SaoPackManifest.PackFile, Path> files = new LinkedHashMap<>();
                files.put(file("a", "jar"), temporary.resolve("mods/a.jar"));
                files.put(file("b", "jar"), temporary.resolve("mods/b.jar"));
                IOException failure = assertThrows(IOException.class, () -> downloads.downloadAll(files));
                assertTrue(failure.getMessage().contains("mods/a.jar"));
                assertTrue(failure.getMessage().contains("mods/b.jar"));
                assertTrue(failure.getMessage().contains("hosts tried: 127.0.0.1"));
            }
            assertFalse(Files.exists(temporary.resolve("mods/a.jar")));
            assertFalse(Files.exists(temporary.resolve("mods/b.jar")));
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(result -> !result.verified() && result.attempts().size() == 2));
            SaoInstallProgress last = snapshots.get(snapshots.size() - 1);
            assertEquals(SaoInstallProgress.Stage.FAILED, last.stage());
            assertEquals(2, last.failures().size()); assertEquals(0, last.completedFiles());
            assertNoTemporaryFiles();
        }
    }

    /// A truncated response retains HMCL's partial context and resumes using Range + If-Range after backoff.
    @Test void interruptedResponseResumesAndVerifiesWholeFile() throws Exception {
        try (LocalServer server = new LocalServer()) {
            String content = "x".repeat(65536);
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<String> range = new AtomicReference<>("");
            server.server.createContext("/payload", exchange -> {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("ETag", "\"stable\"");
                if (requests.getAndIncrement() == 0) {
                    exchange.sendResponseHeaders(200, content.length());
                    exchange.getResponseBody().write(content.substring(0, 32768).getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                } else {
                    range.set(Objects.requireNonNull(exchange.getRequestHeaders().getFirst("Range")));
                    assertEquals("\"stable\"", exchange.getRequestHeaders().getFirst("If-Range"));
                    exchange.getResponseHeaders().set("Content-Range", "bytes 32768-65535/65536");
                    send(exchange, 206, content.substring(32768));
                }
            });
            List<SaoInstallProgress> snapshots = new CopyOnWriteArrayList<>();
            List<SaoModDownloads.Transfer> results = new CopyOnWriteArrayList<>();
            try (SaoModDownloads downloads = downloader(server, List.of("/payload"), snapshots, results, 1)) {
                Path destination = temporary.resolve("mods/resumed.jar");
                downloads.downloadAll(Map.of(file("resumed", content), destination));
                assertEquals(content, Files.readString(destination));
            }
            assertEquals("bytes=32768-", range.get()); assertEquals(2, requests.get());
            assertEquals(65536, results.get(0).networkBytes());
            assertNoTemporaryFiles();
        }
    }

    /// Bulk staging never exceeds six active files and waits for all workers before returning.
    @Test void parallelismIsBoundedAndAllFilesAreDrained() throws Exception {
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), finished = new AtomicInteger();
        CountDownLatch six = new CountDownLatch(6);
        Map<SaoPackManifest.PackFile, Path> files = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) files.put(file("mod" + i, "jar"), temporary.resolve("mod" + i));
        SaoModDownloads.runBatch((file, target) -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max); six.countDown();
            try { assertTrue(six.await(5, TimeUnit.SECONDS)); Thread.sleep(20); }
            finally { active.decrementAndGet(); finished.incrementAndGet(); }
        }, files);
        assertEquals(6, maximum.get()); assertEquals(12, finished.get()); assertEquals(0, active.get());
    }

    /// A failed replacement preserves an existing destination and cleanup completes before the exception escapes.
    @Test void failedDownloadPreservesExistingJar() throws Exception {
        try (LocalServer server = new LocalServer()) {
            server.server.createContext("/bad", exchange -> send(exchange, 200, "BAD"));
            Path destination = temporary.resolve("mods/a.jar"); Files.createDirectories(destination.getParent());
            Files.writeString(destination, "old-verified-content");
            try (SaoModDownloads downloads = downloader(server, List.of("/bad"), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), 0)) {
                assertThrows(IOException.class, () -> downloads.downloadAll(Map.of(file("a", "jar"), destination)));
            }
            assertEquals("old-verified-content", Files.readString(destination));
            assertNoTemporaryFiles();
        }
    }

    /// Shutdown interrupts in-flight workers and blocks later starts, without publishing incomplete files.
    @Test void closeRefusesNewTransfers() throws Exception {
        try (LocalServer server = new LocalServer()) {
            SaoModDownloads downloads = downloader(server, List.of("/unused"), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), 0);
            downloads.close();
            assertThrows(IOException.class, () -> downloads.downloadAll(Map.of(file("a", "jar"), temporary.resolve("mods/a.jar"))));
            assertFalse(Files.exists(temporary.resolve("mods/a.jar")));
        }
    }

    /// Converts immutable candidates to immutable URL strings for exact ordering assertions.
    private static @Unmodifiable List<String> urls(DownloadCandidates candidates) {
        return candidates.getCandidates().stream().map(DownloadCandidate::displayUrl).toList();
    }

    /// Builds a legitimate HTTPS index entry; only injected test candidate resolution uses loopback HTTP.
    private static SaoPackManifest.PackFile file(String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new SaoPackManifest.PackFile("mods/" + name + ".jar", SaoPackArchive.sha1(new ByteArrayInputStream(bytes)),
                bytes.length, null, List.of("https://cdn.modrinth.com/data/test/" + name + ".jar"));
    }

    /// Uses the production transfer subclass with deterministic local candidates and bounded timeouts.
    private static SaoModDownloads downloader(LocalServer server, List<String> paths, List<SaoInstallProgress> snapshots,
                                               List<SaoModDownloads.Transfer> results, int retries) {
        return new SaoModDownloads(ignored -> DownloadCandidates.of(paths.stream().map(path -> new DownloadCandidate(
                WebURL.parse(server.url(path)), null, retries, Duration.ofSeconds(2), Duration.ofSeconds(2))).toList()),
                snapshots::add, results::add, Set.of("127.0.0.1"));
    }

    /// Ensures every private sibling and HMCL destination temporary has been removed.
    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.walk(temporary)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    /// Sends a fixed response and closes its exchange, including on empty redirects.
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }

    /// An ephemeral IPv4 loopback server with concurrent request handling and deterministic shutdown.
    @NotNullByDefault
    private static final class LocalServer implements AutoCloseable {
        /// Bound local server; never contacts an external address.
        private final HttpServer server;
        /// Request workers, shut down with their owning fixture.
        private final ExecutorService executor = Executors.newCachedThreadPool();
        /// Starts the fixture without creating a JavaFX toolkit or opening a window.
        private LocalServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.start();
        }
        /// Returns an exact local endpoint using the ephemeral assigned port.
        private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        /// Stops the server and its worker threads.
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
