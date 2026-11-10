/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.application.Platform;
import javafx.collections.*;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jetbrains.annotations.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/// Self-contained common-feature backend, with one worker and no window/file-manager/network side effects on getters.
@NotNullByDefault
final class SaoExtras implements AutoCloseable {
    /// Launcher storage and fixed instance bridge.
    private final Path root;
    /// Shared HMCL runtime inventory; never downloads Java during a check.
    private final SaoGameBridge game;
    /// Atomic launcher preferences, separate from game options and account data.
    private final SaoSettings settings;
    /// Reuses the bounded metadata transport and last-good cache.
    private final SaoOnlineData online;
    /// Installed manifest reader, sharing the engine's transactional receipt logic.
    private final SaoPackInstaller installer;
    /// Reuses the current sanitized process ring.
    private final SaoGameLogs logs;
    /// Current installed/embedded source, resolved lazily after engine startup.
    private final Supplier<@Nullable SaoPackArchive> source;
    /// Launcher's embedded pack release label.
    private final Supplier<String> embeddedVersion;
    /// Hard runtime gate independent of the frontend's asynchronously published state.
    private final BooleanSupplier running;
    /// Explicit fixture engines cannot make public HTTP requests.
    private final boolean production;
    /// Serialized support work is independent of launch/install tasks.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sao-extras"); thread.setDaemon(true); return thread;
    });
    /// Explicit feedback destination; fixtures replace it without inspecting the real Downloads directory.
    private final Path feedbackDownloads;
    /// Stable observable public links backing list.
    private final ObservableList<SaoData.CommunityLink> links = FXCollections.observableArrayList();
    /// Stable read-only wrapper retained for frontend listeners.
    private final @UnmodifiableView ObservableList<SaoData.CommunityLink> linksView = FXCollections.unmodifiableObservableList(links);
    /// Last valid version.json share, applied on the publication thread to every community-list replacement.
    private @Nullable String quark;
    /// Coalesces concurrent metadata refresh requests.
    private final AtomicBoolean refreshing = new AtomicBoolean();
    /// Fences queued publications and new requests after shutdown.
    private final AtomicBoolean closed = new AtomicBoolean();
    /// Outstanding asynchronous results are completed exceptionally on shutdown, even if queued.
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    /// Loads only the public cache synchronously; construction starts no network or UI work.
    SaoExtras(Path root, SaoGameBridge game, SaoSettings settings, SaoOnlineData online, SaoGameLogs logs, SaoPackInstaller installer,
              Supplier<@Nullable SaoPackArchive> source, Supplier<String> embeddedVersion, BooleanSupplier running, boolean production) {
        this(root, game, settings, online, logs, installer, source, embeddedVersion, running, production,
                SaoDownloads.directories().get(0));
    }

    /// Supplies a disposable feedback destination in hermetic service tests.
    SaoExtras(Path root, SaoGameBridge game, SaoSettings settings, SaoOnlineData online, SaoGameLogs logs, SaoPackInstaller installer,
              Supplier<@Nullable SaoPackArchive> source, Supplier<String> embeddedVersion, BooleanSupplier running, boolean production, Path feedbackDownloads) {
        this.feedbackDownloads = feedbackDownloads;
        this.root = root; this.game = game; this.settings = settings; this.online = online; this.logs = logs; this.installer = installer;
        this.source = source; this.embeddedVersion = embeddedVersion; this.running = running; this.production = production;
        links.setAll(SaoCommunityLinks.withQuark(online.cachedLinks(), null));
    }

    /// Stable cached links, replaced only on the publication thread after a fully valid fetch.
    @UnmodifiableView ObservableList<SaoData.CommunityLink> links() { return linksView; }

    /// Refreshes independently of news/server pings; fixtures remain entirely offline.
    void refreshLinks() {
        if (!production || closed.get() || !refreshing.compareAndSet(false, true)) return;
        async(() -> online.fetchLinks()).whenComplete((values, failure) -> {
            refreshing.set(false); if (failure == null && !closed.get()) links.setAll(SaoCommunityLinks.withQuark(values, quark));
        });
    }

    /// Adapts cached/default/fetched Quark entries after release metadata changes without fetching links again.
    void updateQuark(String value) {
        publish(() -> {
            if (closed.get()) return;
            quark = value; links.setAll(SaoCommunityLinks.withQuark(links, value));
        });
    }

    /// Returns a valid persisted enum even after a future or manually edited settings value.
    SaoData.LauncherBehavior behavior() {
        try { return SaoData.LauncherBehavior.valueOf(settings.text("extras.launcherBehavior", "HIDE")); }
        catch (IllegalArgumentException malformed) { return SaoData.LauncherBehavior.HIDE; }
    }

    /// Synchronously persists next-run behavior without touching any window.
    void behavior(SaoData.LauncherBehavior value) { put("extras.launcherBehavior", value.name()); }

    /// Returns a valid persisted preset, initially automatic.
    synchronized SaoData.GraphicsPreset preset() {
        try { return SaoData.GraphicsPreset.valueOf(settings.text("extras.graphicsPreset", "AUTO")); }
        catch (IllegalArgumentException malformed) { return SaoData.GraphicsPreset.AUTO; }
    }

    /// Writes only launcher preferences while playing; actual game files are deferred to beforeLaunch.
    synchronized void preset(SaoData.GraphicsPreset value) {
        if (value == preset()) return;
        put("extras.graphicsPreset", value.name()); put("extras.graphicsApplied", "");
    }

    /// Resolves an automatic suggestion through HMCL's hardware detector on the support worker.
    CompletableFuture<SaoData.GraphicsPreset> suggestion() { return async(SaoExtras::suggest); }

    /// Resolves unknown GPUs conservatively and never runs an OpenGL context or focuses a window.
    private static SaoData.GraphicsPreset suggest() {
        SaoSystemChecks.Hardware hardware = SaoSystemChecks.hardware();
        return SaoGraphicsPresets.suggest(hardware.memoryBytes() / (1024 * 1024), hardware.gpu(), hardware.discrete());
    }

    /// Serial launch-boundary transaction; unchanged selections preserve in-game changes across later launches.
    synchronized void beforeLaunch() throws IOException {
        if (running.getAsBoolean()) throw new IOException("游戏运行中，不能修改画质");
        SaoData.GraphicsPreset selected = preset();
        String applied = settings.text("extras.graphicsApplied", "");
        if (applied.startsWith("1:" + selected.name() + ":")) return;
        SaoData.GraphicsPreset actual = selected == SaoData.GraphicsPreset.AUTO ? suggest() : selected;
        SaoGraphicsPresets.apply(game.instanceDir(), actual, false);
        settings.put("extras.graphicsApplied", "1:" + selected.name() + ":" + actual.name());
    }

    /// Captures first-open rows without installing, launching, refreshing accounts or accessing the network.
    CompletableFuture<@Unmodifiable List<SaoData.SystemCheck>> systemCheck() { return async(this::checks); }

    /// Reuses HMCL's version/architecture validation, including a selected executable that is no longer usable.
    private @Unmodifiable List<SaoData.SystemCheck> checks() throws Exception {
        @Unmodifiable List<JavaRuntime> java;
        try { java = game.availableJava(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        catch (Exception unavailable) { java = List.of(); }
        String selected = settings.text("java", "");
        boolean invalidSelected = !selected.isBlank() && java.stream().noneMatch(runtime -> {
            try { return Files.isSameFile(runtime.getBinary(), Path.of(selected)); }
            catch (IOException | InvalidPathException failure) { return false; }
        });
        long free;
        try { free = SaoSystemChecks.freeBytes(game.instanceDir()); }
        catch (IOException unavailable) { free = -1; }
        long required;
        try { required = installBytes(); } catch (IOException unavailable) { required = Long.MAX_VALUE; }
        return SaoSystemChecks.evaluate(new SaoSystemChecks.Facts(SaoSystemChecks.hardware().memoryBytes(), free,
                required, SaoSystemChecks.writable(game.instanceDir()), !java.isEmpty(), invalidSelected));
    }

    /// Gates every actual install/update/repair at the real filesystem boundary, even when the UI omits a check.
    void checkInstallCapacity() throws IOException { checkInstallCapacity(source.get()); }

    /// Checks a replacement archive's own inventory rather than the smaller currently selected release.
    void checkInstallCapacity(@Nullable SaoPackArchive candidate) throws IOException {
        Path instance = game.instanceDir();
        if (!SaoSystemChecks.writable(instance)) throw new IOException("实例位置不可写，请检查权限");
        if (SaoSystemChecks.freeBytes(instance) < installBytes(candidate)) throw new IOException("磁盘空间不足，暂不能安装");
    }

    /// Uses the active manifest's byte inventory and ZIP size, with the inspected 0.8 payload as startup fallback.
    private long installBytes() throws IOException {
        return installBytes(source.get());
    }

    /// Computes the disk budget for one specific candidate, retaining the same transactional reserve.
    private long installBytes(@Nullable SaoPackArchive pack) throws IOException {
        long payload = SaoSystemChecks.OATH08_BYTES, archive = SaoSystemChecks.OATH08_ZIP_BYTES;
        if (pack != null) {
            payload = 0;
            for (var file : pack.manifest().files()) {
                try { payload = Math.addExact(payload, file.size()); }
                catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
            }
            archive = Files.size(pack.zip());
        }
        return SaoSystemChecks.requiredBytes(payload, archive);
    }

    /// Builds the complete feedback artifact off-thread; opening/revealing remains an explicit frontend action.
    CompletableFuture<SaoData.FeedbackZip> feedback() {
        return async(() -> {
            SaoSystemChecks.Hardware hardware = SaoSystemChecks.hardware();
            StringBuilder summary = new StringBuilder("OS: ").append(System.getProperty("os.name", "unknown"))
                    .append(' ').append(System.getProperty("os.version", "unknown")).append(' ').append(System.getProperty("os.arch", "unknown"))
                    .append("\nCPU: ").append(hardware.cpu()).append("\nGPU: ").append(hardware.gpu())
                    .append("\nRAM bytes: ").append(hardware.memoryBytes())
                    .append("\nLauncher: ").append(Metadata.VERSION).append("\nEmbedded pack: ").append(embeddedVersion.get());
            @Nullable SaoPackManifest installed = installer.installed();
            summary.append("\nInstalled pack: ").append(installed == null ? "未安装" : installed.version())
                    .append("\nLauncher Java: ").append(System.getProperty("java.version", "unknown"))
                    .append("\nSelected game Java: ").append(settings.text("java", "自动"));
            for (JavaRuntime runtime : game.availableJava()) summary.append("\nCompatible Java 17: ")
                    .append(runtime.getVersion()).append(' ').append(runtime.getPlatform()).append(' ').append(runtime.getBinary());
            List<Path> launcherLogs = new ArrayList<>();
            @Nullable Path current = org.jackhuang.hmcl.util.logging.Logger.LOG.getLogFile();
            if (current != null) {
                launcherLogs.add(current); launcherLogs.addAll(org.jackhuang.hmcl.util.logging.Logger.LOG.findRecentLogFiles(3));
            }
            return new SaoFeedbackZip(root, game.instanceDir(), feedbackDownloads,
                    new SaoFeedbackSanitizer(System.getProperty("user.home", ""), System.getProperty("user.name", "")))
                    .build(summary.toString(), launcherLogs, logs.snapshot());
        });
    }

    /// Persists a nonsecret setting and exposes an actionable exception without private filesystem details.
    private void put(String key, String value) {
        if (closed.get()) throw new IllegalStateException("启动器已关闭");
        try { settings.put(key, value); }
        catch (IOException failure) { throw new UncheckedIOException("无法保存启动器设置", failure); }
    }

    /// Checked support operation executed only by the isolated daemon worker.
    @NotNullByDefault
    @FunctionalInterface
    private interface Work<T> {
        /// Runs filesystem/hardware work without touching scene graph objects.
        T run() throws Exception;
    }

    /// Completes on the same publication thread as existing service methods, with deterministic headless fallback.
    private <T> CompletableFuture<T> async(Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>(); pending.add(future);
        future.whenComplete((value, failure) -> pending.remove(future));
        if (closed.get()) { future.completeExceptionally(new IllegalStateException("启动器已关闭")); return future; }
        try {
            worker.execute(() -> {
                try {
                    T value = work.run(); publish(() -> {
                        if (!closed.get()) future.complete(value); else future.completeExceptionally(new CancellationException());
                    });
                } catch (Exception failure) { publish(() -> future.completeExceptionally(failure)); }
            });
        } catch (RejectedExecutionException ended) { future.completeExceptionally(new IllegalStateException("启动器已关闭")); }
        return future;
    }

    /// Uses JavaFX when initialized, otherwise stays headless with no toolkit startup.
    private static void publish(Runnable action) {
        try { Platform.runLater(action); } catch (IllegalStateException headless) { action.run(); }
    }

    /// Stops support work and completes all queued frontend requests exceptionally.
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow(); publish(() -> pending.forEach(future -> future.completeExceptionally(new CancellationException())));
    }
}
