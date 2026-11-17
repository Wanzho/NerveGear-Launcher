/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.task;

import org.glavo.url.WebURL;
import org.jackhuang.hmcl.util.io.ContentEncoding;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.io.UrlResponseInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/// Four disjoint positional writes into one private spool, followed by the owner's normal integrity pass.
/// Only completed ranges survive a failed attempt, and only a matching strong ETag permits reuse.
@NotNullByDefault
final class SegmentedDownload implements Closeable {
    /// Files at or below eight MiB retain the existing single-stream behavior.
    static final long THRESHOLD = 8L * 1024 * 1024;
    /// Four connections per file; also bounds range bookkeeping and the cancellation drain.
    private static final int COUNT = 4;
    /// Caps simultaneous segment requests across all file tasks, independently of task concurrency.
    private static final Semaphore REQUESTS = new Semaphore(24);
    /// Owner provides HTTP policy, cancellation, progress and response evidence.
    private final FetchTask<?> owner;
    /// Spool is created lazily and removed when the enclosing FetchTask finishes.
    private @Nullable Path spool;
    /// Validated total size for the current spool.
    private long size;
    /// Strong HTTP representation validator; absent validators disable cross-attempt reuse.
    private @Nullable String validator;
    /// Completed range flags; read only after workers have drained.
    private final boolean[] complete = new boolean[COUNT];
    /// Connections belonging to this attempt, used to unblock workers on failure or interruption.
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();

    /// Creates no files or worker threads until a large HTTP body is encountered.
    SegmentedDownload(FetchTask<?> owner) { this.owner = owner; }

