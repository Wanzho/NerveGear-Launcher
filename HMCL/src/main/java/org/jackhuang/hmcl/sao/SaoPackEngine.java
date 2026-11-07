/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/// Production SAO screen backend: serialized background installation, incremental updates, repair and launch.
/// Uses its fixed repository for game data and window inventories, with persisted settings and lazy room transport.
/// Publishes observable changes on JavaFX when a toolkit exists.
@NotNullByDefault
public final class SaoPackEngine implements SaoLauncherService, AutoCloseable {
    /// Initial/checking/working/ready/running state exposed read-only to the screen.
    private final ReadOnlyObjectWrapper<SaoPackState> state = new ReadOnlyObjectWrapper<>(SaoPackState.CHECKING);
    /// Operation fraction or -1 for an indeterminate HMCL task.
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(-1);
    /// Current localized operation label.
    private final ReadOnlyStringWrapper step = new ReadOnlyStringWrapper("");
    /// Last public operation failure, with no password or account token interpolation.
    private final ReadOnlyObjectWrapper<@Nullable String> error = new ReadOnlyObjectWrapper<>();
    /// Last successfully committed pack version.
    private final ReadOnlyObjectWrapper<@Nullable String> installedVersion = new ReadOnlyObjectWrapper<>();
    /// All pack work runs in order on one daemon worker.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sao-pack-engine"); thread.setDaemon(true); return thread;
    });
    /// Prevents concurrent requests, including before JavaFX has published WORKING.
    private final AtomicBoolean busy = new AtomicBoolean(true);
    /// Stops pending work and prevents a late launch after the launcher exits.
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /// Rejects new mutations and settings writes from the moment an uninstall request is accepted.
    private final AtomicBoolean uninstalling = new AtomicBoolean();
    /// Filesystem-only uninstall plan, isolated from system integration in explicit fixture engines.
    private final SaoUninstaller uninstaller;
    /// Prepares the native post-exit cleanup; tests replace this with an inert sequencing recorder.
    private final SaoUninstallHelper.Factory uninstallHelper;
    /// Terminal settings/logger shutdown, injected as a no-op in explicit fixture engines.
    private final Runnable uninstallShutdown;
    /// Requests normal JavaFX application exit only after uninstall reaches its commit point.
    private final Runnable uninstallQuit;
    /// Only the process's production engine is allowed to discover its packaged application for removal.
    private final boolean productionUninstall;
    /// Last completed background logical-byte measurement; -1 means unknown or invalidated.
    private volatile long cachedInstalledBytes = -1;
    /// Timestamp of the last requested measurement, bounding repeated settings-window getter calls.
    private volatile long sizeRequestedAt;
    /// Coalesces background size requests so a directory scan never runs on the JavaFX thread.
    private final AtomicBoolean sizeRefreshing = new AtomicBoolean();
    /// One pending password buffer, retained only until hand-off/failure and wiped by close.
    private volatile char @Nullable [] activePassword;
    /// Last progress publication time, limiting JavaFX queue pressure during large inventories.
    private volatile long lastProgressNanos;
    /// Root storage owned by this launcher.
    private final Path root;
    /// HMCL bridge scoped to this launcher's files.
    private final SaoGameBridge game;
    /// Transactional, inventory-driven pack file installer.
    private final SaoPackInstaller installer;
    /// Skin picker storage inside the fixed game instance.
    private final SaoSkinStore skins;
    /// Active release source: embedded pack, explicit development pack, or last accepted drop.
    private volatile @Nullable SaoPackArchive source;
    /// Human-readable embedded/development release; a missing pack is explicit.
    private volatile String embeddedVersion = "未内置整合包";
    /// Settings survive restarts and never share Minecraft's options storage.
    private final SaoSettings settings;
    /// Local windows share the fixed-instance file helper.
    private final SaoInstanceFiles files;
    /// News cache and bounded server-list protocol client.
    private final SaoOnlineData online;
    /// Sanitized process output and support-export reader.
    private final SaoGameLogs logs;
    /// Embedded update note remains tied to this launcher rather than a subsequently dropped update.
    private volatile SaoData.News embeddedNews = new SaoData.News(SaoData.NewsKind.UPDATE, "v未内置整合包", "", "");
    /// Independent online work cannot delay pack writes or game startup.
    private final ExecutorService onlineWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sao-online"); thread.setDaemon(true); return thread;
    });
    /// Coalesces process-output and task publications so JavaFX never receives one runnable per log line.
    private final ScheduledExecutorService publications = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sao-window-publications"); thread.setDaemon(true); return thread;
    });
    /// Prevents overlapping network refreshes when windows/timers request the same data.
    private final AtomicBoolean onlineRefreshing = new AtomicBoolean();
    /// Prevents local refresh requests from accumulating behind a long installer task.
    private final AtomicBoolean localRefreshing = new AtomicBoolean();
    /// Coalesces explicit Java-window refreshes independently of screenshots and world scans.
    private final AtomicBoolean javaRefreshing = new AtomicBoolean();
    /// Last Java inventory probe time; listings do not repeatedly execute each runtime probe.
    private volatile long javaRefreshedAt;
    /// Coalesces high-frequency game log callbacks.
    private final AtomicBoolean logPublication = new AtomicBoolean();
    /// Coalesces download progress callbacks.
    private final AtomicBoolean taskPublication = new AtomicBoolean();
    /// True from launch preparation until the real process listener reports exit.
    private final AtomicBoolean gameRunning = new AtomicBoolean();
    /// Invalidates queued room callbacks when leaving, replacing a session, or closing the launcher.
    private final java.util.concurrent.atomic.AtomicLong roomGeneration = new java.util.concurrent.atomic.AtomicLong();
    /// Last local refresh time, preventing listeners that reread getters from scheduling a loop.
    private volatile long localRefreshedAt;
    /// Last public notices, copied before publication.
    private volatile @Unmodifiable List<SaoData.News> remoteNews = List.of();
    /// Mutable list backing the read-only news view; changes are published on JavaFX.
    private final ObservableList<SaoData.News> news = FXCollections.observableArrayList();
    /// Stable read-only news view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.News> newsView = FXCollections.unmodifiableObservableList(news);
    /// Mutable list backing the read-only world view.
    private final ObservableList<SaoData.World> worlds = FXCollections.observableArrayList();
    /// Stable read-only worlds view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.World> worldsView = FXCollections.unmodifiableObservableList(worlds);
    /// Mutable list backing the read-only backup view.
    private final ObservableList<SaoData.Backup> backups = FXCollections.observableArrayList();
    /// Stable read-only backups view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.Backup> backupsView = FXCollections.unmodifiableObservableList(backups);
    /// Mutable list backing the read-only shader view.
    private final ObservableList<SaoData.Pack> shaders = FXCollections.observableArrayList();
    /// Stable read-only shaders view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.Pack> shadersView = FXCollections.unmodifiableObservableList(shaders);
    /// Mutable list backing the read-only resource view.
    private final ObservableList<SaoData.Pack> resources = FXCollections.observableArrayList();
    /// Stable read-only resources view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.Pack> resourcesView = FXCollections.unmodifiableObservableList(resources);
    /// Mutable list backing the read-only screenshot view.
    private final ObservableList<Path> screenshots = FXCollections.observableArrayList();
    /// Stable read-only screenshots view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<Path> screenshotsView = FXCollections.unmodifiableObservableList(screenshots);
    /// Mutable list backing the read-only process-output view.
    private final ObservableList<String> gameLog = FXCollections.observableArrayList();
    /// Stable read-only gameLog view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<String> gameLogView = FXCollections.unmodifiableObservableList(gameLog);
    /// Mutable list backing the read-only Java 17 runtime view.
    private final ObservableList<SaoData.JavaRuntime> javas = FXCollections.observableArrayList();
    /// Stable read-only javas view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.JavaRuntime> javasView = FXCollections.unmodifiableObservableList(javas);
    /// Mutable list backing the read-only download view.
    private final ObservableList<SaoData.Task> tasks = FXCollections.observableArrayList();
    /// Stable read-only tasks view retained for the screen's listener lifetime.
    private final @UnmodifiableView ObservableList<SaoData.Task> tasksView = FXCollections.unmodifiableObservableList(tasks);
    /// Retains recent real HMCL task records by identity, guarded by this map's monitor.
    private final Map<org.jackhuang.hmcl.task.Task<?>, SaoData.Task> taskRecords = new LinkedHashMap<>();
    /// Last server status; null means no configured server or no completed probe.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable ServerStatus> serverStatus = new ReadOnlyObjectWrapper<>();
    /// Last run analysis, cleared before a new launch.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable Crash> crash = new ReadOnlyObjectWrapper<>();
    /// Explicitly requested Terracotta room state; never initialized by reading this property.
    private final ReadOnlyObjectWrapper<SaoData.Room> room = new ReadOnlyObjectWrapper<>(SaoData.Room.OFF);


    /// Creates the engine under the launcher's isolated local storage.
    public SaoPackEngine() { this(Metadata.HMCL_LOCAL_HOME, null, true, true, null,
            SaoUninstallHelper::prepare, () -> {
                org.jackhuang.hmcl.setting.SettingsManager.shutdownForUninstall();
                org.jackhuang.hmcl.util.logging.Logger.LOG.shutdown();
            }, Platform::exit); }

    /// Creates an isolated backend, also usable by headless proof tools without toolkit or windows.
    public SaoPackEngine(Path dataRoot) { this(dataRoot, null, true); }

    /// Injects a release in headless tests without consulting resources or development properties.
    SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded) {
        this(dataRoot, supplied, loadEmbedded, false, null, SaoUninstallHelper::prepare, () -> { }, () -> { });
    }

    /// Injects file/helper/shutdown seams for synthetic uninstall tests; never discovers the running application.
    SaoPackEngine(Path dataRoot, SaoUninstaller uninstaller, SaoUninstallHelper.Factory helper, Runnable shutdown, Runnable quit) {
        this(dataRoot, null, false, false, uninstaller, helper, shutdown, quit);
    }

    /// Centralizes production and explicitly isolated fixture initialization without opening any window.
    private SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded,
                          boolean production, @Nullable SaoUninstaller suppliedUninstaller,
                          SaoUninstallHelper.Factory helper, Runnable shutdown, Runnable quit) {
        root = dataRoot.toAbsolutePath().normalize();
        try { settings = new SaoSettings(root); }
        catch (IOException failure) { throw new java.io.UncheckedIOException("Cannot read launcher settings", failure); }
        game = new SaoGameBridge(root, this::reportProgress);
        productionUninstall = production;
        uninstallHelper = helper;
        uninstallShutdown = shutdown;
        uninstallQuit = quit;
        @Unmodifiable List<Path> uninstallRoots = production
                ? List.of(root, Metadata.HMCL_USER_HOME, Metadata.DEPENDENCIES_DIRECTORY) : List.of(root);
        Path desktop = production ? Path.of(System.getProperty("user.home"), "Desktop")
                : Objects.requireNonNull(root.getParent()).resolve("kept-saves");
        uninstaller = suppliedUninstaller != null ? suppliedUninstaller
                : new SaoUninstaller(uninstallRoots, game.instanceDir(), desktop, SaoUninstaller.desktopTrash(), production);
        game.observeTasks(this::recordTask);
        game.setDownloadSource(downloadSource());
        game.restoreJava(java());
        publish(() -> org.jackhuang.hmcl.task.FetchTask.setDownloadExecutorConcurrency(downloadThreads()));
        files = new SaoInstanceFiles(game.instanceDir(), game);
        online = new SaoOnlineData(root);
        logs = new SaoGameLogs(game.instanceDir());
        remoteNews = online.cached();
        installer = new SaoPackInstaller(game.instanceDir(), game::download, Map.of(), this::reportProgress);
        skins = new SaoSkinStore(game.instanceDir());
        worker.execute(() -> {
            try {
                @Nullable String developmentPack = System.getProperty("sao.pack");
                source = !loadEmbedded ? supplied : developmentPack == null || developmentPack.isBlank()
                        ? SaoPackArchive.embedded(root.resolve("pack-cache")) : SaoPackArchive.open(Path.of(developmentPack));
                if (source != null) embeddedVersion = source.manifest().version();
                embeddedNews = SaoOnlineData.embeddedNews(source, embeddedVersion);
                publishNews();
                @Nullable SaoPackArchive accepted = readAcceptedSource();
                if (accepted != null && (source == null
                        || VersionNumber.compare(accepted.manifest().version(), source.manifest().version()) >= 0)) source = accepted;
                files.requiredResources(source);
                refreshLocalNow();
                refreshJavaNow();
                logs.loadPrevious();
                @Nullable SaoData.Crash previousCrash = logs.analyze(settings.flag("lastExitAbnormal", false), previousRunStarted());
                publish(() -> { gameLog.setAll(logs.snapshot()); crash.set(previousCrash); });
                refreshState();
            } catch (Exception failure) { fail("读取整合包失败", failure); }
            finally { busy.set(false); refreshInstalledBytes(); }
        });
    }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoPackState> state() { return state.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public ReadOnlyDoubleProperty progress() { return progress.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public ReadOnlyStringProperty stepLabel() { return step.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<@Nullable String> lastError() { return error.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<@Nullable String> installedVersion() { return installedVersion.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public String embeddedVersion() { return embeddedVersion; }

    /// {@inheritDoc}
    @Override public void linkStart(Account account, char @Nullable [] authPassword) {
        if (closed.get() || uninstalling.get()) {
            if (authPassword != null) Arrays.fill(authPassword, '\0');
            return;
        }
        char @Nullable [] password = authPassword == null ? null : authPassword.clone();
        if (authPassword != null) Arrays.fill(authPassword, '\0');
        SaoPackState current = state.get();
        if (current != SaoPackState.NOT_INSTALLED && current != SaoPackState.UPDATE_READY && current != SaoPackState.READY
                || !busy.compareAndSet(false, true)) {
            if (password != null) Arrays.fill(password, '\0');
            return;
        }
        activePassword = password;
        working();
        worker.execute(() -> {
            try {
                if (closed.get()) throw new IOException("Launcher has closed");
                SaoPackArchive pack = requireSource();
                game.ensureInstalled(false);
                @Nullable SaoPackManifest previous = installer.installed();
                if (previous == null || VersionNumber.compare(pack.manifest().version(), previous.version()) > 0)
                    installer.apply(pack, false);
                files.enforceRequiredResources();
                publish(() -> { installedVersion.set(pack.manifest().version()); step.set("Link Start"); state.set(SaoPackState.RUNNING); });
                if (closed.get()) throw new IOException("Launcher has closed");
                long started = System.currentTimeMillis();
                saveSetting("lastRunStarted", Long.toString(started));
                saveSetting("lastExitAbnormal", false);
                logs.clear();
                publish(() -> { gameLog.clear(); crash.set(null); });
                gameRunning.set(true);
                game.launch(account, pack.manifest(), launchMemoryMiB(), gameWindow(), jvmArgs(), password, closed::get, new ProcessListener() {
                    /// Captures sanitized output only; handoff and authentication lines are filtered first.
                    @Override public void onLog(String line, boolean stderr) { appendGameLog(line); }
                    /// Reconciles the fixed instance and analyzes this run after the real process exits.
                    @Override public void onExit(int code, ExitType type) {
                        gameRunning.set(false);
                        if (closed.get() || uninstalling.get()) return;
                        worker.execute(() -> {
                            try {
                                saveSetting("lastExitAbnormal", code != 0 || type != ExitType.NORMAL);
                                @Nullable SaoData.Crash result = logs.analyze(code != 0 || type != ExitType.NORMAL, started);
                                publish(() -> crash.set(result));
                                refreshLocalNow(); refreshState();
                            } catch (Exception failure) { windowFailure("读取游戏退出信息失败", failure); }
                            finally { busy.set(false); }
                        });
                    }
                });
                // Busy remains true for the running process; its monitor releases it on exit.
            } catch (Exception failure) {
                gameRunning.set(false);
                busy.set(false);
                if (source == null) fail("Link Start 失败", failure);
                else publish(() -> { error.set("Link Start 失败（" + failure.getClass().getSimpleName() + "）。请检查账户、网络和 Java 17 设置。");
                    state.set(SaoPackState.FAILED); step.set(""); progress.set(-1); });
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                activePassword = null;
            }
        });
    }

    /// {@inheritDoc}
    @Override public void repair() {
        submit(() -> {
            SaoPackArchive pack = requireSource();
            game.ensureInstalled(true);
            installer.apply(pack, true);
            refreshState();
        });
    }

    /// {@inheritDoc}
    @Override public void applyUpdateZip(Path zip) {
        submit(() -> {
            SaoPackArchive update = SaoPackArchive.open(zip);
            @Nullable SaoPackManifest previous = installer.installed();
            @Nullable SaoPackArchive currentSource = source;
            boolean recoveringSource = previous != null && update.manifest().version().equals(previous.version())
                    && (currentSource == null || VersionNumber.compare(currentSource.manifest().version(), previous.version()) < 0);
            if (previous != null && VersionNumber.compare(update.manifest().version(), previous.version()) <= 0 && !recoveringSource)
                throw new IOException("更新包版本必须高于已安装版本 " + previous.version());
            SaoPackArchive cached = cacheSource(update);
            game.ensureInstalled(false);
            installer.apply(cached, recoveringSource);
            persistAcceptedSource(cached);
            source = cached;
            files.requiredResources(source);
            refreshLocalNow();
            refreshState();
        });
    }

    /// {@inheritDoc}
    @Override public void setSkin(@Nullable Path png, boolean slim) {
        if (!allowMutation()) return;
        if (!busy.compareAndSet(false, true)) { windowFailure("操作被拒绝", new IOException("请等待当前任务完成")); return; }
        try { skins.set(png, slim); }
        catch (IOException failure) { throw new IllegalArgumentException("无法保存皮肤: " + failure.getMessage(), failure); }
        finally { busy.set(false); }
    }
    /// {@inheritDoc}
    @Override public @Nullable Path skin() { return skins.skin(); }
    /// {@inheritDoc}
    @Override public boolean skinSlim() { return skins.slim(); }
    /// {@inheritDoc}
    @Override public int memoryMiB() { return settings.number("memoryMiB", 4096); }
    /// {@inheritDoc}
    @Override public void setMemoryMiB(int mib) {
        long free = new org.jackhuang.hmcl.util.platform.hardware.HardwareDetector().getFreeMemorySize() / (1024 * 1024);
        long maximum = free > 0 ? Math.max(2048, free) : systemMemoryMiB();
        if (mib < 2048 || mib > maximum) { windowFailure("内存设置失败", new IOException("Memory must be between 2048 MiB and available system memory")); return; }
        saveSetting("memoryMiB", mib);
    }
    /// {@inheritDoc}
    @Override public Path instanceDir() { return game.instanceDir(); }

    /// Returns only a cached count; an expired/unknown inventory is measured on the serialized worker.
    @Override public long installedBytes() { refreshInstalledBytes(); return cachedInstalledBytes; }

    /// {@inheritDoc}
    @Override public Path keptSavesFolder() { return uninstaller.keptSavesFolder(); }

    /// Accepts uninstall only while all game, file and room/download work is idle.
    /// Failed validation or preservation never stops settings persistence or removes an original file.
    @Override public void uninstall(boolean keepSaves) {
        synchronized (org.jackhuang.hmcl.terracotta.TerracottaManager.class) {
            if (closed.get() || uninstalling.get()) {
                windowFailure("卸载被拒绝", new IOException("启动器已关闭或正在卸载")); return;
            }
            boolean taskBusy;
            synchronized (taskRecords) { taskBusy = taskRecords.values().stream().anyMatch(task -> !task.done()); }
            if (gameRunning.get() || state.get() == SaoPackState.RUNNING || taskBusy
                    || org.jackhuang.hmcl.terracotta.TerracottaManager.hasRequestedRoom() || !busy.compareAndSet(false, true)) {
                windowFailure("卸载被拒绝", new IOException("请先退出游戏、离开联机房间并等待所有任务完成")); return;
            }
            uninstalling.set(true);
        }
        working();
        worker.execute(() -> {
            @Nullable SaoUninstallHelper.Prepared helper = null;
            boolean terminal = false;
            try {
                reportProgress("检查卸载目录", -1);
                uninstaller.validate();
                if (keepSaves) { reportProgress("复制并校验存档与截图", -1); uninstaller.preserve(); }
                if (closed.get() || Thread.currentThread().isInterrupted()) throw new IOException("启动器已关闭，卸载未继续");
                @Unmodifiable List<Path> application = productionUninstall ? SaoUninstallHelper.runningApplication() : List.of();
                if (productionUninstall && application.isEmpty())
                    org.jackhuang.hmcl.util.logging.Logger.LOG.info("Uninstall: development/IDE or unsupported application source; self-removal skipped");
                helper = uninstallHelper.prepare(uninstaller.roots(), application);
                // No new backend writes are accepted after this fence. Drain/cancel online work before data removal.
                closed.set(true);
                terminal = true;
                roomGeneration.incrementAndGet();
                online.close();
                onlineWorker.shutdownNow();
                if (!onlineWorker.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("后台联网任务未结束，未移除启动器数据");
                publications.shutdownNow();
                game.close();
                char @Nullable [] password = activePassword;
                if (password != null) Arrays.fill(password, '\0');
                activePassword = null;
                uninstallShutdown.run();
                List<Path> deferred = new ArrayList<>(application);
                deferred.add(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize());
                reportProgress("移除启动器专属数据", -1);
                uninstaller.remove(deferred);
                helper.arm();
                cachedInstalledBytes = 0;
                publish(() -> {
                    error.set(null); progress.set(1); step.set("卸载完成"); installedVersion.set(null);
                    state.set(SaoPackState.NOT_INSTALLED);
                    uninstallQuit.run();
                });
            } catch (Exception failure) {
                if (helper != null) helper.cancel();
                fail(terminal ? "卸载失败，请重启启动器后重试" : "卸载失败，原始数据未删除", failure);
            } finally {
                if (!terminal) uninstalling.set(false);
                busy.set(false);
            }
        });
    }

    /// Schedules at most one directory-size inventory every thirty seconds; failures preserve unknown status.
    private void refreshInstalledBytes() {
        if (closed.get() || uninstalling.get()) return;
        long now = System.nanoTime();
        if (sizeRequestedAt != 0 && now - sizeRequestedAt < TimeUnit.SECONDS.toNanos(30)) return;
        if (!sizeRefreshing.compareAndSet(false, true)) return;
        sizeRequestedAt = now;
        try {
            worker.execute(() -> {
                try {
                    long bytes = uninstaller.installedBytes();
                    if (productionUninstall) for (Path application : SaoUninstallHelper.runningApplication())
                        if (uninstaller.roots().stream().noneMatch(application::startsWith))
                            bytes = Math.addExact(bytes, SaoUninstaller.treeBytes(application));
                    cachedInstalledBytes = bytes;
                }
                catch (Exception ignored) { cachedInstalledBytes = -1; }
                finally { sizeRefreshing.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException closedWorker) { sizeRefreshing.set(false); }
    }

    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.News> news() { return newsView; }
    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable ServerStatus> serverStatus() { return serverStatus.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public void refreshOnline() {
        if (closed.get() || uninstalling.get() || !onlineRefreshing.compareAndSet(false, true)) return;
        onlineWorker.execute(() -> {
            try {
                @Nullable SaoPackArchive pack = source;
                String endpoint = pack == null ? "" : pack.manifest().newsUrl();
                if (!endpoint.isEmpty()) {
                    try { remoteNews = online.fetch(endpoint); publishNews(); }
                    catch (Exception ignored) { /* Retain last-good launcher news during an outage. */ }
                }
                String address = pack == null ? "" : pack.manifest().server();
                if (address.isEmpty()) publish(() -> serverStatus.set(null));
                else {
                    SaoData.ServerStatus result;
                    try { result = online.ping(address); }
                    catch (Exception failure) { result = new SaoData.ServerStatus(false, 0, 0, -1, "", List.of()); }
                    SaoData.ServerStatus status = result;
                    publish(() -> serverStatus.set(status));
                }
            } finally { onlineRefreshing.set(false); }
        });
    }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.World> worlds() { refreshLocal(); return worldsView; }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.Backup> backups() { refreshLocal(); return backupsView; }
    /// {@inheritDoc}
    @Override public void backupWorld(SaoData.World world) { mutate(() -> { files.backup(world); refreshLocalNow(); }); }
    /// {@inheritDoc}
    @Override public void restoreBackup(SaoData.Backup backup) { mutate(() -> { files.restore(backup); refreshLocalNow(); }); }
    /// {@inheritDoc}
    @Override public void exportWorld(SaoData.World world, Path zip) { mutate(() -> files.export(world, zip)); }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.Pack> shaderPacks() { refreshLocal(); return shadersView; }
    /// {@inheritDoc}
    @Override public void setShader(SaoData.@Nullable Pack pack) { mutate(() -> { files.setShader(pack); refreshLocalNow(); }); }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.Pack> resourcePacks() { refreshLocal(); return resourcesView; }
    /// {@inheritDoc}
    @Override public void setResourcePackEnabled(SaoData.Pack pack, boolean enabled) { mutate(() -> { files.setResource(pack, enabled); refreshLocalNow(); }); }
    /// {@inheritDoc}
    @Override public void addPacks(List<Path> dropped, boolean shaders) {
        @Unmodifiable List<Path> copy = List.copyOf(dropped);
        mutate(() -> { files.addPacks(copy, shaders); refreshLocalNow(); });
    }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<Path> screenshots() { refreshLocal(); return screenshotsView; }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<String> gameLog() { return gameLogView; }
    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable Crash> lastCrash() { return crash.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public void exportLogs(Path zip) { background(() -> logs.export(zip)); }
    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.Room> room() { return room.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public void hostRoom() { startRoom(null); }
    /// {@inheritDoc}
    @Override public void joinRoom(String code) {
        if (code.isBlank() || code.length() > 256 || code.chars().anyMatch(Character::isWhitespace)) {
            windowFailure("加入房间失败", new IOException("Invalid room code")); return;
        }
        startRoom(code);
    }
    /// {@inheritDoc}
    @Override public void leaveRoom() {
        roomGeneration.incrementAndGet();
        org.jackhuang.hmcl.terracotta.TerracottaManager.stopRoom();
        publish(() -> room.set(SaoData.Room.OFF));
    }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.JavaRuntime> javas() {
        refreshJava(); return javasView;
    }
    /// {@inheritDoc}
    @Override public @Nullable Path java() {
        String path = settings.text("java", "");
        try { return path.isEmpty() ? null : Path.of(path); }
        catch (InvalidPathException invalid) { return null; }
    }
    /// {@inheritDoc}
    @Override public void setJava(@Nullable Path executable) {
        background(() -> { game.setJava(executable); saveSetting("java", executable == null ? null : executable.toRealPath().toString()); refreshJavaNow(); });
    }
    /// {@inheritDoc}
    @Override public void downloadJava() { mutate(() -> { game.downloadJava(); refreshJavaNow(); }); }
    /// {@inheritDoc}
    @Override public SaoData.Source downloadSource() {
        try { return SaoData.Source.valueOf(settings.text("downloadSource", "AUTO")); }
        catch (IllegalArgumentException invalid) { return SaoData.Source.AUTO; }
    }
    /// {@inheritDoc}
    @Override public void setDownloadSource(SaoData.Source source) {
        saveSetting("downloadSource", source.name()); game.setDownloadSource(source);
    }
    /// {@inheritDoc}
    @Override public int downloadThreads() { return Math.max(1, Math.min(256, settings.number("downloadThreads", 64))); }
    /// {@inheritDoc}
    @Override public void setDownloadThreads(int threads) {
        if (threads < 1 || threads > 256) { windowFailure("下载设置失败", new IOException("Parallel downloads must be between 1 and 256")); return; }
        saveSetting("downloadThreads", threads);
        publish(() -> org.jackhuang.hmcl.task.FetchTask.setDownloadExecutorConcurrency(threads));
    }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.Task> tasks() { return tasksView; }
    /// {@inheritDoc}
    @Override public boolean autoMemory() { return settings.flag("autoMemory", false); }
    /// {@inheritDoc}
    @Override public void setAutoMemory(boolean auto) { saveSetting("autoMemory", auto); }
    /// {@inheritDoc}
    @Override public int systemMemoryMiB() {
        long total = new org.jackhuang.hmcl.util.platform.hardware.HardwareDetector().getTotalMemorySize() / (1024 * 1024);
        return total > 0 ? (int) Math.min(Integer.MAX_VALUE, total) : 4096;
    }
    /// {@inheritDoc}
    @Override public SaoData.GameWindow gameWindow() {
        try {
            com.google.gson.JsonArray values = com.google.gson.JsonParser.parseString(settings.text("window", "[1280,720,false]")).getAsJsonArray();
            return new SaoData.GameWindow(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsBoolean());
        } catch (RuntimeException invalid) { return new SaoData.GameWindow(1280, 720, false); }
    }
    /// {@inheritDoc}
    @Override public void setGameWindow(SaoData.GameWindow window) {
        if (window.width() < 320 || window.width() > 16384 || window.height() < 240 || window.height() > 16384) {
            windowFailure("窗口设置失败", new IOException("Invalid game window size")); return;
        }
        saveSetting("window", "[" + window.width() + "," + window.height() + "," + window.fullscreen() + "]");
    }
    /// {@inheritDoc}
    @Override public String jvmArgs() { return settings.text("jvmArgs", ""); }
    /// {@inheritDoc}
    @Override public void setJvmArgs(String args) {
        if (args.length() > 32768 || args.indexOf('\0') >= 0) { windowFailure("JVM 设置失败", new IOException("Invalid JVM arguments")); return; }
        saveSetting("jvmArgs", args);
    }
    /// {@inheritDoc}
    @Override public String preference(String key, String fallback) { return settings.text("preference." + key, fallback); }
    /// {@inheritDoc}
    @Override public void setPreference(String key, String value) {
        if (!key.matches("[a-zA-Z0-9_.-]{1,80}") || value.length() > 4096) {
            windowFailure("保存偏好失败", new IOException("Invalid preference")); return;
        }
        saveSetting("preference." + key, value);
    }
    /// {@inheritDoc}
    @Override public void openFolder(SaoData.Folder folder) {
        background(() -> { Path directory = files.folder(folder); Files.createDirectories(directory); openExternal(directory, false); });
    }
    /// {@inheritDoc}
    @Override public void openFile(Path file) {
        background(() -> {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("File does not exist");
            try { openExternal(file, false); }
            catch (IOException failure) { openExternal(file.getParent(), true); }
        });
    }

    /// Publishes cached notices plus exactly one embedded release entry, preserving source ordering.
    private void publishNews() {
        List<SaoData.News> combined = new ArrayList<>(remoteNews);
        combined.removeIf(item -> item.kind() == SaoData.NewsKind.UPDATE && item.title().equals(embeddedNews.title()));
        combined.add(embeddedNews);
        @Unmodifiable List<SaoData.News> replacement = List.copyOf(combined);
        publish(() -> replace(news, replacement));
    }

    /// Queues a coalesced read, including while the game runs, without queuing a full scan on every getter call.
    private void refreshLocal() {
        if (closed.get() || System.nanoTime() - localRefreshedAt < TimeUnit.MILLISECONDS.toNanos(500)
                || !localRefreshing.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { refreshLocalNow(); }
            catch (Exception failure) { windowFailure("刷新窗口失败", failure); }
            finally { localRefreshedAt = System.nanoTime(); localRefreshing.set(false); }
        });
    }

    /// Reads complete immutable snapshots before replacing visible records on the JavaFX thread.
    private void refreshLocalNow() throws IOException {
        @Unmodifiable List<SaoData.World> foundWorlds = files.worlds();
        @Unmodifiable List<SaoData.Backup> foundBackups = files.backups();
        @Unmodifiable List<SaoData.Pack> foundShaders = files.packs(true);
        @Unmodifiable List<SaoData.Pack> foundResources = files.packs(false);
        @Unmodifiable List<Path> foundShots = files.screenshots();
        localRefreshedAt = System.nanoTime();
        publish(() -> { replace(worlds, foundWorlds); replace(backups, foundBackups); replace(shaders, foundShaders);
            replace(resources, foundResources); replace(screenshots, foundShots); });
    }

    /// Coalesces Java inventory reads separately from frequently opened image and pack windows.
    private void refreshJava() {
        if (closed.get() || System.nanoTime() - javaRefreshedAt < TimeUnit.SECONDS.toNanos(5)
                || !javaRefreshing.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { refreshJavaNow(); }
            catch (Exception failure) { windowFailure("刷新 Java 失败", failure); }
            finally { javaRefreshedAt = System.nanoTime(); javaRefreshing.set(false); }
        });
    }

    /// Refreshes only verified Java 17 entries; runtime architecture/vendor information comes from HMCL.
    private void refreshJavaNow() throws Exception {
        javaRefreshedAt = System.nanoTime();
        List<SaoData.JavaRuntime> found = new ArrayList<>();
        for (org.jackhuang.hmcl.java.JavaRuntime runtime : game.availableJava())
            found.add(new SaoData.JavaRuntime(runtime.getVersion(), runtime.getBinary(), Objects.toString(runtime.getVendor(), ""),
                    runtime.isManaged() || runtime.getBinary().startsWith(root.resolve("java"))));
        @Unmodifiable List<SaoData.JavaRuntime> replacement = List.copyOf(found);
        publish(() -> replace(javas, replacement));
    }

    /// Avoids observable-list events for an identical snapshot, including when a listener rereads its getter.
    private static <T> void replace(ObservableList<T> target, List<T> replacement) {
        if (!target.equals(replacement)) target.setAll(replacement);
    }

    /// Refuses file mutations while Minecraft owns its options, saves or packs, without changing RUNNING state.
    private boolean allowMutation() {
        if (gameRunning.get() || state.get() == SaoPackState.RUNNING) {
            windowFailure("操作被拒绝", new IOException("游戏运行中，请退出游戏后修改存档、皮肤或资源包")); return false;
        }
        return !closed.get() && !uninstalling.get();
    }

    /// Serializes a local mutation with install/repair and reports contention instead of silently losing a request.
    private void mutate(Operation operation) {
        if (!allowMutation()) return;
        if (!busy.compareAndSet(false, true)) { windowFailure("操作被拒绝", new IOException("请等待当前任务完成")); return; }
        worker.execute(() -> {
            try { operation.run(); publish(() -> error.set(null)); }
            catch (Exception failure) { windowFailure("操作失败", failure); }
            finally { busy.set(false); }
        });
    }

    /// Runs read/export/settings work in the serialized background queue without altering pack state.
    private void background(Operation operation) {
        if (closed.get() || uninstalling.get()) return;
        worker.execute(() -> { try { operation.run(); } catch (Exception failure) { windowFailure("操作失败", failure); } });
    }

    /// Persists one small setting and keeps errors in the service property instead of throwing into a UI handler.
    private void saveSetting(String key, @Nullable Object value) {
        if (closed.get() || uninstalling.get()) return;
        try { settings.put(key, value); }
        catch (IOException failure) { windowFailure("保存设置失败", failure); }
    }

    /// Restores the timestamp of the last actual game run, excluding historical reports on a fresh installation.
    private long previousRunStarted() {
        try { return Long.parseLong(settings.text("lastRunStarted", Long.toString(Long.MAX_VALUE))); }
        catch (NumberFormatException invalid) { return Long.MAX_VALUE; }
    }

    /// Computes HMCL's automatic allocation at launch time, with a minimum appropriate to this pack.
    private int launchMemoryMiB() {
        if (!autoMemory()) return memoryMiB();
        long available = new org.jackhuang.hmcl.util.platform.hardware.HardwareDetector().getFreeMemorySize();
        return Math.max(2048, (int) (org.jackhuang.hmcl.game.HMCLGameRepository.getAutoAllocatedMemory(available,
                org.jackhuang.hmcl.util.platform.Platform.SYSTEM_PLATFORM) / (1024 * 1024)));
    }

    /// Collects only actual HMCL fetch tasks, retaining up to 100 recent immutable rows.
    private void recordTask(org.jackhuang.hmcl.task.Task<?> task, boolean done) {
        synchronized (taskRecords) {
            taskRecords.put(task, new SaoData.Task(SaoGameLogs.sanitize(task.getName()), done ? 1 : task.progressProperty().get(), 0, done));
            while (taskRecords.size() > 100) taskRecords.remove(taskRecords.keySet().iterator().next());
        }
        if (closed.get() || !taskPublication.compareAndSet(false, true)) return;
        publications.schedule(() -> {
            @Unmodifiable List<SaoData.Task> snapshot;
            synchronized (taskRecords) { snapshot = List.copyOf(taskRecords.values()); }
            taskPublication.set(false);
            publish(() -> replace(tasks, snapshot));
        }, 100, TimeUnit.MILLISECONDS);
    }

    /// Adds output to the ring and batches display updates under sustained process output.
    void appendGameLog(String line) {
        logs.append(line);
        if (closed.get() || !logPublication.compareAndSet(false, true)) return;
        publications.schedule(() -> {
            @Unmodifiable List<String> snapshot = logs.snapshot(); logPublication.set(false);
            publish(() -> replace(gameLog, snapshot));
        }, 100, TimeUnit.MILLISECONDS);
    }

    /// Requests the lazy Terracotta controller only from an explicit host/join action.
    private void startRoom(@Nullable String code) {
        if (closed.get() || uninstalling.get()) return;
        synchronized (org.jackhuang.hmcl.terracotta.TerracottaManager.class) {
            if (closed.get() || uninstalling.get()) return;
            if (org.jackhuang.hmcl.terracotta.TerracottaManager.hasRequestedRoom()) {
                windowFailure("联机操作被拒绝", new IOException("请先离开当前联机房间")); return;
            }
            long generation = roomGeneration.incrementAndGet();
            org.jackhuang.hmcl.terracotta.TerracottaManager.startRoom(code,
                    value -> publish(() -> { if (!closed.get() && roomGeneration.get() == generation) room.set(value); }),
                    message -> publish(() -> { if (!closed.get() && roomGeneration.get() == generation) error.set(message); }), this::recordTask);
        }
    }

    /// Opens the requested local object with an operating-system handler, without shell interpolation.
    private static void openExternal(Path file, boolean reveal) throws IOException {
        org.jackhuang.hmcl.util.platform.OperatingSystem os = org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS;
        @Unmodifiable List<String> command = switch (os) {
            case MACOS -> reveal ? List.of("/usr/bin/open", "-R", file.toString()) : List.of("/usr/bin/open", file.toString());
            case WINDOWS -> List.of("explorer.exe", file.toString());
            default -> List.of("xdg-open", file.toString());
        };
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            // Some desktop handlers remain attached to the viewer. Do not block pack work or close that app.
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() != 0
                    && os != org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) throw new IOException("System file handler failed");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Opening file interrupted", interrupted); }
    }

    /// Reports a window error without pretending that an installed pack has become broken.
    private void windowFailure(String context, Exception failure) {
        String message = SaoGameLogs.sanitize(context + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        publish(() -> error.set(message));
    }

    /// Returns the available release or a clear missing-pack error for a launcher built without -PsaoPack.
    SaoPackArchive requireSource() throws IOException {
        @Nullable SaoPackArchive pack = source;
        if (pack == null) throw new IOException("此启动器未内置整合包。请使用包含整合包的 SAO Oath 启动器（构建参数 -PsaoPack=<zip>），或拖入有效整合包。");
        @Nullable SaoPackManifest installed = installer.installed();
        if (installed != null && VersionNumber.compare(pack.manifest().version(), installed.version()) < 0)
            throw new IOException("已安装整合包版本较新，请重新提供当前版本整合包后修复；不会降级玩家文件。");
        return pack;
    }

    /// Copies an accepted update into content-addressed launcher storage; dropped source files stay read-only.
    SaoPackArchive cacheSource(SaoPackArchive update) throws IOException {
        Path cache = SaoPackFiles.resolve(root, "pack-cache");
        Files.createDirectories(cache);
        String key = SaoPackArchive.sha1(new java.io.ByteArrayInputStream(update.manifest().toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path zip = cache.resolve(key + ".zip");
        Path temporary = Files.createTempFile(cache, "accepted-", ".tmp");
        try {
            Files.copy(update.zip(), temporary, StandardCopyOption.REPLACE_EXISTING);
            SaoPackFiles.atomicMove(temporary, zip);
        } finally { Files.deleteIfExists(temporary); }
        return new SaoPackArchive(zip, update.manifest());
    }

    /// Persists public manifest plus cache filename after the inventory commits; no outside absolute paths are stored.
    void persistAcceptedSource(SaoPackArchive pack) throws IOException {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("archive", pack.zip().getFileName().toString());
        json.add("manifest", com.google.gson.JsonParser.parseString(pack.manifest().toJson()));
        Path cache = root.resolve("pack-cache");
        Path temporary = Files.createTempFile(cache, "active-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), java.nio.charset.StandardCharsets.UTF_8);
            SaoPackFiles.atomicMove(temporary, cache.resolve("active.json"));
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Restores the last accepted release so restart never repairs or launches with an older embedded manifest.
    private @Nullable SaoPackArchive readAcceptedSource() throws IOException {
        Path cache = root.resolve("pack-cache");
        Path active = cache.resolve("active.json");
        if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) return null;
        try (java.io.Reader reader = Files.newBufferedReader(active, java.nio.charset.StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            String filename = json.get("archive").getAsString();
            if (!filename.matches("[0-9a-f]{40}\\.zip")) throw new IOException("Invalid cached pack filename");
            Path archive = SaoPackFiles.resolve(cache, filename);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Accepted update archive is missing");
            SaoPackManifest manifest = SaoPackManifest.read(new java.io.StringReader(json.get("manifest").toString()));
            return new SaoPackArchive(archive, manifest);
        } catch (RuntimeException failure) { throw new IOException("Invalid accepted update metadata", failure); }
    }

    /// Reconciles installed receipt and release version without hashing or downloading payloads.
    private void refreshState() throws IOException {
        @Nullable SaoPackManifest installed = installer.installed();
        @Nullable SaoPackArchive pack = source;
        SaoPackState next = pack == null || installed == null ? SaoPackState.NOT_INSTALLED
                : VersionNumber.compare(pack.manifest().version(), installed.version()) > 0 ? SaoPackState.UPDATE_READY : SaoPackState.READY;
        publish(() -> { installedVersion.set(installed == null ? null : installed.version()); state.set(next); progress.set(1); step.set(""); error.set(null); });
    }

    /// An exception-capable serialized background operation.
    @NotNullByDefault
    @FunctionalInterface
    private interface Operation {
        /// Runs only on the engine worker.
        void run() throws Exception;
    }

    /// Accepts one background operation while idle and always releases its operation gate.
    private void submit(Operation operation) {
        if (closed.get() || uninstalling.get() || !busy.compareAndSet(false, true)) return;
        working();
        worker.execute(() -> {
            try { operation.run(); }
            catch (Exception failure) { fail("整合包操作失败", failure); }
            finally { busy.set(false); }
        });
    }

    /// Publishes the initial busy state before an operation starts.
    private void working() { publish(() -> { state.set(SaoPackState.WORKING); error.set(null); progress.set(-1); }); }
    /// Publishes one short, sanitized phase label and progress value.
    private void reportProgress(String label, double fraction) {
        long now = System.nanoTime();
        if (fraction >= 0 && fraction < 1 && now - lastProgressNanos < 50_000_000L) return;
        lastProgressNanos = now;
        publish(() -> { step.set(label); progress.set(fraction); });
    }
    /// Publishes a public error without serializing secrets, account objects or process arguments.
    private void fail(String context, Exception failure) {
        String message = context + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        publish(() -> { error.set(message); state.set(SaoPackState.FAILED); step.set(""); progress.set(-1); });
    }
    /// Uses the JavaFX thread in the app and a synchronous fallback only when no toolkit exists.
    private static void publish(Runnable action) {
        try { Platform.runLater(action); }
        catch (IllegalStateException uninitializedToolkit) { action.run(); }
    }
    /// Stops accepting backend work; does not terminate an already running Minecraft process.
    @Override public void close() {
        closed.set(true);
        roomGeneration.incrementAndGet();
        char @Nullable [] password = activePassword;
        if (password != null) Arrays.fill(password, '\0');
        activePassword = null;
        game.close();
        org.jackhuang.hmcl.terracotta.TerracottaManager.stopRoom();
        online.close(); onlineWorker.shutdownNow(); publications.shutdownNow();
        worker.shutdownNow();
    }
}
