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
package org.jackhuang.hmcl.task;

import org.glavo.url.WebURL;
import org.jackhuang.hmcl.download.DownloadCandidate;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.event.Event;
import org.jackhuang.hmcl.event.EventBus;
import org.jackhuang.hmcl.event.EventManager;
import org.jackhuang.hmcl.util.*;
import org.jackhuang.hmcl.util.io.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URLConnection;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.Lang.*;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Downloads candidate URLs in order, with retries, HTTP caching, and supported range resumption.
@NotNullByDefault
public abstract class FetchTask<T extends @UnknownNullability Object> extends Task<T> {

    protected static final int DEFAULT_RETRY = 5;

    protected final DownloadCandidates candidates;
    protected CacheRepository repository = CacheRepository.getInstance();

    /// Creates a download task with a snapshot of the candidate URLs.
    ///
    /// @param candidates nonempty candidate URLs, with no null elements
    /// @throws IllegalArgumentException if no candidates are supplied
    /// @throws NullPointerException if the list or any element is null
    public FetchTask(DownloadCandidates candidates) {
        this.candidates = candidates;
        setExecutor(DOWNLOAD_EXECUTOR);
    }

    public void setCacheRepository(CacheRepository repository) {
        this.repository = repository;
    }

    /// Active HTTP requests across the original stream, probe and all ranges.
    private final Set<HttpURLConnection> activeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /// Payload bytes in this attempt, observed by the watchdog independently of disk replay.
    private final AtomicLong attemptBytes = new AtomicLong();
    /// Monotonic observation window; zero between attempts, guarded by this task.
    private long throughputStart, throughputBytes;
    /// A slow attempt advances directly to the next health-ordered candidate.
    private volatile boolean slowAttempt;

    /// Production throughput observation window; subclasses can inject shorter fixture windows.
    protected Duration slowDownloadWindow() { return Duration.ofSeconds(10); }

    /// Production handoff threshold in bytes per second.
    protected long minimumDownloadSpeed() { return 200 * 1024L; }

    /// Observes a slow attempt before its sockets are disconnected.
    protected void slowDownload(double seconds, long bytes) {
        LOG.warning("Download below " + minimumDownloadSpeed() + " B/s for " + seconds
                + " s (" + bytes + " bytes); switching to the next candidate");
    }

    /// Applies subclass policy and registers every connection, including range workers, for abort.
    final void prepareDownloadConnection(HttpURLConnection connection) throws IOException, InterruptedException {
        activeRequests.add(connection);
        configureConnection(connection);
    }

    /// Starts a single aggregate payload window; parallel responses must not reset it.
    final void observeDownloadResponse(UrlResponseInfo response) throws IOException {
        receivedResponse(response);
        synchronized (this) {
            if (response.responseCode() / 100 == 2 && throughputStart == 0)
                throughputStart = System.nanoTime();
        }
    }

    /// Releases tracking as soon as a connection is no longer active.
    final void forgetDownloadConnection(HttpURLConnection connection) { activeRequests.remove(connection); }

    /// Detects silent and trickling bodies, using aggregate throughput for segmented files.
    private void checkDownloadSpeed() {
        @Unmodifiable List<HttpURLConnection> abort = List.of();
        synchronized (this) {
            if (!handOverWhenBenched || throughputStart == 0 || slowAttempt) return;
            long elapsed = System.nanoTime() - throughputStart;
            if (elapsed < slowDownloadWindow().toNanos()) return;
            long received = attemptBytes.get() - throughputBytes;
            double seconds = elapsed / 1_000_000_000.0;
            if (received / seconds < minimumDownloadSpeed()) {
                slowAttempt = true;
                slowDownload(seconds, received);
                abort = List.copyOf(activeRequests);
            } else {
                throughputStart = System.nanoTime();
                throughputBytes = attemptBytes.get();
            }
        }
        // Snapshot this attempt: disconnect() may block while the owner advances to another candidate.
        abort.forEach(HttpURLConnection::disconnect);
    }