    /// Probes actual range support instead of trusting Accept-Ranges; null uses the original 200 stream.
    @Nullable Path fetch(UrlResponseInfo original, Duration connect, Duration read) throws IOException, InterruptedException {
        long length = original.headers().firstValueAsLong("content-length").orElse(-1L);
        HttpURLConnection probe = open(original.url(), 0, 0, null, connect, read);
        @Nullable String tag;
        try {
            if (!valid(probe, 0, 0, length)) { invalidate(); return null; }
            tag = strongTag(probe.getHeaderField("ETag"));
            try (InputStream input = probe.getInputStream()) {
                if (input.read() < 0 || input.read() != -1) { invalidate(); return null; }
            }
        } finally { release(probe); }

        if (spool == null) spool = Files.createTempFile("hmcl-ranges-", ".part");
        if (length != size || tag == null || !tag.equals(validator)) {
            invalidate();
            size = length;
            validator = tag;
            try (FileChannel file = FileChannel.open(spool, StandardOpenOption.WRITE)) { file.truncate(0); }
        }
        long retained = 0;
        for (int i = 0; i < COUNT; i++) if (complete[i]) retained += end(i) - start(i) + 1;
        AtomicLong downloaded = new AtomicLong(retained);
        owner.recordDownload(0, retained, size);
        ExecutorService workers = Executors.newFixedThreadPool(COUNT, action -> {
            Thread thread = new Thread(action, "Download-Range"); thread.setDaemon(true); return thread;
        });
        List<Future<Void>> pending = new ArrayList<>();
        CompletionService<Void> finished = new ExecutorCompletionService<>(workers);
        try (FileChannel file = FileChannel.open(spool, StandardOpenOption.WRITE)) {
            for (int i = 0; i < COUNT; i++) {
                if (complete[i]) continue;
                int index = i;
                pending.add(finished.submit(() -> {
                    REQUESTS.acquire();
                    try { transfer(original.url(), index, file, downloaded, connect, read); }
                    finally { REQUESTS.release(); }
                    return null;
                }));
            }
            try {
                for (int i = 0; i < pending.size(); i++) {
                    while (true) {
                        if (owner.isCancelled()) throw new InterruptedException();
                        @Nullable Future<Void> result = finished.poll(100, TimeUnit.MILLISECONDS);
                        if (result != null) { result.get(); break; }
                    }
                }
            } catch (ExecutionException failure) {
                @Nullable Throwable cause = failure.getCause();
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                if (cause instanceof IOException io) throw io;
                throw new IOException("Range worker failed", cause);
            } finally {
                pending.forEach(future -> future.cancel(true));
                workers.shutdownNow();
                active.forEach(HttpURLConnection::disconnect);
                // Windows must not move/delete a spool while a cancelled worker still owns it.
                boolean interrupted = Thread.interrupted();
                while (!workers.isTerminated()) {
                    try { workers.awaitTermination(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (file.size() != size) throw new IOException("Unexpected assembled range size");
        } finally {
            workers.shutdownNow();
        }
        return spool;
    }

    /// Reads one exact response and writes only within its assigned interval; partial ranges are never reused.
    private void transfer(WebURL url, int index, FileChannel file, AtomicLong downloaded,
                          Duration connect, Duration read) throws IOException, InterruptedException {
        long first = start(index), last = end(index);
        HttpURLConnection connection = open(url, first, last, validator, connect, read);
        try {
            if (!valid(connection, first, last, size)
                    || (validator != null && !validator.equals(strongTag(connection.getHeaderField("ETag")))))
                throw new IOException("Range response changed or does not match requested interval");
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                long offset = first;
                while (offset <= last) {
                    if (owner.isCancelled()) throw new InterruptedException();
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, last - offset + 1));
                    if (count < 0) throw new EOFException("Truncated HTTP range");
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                    while (bytes.hasRemaining()) offset += file.write(bytes, offset);
                    // Serialize aggregation with notification so progress cannot move backwards between workers.
                    synchronized (downloaded) {
                        owner.recordDownload(count, downloaded.addAndGet(count), size);
                    }
                }
                if (input.read() != -1) throw new IOException("Oversized HTTP range");
                complete[index] = true;
            }
        } finally { release(connection); }
    }

    /// Applies the same subclass request/redirect policy to every probe and range request.
    private HttpURLConnection open(WebURL url, long first, long last, @Nullable String tag,
                                   Duration connect, Duration read) throws IOException, InterruptedException {
        WebURL current = url;
        for (int redirects = 0; redirects <= 20; redirects++) {
            if (owner.isCancelled()) throw new InterruptedException();
            HttpURLConnection connection = NetworkUtils.createHttpConnection(current);
            active.add(connection);
            boolean keep = false;
            try {
                connection.setConnectTimeout((int) connect.toMillis());
                connection.setReadTimeout((int) read.toMillis());
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Range", "bytes=" + first + "-" + last);
                if (tag != null) connection.setRequestProperty("If-Range", tag);
                owner.prepareDownloadConnection(connection);
                int code = connection.getResponseCode();
                owner.observeDownloadResponse(UrlResponseInfo.of(connection));
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    @Nullable String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) throw new IOException("Empty range redirect");
                    current = current.resolve(location);
                    if (!NetworkUtils.isHttpUri(current)) throw new IOException("Non-HTTP range redirect");
                } else {
                    keep = true;
                    return connection;
                }
            } finally { if (!keep) release(connection); }
        }
        throw new IOException("Too many range redirects");
    }

    /// Requires exact Content-Range, explicit Content-Length and identity encoding before accepting bytes.
    private static boolean valid(HttpURLConnection connection, long first, long last, long total) throws IOException {
        return connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL
                && connection.getContentLengthLong() == last - first + 1
                && ("bytes " + first + "-" + last + "/" + total).equals(connection.getHeaderField("Content-Range"))
                && ContentEncoding.fromConnection(connection) == ContentEncoding.IDENTITY;
    }

    /// Weak validators cannot establish that bytes retained from another host are identical.
    private static @Nullable String strongTag(@Nullable String tag) {
        return tag != null && tag.startsWith("\"") && tag.endsWith("\"") ? tag : null;
    }

    /// Inclusive range start; integer arithmetic covers every byte even for uneven lengths.
    private long start(int index) { return size / COUNT * index; }
    /// Inclusive range end, with the remainder assigned to the last worker.
    private long end(int index) { return index == COUNT - 1 ? size - 1 : start(index + 1) - 1; }
    /// Removes a request from the cancellation set before disconnecting its socket.
    private void release(HttpURLConnection connection) { active.remove(connection); owner.forgetDownloadConnection(connection); connection.disconnect(); }
    /// Rejects retained ranges after invalid support, changed content, or a failed final digest.
    void invalidate() { Arrays.fill(complete, false); validator = null; }
    /// Deletes the private spool after all workers have drained, on success, failure and cancellation.
    @Override public void close() throws IOException { if (spool != null) Files.deleteIfExists(spool); }
}
