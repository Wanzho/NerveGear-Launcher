/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.task;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.download.DownloadCandidate;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Headless loopback tests exercise real FetchTask HTTP, range workers, digest verification and cancellation.
@NotNullByDefault
final class SegmentedDownloadTest {
    /// Destination files live in an isolated JUnit directory.
    @TempDir Path temporary;
    /// Uneven length checks the last range's remainder as well as the eight MiB boundary.
    private static final byte @Unmodifiable [] PAYLOAD = payload();

    /// Reproducible nonuniform bytes expose misplaced, duplicated and missing positional writes.
    private static byte[] payload() {
        byte[] result = new byte[9 * 1024 * 1024 + 37];
        new Random(421).nextBytes(result);
        return result;
    }

    /// A four-way barrier proves concurrency; successful publication requires the expected SHA-1.
    @Test void rangesRunInParallelAndVerifySha1() throws Exception {
        try (Server server = new Server()) {
            CountDownLatch concurrent = new CountDownLatch(4);
            AtomicInteger segments = new AtomicInteger();
            server.server.createContext("/file", exchange -> server.range(exchange, "\"same\"", segments, concurrent, false, false));
            Path target = temporary.resolve("large.jar");
            task(server, target, digest(PAYLOAD), "/file").run();
            assertEquals(4, segments.get());
            assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
        }
    }

    /// A server that ignores the probe uses its already open original 200 response.
    @Test void noRangesFallsBackToSingleStream() throws Exception {
        try (Server server = new Server()) {
            AtomicInteger probes = new AtomicInteger(), streams = new AtomicInteger();
            server.server.createContext("/file", exchange -> {
                if (exchange.getRequestHeaders().getFirst("Range") == null) streams.incrementAndGet();
                else probes.incrementAndGet();
                server.full(exchange, false);
            });
            Path target = temporary.resolve("plain.jar");
            task(server, target, digest(PAYLOAD), "/file").run();
            assertEquals(1, probes.get()); assertEquals(1, streams.get());
            assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
        }
    }

    /// Corrupt bytes never replace an existing destination even when all range sizes are correct.
    @Test void sha1MismatchDoesNotPublish() throws Exception {
        try (Server server = new Server()) {
            server.server.createContext("/file", exchange -> server.range(exchange, "\"same\"", new AtomicInteger(), null, false, false));
            Path target = temporary.resolve("protected.jar");
            Files.writeString(target, "existing");
            assertThrows(Exception.class, () -> task(server, target, "0".repeat(40), "/file").run());
            assertEquals("existing", Files.readString(target));
        }
    }

    /// A slow segmented candidate is abandoned; the next mirror reuses the one completed matching range.
    @Test void slowMirrorKeepsCompletedRangesWithMatchingValidator() throws Exception {
        switchMirror("\"same\"", 3);
    }

    /// Equal size is insufficient: changed validators force all four ranges to be fetched again.
    @Test void changedMirrorValidatorRestartsAllRanges() throws Exception {
        switchMirror("\"changed\"", 4);
    }

    /// Missing validators also restart rather than mixing unproven bytes from two hosts.
    @Test void absentMirrorValidatorRestartsAllRanges() throws Exception {
        switchMirror(null, 4);
    }