    /// Per-execution range spool, retained across candidates only with matching strong validators.
    private @Nullable SegmentedDownload segments;

    /// File tasks opt in; in-memory fetches keep their streaming path.
    protected boolean supportsSegments() { return false; }

    /// Receives payload bytes and current usable partial size, including retained ranges.
    protected void downloadProgress(long received, long retained) throws IOException { }

    /// Records network throughput once, independently of the later sequential digest pass.
    final synchronized void recordDownload(long received, long retained, long total) throws IOException {
        attemptBytes.addAndGet(received);
        updateDownloadSpeed(received);
        downloadProgress(received, retained);
        if (total > 0) updateProgress(retained, total);
    }

    /// Invoked before each download attempt, after any cache lookup.
    ///
    /// @param url the candidate URL before redirects
    /// @throws IOException if the attempt cannot be prepared
    protected void beforeDownload(WebURL url) throws IOException {
    }

    /// Configures an HTTP attempt, including each redirected request, before it connects.
    /// Subclasses may set a product User-Agent, pace requests, or reject a redirect destination.
    protected void configureConnection(HttpURLConnection connection) throws IOException, InterruptedException { }

    /// Observes each HTTP response, including redirects and throttling headers, before body consumption.
    protected void receivedResponse(UrlResponseInfo response) throws IOException { }

