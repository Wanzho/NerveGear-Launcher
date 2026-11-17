/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.glavo.url.WebURL;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.download.DownloadCandidate;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.HMCLDownloadProvider;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.util.io.UrlResponseInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/// Modrinth-first, SHA-1 checked pack transfers using HMCL's candidate fallback and in-task range resume.
/// Never writes network bytes into a final jar; only verified same-directory temporary files are published.
@NotNullByDefault
final class SaoModDownloads implements SaoPackInstaller.Downloader, AutoCloseable {
    /// Optional launcher data root; local imports are checked before candidate resolution.
    private @Nullable Path offlineRoot;

    /// Registers the owned source directory without initiating I/O.
    void useOfflineRoot(Path root) { offlineRoot = root; }

    /// Fixed bulk limit, independent of the player's Minecraft/assets concurrency preference.
    static final int PARALLELISM = 6;
    /// Identifies this launcher on every request and redirect, without account or machine identifiers.
    static final String USER_AGENT = "NerveGear-Launcher/" + Metadata.VERSION;
    /// Attempts on the last remaining source: with nowhere else to go it resumes with backoff instead of failing.
    static final int LAST_SOURCE_RETRY = 6;
    /// Production low-throughput window; a stalled candidate is discarded rather than resumed on that host.
    private final Duration stallWindow;
    /// Minimum sustained payload rate; catches the measured roughly 60 KB/s CloudFront stall.
    private final long minimumBytesPerSecond;
    /// Process-wide MCIM request pacing and Retry-After cooldown, shared across install attempts.
    private static final MirrorPacer PACER = new MirrorPacer();
    /// Provider supplies deduplicated mirror/original candidates for both CDN families.
    private final Function<List<String>, DownloadCandidates> candidates;
    /// Receives coalesced immutable progress; called on download workers.
    private final Consumer<SaoInstallProgress> progress;
    /// Receives final per-file evidence, including verified source and elapsed wall time.
    private final Consumer<Transfer> evidence;
    /// Empty in production; proof commands restrict every redirect to an explicit host allowlist.
    private final @Unmodifiable Set<String> allowedHosts;
    /// Active threads are interrupted by shutdown; files are cleaned by their owning workers.
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    /// Active HTTP connections let shutdown unblock network reads before staging is removed.
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    /// Serializes counter updates and snapshot publication across six workers.
    private final Map<String, Long> partial = new HashMap<>();
    /// Remaining file names and their current response hosts, guarded by this instance.
    private final Map<String, String> remaining = new LinkedHashMap<>();
    /// Terminal failures retained until the next batch starts.
    private final List<SaoInstallProgress.Failure> failures = new ArrayList<>();
    /// Batch counters, guarded by this instance.
    private int completed, total;
    /// Batch byte counters, including retransferred bytes, guarded by this instance.
    private long verifiedBytes, totalBytes, networkBytes, lastPublish, speedNanos, speedBytes;
    /// Most recently measured network throughput, guarded by this instance.
    private double speed;
    /// Prevents new transfers after launcher shutdown.
    private volatile boolean closed;

    /// Creates the production downloader, without connecting or starting workers.
    SaoModDownloads(HMCLDownloadProvider provider, Consumer<SaoInstallProgress> progress) {
        this(provider, progress, transfer -> { }, Set.of());
    }

    /// Creates a proof downloader with final evidence and an optional strict redirect allowlist.
    SaoModDownloads(HMCLDownloadProvider provider, Consumer<SaoInstallProgress> progress,
                    Consumer<Transfer> evidence, Set<String> allowedHosts) {
        this(provider::getPackDownloadCandidates, progress, evidence, allowedHosts);
    }

    /// Injects only candidate resolution for local HTTP integration tests; all transfer code remains real HMCL.
    SaoModDownloads(Function<List<String>, DownloadCandidates> candidates, Consumer<SaoInstallProgress> progress,
                    Consumer<Transfer> evidence, Set<String> allowedHosts) {
        this(candidates, progress, evidence, allowedHosts, Duration.ofSeconds(10), 200 * 1024);
    }

