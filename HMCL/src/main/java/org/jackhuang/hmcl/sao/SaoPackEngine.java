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
// 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
package org.jackhuang.hmcl.sao;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
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
    /// Admits launcher and offline sessions, then hands Minecraft startup to the engine’s bridge.
    private final SaoGameLaunch launch = new SaoGameLaunch(this);

    /// Installs and updates the pack through the engine’s existing worker and operation gate.
    final SaoPackMaintenance maintenance = new SaoPackMaintenance(this);

    /// Initial/checking/working/ready/running state exposed read-only to the screen.
    final ReadOnlyObjectWrapper<SaoPackState> state = new ReadOnlyObjectWrapper<>(SaoPackState.CHECKING);
    /// Operation fraction or -1 for an indeterminate HMCL task.
    final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(-1);
    /// Current localized operation label.
    final ReadOnlyStringWrapper step = new ReadOnlyStringWrapper("");
    /// Detailed measured installation snapshot, additive to the existing label/fraction seam.
    final ReadOnlyObjectWrapper<SaoInstallProgress> installProgress = new ReadOnlyObjectWrapper<>(
            SaoInstallProgress.phase(SaoInstallProgress.Stage.IDLE, ""));
    /// Persistent run marker is production-only so headless fixtures do not simulate application crashes.
    private @Nullable SaoRunMarker runMarker;

    /// Consumes the previous run notice only once per application start.
    @Override public boolean consumePreviousCrash() { return runMarker != null && runMarker.consume(); }

    /// Last public operation failure, with no password or account token interpolation.
    final ReadOnlyObjectWrapper<@Nullable String> error = new ReadOnlyObjectWrapper<>();
    /// Last successfully committed pack version.
    final ReadOnlyObjectWrapper<@Nullable String> installedVersion = new ReadOnlyObjectWrapper<>();
    /// Whether Link Start can launch, and what 下载/更新 does (James, 11 Nov: "下载还是老实叫下载吧").
    final ReadOnlyObjectWrapper<SaoReadiness> readiness = new ReadOnlyObjectWrapper<>(SaoReadiness.CHECKING);
    /// All pack work runs in order on one daemon worker.
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sao-pack-engine"); thread.setDaemon(true); return thread;
    });
    /// Prevents concurrent requests, including before JavaFX has published WORKING.
    final AtomicBoolean busy = new AtomicBoolean(true);
    /// Process checks, termination and desktop handlers must never queue behind pack work.
    private final ExecutorService desktopWorker = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "sao-desktop"); thread.setDaemon(true); return thread;
    });
    /// Exact process supplied by HMCL, retained until exit reconciliation finishes.
    volatile @Nullable Process heldGame;
    /// Completes only after the previous game's worker-owned state has been reconciled.
    volatile CompletableFuture<Void> gameExit = CompletableFuture.completedFuture(null);
    /// Instance-scoped discovery seam; production never enumerates unrelated executable names for termination.
    private Supplier<@Unmodifiable List<SaoGameProcesses.Running>> processFinder = () -> SaoGameProcesses.find(this.game.instanceDir());
    /// UI confirmation seam is inert in headless runs unless explicitly replaced by a test.
    private java.util.function.BiFunction<Boolean, java.util.function.BooleanSupplier, CompletableFuture<Boolean>> processPrompt =
            org.jackhuang.hmcl.ui.sao.SaoDialogs::endGame;
    /// Only one pending process confirmation may own a retry at a time.
    private final AtomicBoolean processQuestion = new AtomicBoolean();
    /// Current gate owner, updated independently of deferred UI publications.
    volatile String activeTask = "检查整合包";
    /// Last contention message; clearing it never clears an unrelated operation failure.
    private volatile @Nullable String contention;

    /// Names the active task instead of leaving a generic refusal behind after completion.
    private String busyReason() {
        if (gameRunning.get() || heldGame != null && heldGame.isAlive()) return "游戏还在运行（可能卡在后台），请先关掉游戏";
        return "正在" + activeTask + "…";
    }

    /// Publishes a temporary refusal and returns the same text for failed operation futures.
    String refuseBusy() {
        String reason = busyReason(); contention = reason;
        publish(() -> { if (busy.get()) error.set(reason); });
        return reason;
    }

    /// Releases the operation gate and removes only its still-visible contention notice.
    void releaseBusy() {
        @Nullable String previous = contention; contention = null;
        busy.set(false);
        publish(() -> { if (previous != null && previous.equals(error.get())) error.set(null); });
    }

    /// Test-only injection avoids OS process enumeration and dialogs in deterministic regression tests.
    void processControl(Supplier<@Unmodifiable List<SaoGameProcesses.Running>> finder,
            java.util.function.BiFunction<Boolean, java.util.function.BooleanSupplier, CompletableFuture<Boolean>> prompt) {
        processFinder = finder; processPrompt = prompt;
    }

    /// Confirms and awaits this instance's process exit before retrying the original action exactly once.
    <T> CompletableFuture<T> guardGame(Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get() || uninstalling.get()) return CompletableFuture.failedFuture(new IOException("启动器正在关闭"));
        if (!processQuestion.compareAndSet(false, true)) return CompletableFuture.failedFuture(new SaoLauncherService.OperationBusyException("请先处理后台游戏提示"));
        desktopWorker.execute(() -> {
            try {
                packReady.join();
                @Nullable Process held = heldGame;
                List<SaoGameProcesses.Running> found = new ArrayList<>(processFinder.get());
                boolean managed = held != null && held.isAlive();
                if (managed) found.add(new SaoGameProcesses.Running() {
                    /// Queries only the exact process delivered by the launch listener.
                    @Override public boolean alive() { return held.isAlive(); }

                    /// Preserves HMCL's exit monitor while terminating the raw game process.
                    @Override public void end() throws Exception {
                        held.destroy();
                        if (!held.waitFor(3, TimeUnit.SECONDS)) { held.destroyForcibly();
                            if (!held.waitFor(7, TimeUnit.SECONDS)) throw new IOException("游戏进程尚未结束，请重试"); }
                    }
                });
                java.util.function.BooleanSupplier alive = () -> !closed.get() && found.stream().anyMatch(SaoGameProcesses.Running::alive);
                if (alive.getAsBoolean()) {
                    if (!processPrompt.apply(managed, alive).get()) throw new java.util.concurrent.CancellationException("已取消");
                    for (SaoGameProcesses.Running process : found) if (process.alive()) process.end();
                    if (alive.getAsBoolean()) throw new IOException("游戏进程尚未结束，请重试");
                }
                if (held != null) gameExit.get(15, TimeUnit.SECONDS);
                if (closed.get() || uninstalling.get()) throw new IOException("启动器正在关闭");
                processQuestion.set(false);
                publish(() -> {
                    try { action.get().whenComplete((value, failure) -> {
                        if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                    }); } catch (Exception failure) { result.completeExceptionally(failure); }
                });
            } catch (Exception failure) {
                processQuestion.set(false);
                if (!(failure instanceof java.util.concurrent.CancellationException)) windowFailure("操作未执行", failure);
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /// Adapts void entry points to the same asynchronous process guard.
    void guardGameAction(Runnable action) {
        guardGame(() -> { action.run(); return CompletableFuture.completedFuture(null); });
    }

    /// Auto-login waits for accepted/embedded manifest selection before choosing its account API.
    final CompletableFuture<Void> packReady = new CompletableFuture<>();
    /// Stops pending work and prevents a late launch after the launcher exits.
    final AtomicBoolean closed = new AtomicBoolean(false);
    /// Rejects new mutations and settings writes from the moment an uninstall request is accepted.
    final AtomicBoolean uninstalling = new AtomicBoolean();
    /// Filesystem-only uninstall plan, isolated from system integration in explicit fixture engines.
    private volatile SaoUninstaller uninstaller;
    /// Prepares the native post-exit cleanup; tests replace this with an inert sequencing recorder.
    private final SaoUninstallHelper.Factory uninstallHelper;
    /// Terminal settings/logger shutdown, injected as a no-op in explicit fixture engines.
    private final Runnable uninstallShutdown;
    /// Requests normal JavaFX application exit only after uninstall reaches its commit point.
    private final Runnable uninstallQuit;
    /// Only the process's production engine is allowed to discover its packaged application for removal.
    final boolean productionUninstall;
    /// Last completed background logical-byte measurement; -1 means unknown or invalidated.
    private volatile long cachedInstalledBytes = -1;
    /// Timestamp of the last requested measurement, bounding repeated settings-window getter calls.
    private volatile long sizeRequestedAt;
    /// Coalesces background size requests so a directory scan never runs on the JavaFX thread.
    private final AtomicBoolean sizeRefreshing = new AtomicBoolean();
    /// One pending password buffer, retained only until hand-off/failure and wiped by close.
    volatile char @Nullable [] activePassword;
    /// Last progress publication time, limiting JavaFX queue pressure during large inventories.
    private volatile long lastProgressNanos;
    /// Root storage owned by this launcher.
    volatile Path root;
    /// HMCL bridge scoped to this launcher's files.
    volatile SaoGameBridge game;
    /// The bridge's install/check/launch steps; replaced only by offline tests.
    volatile SaoReadinessCheck.GameSteps steps;
    /// Transactional, inventory-driven pack file installer.
    volatile SaoPackInstaller installer;
    /// Production account controller owns protected remembered credentials, ephemeral sessions and profile skins.
    final SaoOathAccounts oath;
    /// Skin picker storage inside the fixed game instance.
    private volatile SaoSkinStore skins;
    /// Active release source: embedded pack, explicit development pack, or last accepted drop.
    volatile @Nullable SaoPackArchive source;
    /// Human-readable embedded/development release; a missing pack is explicit.
    private volatile String embeddedVersion = "未内置整合包";
    /// Settings survive restarts and never share Minecraft's options storage.
    final SaoSettings settings;
    /// Local windows share the fixed-instance file helper.
    volatile SaoInstanceFiles files;
    /// News cache and bounded server-list protocol client.
    final SaoOnlineData online;
    /// Explicit fixture engines never perform public metadata reads, including refreshOnline calls.
    final boolean productionUpdates;
    /// Explicit account-test transports retain priority over remotely signed deployment settings.
    private final boolean fixtureAccountEndpoint;
    /// Shared monotonic policy-attempt limiter; independent of news and status refreshes.
    final SaoPolicyRefresh policyRefresh;
    /// Explicit transport fixtures use inert status values instead of DNS or sockets.
    private final boolean inertOnlineFixture;
    /// Last-good public policy is read atomically at the launch boundary, independently of UI publication.
    volatile @Nullable SaoData.VersionMetadata versionMetadata;
    /// Optional note dismissed during this launcher session, scoped to the advertised latest version.
    volatile @Nullable String dismissedLauncherVersion;
    /// Read-only public update comparison for frontend listeners.
    final ReadOnlyObjectWrapper<SaoData.UpdateStatus> updateStatus = new ReadOnlyObjectWrapper<>(
            new SaoData.UpdateStatus(null, false, false, false, ""));
    /// Cancellable prompt state; candidates are never automatically installed.
    final ReadOnlyObjectWrapper<SaoData.DownloadPrompt> downloadPrompt = new ReadOnlyObjectWrapper<>(
            new SaoData.DownloadPrompt(false, null, ""));
    /// Verified replacement state in per-user launcher data, separate from the signed native application.
    final SaoSelfUpdate selfUpdate;
    /// Observable restart instruction or a persisted rollback report.
    final ReadOnlyObjectWrapper<SaoData.LauncherUpdate> launcherUpdate = new ReadOnlyObjectWrapper<>(
            new SaoData.LauncherUpdate("", false, ""));
    /// Stable UI-readable authentication state; failures never replace an accepted signed sequence.
    final ReadOnlyObjectWrapper<SaoData.ReleaseVerification> releaseVerification =
            new ReadOnlyObjectWrapper<>(new SaoData.ReleaseVerification(null, null));
    /// Worker-owned accumulated snapshots, invalidated by installed version or policy changes.
    @Nullable SaoDropSession dropSession;
    /// Coalesces metadata refreshes without delaying pack work.
    final AtomicBoolean updatesRefreshing = new AtomicBoolean();
    /// Invalidates queued prompt callbacks after cancellation or a replacement policy.
    final java.util.concurrent.atomic.AtomicLong downloadGeneration = new java.util.concurrent.atomic.AtomicLong();
    /// Active Downloads poll, cancelled when the prompt closes or the engine shuts down.
    volatile java.util.concurrent.@Nullable ScheduledFuture<?> downloadPoll;
    /// Existing platform link opener, replaced by an inert recorder in tests.
    java.util.function.Consumer<String> linkOpener = org.jackhuang.hmcl.DesktopUtils::openLink;
    /// Injected folders let headless tests exercise polling without inspecting the user's Downloads.
    Supplier<@Unmodifiable List<Path>> downloadDirectories = SaoDownloads::directories;
    /// Sanitized process output and support-export reader.
    volatile SaoGameLogs logs;
    /// Additive common-feature worker keeps support/check/link operations away from pack transactions.
    volatile SaoExtras extras;
    /// Captures each real process's visibility policy and restores the launcher after crash analysis.
    final SaoLauncherLifecycle lifecycle;
    /// Embedded update note remains tied to this launcher rather than a subsequently dropped update.
    private volatile SaoData.News embeddedNews = new SaoData.News(SaoData.NewsKind.UPDATE, "v未内置整合包", "", "");
    /// Independent online work cannot delay pack writes or game startup.
    final ExecutorService onlineWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "sao-online"); thread.setDaemon(true); return thread;
    });
    /// Coalesces process-output and task publications so JavaFX never receives one runnable per log line.
    final ScheduledExecutorService publications = Executors.newSingleThreadScheduledExecutor(r -> {
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
    final AtomicBoolean gameRunning = new AtomicBoolean();
    /// Invalidates queued room callbacks when leaving, replacing a session, or closing the launcher.
    private final java.util.concurrent.atomic.AtomicLong roomGeneration = new java.util.concurrent.atomic.AtomicLong();
    /// Last local refresh time, preventing listeners that reread getters from scheduling a loop.
    private volatile long localRefreshedAt;
    /// Last public notices, copied before publication.
    volatile @Unmodifiable List<SaoData.News> remoteNews = List.of();
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
    final ObservableList<String> gameLog = FXCollections.observableArrayList();
    /// Capability inspected on the worker before publishing this run or its log reset.
    volatile boolean legacyHelmetSignal;
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
    /// Groups asset transfers and owns explicit terminal outcomes independently of UI publication.
    private final SaoDownloadTasks taskRecords = new SaoDownloadTasks();
    /// Last server status; null means no configured server or no completed probe.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable ServerStatus> serverStatus = new ReadOnlyObjectWrapper<>();
    /// Last run analysis, cleared before a new launch.
    final ReadOnlyObjectWrapper<SaoData.@Nullable Crash> crash = new ReadOnlyObjectWrapper<>();
    /// Explicitly requested Terracotta room state; never initialized by reading this property.
    private final ReadOnlyObjectWrapper<SaoData.Room> room = new ReadOnlyObjectWrapper<>(SaoData.Room.OFF);

    /// Creates the engine under the launcher's isolated local storage.
    public SaoPackEngine() {
        this(Metadata.HMCL_LOCAL_HOME, null, true, true, null,
            SaoUninstallHelper::prepare, () -> {
                org.jackhuang.hmcl.setting.SettingsManager.shutdownForUninstall();
                org.jackhuang.hmcl.util.logging.Logger.LOG.shutdown();
            }, Platform::exit);
    }

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

    /// Isolates account contracts under a fixture root with explicit API, protected-store and Microsoft/Mojang seams.
    SaoPackEngine(Path dataRoot, Supplier<URI> endpoint, SaoOathCredentials credentials,
                  SaoOathAccounts.PremiumProof premium, URI mojangJoin) {
        this(dataRoot, null, false, false, null, SaoUninstallHelper::prepare, () -> { }, () -> { },
                new AccountHooks(endpoint, credentials, premium, mojangJoin));
    }

    /// Combines inert uninstall system hooks with an explicit protected store for full cleanup contracts.
    SaoPackEngine(Path dataRoot, SaoUninstaller plan, SaoUninstallHelper.Factory helper,
                  Runnable shutdown, Runnable quit, SaoOathCredentials credentials) {
        this(dataRoot, null, false, false, plan, helper, shutdown, quit,
                new AccountHooks(() -> URI.create("http://127.0.0.1:1/"), credentials,
                        () -> { throw new SaoAccountException("请先登录正版账号"); },
                        URI.create("http://127.0.0.1:1/session/minecraft/join")));
    }

    /// Injectable account dependencies, used only by explicit fixture construction.
    @NotNullByDefault
    private record AccountHooks(Supplier<URI> endpoint, SaoOathCredentials credentials,
                                SaoOathAccounts.PremiumProof premium, URI mojangJoin) { }

    /// Preserves the established installation/uninstall constructor while selecting production account dependencies.
    private SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded,
                          boolean production, @Nullable SaoUninstaller suppliedUninstaller,
                          SaoUninstallHelper.Factory helper, Runnable shutdown, Runnable quit) {
        this(dataRoot, supplied, loadEmbedded, production, suppliedUninstaller, helper, shutdown, quit, null);
    }

    /// Centralizes production and explicitly isolated fixture initialization without opening any window.
    private SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded,
                          boolean production, @Nullable SaoUninstaller suppliedUninstaller,
                          SaoUninstallHelper.Factory helper, Runnable shutdown, Runnable quit, @Nullable AccountHooks accountHooks) {
        this(dataRoot, supplied, loadEmbedded, production, suppliedUninstaller, helper, shutdown, quit, accountHooks, null);
    }

    /// Restricts custom public keys to explicit isolated fixtures; production construction always uses compiled trust roots.
    SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded, SaoReleaseSignatures trust) {
        this(dataRoot, supplied, loadEmbedded, false, null, SaoUninstallHelper::prepare, () -> { }, () -> { }, null, trust);
    }

    /// Initializes the shared backend with production-only compiled trust or explicitly supplied offline test keys.
    private SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded,
                          boolean production, @Nullable SaoUninstaller suppliedUninstaller,
                          SaoUninstallHelper.Factory helper, Runnable shutdown, Runnable quit, @Nullable AccountHooks accountHooks,
                          @Nullable SaoReleaseSignatures fixtureTrust) {
        this(dataRoot, supplied, loadEmbedded, production, suppliedUninstaller, helper, shutdown, quit,
                accountHooks, fixtureTrust, null, System::nanoTime);
    }

    /// Exercises real verified refresh/publication paths with an inert exchange and deterministic monotonic clock.
    SaoPackEngine(Path dataRoot, SaoReleaseSignatures trust, SaoOnlineData.Exchange exchange,
                  java.util.function.LongSupplier clock) {
        this(dataRoot, null, false, false, null, SaoUninstallHelper::prepare, () -> { }, () -> { },
                null, trust, exchange, clock);
    }

    /// Initializes production or entirely offline refresh fixtures without changing compiled trust in production.
    private SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded,
                          boolean production, @Nullable SaoUninstaller suppliedUninstaller,
                          SaoUninstallHelper.Factory helper, Runnable shutdown, Runnable quit, @Nullable AccountHooks accountHooks,
                          @Nullable SaoReleaseSignatures fixtureTrust, @Nullable SaoOnlineData.Exchange fixtureExchange,
                          java.util.function.LongSupplier clock) {
        root = dataRoot.toAbsolutePath().normalize();
        if (production) runMarker = new SaoRunMarker(root);
        try {
            Path preferences = production ? Metadata.HMCL_USER_HOME : root;
            if (production && !Files.exists(preferences.resolve("launcher-settings.json")) && Files.isRegularFile(root.resolve("launcher-settings.json"))) {
                Files.createDirectories(preferences);
                Files.copy(root.resolve("launcher-settings.json"), preferences.resolve("launcher-settings.json"));
            }
            settings = new SaoSettings(preferences);
            root = SaoGameLocation.resolve(settings, root);
        }
        catch (IOException failure) { throw new java.io.UncheckedIOException("Cannot read launcher settings", failure); }
        game = new SaoGameBridge(root, this::reportProgress);
        game.observeInstallProgress(this::reportInstallProgress);
        steps = SaoReadinessCheck.of(game);
        productionUninstall = production;
        fixtureAccountEndpoint = accountHooks != null;
        uninstallHelper = helper;
        uninstallShutdown = shutdown;
        uninstallQuit = quit;
        @Unmodifiable List<Path> uninstallRoots = production
                ? List.of(root, Metadata.HMCL_USER_HOME, Metadata.DEPENDENCIES_DIRECTORY) : List.of(root);
        Path desktop = production ? SaoUninstaller.realDesktop()
                : Objects.requireNonNull(root.getParent()).resolve("kept-saves");
        uninstaller = suppliedUninstaller != null ? suppliedUninstaller
                : new SaoUninstaller(uninstallRoots, game.instanceDir(), desktop, SaoUninstaller.desktopTrash(), production);
        game.observeTasks(this::recordTask);
        game.setDownloadSource(downloadSource());
        game.restoreJava(java());
        publish(() -> org.jackhuang.hmcl.task.FetchTask.setDownloadExecutorConcurrency(downloadThreads()));
        files = new SaoInstanceFiles(game.instanceDir(), game);
        files.settings(settings);
        SaoReleaseSignatures trust = production || fixtureTrust == null ? SaoReleaseSignatures.PRODUCTION : fixtureTrust;
        online = fixtureExchange == null ? new SaoOnlineData(production ? Metadata.HMCL_USER_HOME : dataRoot, trust) : new SaoOnlineData(dataRoot, fixtureExchange, trust);
        productionUpdates = production || fixtureExchange != null;
        inertOnlineFixture = fixtureExchange != null;
        policyRefresh = new SaoPolicyRefresh(clock);
        selfUpdate = new SaoSelfUpdate(production ? SaoSelfUpdate.defaultRoot() : dataRoot.resolve("self-update"));
        versionMetadata = online.cachedVersion();
        logs = new SaoGameLogs(game.instanceDir());
        Path accountRoot = production ? Metadata.HMCL_USER_HOME : dataRoot.toAbsolutePath().normalize();
        try {
            SaoOathCredentials credentialStore = accountHooks == null ? new SaoOathCredentialStore(accountRoot, production) : accountHooks.credentials();
            oath = new SaoOathAccounts(accountRoot, root,
                    accountHooks == null ? this::accountEndpoint : accountHooks.endpoint(),
                    () -> production ? org.jackhuang.hmcl.util.i18n.I18n.getLocale().getLocale().toLanguageTag() : Locale.getDefault().toLanguageTag(), credentialStore,
                    accountHooks == null ? SaoMicrosoftLogin.provider() : accountHooks.premium(),
                    accountHooks == null ? SaoAccountClient.originalMojangJoin() : accountHooks.mojangJoin(),
                    account -> linkStart(account, null), message -> publish(() -> error.set(message)));
        } catch (IOException failure) { throw new java.io.UncheckedIOException("Cannot initialize protected account storage", failure); }
        applyAccountPolicy(versionMetadata);
        if (production && accountHooks == null && oath.rememberedName() == null) oath.restoreMicrosoft();
        remoteNews = online.cached();
        installer = new SaoPackInstaller(game.instanceDir(), new SaoPackInstaller.Downloader() {
            /// Downloads one dependency through the production mirror/hash path.
            @Override public void download(SaoPackManifest.PackFile file, Path path) throws Exception { game.download(file, path); }

            /// Applies the official CDN policy on the currently bound game bridge.
            @Override public void downloadOfficialMod(SaoPackManifest.PackFile file, Path path) throws Exception { game.downloadOfficialMod(file, path); }

            /// Preserves detailed counters and all-file failure reporting for the entire batch.
            @Override public void downloadAll(Map<SaoPackManifest.PackFile, Path> files) throws Exception { game.downloadAll(files); }
        }, Map.of(), this::reportProgress);
        skins = new SaoSkinStore(game.instanceDir());
        extras = new SaoExtras(root, game, settings, online, logs, installer, () -> source, this::embeddedVersion, gameRunning::get, production);
        if (versionMetadata != null) extras.updateQuark(versionMetadata.quark());
        lifecycle = new SaoLauncherLifecycle(SaoPackEngine::publish, new SaoLauncherLifecycle.Window() {
            /// Implicit-exit setting to restore after the hidden main window returns.
            private boolean implicitExit;
            /// Hides only in the production engine; fixture construction never initializes a stage or toolkit.
            @Override public void hide() {
                oath.friends.notifications.attention(false, false);
                if (!production) return;
                @Nullable javafx.stage.Stage stage = org.jackhuang.hmcl.ui.Controllers.getStage();
                if (stage == null) return;
                implicitExit = Platform.isImplicitExit();
                Platform.setImplicitExit(false);
                // The screen may hold the hide until the game shows its own visor (see SaoHandoff).
                stage.hide();
            }

            /// Restores the main window while the engine finishes processing the exit.
            @Override public void show() {
                if (closed.get()) return;
                oath.friends.notifications.attention(true, false);
                if (!production) return;
                @Nullable javafx.stage.Stage stage = org.jackhuang.hmcl.ui.Controllers.getStage();
                if (stage != null) { stage.setIconified(false); stage.show(); Platform.setImplicitExit(implicitExit); }
            }

            /// All policies finish through the screen, including KEEP and deferred CLOSE.
            @Override public void handover(Runnable complete) { SaoHandoff.hide(complete); }

            /// Drops the screen callback on early exit for every visibility policy.
            @Override public void cancel() { SaoHandoff.cancel(); }

            /// Uses HMCL's normal application shutdown after the protected launch handoff, never System.exit.
            @Override public void close() { if (production) org.jackhuang.hmcl.Launcher.stopApplication(); }
        });
        worker.execute(() -> {
            try {
                @Nullable String missing = SaoGameLocation.missing(settings, root);
                if (missing != null) { maintenance.refuse(new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, missing, SaoReadiness.Action.NONE)); return; }
                @Nullable String developmentPack = System.getProperty("sao.pack");
                source = !loadEmbedded ? supplied : developmentPack == null || developmentPack.isBlank()
                        ? SaoPackArchive.embedded(root.resolve("pack-cache")) : SaoPackArchive.open(Path.of(developmentPack));
                if (source != null) embeddedVersion = source.manifest().version();
                @Nullable SaoPackArchive shipped = source;
                embeddedArchive = shipped == null ? null : shipped.zip().getFileName().toString();
                embeddedNews = SaoOnlineData.embeddedNews(source, embeddedVersion);
                publishNews();
                installer.recover();
                @Nullable SaoPackArchive accepted = maintenance.readAcceptedSource();
                if (accepted != null && (source == null
                        || VersionNumber.compare(accepted.manifest().version(), source.manifest().version()) >= 0)) source = accepted;
                files.requiredResources(source);
                maintenance.verifyLocalPackOrigin();
                refreshLocalNow();
                refreshJavaNow();
                logs.loadPrevious();
                @Nullable SaoData.Crash previousCrash = logs.analyze(settings.flag("lastExitAbnormal", false), previousRunStarted());
                publish(() -> { gameLog.setAll(logs.snapshot()); crash.set(previousCrash); });
                maintenance.refreshState();
                try { SaoData.LauncherUpdate value = selfUpdate.status(); publish(() -> launcherUpdate.set(value)); }
                catch (IOException ignored) { /* The immutable bundled launcher remains usable. */ }
            } catch (Exception failure) { fail("读取整合包失败", failure); }
            catch (StackOverflowError failure) {
                // Report diagnostic parser exhaustion instead of leaving the startup card at CHECKING forever.
                fail("启动检查失败，请导出日志", new IOException("检查线程栈溢出", failure));
            }
            finally {
                releaseBusy(); packReady.complete(null); refreshInstalledBytes();
                if (production) { maintenance.refreshUpdates(false); extras.refreshLinks(); }
            }
        });
    }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoPackState> state() { return state.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public ReadOnlyDoubleProperty progress() { return progress.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public ReadOnlyStringProperty stepLabel() { return step.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoInstallProgress> installProgress() { return installProgress.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<@Nullable String> lastError() { return error.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<@Nullable String> installedVersion() { return installedVersion.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public String embeddedVersion() { return embeddedVersion; }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoReadiness> readiness() { return readiness.getReadOnlyProperty(); }

    /// File name of the embedded release archive in pack-cache, kept when superseded archives are pruned.
    volatile @Nullable String embeddedArchive;

    /// Persisted next-run visibility, mapped through HMCL's existing LauncherVisibility enum.
    @Override public SaoData.LauncherBehavior launcherBehavior() { return extras.behavior(); }

    /// Does not mutate the captured policy of an already running process.
    @Override public void setLauncherBehavior(SaoData.LauncherBehavior behavior) {
        if (!closed.get() && !uninstalling.get()) extras.behavior(behavior);
    }

    /// Atomically builds a sanitized Downloads ZIP and completes on the publication thread.
    @Override public CompletableFuture<SaoData.FeedbackZip> buildFeedbackZip() { return extras.feedback(); }

    /// Stable read-only last-good public links.
    @Override public @UnmodifiableView ObservableList<SaoData.CommunityLink> links() { return extras.links(); }

    /// Explicit independent refresh; no account calls or server ping.
    @Override public void refreshLinks() { extras.refreshLinks(); }

    /// Selected next-launch preset.
    @Override public SaoData.GraphicsPreset graphicsPreset() { return extras.preset(); }

    /// Stores a pending selection even while playing, leaving game files untouched until the next launch.
    @Override public void setGraphicsPreset(SaoData.GraphicsPreset preset) {
        if (!closed.get() && !uninstalling.get()) extras.preset(preset);
    }

    /// Headless HMCL RAM/GPU suggestion, without creating an OpenGL context.
    @Override public CompletableFuture<SaoData.GraphicsPreset> suggestedGraphicsPreset() { return extras.suggestion(); }

    /// Asynchronous first-open checks, including compatible Java discovery without downloads.
    @Override public CompletableFuture<@Unmodifiable List<SaoData.SystemCheck>> systemCheck() { return extras.systemCheck(); }

    /// Version shared by the UI's notice/licence bundle and persisted acceptance preference.
    @Override public String userNoticeVersion() { return SaoLauncherService.super.userNoticeVersion(); }

    /// Old acceptance versions do not satisfy the current notice bundle.
    @Override public boolean userNoticeAccepted() { return SaoLauncherService.super.userNoticeAccepted(); }

    /// Records only an explicit acceptance of the exact currently displayed version.
    @Override public void acceptUserNotice(String version) { SaoLauncherService.super.acceptUserNotice(version); }

    /// Explicit frontend visibility/focus changes gate ephemeral social notifications.
    @Override public void setLauncherAttention(boolean visible, boolean focused) { oath.friends.notifications.attention(visible, focused); }

    /// Stable event stream driven exclusively by authenticated live/poll deliveries and request transitions.
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable Notification> notification() { return oath.friends.notifications.event(); }


    /// {@inheritDoc}
    @Override public void linkStart(Account account, char @Nullable [] authPassword) { launch.linkStart(account, authPassword); }

    /// Validates the exact ephemeral name without persisting it or consulting ARGUS ID.
    @Override public void linkStartOffline(String name) { launch.linkStartOffline(name); }

    /// Online launch failure text: the launcher's own Chinese reason from the cause chain when there is one, else the
    /// exception type with the usual hint.
    static String launchFailure(Throwable failure) {
        @Nullable String clockProblem = SaoClockCheck.SYSTEM.currentProblem();
        if (clockProblem != null) return clockProblem;
        @Nullable Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            @Nullable String message = cause.getMessage();
            if (message != null && message.length() <= 120 && message.indexOf('\n') < 0 && message.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF))
                return "Link Start 失败：" + message;
        }
        return "Link Start 失败（" + failure.getClass().getSimpleName() + "）。请检查账户、网络和 Java 17 设置。";
    }

    /// Whether two policies name the same pack, quick updates, patch origin and launcher release; a maintenance notice or sequence
    /// bump alone keeps dropped files and an open download.
    static boolean sameRelease(SaoData.@Nullable VersionMetadata a, SaoData.@Nullable VersionMetadata b) {
        if (a == null || b == null) return a == b;
        return a.pack().equals(b.pack()) && a.updates().equals(b.updates()) && a.launcher().equals(b.launcher())
                && Objects.equals(a.patchOrigin(), b.patchOrigin());
    }

    /// Resolves explicit deployment overrides and otherwise uses the built-in account service.
    private URI accountEndpoint() {
        @Nullable String property = System.getProperty("sao.account.api");
        if (property != null && !property.isBlank()) return SaoAccountClient.checkedRoot(property);
        @Nullable String environment = System.getenv("SAO_ACCOUNT_API");
        if (environment != null && !environment.isBlank()) return SaoAccountClient.checkedRoot(environment);
        String configured = settings.text("accountApi", "");
        if (!configured.isBlank()) return SaoAccountClient.checkedRoot(configured);
        return SaoAccountClient.checkedRoot(SaoAccountClient.DEFAULT_API);
    }

    /// Applies authenticated account policy only when no explicit deployment/test override is configured.
    void applyAccountPolicy(@Nullable SaoData.VersionMetadata metadata) {
        @Nullable String property = System.getProperty("sao.account.api");
        @Nullable String environment = System.getenv("SAO_ACCOUNT_API");
        boolean explicit = fixtureAccountEndpoint || property != null && !property.isBlank() || environment != null && !environment.isBlank()
                || !settings.text("accountApi", "").isBlank();
        oath.applyAccountApi(explicit || metadata == null ? null : metadata.accountApi());
    }

    /// Reads one current verified game target, without consulting a UI property or performing network work.
    @Nullable String signedServer() {
        @Nullable SaoData.VersionMetadata metadata = versionMetadata;
        return metadata == null ? null : metadata.server();
    }

    /// Gives isolated package tests the same account controller used by every production seam method.
    SaoOathAccounts accounts() { return oath; }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable OathAccount> oathAccount() { return oath.account(); }

    /// {@inheritDoc}
    @Override public @Nullable String rememberedOathName() { return oath.rememberedName(); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathLogin(String name, char @Nullable [] password, boolean remember) { return oath.login(name, password, remember); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathRegister(String name, String email, char[] password) { return oath.register(name, email, password); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathVerify(String code) { return oath.verify(code); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathResend() { return oath.resend(); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathForgot(String name) { return oath.forgot(name); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathReset(String name, String code, char[] password) { return oath.reset(name, code, password); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathChangeEmail(String email, char[] password) { return oath.changeEmail(email, password); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathChangePassword(char[] password, char[] newPassword) { return oath.changePassword(password, newPassword); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathLinkPremium() { return oath.linkPremium(); }

    /// Displays Microsoft independently of ARGUS admission.
    @Override public ReadOnlyObjectProperty<@Nullable SaoMicrosoftIdentity> microsoftIdentity() { return oath.microsoftIdentity(); }

    /// Exposes background admission progress without changing the screen.
    @Override public ReadOnlyObjectProperty<SaoOathLinking> oathLinking() { return oath.oathLinking(); }

    /// Exposes the existing sign-in wait without changing screen components.
    @Override public javafx.beans.property.ReadOnlyBooleanProperty oathLinkWaiting() { return oath.oathLinkWaiting(); }

    /// Fences a cancelled background admission before late results arrive.
    @Override public void cancelOathLinking() { oath.cancelMicrosoftLink(); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathPremiumLogin() { return oath.premiumLogin(); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Boolean> oathNameAvailable(String name) { return oath.nameAvailable(name); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathRename(String name, char[] password) { return oath.rename(name, password); }

    @Override public boolean oathPremiumOnly() { return oath.premiumOnly(); }

    /// Stable live friends list for the ARGUS friends screen.
    @Override public @UnmodifiableView ObservableList<SaoData.Friend> friends() { return oath.friends.friends(); }

    /// Both incoming and outgoing friend requests from the account server.
    @Override public @UnmodifiableView ObservableList<SaoData.FriendRequest> friendRequests() { return oath.friends.requests(); }

    /// Live conversations ordered by most recent message.
    @Override public @UnmodifiableView ObservableList<SaoData.Chat> chats() { return oath.friends.chats(); }

    /// Stable oldest-first messages; repeated access loads older server history pages.
    @Override public @UnmodifiableView ObservableList<SaoData.ChatMessage> messages(String id) { return oath.friends.messages(id); }

    /// Requests friendship by the current game nickname.
    @Override public CompletableFuture<Void> addFriend(String name) { return oath.friends.operation("request",SaoFriends.object("name",name)).thenApply(value -> null); }

    /// Accepts or declines only an incoming request.
    @Override public CompletableFuture<Void> answerFriend(String name,boolean accept) { var body = SaoFriends.object("name",name);body.addProperty("accept",accept); return oath.friends.operation("answer",body).thenApply(value -> null); }

    /// Ends the friendship and its direct conversation.
    @Override public CompletableFuture<Void> removeFriend(String name) { return oath.friends.operation("remove",SaoFriends.object("name",name)).thenApply(value -> null); }

    /// Blocks requests and messages from the named player.
    @Override public CompletableFuture<Void> blockPlayer(String name) { return oath.friends.operation("block",SaoFriends.object("name",name)).thenApply(value -> null); }

    /// Resolves the server's unique direct conversation for a current friend.
    @Override public CompletableFuture<String> directChat(String name) { return oath.friends.operation("direct",SaoFriends.object("name",name)).thenApply(value -> value.get("id").getAsString()); }

    /// Creates an invitation-only ARGUS group from current friends.
    @Override public CompletableFuture<String> createGroup(String title,List<String> members) { var body = SaoFriends.object("title",title);var names = new com.google.gson.JsonArray();members.forEach(names::add);body.add("members",names); return oath.friends.operation("group",body).thenApply(value -> value.get("id").getAsString()); }

    /// Adds the caller's friends to a group without exposing pre-invitation history.
    @Override public CompletableFuture<Void> inviteToGroup(String id,List<String> members) { var body = SaoFriends.object("chat",id);var names = new com.google.gson.JsonArray();members.forEach(names::add);body.add("members",names); return oath.friends.operation("invite",body).thenApply(value -> null); }

    /// Leaves an ARGUS group, deleting it if this was the last member.
    @Override public CompletableFuture<Void> leaveGroup(String id) { return oath.friends.operation("leave",SaoFriends.object("chat",id)).thenApply(value -> null); }

    /// Shows pending immediately and reconciles the server-assigned message ID after delivery.
    @Override public CompletableFuture<Void> sendMessage(String id,String text) { return oath.friends.send(id,text); }

    /// Sends a reply with the authoritative same-chat source ID.
    @Override public CompletableFuture<Void> sendMessage(String id,String text,long replyTo) { return oath.friends.send(id,text,replyTo,false); }

    /// Sends a presence-derived Link Start invitation.
    @Override public CompletableFuture<Void> sendPlayInvite(String id) { return oath.friends.send(id,"",0,true); }

    /// Resolves a validated destination, leaving the actual launch to the screen's explicit action.
    @Override public CompletableFuture<String> acceptInvite(SaoData.ChatMessage message) { return oath.friends.acceptInvite(message,knownServers()); }

    /// Erases one own message and refreshes its stable observable list.
    @Override public CompletableFuture<Void> recallMessage(SaoData.ChatMessage message) { var body = SaoFriends.object("chat",message.chat());body.addProperty("message",message.id()); return oath.friends.operation("recall",body).thenApply(v -> null); }

    /// Emits locally coalesced ephemeral typing.
    @Override public CompletableFuture<Void> typing(String id) { return oath.friends.typing(id); }

    /// Persists the current user's conversation flags.
    @Override public CompletableFuture<Void> setChatPreferences(String id,boolean muted,boolean pinned) { var body = SaoFriends.object("chat",id);body.addProperty("muted",muted);body.addProperty("pinned",pinned); return oath.friends.operation("preferences",body).thenApply(v -> null); }

    /// Persists a private friend remark without renaming the friend.
    @Override public CompletableFuture<Void> setFriendRemark(String name,String remark) { return oath.friends.operation("remark",SaoFriends.object("name",name,"remark",remark)).thenApply(v -> null); }

    /// Searches retained visible text independently of history/read/delivery state.
    @Override public CompletableFuture<@org.jetbrains.annotations.Unmodifiable List<SaoData.ChatMessage>> searchMessages(String text,String chat,int limit) { return oath.friends.search(text,chat,limit); }

    /// Explicit older-page callback for a scrolling conversation.
    @Override public void loadOlderMessages(String id) { oath.friends.loadOlder(id); }

    /// Advances read status through messages actually applied to this open conversation.
    @Override public void markRead(String id) { oath.friends.markRead(id); }

    /// Current server card merged with this account's local process history.
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable PlayerCard> playerCard() { return oath.playerCard(); }

    /// {@inheritDoc}
    @Override public String serverAddress() {
        @Nullable SaoPackArchive pack = source;
        @Nullable String signed = signedServer();
        return pack != null && !pack.manifest().server().isEmpty() ? pack.manifest().server()
                : signed != null ? signed : SaoLauncherService.super.serverAddress();
    }

    /// Preserves the existing canonical invite-target construction while explicitly implementing the screen seam.
    @Override public @Unmodifiable List<String> knownServers() { return SaoLauncherService.super.knownServers(); }

    /// {@inheritDoc}
    @Override public @org.jetbrains.annotations.Unmodifiable List<String> serverNodes() {
        @Nullable SaoPackArchive pack = source;
        return pack != null && !pack.manifest().serverNodes().isEmpty() ? pack.manifest().serverNodes() : SaoLauncherService.super.serverNodes();
    }

    /// {@inheritDoc}
    @Override public void oathLogout() { oath.logout(); }

    /// The production Microsoft device/browser flow (every service default is stated here, SaoExtrasSystemTest).
    @Override public SaoMicrosoftFlow microsoftSignIn() { return SaoMicrosoftFlows.start(); }

    /// A Microsoft identity, or an ARGUS ID linked to Microsoft, is known.
    @Override public boolean microsoftLinked() {
        SaoData.@Nullable OathAccount account = oathAccount().get();
        return microsoftIdentity().get() != null || account != null && account.premiumName() != null;
    }

    /// {@inheritDoc}
    @Override public void linkStartOath() { launch.linkStartOath(); }

    /// {@inheritDoc}
    @Override public @Nullable Path oathSkin() { return oath.skin(); }

    /// Publishes the server-owned appearance choices and inherited previews.
    @Override public ReadOnlyObjectProperty<SaoTextureState> inheritedTextures() { return oath.inheritedTextures(); }

    /// Selects an available uploaded or linked skin without uploading another PNG.
    @Override public CompletableFuture<Void> setSkinSource(String source) { return oath.setSkinSource(source); }

    /// Selects a cape source while retaining all stored source images.
    @Override public CompletableFuture<Void> setCapeSource(String source) { return oath.setCapeSource(source); }

    /// Refreshes linked cosmetics asynchronously under the server account cooldown.
    @Override public CompletableFuture<Void> refreshInheritedTextures() { return oath.refreshInheritedTextures(); }

    /// Runs the explicit stored-session cosmetic sync without opening Microsoft sign-in.
    @Override public CompletableFuture<Void> syncMicrosoftSkin() { return oath.syncMicrosoftSkin(); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> uploadSkin(@Nullable Path png, boolean slim) { return oath.uploadSkin(png, slim); }

    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.SavedSkin> savedSkins() { return oath.savedSkins(); }

    /// {@inheritDoc}
    @Override public SaoData.SavedSkin saveSkin(String name, byte[] png, boolean slim) { return oath.saveSkin(name, png, slim); }

    /// {@inheritDoc}
    @Override public void deleteSavedSkin(SaoData.SavedSkin skin) { oath.deleteSavedSkin(skin); }

    /// {@inheritDoc}
    @Override public void download() { maintenance.download(); }

    /// The cheap check: versions against the signed release, the instance, Java 17 and pack file presence/size.
    /// Worker thread only.
    SaoReadiness computeReadiness() throws IOException { return maintenance.computeReadiness(); }

    /// Offline readiness uses installed content, never a newer remote release as a launch prerequisite.
    SaoReadiness computeReadiness(boolean offline) throws IOException { return maintenance.computeReadiness(offline); }

    /// Offline tests replace the bridge's install/check/launch steps; never used in production.
    void setGameSteps(SaoReadinessCheck.GameSteps replacement) { steps = Objects.requireNonNull(replacement); maintenance.refreshReadinessLater(); }

    /// {@inheritDoc}
    @Override public void repair() { maintenance.repair(); }

    /// {@inheritDoc}
    @Override public void applyUpdateZip(Path zip) { maintenance.applyUpdateZip(zip); }

    /// Every file-picker, Downloads confirmation and older single-ZIP drop uses the accumulated drop path.
    @Override public void applyDownloadedPack(Path zip) { maintenance.applyDownloadedPack(zip); }

    /// Offers cleanup only for copies whose pack transaction successfully committed.
    @Override public boolean offlineBundleCleanupAvailable() { return maintenance.offlineBundleCleanupAvailable(); }

    /// Deletes completed extracted copies on the serialized worker; failed installations retain their inputs.
    @Override public CompletableFuture<Void> deleteOfflineBundleCopies() { return maintenance.deleteOfflineBundleCopies(); }

    /// Exposes the persisted restart instruction and a bootstrap failure report independently of pack state.
    @Override public ReadOnlyObjectProperty<SaoData.LauncherUpdate> launcherUpdate() { return launcherUpdate.getReadOnlyProperty(); }

    /// The marker is tied to the accepted cache filename and survives restart, independently of account state.
    @Override public boolean localPack() { return maintenance.localPack(); }

    @Override public boolean betaPatchedPack() { return maintenance.betaPatchedPack(); }

    /// Accumulates good snapshots even when a batch also contains a wrong file; full packs supersede quick hops.
    @Override public CompletableFuture<Void> acceptDroppedFiles(List<Path> paths) { return maintenance.acceptDroppedFiles(paths); }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.UpdateStatus> updateStatus() { return updateStatus.getReadOnlyProperty(); }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.DownloadPrompt> downloadPrompt() { return downloadPrompt.getReadOnlyProperty(); }

    /// Exposes immutable authenticated sequences through the same UI-thread publication as update/news values.
    @Override public ReadOnlyObjectProperty<SaoData.ReleaseVerification> releaseVerification() { return releaseVerification.getReadOnlyProperty(); }

    /// Applies the mandatory launcher gate at both submission and execution, without a network wait.
    boolean launcherPermitted() {
        if (!locationAvailable()) return false;
        SaoData.UpdateStatus value = SaoUpdates.status(versionMetadata, installedVersion.get(), Metadata.VERSION, dismissedLauncherVersion);
        if (value.launcherBlocked()) publish(() -> { updateStatus.set(value); error.set(value.message()); });
        return !value.launcherBlocked();
    }

    /// {@inheritDoc}
    @Override public void dismissLauncherUpdate() { maintenance.dismissLauncherUpdate(); }

    /// Overrides external link dispatch in isolated tests; validation always occurs before this seam.
    void setLinkOpener(java.util.function.Consumer<String> opener) { linkOpener = Objects.requireNonNull(opener); }

    /// Overrides Downloads discovery only in explicitly isolated fixture engines.
    void setDownloadDirectories(Supplier<@Unmodifiable List<Path>> directories) { downloadDirectories = Objects.requireNonNull(directories); }

    /// {@inheritDoc}
    @Override public void openPackDownload() { maintenance.openPackDownload(); }

    /// {@inheritDoc}
    @Override public void openLauncherDownload() { maintenance.openLauncherDownload(); }

    /// {@inheritDoc}
    @Override public synchronized void watchPackDownload() { maintenance.watchPackDownload(); }

    /// {@inheritDoc}
    @Override public synchronized void cancelPackDownload() { maintenance.cancelPackDownload(); }

    /// {@inheritDoc}
    @Override public void refreshUpdates() { maintenance.refreshUpdates(); }

    /// {@inheritDoc}
    @Override public void setSkin(@Nullable Path png, boolean slim) {
        @Nullable Process process = heldGame;
        if (process != null && process.isAlive()) guardGameAction(() -> setSkinNow(png, slim));
        else setSkinNow(png, slim);
    }

    /// Writes a skin only after any game process has exited.
    private void setSkinNow(@Nullable Path png, boolean slim) {
        if (!allowMutation()) return;
        if (!busy.compareAndSet(false, true)) { refuseBusy(); return; }
        activeTask = "保存皮肤";
        try { skins.set(png, slim); }
        catch (IOException failure) { throw new IllegalArgumentException("无法保存皮肤: " + failure.getMessage(), failure); }
        finally { releaseBusy(); }
    }

    /// {@inheritDoc}
    @Override public @Nullable Path skin() { return skins.skin(); }

    /// {@inheritDoc}
    @Override public boolean skinSlim() { return oath.skin() != null ? oath.skinSlim() : skins.slim(); }

    /// {@inheritDoc}
    @Override public int memoryMiB() { return settings.number("memoryMiB", defaultMemoryMiB(systemMemoryMiB())); }

    /// {@inheritDoc}
    @Override public void setMemoryMiB(int mib) {
        // Same bound as the settings slider (total memory), so a value the slider shows is never silently refused.
        long maximum = Math.max(4096, systemMemoryMiB());
        if (mib < 2048 || mib > maximum) { windowFailure("内存设置失败", new IOException("Memory must be between 2048 MiB and available system memory")); return; }
        saveSetting("memoryMiB", mib);
    }

    /// {@inheritDoc}
    @Override public Path instanceDir() { return game.instanceDir(); }

    /// Refuses every game write while a selected drive is disconnected; never reinstalls into a fallback.
    boolean locationAvailable() {
        @Nullable String missing = SaoGameLocation.missing(settings, root);
        if (missing == null) return true;
        publish(() -> error.set(missing)); return false;
    }

    /// Current game data folder, independent of the launcher executable and Roaming preferences.
    @Override public Path gameDataRoot() { return root; }

    /// Inventories data and free space off the UI thread.
    @Override public CompletableFuture<String> gameLocationInfo() {
        CompletableFuture<String> result = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                @Nullable String missing = SaoGameLocation.missing(settings, root);
                result.complete(missing != null ? missing : root + String.format(java.util.Locale.ROOT,
                        "（游戏数据 %.2f GB，剩余 %.2f GB）", SaoGameLocation.bytes(root) / (double) SaoDiskSpace.GB,
                        SaoDiskSpace.SYSTEM.available(root) / (double) SaoDiskSpace.GB));
            } catch (Exception failure) { result.complete(root + "（无法读取容量：" + failure.getMessage() + "）"); }
        });
        return result;
    }

    /// Searches on the desktop worker so directory enumeration never blocks the interface.
    @Override public CompletableFuture<@Unmodifiable List<Path>> findExistingGames() {
        Path own = root;
        return CompletableFuture.supplyAsync(() -> SaoExistingMinecraft.find(own), desktopWorker);
    }

    /// Reads the current approval independently from discovery.
    @Override public CompletableFuture<@Unmodifiable List<Path>> reuseSources() {
        return CompletableFuture.supplyAsync(() -> SaoReuseSources.read(root), worker);
    }

    /// Serialized with install and relocation; refreshed cache takes effect on the next download.
    @Override public CompletableFuture<Void> setReuseSources(List<Path> folders) {
        @Unmodifiable List<Path> selection = List.copyOf(folders);
        return CompletableFuture.runAsync(() -> {
            try {
                if (!locationAvailable()) throw new IOException("请先连接游戏所在磁盘");
                SaoReuseSources.save(root, selection);
                game.refreshReuseSources();
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, worker);
    }

    /// Secondary drive shortcuts use the same validation as the arbitrary folder picker.
    @Override public CompletableFuture<@Unmodifiable List<SaoGameLocation.Drive>> gameDrives() {
        CompletableFuture<@Unmodifiable List<SaoGameLocation.Drive>> result = new CompletableFuture<>();
        worker.execute(() -> {
            List<SaoGameLocation.Drive> drives = new ArrayList<>();
            try {
                long bytes = SaoGameLocation.bytes(root);
                for (Path drive : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
                    Path target = drive.resolve("NerveGear"); long free = 0; @Nullable String problem = null;
                    try {
                        free = SaoGameLocation.volume(target).free();
                        SaoGameLocation.validate(target, root, bytes, 180, SaoGameLocation::volume,
                                SaoGameLocation.syncRoots(), com.sun.jna.Platform.isWindows());
                    } catch (Exception failure) { problem = failure.getMessage(); }
                    drives.add(new SaoGameLocation.Drive(target, free, problem));
                }
                result.complete(List.copyOf(drives));
            } catch (Exception failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    /// Rebinds every game-scoped component after a verified copy; Roaming accounts and metadata stay stable.
    private void bindGameRoot(Path selected) throws Exception {
        game.close();
        root = selected;
        game = new SaoGameBridge(root, this::reportProgress);
        game.observeInstallProgress(this::reportInstallProgress);
        game.observeTasks(this::recordTask); game.setDownloadSource(downloadSource()); game.restoreJava(java());
        steps = SaoReadinessCheck.of(game);
        files = new SaoInstanceFiles(game.instanceDir(), game);
        files.settings(settings);
        installer = new SaoPackInstaller(game.instanceDir(), new SaoPackInstaller.Downloader() {
            /// Uses the currently bound bridge for single downloads.
            @Override public void download(SaoPackManifest.PackFile file, Path path) throws Exception { game.download(file, path); }

            /// Applies the official CDN policy on the currently bound game bridge.
            @Override public void downloadOfficialMod(SaoPackManifest.PackFile file, Path path) throws Exception { game.downloadOfficialMod(file, path); }

            /// Preserves batch verification and failure reporting.
            @Override public void downloadAll(Map<SaoPackManifest.PackFile, Path> batch) throws Exception { game.downloadAll(batch); }
        }, Map.of(), this::reportProgress);
        skins = new SaoSkinStore(game.instanceDir()); logs = new SaoGameLogs(game.instanceDir());
        extras.rebind(root, game, logs, installer);
        oath.relocateTickets(root);
        @Unmodifiable List<Path> roots = productionUninstall ? List.of(root, Metadata.HMCL_USER_HOME) : List.of(root);
        uninstaller = new SaoUninstaller(roots, game.instanceDir(), productionUninstall ? SaoUninstaller.realDesktop()
                : Objects.requireNonNull(root.getParent()).resolve("kept-saves"), SaoUninstaller.desktopTrash(), productionUninstall);
        files.requiredResources(source);
    }

    /// Moves or explicitly adopts a folder while the game, account operations and other data writers are idle.
    @Override public CompletableFuture<Void> chooseGameLocation(Path selected) {
        return guardGame(() -> chooseGameLocationNow(selected));
    }

    /// Performs the existing move transaction after confirming old game processes have exited.
    private CompletableFuture<Void> chooseGameLocationNow(Path selected) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (org.jackhuang.hmcl.terracotta.TerracottaManager.class) {
            if (closed.get() || uninstalling.get() || gameRunning.get()
                    || org.jackhuang.hmcl.terracotta.TerracottaManager.hasRequestedRoom()) {
                result.completeExceptionally(new IOException("请先关闭游戏、离开联机房间并等待当前任务完成")); return result;
            }
            if (!busy.compareAndSet(false, true)) { result.completeExceptionally(new SaoLauncherService.OperationBusyException(refuseBusy())); return result; }
        }
        activeTask = "搬家游戏数据";
        worker.execute(() -> {
            Path old = root;
            @Nullable SaoPackArchive oldSource = source;
            boolean frozen = false;
            try {
                if (!oath.tryFreeze()) throw new IOException("请等待账号操作完成");
                frozen = true;
                extras.pauseForMove();
                if (processFinder.get().stream().anyMatch(SaoGameProcesses.Running::alive))
                    throw new IOException("游戏又在后台启动了，请重试搬家");
                Path target = selected.toAbsolutePath().normalize();
                if (target.equals(old) && SaoGameLocation.owned(target)) {
                    bindGameRoot(target); source = maintenance.readAcceptedSource();
                    if (source == null || !computeReadiness().ready()) throw new IOException("这个游戏目录尚未 READY，请检查游戏文件");
                    refreshLocalNow(); refreshJavaNow(); maintenance.refreshState();
                    publish(() -> result.complete(null)); return;
                }
                SaoGameLocation.validate(target, old);
                boolean adopt = SaoGameLocation.owned(target);
                boolean missing = SaoGameLocation.missing(settings, old) != null;
                if (missing && !adopt) throw new IOException("原游戏位置不可用。请重新连接原磁盘，或选择已有 NerveGear 数据的文件夹；不会重新下载");
                boolean hasGame = installer.installed() != null;
                String selectedJava = settings.text("java", "");
                if (!selectedJava.isBlank()) {
                    Path executable = Path.of(selectedJava);
                    if (executable.isAbsolute() && executable.normalize().startsWith(old))
                        settings.put("java", old.relativize(executable.normalize()).toString());
                }
                cancelPackDownload();
                SaoGameLocation.Switch pointer = new SaoGameLocation.Switch() {
                    /// Atomically persists before rebinding; caller restores the old pointer on any failure.
                    @Override public void select(Path folder) throws Exception {
                        settings.put("gameDataRoot", folder.toString());
                        source = oldSource != null && oldSource.zip().startsWith(old)
                                ? new SaoPackArchive(folder.resolve(old.relativize(oldSource.zip())), oldSource.manifest()) : oldSource;
                        bindGameRoot(folder);
                        if (!folder.equals(old) && adopt) source = maintenance.readAcceptedSource();
                        files.requiredResources(source);
                    }

                    /// Fresh location selection needs no game yet; existing games must be READY before old deletion.
                    @Override public boolean ready() throws Exception {
                        if (hasGame || adopt) {
                            if (source == null || !computeReadiness().ready()) return false;
                            refreshLocalNow(); refreshJavaNow();
                        }
                        return true;
                    }
                };
                if (adopt) {
                    try { pointer.select(target); if (!pointer.ready()) throw new IOException("这个游戏目录尚未 READY，已保留原位置"); }
                    catch (Exception failure) { pointer.select(old); source = oldSource; throw failure; }
                    // Adopting independent data never deletes the original player's installation.
                } else {
                    SaoGameLocation.move(old, target, pointer,
                            (from, to) -> Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES),
                            (done, total) -> reportProgress(String.format(java.util.Locale.ROOT, "正在搬到 %s %.2f/%.2f GB",
                                    target.getRoot().toString().replace(":\\", " 盘"), done / (double) SaoDiskSpace.GB, total / (double) SaoDiskSpace.GB),
                                    total == 0 ? 1.0 : done / (double) total), hasGame);
                }
                if (source == null && !hasGame && !adopt) source = SaoPackArchive.embedded(root.resolve("pack-cache"));
                refreshLocalNow(); refreshJavaNow(); maintenance.refreshState(); cachedInstalledBytes = -1;
                if (!hasGame && !adopt) publish(() -> step.set("新游戏位置已选择；原位置的未完成数据保留，安装就绪后可清理"));
                publish(() -> result.complete(null));
            } catch (Exception failure) {
                String message = windowFailure("游戏位置切换失败", failure);
                publish(() -> result.completeExceptionally(new IOException(message, failure)));
            } finally {
                extras.resumeAfterMove();
                if (frozen) oath.unfreeze();
                releaseBusy();
            }
        });
        return result;
    }

    /// Returns only a cached count; an expired/unknown inventory is measured on the serialized worker.
    @Override public long installedBytes() { refreshInstalledBytes(); return cachedInstalledBytes; }

    /// {@inheritDoc}
    @Override public Path keptSavesFolder() { return uninstaller.keptSavesFolder(); }

    /// Accepts uninstall only while all game, file and room/download work is idle.
    /// Failed validation or preservation never stops settings persistence or removes an original file.
    /// Reports an uninstall refusal as a fresh error event, so the dialog sees a repeat of the same refusal too.
    private void refuseUninstall(IOException reason) {
        publish(() -> error.set(null));
        windowFailure("卸载被拒绝", reason);
    }

    @Override public void uninstall(boolean keepSaves) {
        synchronized (org.jackhuang.hmcl.terracotta.TerracottaManager.class) {
            if (closed.get() || uninstalling.get()) {
                refuseUninstall(new IOException("启动器已关闭或正在卸载")); return;
            }
            boolean taskBusy = taskRecords.busy();
            if (gameRunning.get() || state.get() == SaoPackState.RUNNING || taskBusy || oath.busy()
                    || org.jackhuang.hmcl.terracotta.TerracottaManager.hasRequestedRoom() || !busy.compareAndSet(false, true)) {
                refuseUninstall(new IOException("请先退出游戏、离开联机房间并等待所有任务完成")); return;
            }
            if (!oath.tryFreeze()) {
                releaseBusy();
                refuseUninstall(new IOException("请等待账号操作完成")); return;
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
                oath.uninstallCredentials();
                oath.close();
                // No new backend writes are accepted after this fence. Drain/cancel online work before data removal.
                closed.set(true);
                desktopWorker.shutdownNow();
                terminal = true;
                roomGeneration.incrementAndGet();
                extras.close();
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
                    readiness.set(new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, SaoReadinessCheck.NOT_DOWNLOADED, SaoReadiness.Action.PACK_WINDOW));
                    state.set(SaoPackState.NOT_INSTALLED);
                    uninstallQuit.run();
                });
            } catch (Exception failure) {
                if (helper != null) helper.cancel();
                fail(terminal ? "卸载失败，请重启启动器后重试" : "卸载失败，原始数据未删除", failure);
            } finally {
                if (!terminal) { uninstalling.set(false); oath.unfreeze(); }
                releaseBusy();
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
    @Override public void refreshOnline() { refreshOnline(false); }

    /// Keeps news/card/status freshness independent of the slower verified version-policy cadence.
    @Override public void refreshOnline(boolean forcePolicy) {
        if (closed.get() || uninstalling.get() || !onlineRefreshing.compareAndSet(false, true)) return;
        maintenance.refreshUpdates(forcePolicy);
        // The explicit offline exchange fixture cannot authorize real account-card transport.
        if (!inertOnlineFixture) oath.refreshCard();
        onlineWorker.execute(() -> {
            try {
                @Nullable SaoPackArchive pack = source;
                String address = !productionUpdates && (pack == null || pack.manifest().server().isEmpty())
                        ? "" : serverAddress();
                if (address.isEmpty()) publish(() -> serverStatus.set(null));
                else {
                    SaoData.ServerStatus result;
                    try { result = inertOnlineFixture ? new SaoData.ServerStatus(false, 0, 0, -1, "", List.of()) : online.ping(address); }
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
    @Override public CompletableFuture<Void> backupWorld(SaoData.World world) { return mutate("备份存档", () -> { files.backup(world); refreshLocalNow(); }); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> restoreBackup(SaoData.Backup backup) { return mutate("恢复存档", () -> { files.restore(backup); refreshLocalNow(); }); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> exportWorld(SaoData.World world, Path zip) { return mutate("导出存档", () -> files.export(world, zip)); }

    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.Pack> shaderPacks() { refreshLocal(); return shadersView; }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> setShader(SaoData.@Nullable Pack pack) { return mutate("切换光影", () -> { files.setShader(pack); refreshLocalNow(); }); }

    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.Pack> resourcePacks() { refreshLocal(); return resourcesView; }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> setResourcePackEnabled(SaoData.Pack pack, boolean enabled) { return mutate("切换资源包", () -> { files.setResource(pack, enabled); refreshLocalNow(); }); }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> addPacks(List<Path> dropped, boolean shaders) {
        @Unmodifiable List<Path> copy = List.copyOf(dropped);
        return mutate("导入资源包", () -> { files.addPacks(copy, shaders); refreshLocalNow(); });
    }

    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<Path> screenshots() { refreshLocal(); return screenshotsView; }

    /// {@inheritDoc}
    /// Returns the current installed entrance mod capability, never inferred from registration timing.
    @Override public boolean legacyHelmetSignal() { return legacyHelmetSignal; }

    /// Streams sanitized lines without depending on the display ring's size or snapshot replacement.
    @Override public void observeGameLog(java.util.function.Consumer<java.util.Optional<String>> listener) {
        logs.observe(event -> publish(() -> listener.accept(event)));
    }

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

    /// The game's graphics card, the discrete one first; null when HMCL's hardware probe finds none.
    private static org.jackhuang.hmcl.util.platform.hardware.@Nullable GraphicsCard gameCard() {
        @Nullable List<org.jackhuang.hmcl.util.platform.hardware.GraphicsCard> cards = org.jackhuang.hmcl.util.platform.SystemInfo.getGraphicsCards();
        if (cards == null || cards.isEmpty()) return null;
        for (var card : cards)
            if (card.getType() == org.jackhuang.hmcl.util.platform.hardware.GraphicsCard.Type.Discrete) return card;
        return cards.get(0);
    }

    /// {@inheritDoc}
    @Override public CompletableFuture<@Nullable String> graphicsVendor() {
        return CompletableFuture.supplyAsync(() -> {
            var card = gameCard();
            if (card == null) return null;
            String name = (card.getVendor() == null ? "" : card.getVendor().toString()) + " " + card.getName();
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (n.contains("nvidia") || n.contains("geforce") || n.contains("quadro")) return "NVIDIA";
            if (n.contains("amd") || n.contains("radeon") || n.contains("ati ")) return "AMD";
            if (n.contains("intel")) return "Intel";
            return null;
        });
    }

    /// {@inheritDoc}
    @Override public boolean canPreferDiscreteGpu() {
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS != org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) return false;
        @Nullable List<org.jackhuang.hmcl.util.platform.hardware.GraphicsCard> cards = org.jackhuang.hmcl.util.platform.SystemInfo.getGraphicsCards();
        return cards != null && cards.stream().anyMatch(c -> c.getType() == org.jackhuang.hmcl.util.platform.hardware.GraphicsCard.Type.Discrete);
    }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> preferDiscreteGpu() {
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS != org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS)
            return CompletableFuture.failedFuture(new IOException("只在 Windows 上可用"));
        return CompletableFuture.runAsync(() -> {
            java.util.Set<Path> binaries = new java.util.LinkedHashSet<>();
            @Nullable Path picked = java();
            if (picked != null) binaries.add(picked);
            try {
                for (org.jackhuang.hmcl.java.JavaRuntime runtime : game.availableJava()) binaries.add(runtime.getBinary());
            } catch (Exception unavailable) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Discrete GPU preference: Java list unavailable", unavailable);
            }
            java.util.Set<Path> targets = new java.util.LinkedHashSet<>();
            for (Path binary : binaries) {
                Path bin = binary.toAbsolutePath().getParent();
                if (bin == null) continue;
                for (String exe : new String[]{"java.exe", "javaw.exe"})
                    if (Files.isRegularFile(bin.resolve(exe))) targets.add(bin.resolve(exe));
            }
            if (targets.isEmpty()) throw new java.util.concurrent.CompletionException(new IOException("没找到游戏用的 Java"));
            for (Path exe : targets) {
                try {
                    // GpuPreference=2 is "high performance", the value Windows' own Graphics settings page writes.
                    Process p = new ProcessBuilder("reg", "add", "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences",
                            "/v", exe.toString(), "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f").redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
                        throw new IOException("reg add exit " + (p.isAlive() ? "timeout" : p.exitValue()));
                } catch (IOException | InterruptedException failed) {
                    if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
                    org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Discrete GPU preference failed for " + exe, failed);
                    throw new java.util.concurrent.CompletionException(new IOException("设置独显失败"));
                }
            }
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("Discrete GPU preference set for " + targets.size() + " Java executables");
        });
    }

    /// Resolves portable runtime choices against this data root; missing legacy paths fall back to Java discovery.
    @Override public @Nullable Path java() {
        String path = settings.text("java", "");
        if (path.isEmpty()) return null;
        try {
            Path stored = Path.of(path);
            Path resolved = stored.isAbsolute() ? stored.normalize() : root.resolve(stored).normalize();
            if (!stored.isAbsolute() && !resolved.startsWith(root)) return null;
            return Files.isRegularFile(resolved) ? resolved : null;
        } catch (InvalidPathException invalid) { return null; }
    }

    /// Validates the runtime before storing local choices relative to the movable launcher data root.
    @Override public CompletableFuture<Void> setJava(@Nullable Path executable) {
        return background(() -> {
            game.setJava(executable);
            @Nullable Path selected = executable == null ? null : executable.toAbsolutePath().normalize();
            saveSetting("java", selected == null ? null
                    : (selected.startsWith(root) ? root.relativize(selected) : selected.toRealPath()).toString());
            refreshJavaNow();
        });
    }

    /// {@inheritDoc}
    @Override public CompletableFuture<Void> downloadJava() { return mutate("下载 Java", () -> { game.downloadJava(); refreshJavaNow(); }); }

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
    @Override public int downloadThreads() { return Math.max(1, Math.min(256, settings.number("downloadThreads", 16))); }

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
        long total = org.jackhuang.hmcl.util.platform.SystemInfo.getTotalMemorySize() / (1024 * 1024);
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
    @Override public boolean betaQuickUpdates() { return Boolean.parseBoolean(preference("betaQuickUpdates", "false")); }

    /// {@inheritDoc}
    @Override public void setBetaQuickUpdates(boolean enabled) { setPreference("betaQuickUpdates", Boolean.toString(enabled)); }

    /// {@inheritDoc}
    @Override public boolean allowModifiedModsOffline() { return Boolean.parseBoolean(preference("allowModifiedModsOffline", "false")); }

    /// {@inheritDoc}
    @Override public void setAllowModifiedModsOffline(boolean allow) { setPreference("allowModifiedModsOffline", Boolean.toString(allow)); }

    /// {@inheritDoc}
    @Override public void openFolder(SaoData.Folder folder) {
        if (closed.get() || uninstalling.get()) return;
        SaoInstanceFiles selected = files;
        desktopWorker.execute(() -> {
            @Nullable Path directory = null;
            try { directory = selected.folder(folder); Files.createDirectories(directory); openExternal(directory, false); }
            catch (IOException failure) {
                windowFailure("打开文件夹失败", failure);
                if (directory != null) org.jackhuang.hmcl.ui.sao.SaoDialogs.folderFailure(directory);
            }
        });
    }

    /// {@inheritDoc}
    @Override public void openFile(Path file) {
        if (closed.get() || uninstalling.get()) return;
        desktopWorker.execute(() -> {
            try { openExternal(file, true); }
            catch (IOException failure) {
                windowFailure("打开文件夹失败", failure);
                org.jackhuang.hmcl.ui.sao.SaoDialogs.folderFailure(file);
            }
        });
    }

    /// Publishes cached notices plus exactly one embedded release entry, preserving source ordering.
    void publishNews() {
        List<SaoData.News> combined = new ArrayList<>(remoteNews);
        combined.removeIf(item -> item.kind() == SaoData.NewsKind.UPDATE && item.title().equals(embeddedNews.title()));
        // A thin launcher carries no pack: its placeholder ("v未内置整合包", no date, no text) is not a notice.
        if (!embeddedNews.title().contains("未内置整合包")) combined.add(embeddedNews);
        @Unmodifiable List<SaoData.News> replacement = List.copyOf(combined);
        SaoData.ReleaseVerification verification = online.verification();
        publish(() -> { replace(news, replacement); releaseVerification.set(verification); });
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
    void refreshLocalNow() throws IOException {
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
        if (!locationAvailable()) return false;
        if (gameRunning.get() || state.get() == SaoPackState.RUNNING) {
            windowFailure("操作被拒绝", new IOException("游戏运行中，请退出游戏后修改存档、皮肤或资源包")); return false;
        }
        return !closed.get() && !uninstalling.get();
    }

    /// Serializes a local mutation with install/repair and reports contention instead of silently losing a request.
    /// The returned future completes on the JavaFX thread; a failure carries the same text as the error property.
    CompletableFuture<Void> mutate(String task, Operation operation) { return guardGame(() -> mutateNow(task, operation)); }

    /// Performs one local mutation after the shared process guard.
    private CompletableFuture<Void> mutateNow(String task, Operation operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!allowMutation()) {
            result.completeExceptionally(new IOException(closed.get() || uninstalling.get() ? "启动器正在关闭"
                    : "操作被拒绝: 游戏运行中，请退出游戏后修改存档、皮肤或资源包"));
            return result;
        }
        if (!busy.compareAndSet(false, true)) { result.completeExceptionally(new SaoLauncherService.OperationBusyException(refuseBusy())); return result; }
        activeTask = task;
        worker.execute(() -> {
            try { operation.run(); publish(() -> { error.set(null); result.complete(null); }); }
            catch (Exception failure) { String message = windowFailure("操作失败", failure); publish(() -> result.completeExceptionally(new IOException(message))); }
            finally { releaseBusy(); }
        });
        return result;
    }

    /// Runs read/export/settings work in the serialized background queue without altering pack state.
    private CompletableFuture<Void> background(Operation operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed.get() || uninstalling.get()) { result.completeExceptionally(new IOException("启动器正在关闭")); return result; }
        worker.execute(() -> {
            try { operation.run(); publish(() -> result.complete(null)); }
            catch (Exception failure) { String message = windowFailure("操作失败", failure); publish(() -> result.completeExceptionally(new IOException(message))); }
        });
        return result;
    }

    /// Persists one small setting and keeps errors in the service property instead of throwing into a UI handler.
    void saveSetting(String key, @Nullable Object value) {
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
    int launchMemoryMiB() {
        if (!autoMemory()) return memoryMiB();
        // The platform detector reports available memory (macOS/Linux free pages alone would cap the game at 2 GB).
        long available = org.jackhuang.hmcl.util.platform.SystemInfo.getFreeMemorySize();
        return Math.max(2048, (int) (org.jackhuang.hmcl.game.HMCLGameRepository.getAutoAllocatedMemory(available,
                org.jackhuang.hmcl.util.platform.Platform.SYSTEM_PLATFORM) / (1024 * 1024)));
    }

    /// Reserves two GiB for the operating system when choosing a new profile's game heap.
    static int defaultMemoryMiB(int totalMiB) {
        return Math.min(8192, Math.max(2048, totalMiB - 2048));
    }

    /// Refuses a persisted allocation larger than this machine after a profile has moved to a smaller machine.
    static int checkedMemoryMiB(int requestedMiB, int totalMiB) throws IOException {
        if (requestedMiB > totalMiB)
            throw new IOException("游戏内存超过系统物理内存，请在设置中降低内存分配");
        return requestedMiB;
    }

    /// Collects graph-scoped transfer outcomes and batches immutable, human-readable download rows.
    private void recordTask(org.jackhuang.hmcl.task.Task<?> task, SaoDownloadTasks.Status status) {
        taskRecords.record(task, status);
        if (closed.get() || !taskPublication.compareAndSet(false, true)) return;
        publications.schedule(() -> {
            // Read on the publication thread, so a queued old snapshot cannot resurrect a finished transfer.
            taskPublication.set(false);
            publish(() -> replace(tasks, taskRecords.snapshot()));
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
        if (!locationAvailable() || closed.get() || uninstalling.get() || busy.get()) return;
        synchronized (org.jackhuang.hmcl.terracotta.TerracottaManager.class) {
            if (closed.get() || uninstalling.get() || busy.get()) return;
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
        @Unmodifiable List<String> command = SaoDesktopOpen.command(os, file.toAbsolutePath().toString(), reveal,
                Objects.requireNonNullElse(System.getenv("SystemRoot"), "C:\\Windows"));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            // Some desktop handlers remain attached to the viewer. Do not block pack work or close that app.
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() != 0) throw new IOException("System file handler failed");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Opening file interrupted", interrupted); }
    }

    /// Reports a window error without pretending that an installed pack has become broken.
    String windowFailure(String context, Exception failure) {
        String message = SaoGameLogs.sanitize(context + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        logFailure(message, failure);
        publish(() -> error.set(message));
        return message;
    }

    /// Records sanitized diagnostic text and stack frames without serializing account objects or launch arguments.
    static void logFailure(String message, Exception failure) {
        StringBuilder detail = new StringBuilder(message).append(" [").append(failure.getClass().getName()).append(']');
        StackTraceElement @Unmodifiable [] frames = failure.getStackTrace();
        for (StackTraceElement frame : frames) detail.append("\n  at ").append(frame);
        org.jackhuang.hmcl.util.logging.Logger.LOG.warning(SaoGameLogs.sanitize(detail.toString()));
    }

    /// Returns the available release or a clear missing-pack error for a launcher built without -PsaoPack.
    SaoPackArchive requireSource() throws IOException {
        @Nullable SaoPackArchive pack = source;
        if (pack == null || !Files.isRegularFile(pack.zip(), LinkOption.NOFOLLOW_LINKS)) throw new IOException(SaoUpdates.prompt(List.of()));
        @Nullable SaoPackManifest installed = installer.installed();
        if (installed != null && VersionNumber.compare(pack.manifest().version(), installed.version()) < 0)
            throw new IOException("已安装整合包版本较新。" + SaoUpdates.prompt(List.of()));
        return pack;
    }

    /// Caches an already verified archive without changing which snapshot the installed receipt references.
    SaoPackArchive cacheSource(SaoPackArchive update) throws IOException { return maintenance.cacheSource(update); }

    /// Persists public manifest plus cache filename after the inventory commits; no outside absolute paths are stored.
    void persistAcceptedSource(SaoPackArchive pack) throws IOException {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("archive", pack.zip().getFileName().toString());
        json.add("manifest", com.google.gson.JsonParser.parseString(pack.manifest().toJson()));
        Path cache = root.resolve("pack-cache");
        Path temporary = Files.createTempFile(cache, "active-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), java.nio.charset.StandardCharsets.UTF_8);
            // Publish the accepted-source pointer only after its complete contents have reached storage.
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            SaoPackFiles.atomicMove(temporary, cache.resolve("active.json"));
        } finally { Files.deleteIfExists(temporary); }
    }

    /// An exception-capable serialized background operation.
    @NotNullByDefault
    @FunctionalInterface
    interface Operation {
        /// Runs only on the engine worker.
        void run() throws Exception;
    }

    /// Accepts one background operation while idle and always releases its operation gate.
    void submit(Operation operation) {
        if (!locationAvailable()) return;
        if (closed.get() || uninstalling.get()) return;
        if (!busy.compareAndSet(false, true)) { refuseBusy(); return; }
        working();
        worker.execute(() -> {
            try { operation.run(); }
            catch (Exception failure) { fail("整合包操作失败", failure); }
            finally { releaseBusy(); }
        });
    }

    /// Publishes the initial busy state before an operation starts.
    void working() {
        activeTask = "安装整合包";
        publish(() -> {
            state.set(SaoPackState.WORKING);
            error.set(null);
            progress.set(-1);
            installProgress.set(SaoInstallProgress.phase(SaoInstallProgress.Stage.PREPARING, "准备安装"));
        });
    }

    /// Publishes measured batch counters and keeps the existing stepLabel/progress properties useful.
    private void reportInstallProgress(SaoInstallProgress snapshot) {
        activeTask = snapshot.label();
        publish(() -> {
            if (closed.get()) return;
            installProgress.set(snapshot); step.set(snapshot.label());
            progress.set(snapshot.totalFiles() == 0 ? -1 : snapshot.completedFiles() / (double) snapshot.totalFiles());
        });
    }

    /// Publishes one short, sanitized phase label and progress value.
    void reportProgress(String label, double fraction) {
        activeTask = label;
        long now = System.nanoTime();
        if (fraction >= 0 && fraction < 1 && now - lastProgressNanos < 50_000_000L) return;
        lastProgressNanos = now;
        publish(() -> {
            step.set(label); progress.set(fraction);
            SaoInstallProgress.Stage stage = label.startsWith("安装 NeoForge") ? SaoInstallProgress.Stage.INSTALL_NEOFORGE
                    : label.startsWith("下载 Minecraft") || label.startsWith("安装 Minecraft") ? SaoInstallProgress.Stage.DOWNLOAD_MINECRAFT
                    : label.startsWith("下载 Java") ? SaoInstallProgress.Stage.DOWNLOAD_JAVA
                    : label.contains("Minecraft / NeoForge 依赖") ? SaoInstallProgress.Stage.CHECK_DEPENDENCIES
                    : label.startsWith("检查整合包") ? SaoInstallProgress.Stage.CHECKING
                    : label.startsWith("应用整合包") ? SaoInstallProgress.Stage.APPLY_PACK
                    : label.equals("整合包已就绪") ? SaoInstallProgress.Stage.COMPLETE : SaoInstallProgress.Stage.PREPARING;
            installProgress.set(SaoInstallProgress.phase(stage, label));
        });
    }

    /// Publishes a public error without serializing secrets, account objects or process arguments.
    void fail(String context, Exception failure) {
        String message = SaoGameLogs.sanitize(context + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        logFailure(message, failure);
        publish(() -> {
            error.set(message); state.set(SaoPackState.FAILED); step.set(""); progress.set(-1);
            SaoInstallProgress last = installProgress.get();
            installProgress.set(new SaoInstallProgress(SaoInstallProgress.Stage.FAILED, context, last.completedFiles(), last.totalFiles(),
                    last.bytesDone(), last.totalBytes(), last.networkBytes(), 0, last.failures()));
        });
    }

    /// Uses the JavaFX thread in the app and a synchronous fallback only when no toolkit exists.
    static void publish(Runnable action) {
        try { Platform.runLater(action); }
        catch (IllegalStateException uninitializedToolkit) { action.run(); }
    }

    /// Stops accepting backend work; does not terminate an already running Minecraft process.
    @Override public void close() {
        if (runMarker != null) runMarker.close();
        closed.set(true);
        desktopWorker.shutdownNow();
        cancelPackDownload();
        packReady.complete(null);
        roomGeneration.incrementAndGet();
        char @Nullable [] password = activePassword;
        if (password != null) Arrays.fill(password, '\0');
        activePassword = null;
        oath.close();
        game.close();
        org.jackhuang.hmcl.terracotta.TerracottaManager.stopRoom();
        extras.close(); online.close(); onlineWorker.shutdownNow(); publications.shutdownNow();
        worker.shutdownNow();
    }
}