    /// Delay before the next retry of a candidate; subclasses may provide bounded exponential backoff.
    /// Grows 0.5 s, 1 s, 2 s, 4 s with a little jitter, so a struggling mirror is not hammered by every file at once.
    protected long retryDelayMillis(int failedAttempt) {
        long base = 500L << Math.min(failedAttempt, 3);
        return base + java.util.concurrent.ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    /// True while a later candidate exists, so a host benched mid-retry hands over instead of retrying.
    private volatile boolean handOverWhenBenched;

    /// The host of a candidate, or null when it has none.
    static @Nullable String hostOf(WebURL url) {
        try { return java.net.URI.create(url.toString()).getHost(); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    /// Whether a recoverable failure should retry this candidate; false advances to the next candidate.
    /// Subclasses can reject stalled or permanently invalid responses without retrying the same host.
    protected boolean shouldRetry(Exception failure) { return true; }

    protected abstract void useCachedResult(Path cachedFile) throws IOException;

    protected abstract EnumCheckETag shouldCheckETag();

    private Context getContext() throws IOException {
        return getContext(null, false, null);
    }

    protected abstract Context getContext(@Nullable UrlResponseInfo response, boolean checkETag, @Nullable String bmclapiHash) throws IOException;

    /// Attempts candidates in order, propagates interruption, and retains each candidate's failure as context.
    @Override
    public void execute() throws Exception {
        boolean checkETag;
        switch (shouldCheckETag()) {
            case CHECK_E_TAG -> checkETag = true;
            case NOT_CHECK_E_TAG -> checkETag = false;
            default -> {
                return;
            }
        }

        @Nullable ArrayList<DownloadException> exceptions = null;

        if (SEMAPHORE != null)
            SEMAPHORE.acquire();
        java.util.concurrent.ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "Download-Throughput"); thread.setDaemon(true); return thread;
        });
        watchdog.scheduleWithFixedDelay(this::checkDownloadSpeed, 50, 50, TimeUnit.MILLISECONDS);
        try {
            segments = supportsSegments() ? new SegmentedDownload(this) : null;
            // 自动选择 (15 Nov): hosts that keep failing go to the back of the line; see DownloadHostHealth.
            DownloadHostHealth health = DownloadHostHealth.shared();
            List<DownloadCandidate> ordered = health.order(candidates.getCandidates(),
                    c -> c.url() == null ? null : hostOf(c.url()));
            for (int index = 0; index < ordered.size(); index++) {
                DownloadCandidate candidate = ordered.get(index);
                WebURL url = candidate.url();
                if (url == null) {
                    if (exceptions == null)
                        exceptions = new ArrayList<>();
                    exceptions.add(new DownloadException(candidate.rawUrl(), new MalformedURLException("Invalid URL: " + candidate)));
                    continue;
                }

                int retry = candidate.retry() >= 0 ? candidate.retry() : DEFAULT_RETRY;
                // A benched host is the last resort: one quick extra try, not five.
                if (health.benched(hostOf(url))) retry = Math.min(retry, 1);
                handOverWhenBenched = index < ordered.size() - 1;
                Duration connectTimeout = Objects.requireNonNullElse(candidate.connectTimeout(), NetworkUtils.TIMEOUT);
                Duration readTimeout = Objects.requireNonNullElse(candidate.readTimeout(), NetworkUtils.TIMEOUT);

                try {
                    if (NetworkUtils.isHttpUri(url))
                        downloadHttp(url, retry, connectTimeout, readTimeout, checkETag);
                    else
                        downloadNotHttp(url, retry, connectTimeout, readTimeout);
                    return;
                } catch (DownloadException e) {
                    if (exceptions == null)
                        exceptions = new ArrayList<>();
                    exceptions.add(e);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            synchronized (this) { throughputStart = 0; }
            watchdog.shutdownNow();
            boolean interrupted = Thread.interrupted();
            while (!watchdog.isTerminated()) {
                try { watchdog.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (segments != null) {
                IOUtils.closeQuietly(segments);
                segments = null;
            }
            if (SEMAPHORE != null)
                SEMAPHORE.release();
        }

        if (exceptions != null) {
            DownloadException last = exceptions.remove(exceptions.size() - 1);
            for (DownloadException exception : exceptions) {
                last.addSuppressed(exception);
            }
            throw last;
        }
    }

    private static final class HttpResumeContext {
        private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");

        static @Nullable FetchTask.HttpResumeContext of(UrlResponseInfo response) throws IOException {
            if (response.responseCode() != HttpURLConnection.HTTP_OK)
                return null;

            boolean acceptRanges = response.headers().firstValue("accept-ranges").orElse("").equalsIgnoreCase("bytes");
            if (!acceptRanges)
                return null;

            var contentEncoding = ContentEncoding.fromHeaders(response.headers());
            if (contentEncoding != ContentEncoding.IDENTITY)
                return null;

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength < 0)
                return null;

            String eTag = response.headers().firstValue("etag").orElse(null);
            String strongETag = isStrongETag(eTag) ? eTag : null;
            String lastModified = response.headers().firstValue("last-modified").orElse(null);
            if (strongETag == null && StringUtils.isBlank(lastModified))
                return null;

            return new HttpResumeContext(response.url(), contentLength, strongETag, lastModified);
        }

        /// Final response URL whose partial content is retained for resumption.
        private final WebURL url;
        private final long contentLength;
        private final @Nullable String strongETag;
        private final @Nullable String lastModified;

        long countUncompressed;

        /// Records the response URL, total byte length, and available validators for resumption.
        private HttpResumeContext(WebURL url, long contentLength, @Nullable String strongETag, @Nullable String lastModified) {
            this.url = url;
            this.contentLength = contentLength;
            this.strongETag = strongETag;
            this.lastModified = lastModified;
        }

        private static boolean isStrongETag(@Nullable String eTag) {
            return StringUtils.isNotBlank(eTag) && !eTag.regionMatches(true, 0, "W/", 0, 2);
        }

        String ifRange() {
            return strongETag != null ? strongETag : Objects.requireNonNull(lastModified);
        }

        boolean canResume(int statusCode, UrlResponseInfo response) throws IOException {
            if (statusCode != HttpURLConnection.HTTP_PARTIAL)
                return false;

            var contentEncoding = ContentEncoding.fromHeaders(response.headers());
            if (contentEncoding != ContentEncoding.IDENTITY)
                return false;

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (this.contentLength != contentLength + this.countUncompressed)
                return false;

            if (strongETag != null) {
                String eTag = response.headers().firstValue("etag").orElse(null);
                if (!strongETag.equals(eTag))
                    return false;
            } else {
                if (!url.equals(response.url()))
                    return false;

                String lastModified = response.headers().firstValue("last-modified").orElse("");
                if (!Objects.requireNonNull(this.lastModified).equals(lastModified))
                    return false;
            }

            String contentRange = response.headers().firstValue("content-range").orElse("");
            Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange);
            if (!matcher.matches())
                return false;

            try {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                long total = Long.parseLong(matcher.group(3));

                if (start != countUncompressed || end < start || total != this.contentLength)
                    return false;

                return end - start + 1 == contentLength;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        boolean hasPartialContent() {
            return countUncompressed > 0 && countUncompressed < contentLength;
        }
    }

    private void download(Context context,
                          @Nullable FetchTask.HttpResumeContext resume, InputStream inputStream,
                          long contentLength,
                          ContentEncoding contentEncoding) throws IOException, InterruptedException {
        boolean success;
        try (var counter = new CounterInputStream(inputStream);
             var input = contentEncoding.wrap(counter)) {
            long lastDownloaded = 0L;
            long initial = resume == null ? 0 : resume.countUncompressed;
            byte[] buffer = new byte[IOUtils.DEFAULT_BUFFER_SIZE];
            while (true) {
                if (isCancelled()) break;

                int len = input.read(buffer);
                if (len == -1) break;

                try {
                    context.write(buffer, 0, len);
                } catch (Throwable e) {
                    context.broken = true;
                    throw e;
                }

                if (resume != null)
                    resume.countUncompressed += len;

                recordDownload(counter.downloaded - lastDownloaded, initial + counter.downloaded,
                        initial + contentLength);
                lastDownloaded = counter.downloaded;
            }

            if (isCancelled())
                throw new InterruptedException();

            updateDownloadSpeed(counter.downloaded - lastDownloaded);

            if (contentLength >= 0 && counter.downloaded != contentLength)
                throw new IOException("Unexpected file size: " + counter.downloaded + ", expected: " + contentLength);

            success = true;
        }

        if (success) {
            context.withResult(true);
        }
    }

    /// Downloads an HTTP candidate, following HTTP(S) redirects and retrying recoverable failures.
    private void downloadHttp(WebURL url, int retry, Duration connectTimeout, Duration readTimeout, boolean checkETag) throws DownloadException, InterruptedException {
        if (checkETag) {
            // Handle cache
            try {
                Path cache = repository.getCachedRemoteFile(url, true);
                useCachedResult(cache);
                LOG.info("Using cached file for " + NetworkUtils.dropQuery(url));
                return;
            } catch (IOException ignored) {
            }
        }

        @Nullable Context context = null;
        @Nullable HttpResumeContext resumeContext = null;

        @Nullable ArrayList<Exception> exceptions = null;

        // If loading the cache fails, the cache should not be loaded again.
        boolean useCachedResult = true;
        try {
            for (int attempts = 0, attemptsLimit = retry + 1; attempts < attemptsLimit; attempts++) {
                if (isCancelled()) {
                    throw new InterruptedException();
                }

                @Nullable List<WebURL> redirects = null;
                try {
                    synchronized (this) {
                        throughputStart = throughputBytes = 0;
                        attemptBytes.set(0);
                        slowAttempt = false;
                    }
                    beforeDownload(url);
                    updateProgress(0);

                    @Nullable HttpURLConnection connection;
                    UrlResponseInfo responseInfo;
                    @Nullable String bmclapiHash;
                    int responseCode;

                    WebURL currentURI = url;

                    LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                    headers.put("accept-encoding", supportsSegments() ? "identity" : "gzip");

                    boolean resumeRequested = resumeContext != null && resumeContext.hasPartialContent();
                    if (useCachedResult && checkETag && !resumeRequested)
                        headers.putAll(repository.injectConnection(url));
                    if (resumeRequested) {
                        headers.put("range", "bytes=" + resumeContext.countUncompressed + "-");
                        headers.put("if-range", resumeContext.ifRange());
                    }

                    do {
                        connection = NetworkUtils.createHttpConnection(currentURI);
                        connection.setConnectTimeout((int) connectTimeout.toMillis());
                        connection.setReadTimeout((int) readTimeout.toMillis());
                        boolean keepConnection = false;
                        try {
                            headers.forEach(connection::setRequestProperty);
                            prepareDownloadConnection(connection);
                            responseCode = connection.getResponseCode();
                            responseInfo = UrlResponseInfo.of(connection);
                            observeDownloadResponse(responseInfo);

                            bmclapiHash = responseInfo.headers().firstValue("x-bmclapi-hash").orElse(null);
                            if (DigestUtils.isSha1Digest(bmclapiHash)) {
                                Optional<Path> cache = repository.checkExistentFile(null, "SHA-1", bmclapiHash);
                                if (cache.isPresent()) {
                                    useCachedResult(cache.get());
                                    LOG.info("Using cached file for " + NetworkUtils.dropQuery(url));
                                    return;
                                }
                            }

                            if (responseCode >= 300 && responseCode <= 308 && responseCode != 306 && responseCode != 304) {
                                if (redirects == null) {
                                    redirects = new ArrayList<>();
                                } else if (redirects.size() >= 20) {
                                    throw new IOException("Too much redirects");
                                }

                                @Nullable String location = connection.getHeaderField("Location");
                                if (StringUtils.isBlank(location))
                                    throw new IOException("Redirected to an empty location");

                                WebURL target = currentURI.resolve(location);
                                redirects.add(target);

                                if (!NetworkUtils.isHttpUri(target))
                                    throw new IOException("Redirected to non-HTTP URL: " + target);

                                currentURI = target;
                            } else {
                                keepConnection = true;
                                break;
                            }
                        } finally {
                            if (!keepConnection && connection != null) {
                                closeHttpConnection(connection);
                                connection = null;
                            }
                        }
                    } while (true);

                    @Nullable InputStream inputStream = null;
                    boolean responseBodyConsumed = false;
                    try {
                        if (resumeRequested && responseCode == 416) {
                            resumeContext = null;
                            discardContext(context);
                            context = null;
                            attemptsLimit++;
                            continue;
                        }

                        if (useCachedResult && responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            // Handle cache
                            try {
                                Path cache = repository.getCachedRemoteFile(responseInfo.url(), false);
                                useCachedResult(cache);
                                LOG.info("Using cached file for " + NetworkUtils.dropQuery(url));
                                return;
                            } catch (CacheRepository.CacheExpiredException e) {
                                LOG.info("Cache expired for " + NetworkUtils.dropQuery(url));
                            } catch (IOException e) {
                                LOG.warning("Unable to use cached file, redownload " + NetworkUtils.dropQuery(url), e);
                                repository.removeRemoteEntry(currentURI);
                                useCachedResult = false;
                                // Now we must reconnect the server since 304 may result in empty content,
                                // if we want to redownload the file, we must reconnect the server without etag settings.
                                attemptsLimit++;
                                continue;
                            }
                        } else if (responseCode == 429) {
                            throw new ResponseCodeException(url, responseCode);
                        } else if (responseCode / 100 == 4) {
                            throw new FileNotFoundException(url.toString());
                        } else if (responseCode / 100 != 2) {
                            throw new ResponseCodeException(url, responseCode);
                        }

                        long contentLength = responseInfo.headers().firstValueAsLong("content-length").orElse(-1L);
                        var contentEncoding = ContentEncoding.fromHeaders(responseInfo.headers());

                        if (!resumeRequested && responseCode == HttpURLConnection.HTTP_OK
                                && contentEncoding == ContentEncoding.IDENTITY && segments != null
                                && contentLength > SegmentedDownload.THRESHOLD) {
                            @Nullable Path assembled = segments.fetch(responseInfo, connectTimeout, readTimeout);
                            if (assembled != null) {
                                synchronized (this) { throughputStart = 0; }
                                discardContext(context);
                                context = getContext(responseInfo, checkETag, bmclapiHash);
                                // Digest and integrity handlers still see the complete file in byte order.
                                try (InputStream assembledInput = java.nio.file.Files.newInputStream(assembled)) {
                                    byte[] buffer = new byte[IOUtils.DEFAULT_BUFFER_SIZE];
                                    int length;
                                    while ((length = assembledInput.read(buffer)) != -1) {
                                        if (isCancelled()) throw new InterruptedException();
                                        context.write(buffer, 0, length);
                                    }
                                }
                                context.withResult(true);
                                Context finished = context;
                                context = null;
                                finished.close();
                                DownloadHostHealth.shared().success(hostOf(url));
                                return;
                            }
                        }

                        if (context == null) {
                            context = getContext(responseInfo, checkETag, bmclapiHash);
                            resumeContext = HttpResumeContext.of(responseInfo);
                        } else if (resumeRequested) {
                            if (resumeContext.canResume(responseCode, responseInfo)) {
                                // Resume download
                                LOG.info("Resuming " + resumeContext.url + " from " + resumeContext.countUncompressed);
                            } else {
                                // Failed to resume download, so we will retry from the beginning
                                resumeContext = null;
                                discardContext(context);
                                context = null;
                                attemptsLimit++;
                                continue;
                            }
                        } else {
                            discardContext(context);
                            context = getContext(responseInfo, checkETag, bmclapiHash);
                            resumeContext = HttpResumeContext.of(responseInfo);
                        }

                        recordDownload(0, resumeRequested && resumeContext != null ? resumeContext.countUncompressed : 0, contentLength);
                        try {
                            inputStream = connection.getInputStream();
                            download(context,
                                    resumeContext, inputStream,
                                    contentLength,
                                    contentEncoding);
                            inputStream = null;
                            responseBodyConsumed = true;
                            synchronized (this) { throughputStart = 0; }
                        } catch (IOException | InterruptedException | RuntimeException | Error e) {
                            if (context.broken) {
                                IOUtils.closeQuietly(context, e);
                                context = null;
                                resumeContext = null;
                            }
                            throw e;
                        }
                        try {
                            context.close();
                        } catch (IOException | RuntimeException | Error e) {
                            context.withResult(false);
                            IOUtils.closeQuietly(context, e);
                            context = null;
                            resumeContext = null;
                            throw e;
                        }
                        context = null;
                        DownloadHostHealth.shared().success(hostOf(url));
                        return;
                    } catch (java.net.SocketTimeoutException timeout) {
                        // A read deadline can win the race against the watchdog's next 50 ms tick.
                        checkDownloadSpeed();
                        throw timeout;
                    } finally {
                        IOUtils.closeQuietly(inputStream);
                        synchronized (this) { throughputStart = 0; }
                        if (connection != null) {
                            forgetDownloadConnection(connection);
                            if (!responseBodyConsumed) closeHttpConnection(connection);
                        }
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (FileNotFoundException ex) {
                    LOG.warning("Failed to download " + url + ", not found" + (redirects == null ? "" : ", redirects: " + redirects), ex);
                    throw toDownloadException(url, ex, exceptions); // we will not try this URL again
                } catch (ChecksumMismatchException corrupt) {
                    if (segments != null) segments.invalidate();
                    // Retrying identical corrupt content wastes mirror bandwidth; immediately use the next host.
                    throw toDownloadException(url, corrupt, exceptions);
                } catch (Exception ex) {
                    if (slowAttempt || !shouldRetry(ex)) throw toDownloadException(url, ex, exceptions);
                    if (exceptions == null)
                        exceptions = new ArrayList<>();

                    exceptions.add(ex);
                    DownloadHostHealth.shared().failure(hostOf(url));
                    if (handOverWhenBenched && DownloadHostHealth.shared().benched(hostOf(url))) {
                        LOG.warning("Failed to download " + url + ", host benched, trying the next source", ex);
                        throw toDownloadException(url, null, exceptions);
                    }

                    LOG.warning("Failed to download " + url + ", repeat times: " + attempts + (redirects == null ? "" : ", redirects: " + redirects), ex);

                    if (attempts < attemptsLimit - 1) {
                        // Wait for a while before retrying
                        Thread.sleep(retryDelayMillis(attempts));
                    }
                }
            }
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (IOException e) {
                    LOG.warning("Failed to close context for " + NetworkUtils.dropQuery(url), e);
                }
            }
        }

        throw toDownloadException(url, null, exceptions);
    }

    /// Disconnects an HTTP URL connection whose response body will not be consumed.
    private void closeHttpConnection(HttpURLConnection connection) {
        forgetDownloadConnection(connection);
        IOUtils.closeQuietly(connection.getErrorStream());
        connection.disconnect();
    }

    private static void discardContext(@Nullable Context context) {
        if (context != null) {
            context.withResult(false);
            IOUtils.closeQuietly(context);
        }
    }

    /// Downloads a non-HTTP candidate through its installed URL handler, retrying I/O failures.
    private void downloadNotHttp(WebURL url, int retry, Duration connectTimeout, Duration readTimeout) throws DownloadException, InterruptedException {
        @Nullable ArrayList<Exception> exceptions = null;
        for (int attempts = 0, attemptsLimit = retry + 1; attempts < attemptsLimit; attempts++) {
            if (isCancelled()) {
                throw new InterruptedException();
            }

            try {
                beforeDownload(url);
                updateProgress(0);

                URLConnection conn = NetworkUtils.createConnection(url);
                conn.setConnectTimeout((int) connectTimeout.toMillis());
                conn.setReadTimeout((int) readTimeout.toMillis());
                try (Context context = getContext()) {
                    download(context,
                            null, conn.getInputStream(),
                            conn.getContentLengthLong(),
                            ContentEncoding.fromConnection(conn));
                }
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (FileNotFoundException ex) {
                LOG.warning("Failed to download " + url + ", not found", ex);

                throw toDownloadException(url, ex, exceptions); // we will not try this URL again
            } catch (Exception ex) {
                if (exceptions == null)
                    exceptions = new ArrayList<>();

                exceptions.add(ex);
                LOG.warning("Failed to download " + url + ", repeat times: " + attempts, ex);
            }
        }

        throw toDownloadException(url, null, exceptions);
    }

    /// Wraps the final failure and attaches preceding failures as suppressed exceptions.
    private static DownloadException toDownloadException(WebURL url, @Nullable Exception last, @Nullable ArrayList<Exception> exceptions) {
        if (exceptions == null || exceptions.isEmpty()) {
            return new DownloadException(url, last != null
                    ? last
                    : new IOException("No exceptions"));
        } else {
            if (last == null)
                last = exceptions.remove(exceptions.size() - 1);

            for (Exception e : exceptions) {
                last.addSuppressed(e);
            }
            return new DownloadException(url, last);
        }
    }

    private static final Timer timer = new Timer("DownloadSpeedRecorder", true);
    private static final AtomicLong downloadSpeed = new AtomicLong(0L);
    public static final EventManager<SpeedEvent> SPEED_EVENT = EventBus.EVENT_BUS.channel(SpeedEvent.class);

    static {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                SPEED_EVENT.fireEvent(new SpeedEvent(SPEED_EVENT, downloadSpeed.getAndSet(0)));
            }
        }, 0, 1000);
    }

    private static void updateDownloadSpeed(long speed) {
        downloadSpeed.addAndGet(speed);
    }

    private static final class CounterInputStream extends FilterInputStream {
        long downloaded;

        CounterInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0)
                downloaded++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n >= 0)
                downloaded += n;
            return n;
        }
    }