    /// Injects a short stall window for deterministic loopback tests without changing production policy.
    SaoModDownloads(Function<List<String>, DownloadCandidates> candidates, Consumer<SaoInstallProgress> progress,
                    Consumer<Transfer> evidence, Set<String> allowedHosts, Duration stallWindow, long minimumBytesPerSecond) {
        if (stallWindow.isNegative() || stallWindow.isZero() || minimumBytesPerSecond < 1)
            throw new IllegalArgumentException("Invalid stall policy");
        this.stallWindow = stallWindow; this.minimumBytesPerSecond = minimumBytesPerSecond;
        this.candidates = candidates; this.progress = progress; this.evidence = evidence;
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    /// One file's measured result; used by the live proof tool without keeping downloaded jars.
    @NotNullByDefault
    record Transfer(String path, long size, String expectedSha1, String actualSha1, boolean verified, String finalHost,
                    double seconds, double bytesPerSecond, long networkBytes,
                    @Unmodifiable List<SaoInstallProgress.Attempt> attempts, @Unmodifiable List<Stall> stalls, String error) {
        /// Freezes HTTP evidence before it leaves its worker.
        Transfer { attempts = List.copyOf(attempts); stalls = List.copyOf(stalls); }
    }

    /// Measured low-speed event; elapsed time and bytes cover one candidate's payload window.
    @NotNullByDefault
    record Stall(String host, double seconds, long bytes, double bytesPerSecond) { }

    /// Starts a fresh stage and waits for all files, collecting every terminal failure before throwing.
    @Override public void downloadAll(Map<SaoPackManifest.PackFile, Path> files) throws Exception {
        beginDownloads(files);
        runBatch(this, files);
    }

    /// Opens one measured download stage for either a batch or a standalone official quick dependency.
    private synchronized void beginDownloads(Map<SaoPackManifest.PackFile, Path> files) {
        remaining.clear();
        largestFirst(files).forEach(item -> remaining.put(item.getKey().path(), "等待连接"));
        partial.clear(); failures.clear(); completed = 0; total = files.size(); verifiedBytes = 0;
        totalBytes = files.keySet().stream().mapToLong(SaoPackManifest.PackFile::size).sum(); networkBytes = 0;
        speedNanos = System.nanoTime(); lastPublish = speedBytes = 0; speed = 0;
        publish(true, false);
    }

    /// Stable descending-size submission order makes the largest jars occupy the first worker slots.
    static @Unmodifiable List<Map.Entry<SaoPackManifest.PackFile, Path>> largestFirst(Map<SaoPackManifest.PackFile, Path> files) {
        return files.entrySet().stream().sorted(Comparator.comparingLong(
                (Map.Entry<SaoPackManifest.PackFile, Path> item) -> item.getKey().size()).reversed()).toList();
    }

    /// Runs up to six blocking downloads and drains workers before the caller can delete staging.
    /// Generic fixture downloaders also use this path, so transactional tests cover real bulk behavior.
    static void runBatch(SaoPackInstaller.Downloader downloader, Map<SaoPackManifest.PackFile, Path> files) throws Exception {
        if (files.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLELISM, files.size()), action -> {
            Thread thread = new Thread(action, "sao-mod-download"); thread.setDaemon(true); return thread;
        });
        List<Future<String>> pending = new ArrayList<>();
        try {
            for (var item : largestFirst(files)) pending.add(pool.submit(() -> {
                try { downloader.download(item.getKey(), item.getValue()); return ""; }
                catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    return item.getKey().path() + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
                }
            }));
            List<String> errors = new ArrayList<>();
            for (Future<String> result : pending) {
                String error = result.get();
                if (!error.isEmpty()) errors.add(error);
            }
            if (!errors.isEmpty()) throw new IOException("模组下载失败 " + errors.size() + "/" + files.size() + "\n" + String.join("\n", errors));
        } finally {
            pool.shutdownNow();
            // Do not race staging cleanup with a worker still writing after cancellation.
            boolean interrupted = Thread.interrupted();
            while (!pool.isTerminated()) {
                try { pool.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /// Fetches a file into a private sibling, rechecks its hash/size, then atomically publishes it.
    @Override public void download(SaoPackManifest.PackFile file, Path destination) throws Exception {
        download(file, destination, false);
    }

    /// Uses only the manifest's one official file URL, including on range retries and redirects.
    @Override public void downloadOfficialMod(SaoPackManifest.PackFile file, Path destination) throws Exception {
        if (file.downloads().size() != 1 || !SaoQuickUpdate.officialModUrl(file.downloads().get(0)))
            throw new IOException("Invalid official Modrinth URL");
        beginDownloads(Map.of(file, destination));
        download(file, destination, true);
    }

    /// Shares bounded staging, checksum verification and publication between both source policies.
    private void download(SaoPackManifest.PackFile file, Path destination, boolean officialOnly) throws Exception {
        if (closed) throw new IOException("Pack downloader has closed");
        Thread worker = Thread.currentThread();
        long start = System.nanoTime();
        DownloadCandidates resolved = DownloadCandidates.of(file.downloads().get(0));
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path staged = Files.createTempFile(destination.toAbsolutePath().getParent(), ".sao-download-", ".part");
        TransferTask task = new TransferTask(resolved, staged, file, officialOnly);
        workers.add(worker);
        boolean verified = false;
        String actualSha1 = "";
        String error = "";
        try {
            if (closed || worker.isInterrupted()) throw new InterruptedException();
            if (SaoPackFiles.matches(destination, file)) {
                task.finalHost = "existing"; actualSha1 = file.sha1(); verified = true; return;
            }
            @Nullable Path localRoot = offlineRoot;
            if (localRoot != null && SaoOfflineBundle.copyMod(localRoot, file, staged)) {
                task.finalHost = "offline-bundle";
            } else try (@Nullable InputStream bundled = SaoModDownloads.class.getResourceAsStream("/assets/sao/files/" + file.sha1())) {
                if (bundled != null) Files.copy(bundled, staged, StandardCopyOption.REPLACE_EXISTING);
                else {
                    task = new TransferTask(officialOnly ? resolved : candidates.apply(file.downloads()), staged, file, officialOnly);
                    task.run();
                }
            }
            if (closed || worker.isInterrupted()) throw new InterruptedException();
            if (Files.size(staged) != file.size()) throw new IOException("Pack size mismatch: " + file.path());
            try (InputStream input = Files.newInputStream(staged)) { actualSha1 = SaoPackArchive.sha1(input); }
            if (!actualSha1.equals(file.sha1())) throw new IOException("Pack SHA-1 mismatch: " + file.path());
            if (!SaoPackFiles.matches(staged, file)) throw new IOException("Pack index digest mismatch: " + file.path());
            SaoPackFiles.atomicMove(staged, destination);
            verified = true;
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "Transfer failed");
            if (error.length() > 2000) error = error.substring(0, 2000);
            throw new IOException(file.path() + " [hosts tried: " + task.hosts() + "] " + error, failure);
        } finally {
            try { Files.deleteIfExists(staged); }
            finally {
                workers.remove(worker);
                connections.removeAll(task.requests);
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                synchronized (this) {
                    remaining.remove(file.path());
                    partial.remove(file.path());
                    if (verified) { completed++; verifiedBytes += file.size(); }
                    else failures.add(new SaoInstallProgress.Failure(file.path(), task.attempts, error));
                    publish(true, !failures.isEmpty());
                }
                evidence.accept(new Transfer(file.path(), file.size(), file.sha1(), actualSha1, verified, task.finalHost, seconds,
                        task.bytes / Math.max(seconds, 0.001), task.bytes, task.attempts, task.stalls, error));
            }
        }
    }

    /// Gives the last candidate extra resume attempts; a slow or briefly dropped only source must not fail the batch.
    static DownloadCandidates patientLastSource(DownloadCandidates resolved) {
        List<DownloadCandidate> list = new ArrayList<>(resolved.getCandidates());
        if (list.isEmpty()) return resolved;
        DownloadCandidate last = list.get(list.size() - 1);
        if (last.retry() >= LAST_SOURCE_RETRY) return resolved;
        list.set(list.size() - 1, new DownloadCandidate(last.url(), last.rawUrl(), LAST_SOURCE_RETRY, last.connectTimeout(), last.readTimeout()));
        return DownloadCandidates.of(list);
    }

    /// Publishes measured counters at most five times/second, plus every file completion/failure.
    private synchronized void publish(boolean force, boolean failed) {
        long now = System.nanoTime();
        if (!force && now - lastPublish < 200_000_000L) return;
        if (now - speedNanos >= 200_000_000L) {
            speed = (networkBytes - speedBytes) * 1_000_000_000.0 / (now - speedNanos);
            speedBytes = networkBytes; speedNanos = now;
        }
        lastPublish = now;
        long done = verifiedBytes + partial.values().stream().mapToLong(Long::longValue).sum();
        boolean finished = completed + failures.size() >= total;
        String label = "下载模组 " + completed + "/" + total;
        if (!remaining.isEmpty() && remaining.size() <= 3) {
            label = "剩下 " + remaining.size() + " 个：" + String.join("、", remaining.entrySet().stream()
                    .map(item -> Path.of(item.getKey()).getFileName() + "（" + item.getValue() + "）").toList());
        }
        progress.accept(new SaoInstallProgress(failed && finished ? SaoInstallProgress.Stage.FAILED : SaoInstallProgress.Stage.DOWNLOAD_MODS,
                label, completed, total, Math.min(done, totalBytes), totalBytes,
                networkBytes, finished ? 0 : speed, failures));
    }

    /// Interrupts active requests; installer waits for the workers before deleting its private stage.
    @Override public void close() {
        closed = true;
        workers.forEach(Thread::interrupt);
        connections.forEach(HttpURLConnection::disconnect);
    }

    /// HMCL transfer with explicit product UA, shared MCIM pacing, measured bytes and redirect evidence.
    @NotNullByDefault
    private final class TransferTask extends FileDownloadTask {
        /// The index identity checked for every candidate, in addition to HMCL's SHA-1 check.
        private final SaoPackManifest.PackFile file;
        /// Quick-update remote dependencies must stay on the publisher's CDN.
        private final boolean officialOnly;
        /// Actual request history, guarded by the list while range workers record concurrent responses.
        private final List<SaoInstallProgress.Attempt> attempts = new ArrayList<>();
        /// Connections registered for cancellation and removed when the file finishes.
        private final List<HttpURLConnection> requests = new CopyOnWriteArrayList<>();
        /// Associates each response with its request even when range responses arrive out of order.
        private final ThreadLocal<Integer> requestIndex = ThreadLocal.withInitial(() -> -1);
        /// Last body host; becomes the verified source only when the overall file succeeds.
        private volatile String finalHost = "bundled";
        /// Network byte count across retries and candidates.
        private volatile long bytes;
        /// Stall observations are written under this task's monitor and frozen after the watchdog is drained.
        private final List<Stall> stalls = new ArrayList<>();
        /// Uses the same aggregate watchdog for single streams and parallel ranges.
        @Override protected Duration slowDownloadWindow() { return stallWindow; }

        /// Production uses 200 KiB/s; tests can inject a deterministic local threshold.
        @Override protected long minimumDownloadSpeed() { return minimumBytesPerSecond; }

        /// Captures the evidence before FetchTask disconnects every socket for this candidate.
        @Override protected void slowDownload(double seconds, long received) {
            super.slowDownload(seconds, received);
            stalls.add(new Stall(finalHost, seconds, received, received / seconds));
        }

        /// Disables URL/ETag caching so an unchecked cached result cannot bypass the index digest.
        private TransferTask(DownloadCandidates candidates, Path path, SaoPackManifest.PackFile file, boolean officialOnly) {
            super(candidates, path, new IntegrityCheck("SHA-1", file.sha1()));
            this.file = file;
            this.officialOnly = officialOnly;
            addIntegrityCheckHandler((temporary, target) -> {
                if (Files.size(temporary) != file.size()) throw new IOException("Index size mismatch: " + file.path());
            });
        }

        /// Requires checksum verification even if a URL previously populated HMCL's HTTP cache.
        @Override protected EnumCheckETag shouldCheckETag() { return EnumCheckETag.NOT_CHECK_E_TAG; }

        /// A BMCLAPI hash hint may offer a cache entry even with ETag disabled; recheck the index identity.
        @Override protected void useCachedResult(Path cached) throws IOException {
            super.useCachedResult(cached);
            if (!SaoPackFiles.matches(getPath(), file)) throw new IOException("Cached dependency does not match index SHA-1/size");
        }

        /// Records each real request, setting UA after HMCL's defaults and before connect.
        @Override protected void configureConnection(HttpURLConnection connection) throws IOException, InterruptedException {
            String host = connection.getURL().getHost();
            if (officialOnly && !SaoQuickUpdate.officialModUrl(connection.getURL().toExternalForm()))
                throw new IOException("Modrinth redirected outside its official version-file CDN");
            if (!allowedHosts.isEmpty() && !allowedHosts.contains(host)) throw new IOException("Proof host is not allowed: " + host);
            if (closed) throw new InterruptedException();
            if (host.equals("mod.mcimirror.top")) PACER.acquire();
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Encoding", "identity");
            synchronized (attempts) {
                requestIndex.set(attempts.size());
                attempts.add(new SaoInstallProgress.Attempt(connection.getURL().toString(), host, 0));
            }
            requests.add(connection); connections.add(connection);
        }

        /// Saves only URL, host and status, never response credentials or unrelated headers.
        @Override protected void receivedResponse(UrlResponseInfo response) {
            SaoInstallProgress.Attempt request;
            synchronized (attempts) {
                int index = requestIndex.get();
                request = attempts.get(index);
                attempts.set(index, new SaoInstallProgress.Attempt(request.url(), request.host(), response.responseCode()));
            }
            if (response.responseCode() / 100 == 2) {
                finalHost = request.host();
                synchronized (SaoModDownloads.this) {
                    @Nullable String previous = remaining.put(file.path(), finalHost);
                    publish(!finalHost.equals(previous), false);
                }
            }
            if (request.host().equals("mod.mcimirror.top") && (response.responseCode() == 429 || response.responseCode() == 503))
                PACER.cooldown(response.headers().firstValue("retry-after").orElse("5"));
        }

        /// Three attempts per candidate, separated by 1/2 seconds plus jitter, retain HMCL partial contexts.
        @Override protected long retryDelayMillis(int failedAttempt) {
            return Math.min(8000L, 1000L << Math.min(failedAttempt, 3)) + ThreadLocalRandom.current().nextLong(251);
        }

        /// Counts network payload once, including discarded retries, and reports retained range progress.
        @Override protected void downloadProgress(long received, long retained) throws IOException {
            if (retained > file.size()) throw new IOException("Index byte limit exceeded: " + file.path());
            synchronized (SaoModDownloads.this) {
                bytes += received;
                networkBytes += received;
                partial.put(file.path(), retained);
                publish(false, false);
            }
        }

        /// Summarizes actual attempted hosts, preserving order and removing repeats.
        private String hosts() { return String.join(", ", attempts.stream().map(SaoInstallProgress.Attempt::host).distinct().toList()); }
    }

    /// Spaces MCIM requests by 500 ms (at most two starts/second) and honors server Retry-After.
    @NotNullByDefault
    private static final class MirrorPacer {
        /// Next allowed request time, using monotonic nanoseconds.
        private long next;
        /// Shared server-imposed cooldown deadline.
        private long blockedUntil;

        /// Reserves one request slot; waits interruptibly without occupying another download worker's monitor.
        private void acquire() throws InterruptedException {
            while (true) {
                long wait;
                synchronized (this) {
                    long now = System.nanoTime();
                    wait = Math.max(next, blockedUntil) - now;
                    if (wait <= 0) { next = now + 500_000_000L; return; }
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(wait, TimeUnit.SECONDS.toNanos(1)));
            }
        }

        /// Accepts both HTTP-date and delta-seconds; an invalid value gets a conservative five-second pause.
        private synchronized void cooldown(String retryAfter) {
            long seconds;
            try { seconds = Long.parseLong(retryAfter); }
            catch (NumberFormatException invalidSeconds) {
                try { seconds = Duration.between(Instant.now(), ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds(); }
                catch (RuntimeException invalidDate) { seconds = 5; }
            }
            // A hostile header must not overflow nanoseconds. Large valid cooldowns remain interruptible.
            seconds = Math.max(1, Math.min(seconds, 86_400));
            blockedUntil = Math.max(blockedUntil, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
        }
    }
}