    /// Runs the same slow-to-fast handoff with a controlled validator on the replacement representation.
    private void switchMirror(@Nullable String nextTag, int expectedFastRanges) throws Exception {
        try (Server server = new Server()) {
            AtomicInteger slow = new AtomicInteger(), fast = new AtomicInteger();
            server.server.createContext("/slow", exchange -> server.range(exchange, "\"same\"", slow, null, true, false));
            server.server.createContext("/fast", exchange -> server.range(exchange, nextTag, fast, null, false, false));
            Path target = temporary.resolve("switched.jar");
            long start = System.nanoTime();
            task(server, target, digest(PAYLOAD), "/slow", "/fast").run();
            assertEquals(4, slow.get(), "one attempt only on the slow host");
            assertEquals(expectedFastRanges, fast.get());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15));
            assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
        }
    }

    /// Slow single streams use the same watchdog and advance without exhausting retries.
    @Test void slowSingleStreamSwitchesToFastCandidate() throws Exception {
        try (Server server = new Server()) {
            AtomicInteger slow = new AtomicInteger();
            server.server.createContext("/slow", exchange -> { slow.incrementAndGet(); server.full(exchange, true); });
            server.server.createContext("/fast", exchange -> server.full(exchange, false));
            Path target = temporary.resolve("single.jar");
            task(server, target, digest(PAYLOAD), "/slow", "/fast").run();
            assertEquals(2, slow.get(), "original response plus unsupported range probe");
            assertArrayEquals(PAYLOAD, Files.readAllBytes(target));
        }
    }

    /// A 206 with a false interval must never be accepted as a valid segmented representation.
    @Test void wrongContentRangeDoesNotPublish() throws Exception {
        try (Server server = new Server()) {
            server.server.createContext("/file", exchange -> server.range(exchange, "\"same\"", new AtomicInteger(), null, false, true));
            Path target = temporary.resolve("invalid.jar");
            assertThrows(Exception.class, () -> task(server, target, digest(PAYLOAD), "/file").run());
            assertFalse(Files.exists(target));
        }
    }

    /// Cancellation drains all range writers and preserves a preexisting final file.
    @Test void cancellationDoesNotPublishPartialFile() throws Exception {
        try (Server server = new Server()) {
            AtomicInteger ranges = new AtomicInteger();
            server.server.createContext("/file", exchange -> server.range(exchange, "\"same\"", ranges, null, true, false));
            Path target = temporary.resolve("cancelled.jar");
            Files.writeString(target, "existing");
            TestTask download = task(server, target, digest(PAYLOAD), "/file");
            download.setCancelled(() -> ranges.get() == 4);
            assertThrows(InterruptedException.class, download::run);
            assertEquals("existing", Files.readString(target));
        }
    }

    /// Creates a real file task without HTTP cache shortcuts and with a short deterministic stall window.
    private static TestTask task(Server server, Path target, String sha1, String... paths) {
        return new TestTask(DownloadCandidates.of(Arrays.stream(paths).map(path -> new DownloadCandidate(
                WebURL.parse(server.url(path)), null, 0, Duration.ofSeconds(2), Duration.ofSeconds(2))).toList()), target, sha1);
    }

    /// Expected digest is computed independently from FetchTask's streaming digest pass.
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    /// Fixture policy changes only cache use and the watchdog duration.
    @NotNullByDefault
    private static final class TestTask extends FileDownloadTask {
        /// Requires SHA-1 for every destination publication.
        private TestTask(DownloadCandidates candidates, Path path, String sha1) {
            super(candidates, path, new IntegrityCheck("SHA-1", sha1));
        }
        /// Test traffic always reaches the loopback server.
        @Override protected EnumCheckETag shouldCheckETag() { return EnumCheckETag.NOT_CHECK_E_TAG; }
        /// Leaves enough time for the first fast range to complete before measuring the three slow ones.
        @Override protected Duration slowDownloadWindow() { return Duration.ofMillis(800); }
    }

    /// Concurrent local HTTP origin; all fixture threads and exchanges are explicitly closed.
    @NotNullByDefault
    private static final class Server implements AutoCloseable {
        /// Ephemeral loopback HTTP listener.
        private final HttpServer server;
        /// Concurrent exchange handling makes a four-way range barrier meaningful.
        private final ExecutorService executor = Executors.newCachedThreadPool();
        /// Starts an ephemeral local listener, without GUI or public network access.
        private Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.start();
        }
        /// Constructs one loopback candidate URL.
        private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        /// Sends a full response, optionally trickling at roughly 25 KiB/s.
        private void full(HttpExchange exchange, boolean slow) throws IOException {
            try {
                exchange.sendResponseHeaders(200, PAYLOAD.length);
                write(exchange, 0, PAYLOAD.length - 1, slow);
            } catch (IOException ignored) { }
            finally { exchange.close(); }
        }
        /// Implements strict byte ranges and optional slow, invalid or barrier-controlled responses.
        private void range(HttpExchange exchange, @Nullable String tag, AtomicInteger count,
                           @Nullable CountDownLatch barrier, boolean slow, boolean invalid) throws IOException {
            try {
                @Nullable String header = exchange.getRequestHeaders().getFirst("Range");
                if (header == null) { full(exchange, false); return; }
                String[] bounds = header.substring("bytes=".length()).split("-");
                int first = Integer.parseInt(bounds[0]), last = Integer.parseInt(bounds[1]);
                if (tag != null) exchange.getResponseHeaders().set("ETag", tag);
                boolean probe = first == 0 && last == 0;
                if (!probe) {
                    count.incrementAndGet();
                    if (barrier != null) {
                        barrier.countDown();
                        if (!barrier.await(5, TimeUnit.SECONDS)) throw new IOException("Ranges were not parallel");
                    }
                }
                exchange.getResponseHeaders().set("Content-Range", "bytes " + (invalid && !probe ? first + 1 : first) + "-" + last + "/" + PAYLOAD.length);
                exchange.sendResponseHeaders(206, last - first + 1);
                write(exchange, first, last, slow && first > 0);
            } catch (IOException ignored) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }
        /// Writes an interval, stopping promptly when the client abandons a candidate.
        private void write(HttpExchange exchange, int first, int last, boolean slow) throws IOException {
            try (OutputStream output = exchange.getResponseBody()) {
                for (int offset = first; offset <= last;) {
                    int length = Math.min(slow ? 1024 : 64 * 1024, last - offset + 1);
                    output.write(PAYLOAD, offset, length); output.flush(); offset += length;
                    if (slow) {
                        try { Thread.sleep(40); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
        }
        /// Stops exchanges and worker threads after each assertion scope.
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