    public static class SpeedEvent extends Event {
        private final long speed;

        public SpeedEvent(Object source, long speed) {
            super(source);

            this.speed = speed;
        }

        /**
         * Download speed in byte/sec.
         *
         * @return download speed
         */
        public long getSpeed() {
            return speed;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this).append("speed", speed).toString();
        }
    }

    protected static abstract class Context implements Closeable {
        private boolean success;
        private boolean broken;

        protected final boolean isSuccess() {
            return success;
        }

        public void withResult(boolean success) {
            this.success = success;
        }

        public abstract void reset() throws IOException;

        public abstract void write(byte[] buffer, int offset, int len) throws IOException;

        @Override
        public abstract void close() throws IOException;
    }

    protected enum EnumCheckETag {
        CHECK_E_TAG,
        NOT_CHECK_E_TAG,
        CACHED
    }


    public static int DEFAULT_CONCURRENCY = Math.min(Runtime.getRuntime().availableProcessors() * 4, 16);
    private static int downloadExecutorConcurrency = DEFAULT_CONCURRENCY;

    // For Java 21 or later, DOWNLOAD_EXECUTOR dispatches tasks to virtual threads, and concurrency is controlled by SEMAPHORE.
    // For versions earlier than Java 21, DOWNLOAD_EXECUTOR is a ThreadPoolExecutor, SEMAPHORE is null, and concurrency is controlled by the thread pool size.

    private static final ExecutorService DOWNLOAD_EXECUTOR;
    private static final @Nullable Semaphore SEMAPHORE;

    static {
        ExecutorService executorService = Schedulers.newVirtualThreadPerTaskExecutor("Download");
        if (executorService != null) {
            DOWNLOAD_EXECUTOR = executorService;
            SEMAPHORE = new Semaphore(DEFAULT_CONCURRENCY);
        } else {
            DOWNLOAD_EXECUTOR = threadPool("Download", true, downloadExecutorConcurrency, 10, TimeUnit.SECONDS);
            SEMAPHORE = null;
        }
    }

    @FXThread
    public static void setDownloadExecutorConcurrency(int concurrency) {
        concurrency = Math.max(concurrency, 1);

        int prevDownloadExecutorConcurrency = downloadExecutorConcurrency;
        int change = concurrency - prevDownloadExecutorConcurrency;
        if (change == 0)
            return;

        downloadExecutorConcurrency = concurrency;
        if (SEMAPHORE != null) {
            if (change > 0) {
                SEMAPHORE.release(change);
            } else {
                int permits = -change;
                if (!SEMAPHORE.tryAcquire(permits)) {
                    Schedulers.io().execute(() -> {
                        try {
                            for (int i = 0; i < permits; i++) {
                                SEMAPHORE.acquire();
                            }
                        } catch (InterruptedException e) {
                            throw new AssertionError("Unreachable", e);
                        }
                    });
                }
            }
        } else {
            var downloadExecutor = (ThreadPoolExecutor) DOWNLOAD_EXECUTOR;

            if (downloadExecutor.getMaximumPoolSize() <= concurrency) {
                downloadExecutor.setMaximumPoolSize(concurrency);
                downloadExecutor.setCorePoolSize(concurrency);
            } else {
                downloadExecutor.setCorePoolSize(concurrency);
                downloadExecutor.setMaximumPoolSize(concurrency);
            }
        }
    }

    public static int getDownloadExecutorConcurrency() {
        return downloadExecutorConcurrency;
    }

    private static volatile boolean initialized = false;

    public static void notifyInitialized() {
        initialized = true;
    }

}
