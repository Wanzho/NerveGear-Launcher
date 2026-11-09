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
    /// Initial/checking/working/ready/running state exposed read-only to the screen.
    private final ReadOnlyObjectWrapper<SaoPackState> state = new ReadOnlyObjectWrapper<>(SaoPackState.CHECKING);
    /// Operation fraction or -1 for an indeterminate HMCL task.
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(-1);
    /// Current localized operation label.
    private final ReadOnlyStringWrapper step = new ReadOnlyStringWrapper("");
    /// Detailed measured installation snapshot, additive to the existing label/fraction seam.
    private final ReadOnlyObjectWrapper<SaoInstallProgress> installProgress = new ReadOnlyObjectWrapper<>(
            SaoInstallProgress.phase(SaoInstallProgress.Stage.IDLE, ""));
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
    /// Auto-login waits for accepted/embedded manifest selection before choosing its account API.
    private final CompletableFuture<Void> packReady = new CompletableFuture<>();
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
    /// Production account controller owns protected remembered credentials, ephemeral sessions and profile skins.
    private final SaoOathAccounts oath;
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
    /// Explicit fixture engines never perform public metadata reads, including refreshOnline calls.
    private final boolean productionUpdates;
    /// Last-good public policy is read atomically at the launch boundary, independently of UI publication.
    private volatile @Nullable SaoData.VersionMetadata versionMetadata;
    /// Optional note dismissed during this launcher session, scoped to the advertised latest version.
    private volatile @Nullable String dismissedLauncherVersion;
    /// Read-only public update comparison for frontend listeners.
    private final ReadOnlyObjectWrapper<SaoData.UpdateStatus> updateStatus = new ReadOnlyObjectWrapper<>(
            new SaoData.UpdateStatus(null, false, false, false, ""));
    /// Cancellable prompt state; candidates are never automatically installed.
    private final ReadOnlyObjectWrapper<SaoData.DownloadPrompt> downloadPrompt = new ReadOnlyObjectWrapper<>(
            new SaoData.DownloadPrompt(false, null, ""));
    /// Verified replacement state in per-user launcher data, separate from the signed native application.
    private final SaoSelfUpdate selfUpdate;
    /// Observable restart instruction or a persisted rollback report.
    private final ReadOnlyObjectWrapper<SaoData.LauncherUpdate> launcherUpdate = new ReadOnlyObjectWrapper<>(
            new SaoData.LauncherUpdate("", false, ""));
    /// Worker-owned accumulated snapshots, invalidated by installed version or policy changes.
    private @Nullable SaoDropSession dropSession;
    /// Coalesces metadata refreshes without delaying pack work.
    private final AtomicBoolean updatesRefreshing = new AtomicBoolean();
    /// Invalidates queued prompt callbacks after cancellation or a replacement policy.
    private final java.util.concurrent.atomic.AtomicLong downloadGeneration = new java.util.concurrent.atomic.AtomicLong();
    /// Active Downloads poll, cancelled when the prompt closes or the engine shuts down.
    private volatile java.util.concurrent.@Nullable ScheduledFuture<?> downloadPoll;
    /// Existing platform link opener, replaced by an inert recorder in tests.
    private java.util.function.Consumer<String> linkOpener = org.jackhuang.hmcl.DesktopUtils::openLink;
    /// Injected folders let headless tests exercise polling without inspecting the user's Downloads.
    private Supplier<@Unmodifiable List<Path>> downloadDirectories = SaoDownloads::directories;
    /// Sanitized process output and support-export reader.
    private final SaoGameLogs logs;
    /// Additive common-feature worker keeps support/check/link operations away from pack transactions.
    private final SaoExtras extras;
    /// Captures each real process's visibility policy and restores the launcher after crash analysis.
    private final SaoLauncherLifecycle lifecycle;
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
        root = dataRoot.toAbsolutePath().normalize();
        try { settings = new SaoSettings(root); }
        catch (IOException failure) { throw new java.io.UncheckedIOException("Cannot read launcher settings", failure); }
        game = new SaoGameBridge(root, this::reportProgress);
        game.observeInstallProgress(this::reportInstallProgress);
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
        productionUpdates = production;
        selfUpdate = new SaoSelfUpdate(production ? SaoSelfUpdate.defaultRoot() : root.resolve("self-update"));
        versionMetadata = online.cachedVersion();
        logs = new SaoGameLogs(game.instanceDir());
        Path accountRoot = production ? Metadata.HMCL_USER_HOME : root;
        try {
            SaoOathCredentials credentialStore = accountHooks == null ? new SaoOathCredentialStore(accountRoot, production) : accountHooks.credentials();
            oath = new SaoOathAccounts(accountRoot,
                    accountHooks == null ? this::accountEndpoint : accountHooks.endpoint(),
                    () -> production ? org.jackhuang.hmcl.util.i18n.I18n.getLocale().getLocale().toLanguageTag() : Locale.getDefault().toLanguageTag(), credentialStore,
                    accountHooks == null ? SaoMicrosoftLogin::session : accountHooks.premium(),
                    accountHooks == null ? SaoAccountClient.originalMojangJoin() : accountHooks.mojangJoin(),
                    account -> linkStart(account, null), message -> publish(() -> error.set(message)));
        } catch (IOException failure) { throw new java.io.UncheckedIOException("Cannot initialize protected account storage", failure); }
        remoteNews = online.cached();
        installer = new SaoPackInstaller(game.instanceDir(), new SaoPackInstaller.Downloader() {
            /// Downloads one dependency through the production mirror/hash path.
            @Override public void download(SaoPackManifest.PackFile file, Path path) throws Exception { game.download(file, path); }
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
                if (stage != null) { implicitExit = Platform.isImplicitExit(); Platform.setImplicitExit(false); stage.hide(); }
            }
            /// The crash property is already published before this operation is queued.
            @Override public void show() {
                if (closed.get()) return;
                oath.friends.notifications.attention(true, false);
                if (!production) return;
                @Nullable javafx.stage.Stage stage = org.jackhuang.hmcl.ui.Controllers.getStage();
                if (stage != null) { stage.setIconified(false); stage.show(); Platform.setImplicitExit(implicitExit); }
            }
            /// Uses HMCL's normal application shutdown after the protected launch handoff, never System.exit.
            @Override public void close() { if (production) org.jackhuang.hmcl.Launcher.stopApplication(); }
        });
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
                try { SaoData.LauncherUpdate value = selfUpdate.status(); publish(() -> launcherUpdate.set(value)); }
                catch (IOException ignored) { /* The immutable bundled launcher remains usable. */ }
            } catch (Exception failure) { fail("读取整合包失败", failure); }
            finally {
                busy.set(false); packReady.complete(null); refreshInstalledBytes();
                if (production) { refreshUpdates(); extras.refreshLinks(); }
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
    @Override public void linkStart(Account account, char @Nullable [] authPassword) {
        startGame(account, authPassword, false);
    }

    /// Validates the exact ephemeral name without persisting it or consulting ARGUS ID.
    @Override public void linkStartOffline(String name) {
        if (closed.get() || uninstalling.get()) return;
        SaoPackState current = state.get();
        if (current != SaoPackState.NOT_INSTALLED && current != SaoPackState.UPDATE_READY && current != SaoPackState.READY
                || busy.get()) return;
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            publish(() -> error.set("名字须为 3–16 位英文字母、数字或下划线"));
            return;
        }
        startGame(SaoGameBridge.offlineAccount(name), null, true);
    }

    /// Runs the shared install/update/launch lifecycle; offline mode suppresses server authentication and joining.
    private void startGame(Account account, char @Nullable [] authPassword, boolean offline) {
        if (closed.get() || uninstalling.get()) {
            if (authPassword != null) Arrays.fill(authPassword, '\0');
            return;
        }
        if (!launcherPermitted()) {
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
                if (!launcherPermitted()) { busy.set(false); refreshState(); return; }
                // A prior ARGUS launch must not leak a handoff into dependency preparation or this run.
                if (offline) game.clearOfflineTicket();
                SaoPackArchive pack = requireSource();
                @Nullable SaoPackManifest previous = installer.installed();
                if (previous == null || VersionNumber.compare(pack.manifest().version(), previous.version()) > 0) extras.checkInstallCapacity();
                game.ensureInstalled(false);
                if (previous == null || VersionNumber.compare(pack.manifest().version(), previous.version()) > 0)
                    installer.apply(pack, false);
                files.enforceRequiredResources();
                extras.beforeLaunch();
                if (!launcherPermitted()) { busy.set(false); refreshState(); return; }
                publish(() -> { installedVersion.set(pack.manifest().version()); step.set("Link Start"); state.set(SaoPackState.RUNNING); });
                if (closed.get()) throw new IOException("Launcher has closed");
                long started = System.currentTimeMillis();
                saveSetting("lastRunStarted", Long.toString(started));
                saveSetting("lastExitAbnormal", false);
                logs.clear();
                publish(() -> { gameLog.clear(); crash.set(null); });
                lifecycle.begin(launcherBehavior());
                gameRunning.set(true);
                game.launch(account, pack.manifest(), launchMemoryMiB(), gameWindow(), jvmArgs(), password, offline, closed::get, new ProcessListener() {
                    /// Starts accounting after the OS process exists and before HMCL starts its exit monitor.
                    @Override public void setProcess(org.jackhuang.hmcl.util.platform.ManagedProcess process) {
                        if (account instanceof SaoOathLaunchAccount launched) oath.gameStarted(launched);
                        lifecycle.started();
                    }
                    /// Captures sanitized output only; handoff and authentication lines are filtered first.
                    @Override public void onLog(String line, boolean stderr) { appendGameLog(line); }
                    /// Reconciles the fixed instance and analyzes this run after the real process exits.
                    @Override public void onExit(int code, ExitType type) {
                        if (account instanceof SaoOathLaunchAccount) oath.gameExited();
                        gameRunning.set(false);
                        if (closed.get() || uninstalling.get()) return;
                        worker.execute(() -> {
                            try {
                                saveSetting("lastExitAbnormal", code != 0 || type != ExitType.NORMAL);
                                @Nullable SaoData.Crash result = logs.analyze(code != 0 || type != ExitType.NORMAL, started);
                                publish(() -> crash.set(result));
                                refreshLocalNow(); refreshState();
                            } catch (Exception failure) { windowFailure("读取游戏退出信息失败", failure); }
                            finally { busy.set(false); lifecycle.exited(); }
                        });
                    }
                });
                lifecycle.handedOff();
                // Busy remains true for the running process; its monitor releases it on exit.
            } catch (Exception failure) {
                gameRunning.set(false);
                lifecycle.exited();
                busy.set(false);
                if (offline) publish(() -> { error.set("离线启动失败，请检查整合包和 Java 17 设置");
                    state.set(SaoPackState.FAILED); step.set(""); progress.set(-1); });
                else if (source == null) fail("Link Start 失败", failure);
                else publish(() -> { error.set("Link Start 失败（" + failure.getClass().getSimpleName() + "）。请检查账户、网络和 Java 17 设置。");
                    state.set(SaoPackState.FAILED); step.set(""); progress.set(-1); });
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                activePassword = null;
            }
        });
    }

    /// Resolves deployment overrides before pack metadata and launcher configuration; HTTP is literal-loopback only.
    private URI accountEndpoint() {
        @Nullable String property = System.getProperty("sao.account.api");
        if (property != null && !property.isBlank()) return SaoAccountClient.checkedRoot(property);
        @Nullable String environment = System.getenv("SAO_ACCOUNT_API");
        if (environment != null && !environment.isBlank()) return SaoAccountClient.checkedRoot(environment);
        String configured = settings.text("accountApi", "");
        if (!configured.isBlank()) return SaoAccountClient.checkedRoot(configured);
        packReady.join();
        @Nullable SaoPackArchive pack = source;
        return SaoAccountClient.checkedRoot(pack != null && !pack.manifest().accountApi().isBlank()
                ? pack.manifest().accountApi() : SaoAccountClient.DEFAULT_API);
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
    @Override public CompletableFuture<Void> oathChangeEmail(String email) { return oath.changeEmail(email); }
    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathChangePassword(char[] password, char[] newPassword) { return oath.changePassword(password, newPassword); }
    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathLinkPremium() { return oath.linkPremium(); }
    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathPremiumLogin() { return oath.premiumLogin(); }
    /// {@inheritDoc}
    @Override public CompletableFuture<Boolean> oathNameAvailable(String name) { return oath.nameAvailable(name); }
    /// {@inheritDoc}
    @Override public CompletableFuture<Void> oathRename(String name) { return oath.rename(name); }
    /// Stable live friends list for the ARGUS friends screen.
    @Override public @UnmodifiableView ObservableList<SaoData.Friend> friends(){return oath.friends.friends();}
    /// Both incoming and outgoing friend requests from the account server.
    @Override public @UnmodifiableView ObservableList<SaoData.FriendRequest> friendRequests(){return oath.friends.requests();}
    /// Live conversations ordered by most recent message.
    @Override public @UnmodifiableView ObservableList<SaoData.Chat> chats(){return oath.friends.chats();}
    /// Stable oldest-first messages; repeated access loads older server history pages.
    @Override public @UnmodifiableView ObservableList<SaoData.ChatMessage> messages(String id){return oath.friends.messages(id);}
    /// Requests friendship by the current game nickname.
    @Override public CompletableFuture<Void> addFriend(String name){return oath.friends.operation("request",SaoFriends.object("name",name)).thenApply(value->null);}
    /// Accepts or declines only an incoming request.
    @Override public CompletableFuture<Void> answerFriend(String name,boolean accept){var body=SaoFriends.object("name",name);body.addProperty("accept",accept);return oath.friends.operation("answer",body).thenApply(value->null);}
    /// Ends the friendship and its direct conversation.
    @Override public CompletableFuture<Void> removeFriend(String name){return oath.friends.operation("remove",SaoFriends.object("name",name)).thenApply(value->null);}
    /// Blocks requests and messages from the named player.
    @Override public CompletableFuture<Void> blockPlayer(String name){return oath.friends.operation("block",SaoFriends.object("name",name)).thenApply(value->null);}
    /// Resolves the server's unique direct conversation for a current friend.
    @Override public CompletableFuture<String> directChat(String name){return oath.friends.operation("direct",SaoFriends.object("name",name)).thenApply(value->value.get("id").getAsString());}
    /// Creates an invitation-only ARGUS group from current friends.
    @Override public CompletableFuture<String> createGroup(String title,List<String> members){var body=SaoFriends.object("title",title);var names=new com.google.gson.JsonArray();members.forEach(names::add);body.add("members",names);return oath.friends.operation("group",body).thenApply(value->value.get("id").getAsString());}
    /// Adds the caller's friends to a group without exposing pre-invitation history.
    @Override public CompletableFuture<Void> inviteToGroup(String id,List<String> members){var body=SaoFriends.object("chat",id);var names=new com.google.gson.JsonArray();members.forEach(names::add);body.add("members",names);return oath.friends.operation("invite",body).thenApply(value->null);}
    /// Leaves an ARGUS group, deleting it if this was the last member.
    @Override public CompletableFuture<Void> leaveGroup(String id){return oath.friends.operation("leave",SaoFriends.object("chat",id)).thenApply(value->null);}
    /// Shows pending immediately and reconciles the server-assigned message ID after delivery.
    @Override public CompletableFuture<Void> sendMessage(String id,String text){return oath.friends.send(id,text);}
    /// Sends a reply with the authoritative same-chat source ID.
    @Override public CompletableFuture<Void> sendMessage(String id,String text,long replyTo){return oath.friends.send(id,text,replyTo,false);}
    /// Sends a presence-derived Link Start invitation.
    @Override public CompletableFuture<Void> sendPlayInvite(String id){return oath.friends.send(id,"",0,true);}
    /// Resolves a validated destination, leaving the actual launch to the screen's explicit action.
    @Override public CompletableFuture<String> acceptInvite(SaoData.ChatMessage message){return oath.friends.acceptInvite(message,knownServers());}
    /// Erases one own message and refreshes its stable observable list.
    @Override public CompletableFuture<Void> recallMessage(SaoData.ChatMessage message){var body=SaoFriends.object("chat",message.chat());body.addProperty("message",message.id());return oath.friends.operation("recall",body).thenApply(v->null);}
    /// Emits locally coalesced ephemeral typing.
    @Override public CompletableFuture<Void> typing(String id){return oath.friends.typing(id);}
    /// Persists the current user's conversation flags.
    @Override public CompletableFuture<Void> setChatPreferences(String id,boolean muted,boolean pinned){var body=SaoFriends.object("chat",id);body.addProperty("muted",muted);body.addProperty("pinned",pinned);return oath.friends.operation("preferences",body).thenApply(v->null);}
    /// Persists a private friend remark without renaming the friend.
    @Override public CompletableFuture<Void> setFriendRemark(String name,String remark){return oath.friends.operation("remark",SaoFriends.object("name",name,"remark",remark)).thenApply(v->null);}
    /// Searches retained visible text independently of history/read/delivery state.
    @Override public CompletableFuture<@org.jetbrains.annotations.Unmodifiable List<SaoData.ChatMessage>> searchMessages(String text,String chat,int limit){return oath.friends.search(text,chat,limit);}
    /// Explicit older-page callback for a scrolling conversation.
    @Override public void loadOlderMessages(String id){oath.friends.loadOlder(id);}
    /// Advances read status through messages actually applied to this open conversation.
    @Override public void markRead(String id){oath.friends.markRead(id);}
    /// Current server card merged with this account's local process history.
    @Override public ReadOnlyObjectProperty<SaoData.@Nullable PlayerCard> playerCard() { return oath.playerCard(); }
    /// {@inheritDoc}
    @Override public String serverAddress() {
        @Nullable SaoPackArchive pack = source;
        return pack != null && !pack.manifest().server().isEmpty() ? pack.manifest().server() : SaoLauncherService.super.serverAddress();
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
    /// {@inheritDoc}
    @Override public void linkStartOath() { if (!closed.get() && !uninstalling.get() && launcherPermitted()) oath.linkStart(); }
    /// {@inheritDoc}
    @Override public @Nullable Path oathSkin() { return oath.skin(); }
    /// {@inheritDoc}
    @Override public CompletableFuture<Void> uploadSkin(@Nullable Path png, boolean slim) { return oath.uploadSkin(png, slim); }
    /// {@inheritDoc}
    @Override public @UnmodifiableView ObservableList<SaoData.SavedSkin> savedSkins() { return oath.savedSkins(); }
    /// {@inheritDoc}
    @Override public SaoData.SavedSkin saveSkin(String name, byte[] png, boolean slim) { return oath.saveSkin(name, png, slim); }
    /// {@inheritDoc}
    @Override public void deleteSavedSkin(SaoData.SavedSkin skin) { oath.deleteSavedSkin(skin); }

    /// {@inheritDoc}
    @Override public void repair() {
        submit(() -> {
            SaoPackArchive pack = requireSource();
            extras.checkInstallCapacity();
            game.ensureInstalled(true);
            installer.apply(pack, true);
            refreshState();
        });
    }

    /// {@inheritDoc}
    @Override public void applyUpdateZip(Path zip) {
        applyDownloadedPack(zip);
    }

    /// Applies an already private verified complete pack through the existing preserving installer.
    private void applyPackNow(SaoPackArchive update) throws Exception {
        @Nullable SaoPackManifest previous = installer.installed();
        if (previous != null && VersionNumber.compare(update.manifest().version(), previous.version()) < 0)
            throw new IOException("不会降级已安装整合包 " + previous.version());
        extras.checkInstallCapacity(update);
        SaoPackArchive cached = cacheSource(update);
        game.ensureInstalled(false);
        installer.apply(cached, true);
        acceptSource(cached);
    }

    /// Updates repair/restart source only after the complete instance transaction has committed.
    private void acceptSource(SaoPackArchive cached) throws IOException {
        source = cached;
        persistAcceptedSource(cached);
        files.requiredResources(source);
        refreshLocalNow();
        refreshState();
    }

    /// Every file-picker, Downloads confirmation and older single-ZIP drop uses the accumulated drop path.
    @Override public void applyDownloadedPack(Path zip) { acceptDroppedFiles(List.of(zip)); }

    /// Exposes the persisted restart instruction and a bootstrap failure report independently of pack state.
    @Override public ReadOnlyObjectProperty<SaoData.LauncherUpdate> launcherUpdate() { return launcherUpdate.getReadOnlyProperty(); }

    /// Accumulates good snapshots even when a batch also contains a wrong file; full packs supersede quick hops.
    @Override public CompletableFuture<Void> acceptDroppedFiles(List<Path> paths) {
        @Unmodifiable List<Path> dropped = List.copyOf(paths);
        if (!packReady.isDone()) return packReady.thenCompose(ignored -> acceptDroppedFiles(dropped));
        CompletableFuture<Void> result = new CompletableFuture<>();
        @Nullable SaoData.VersionMetadata metadata = versionMetadata;
        if (metadata == null) {
            IOException failure = new IOException("暂无有效版本信息，请稍后重试");
            windowFailure("无法校验整合包", failure); result.completeExceptionally(failure); return result;
        }
        if (closed.get() || uninstalling.get() || !busy.compareAndSet(false, true)) {
            result.completeExceptionally(new IOException("请等待当前任务完成")); return result;
        }
        working();
        worker.execute(() -> {
            @Nullable SaoPackArchive full = null;
            @Nullable IOException rejected = null;
            boolean accepted = false;
            try {
                SaoDropSession session = currentDropSession(metadata);
                for (Path path : dropped) {
                    try {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
                        long size = Files.size(path);
                        String hash = SaoUpdates.sha256(path);
                        if (size == metadata.pack().size() && hash.equals(metadata.pack().sha256())) {
                            if (full == null) full = SaoUpdates.snapshot(path, metadata.pack(), root.resolve("pack-cache"));
                            accepted = true;
                        } else if (metadata.launcher().artifact() != null && size == metadata.launcher().artifact().size()
                                && hash.equals(metadata.launcher().artifact().sha256())) {
                            SaoData.LauncherUpdate value = selfUpdate.stage(path, metadata, Metadata.VERSION);
                            publish(() -> launcherUpdate.set(value)); accepted = true;
                        } else if (session.acceptQuick(path)) accepted = true;
                        else throw new IOException(SaoUpdates.INVALID_ARCHIVE);
                    } catch (IOException failure) { rejected = failure; }
                }
                if (!metadata.equals(versionMetadata)) throw new IOException("版本信息已更新，请重新拖入文件");
                if (full != null) {
                    applyPackNow(full); session.close(); dropSession = null;
                } else if (!session.ready().isEmpty()) {
                    try {
                        SaoPackArchive complete = installer.applyQuick(session.ready(), source, root.resolve("pack-cache"), extras::checkInstallCapacity);
                        acceptSource(complete); session.close(); dropSession = null;
                    } catch (IOException failure) {
                        if (SaoQuickUpdate.FULL_REQUIRED.equals(failure.getMessage())) {
                            session.requireFull(); refreshState(); publishDropPrompt(true, null);
                            publish(() -> error.set(SaoQuickUpdate.FULL_REQUIRED + SaoUpdates.prompt(List.of())));
                            busy.set(false); result.completeExceptionally(failure); return;
                        }
                        throw failure;
                    }
                }
                refreshState(); publishDropPrompt(downloadPrompt.get().watching(), null);
                if (rejected != null) {
                    IOException failure = rejected;
                    if (!accepted) { fail("文件校验失败", failure); busy.set(false); result.completeExceptionally(failure); return; }
                    publish(() -> error.set(failure.getMessage()));
                }
                busy.set(false); result.complete(null);
            } catch (Exception failure) {
                fail("整合包操作失败", failure); busy.set(false); result.completeExceptionally(failure);
            } finally {
                if (full != null) try { Files.deleteIfExists(full.zip()); } catch (IOException ignored) { }
                busy.set(false);
            }
        });
        return result;
    }

    /// Replaces obsolete retained drops on the worker, using the real receipt rather than delayed UI publication.
    private SaoDropSession currentDropSession(SaoData.VersionMetadata metadata) throws IOException {
        @Nullable SaoPackManifest installed = installer.installed();
        @Nullable String version = installed == null ? null : installed.version();
        @Nullable SaoDropSession session = dropSession;
        if (session == null || !session.metadata.equals(metadata) || !Objects.equals(session.installedVersion, version)) {
            if (session != null) session.close();
            session = new SaoDropSession(metadata, version, root.resolve("pack-cache")); dropSession = session;
        }
        return session;
    }

    /// Publishes exact no-pack/full-pack or ordered quick-hop text with immutable present/missing lists.
    private void publishDropPrompt(boolean watching, @Nullable Path candidate) throws IOException {
        @Nullable SaoData.VersionMetadata metadata = versionMetadata;
        SaoData.DownloadPrompt value = metadata == null ? new SaoData.DownloadPrompt(watching, candidate, SaoUpdates.prompt(List.of()))
                : currentDropSession(metadata).prompt(watching, candidate);
        publish(() -> downloadPrompt.set(value));
    }

    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.UpdateStatus> updateStatus() { return updateStatus.getReadOnlyProperty(); }
    /// {@inheritDoc}
    @Override public ReadOnlyObjectProperty<SaoData.DownloadPrompt> downloadPrompt() { return downloadPrompt.getReadOnlyProperty(); }

    /// Computes against the actual installed receipt; remote metadata never marks the local pack broken.
    private void publishUpdates() {
        SaoData.UpdateStatus value = SaoUpdates.status(versionMetadata, installedVersion.get(), Metadata.VERSION, dismissedLauncherVersion);
        publish(() -> updateStatus.set(value));
    }

    /// Applies the mandatory launcher gate at both submission and execution, without a network wait.
    boolean launcherPermitted() {
        SaoData.UpdateStatus value = SaoUpdates.status(versionMetadata, installedVersion.get(), Metadata.VERSION, dismissedLauncherVersion);
        if (value.launcherBlocked()) publish(() -> { updateStatus.set(value); error.set(value.message()); });
        return !value.launcherBlocked();
    }

    /// {@inheritDoc}
    @Override public void dismissLauncherUpdate() {
        @Nullable SaoData.VersionMetadata metadata = versionMetadata;
        if (metadata != null) { dismissedLauncherVersion = metadata.launcher().latest(); publishUpdates(); }
    }

    /// Overrides external link dispatch in isolated tests; validation always occurs before this seam.
    void setLinkOpener(java.util.function.Consumer<String> opener) { linkOpener = Objects.requireNonNull(opener); }

    /// Overrides Downloads discovery only in explicitly isolated fixture engines.
    void setDownloadDirectories(Supplier<@Unmodifiable List<Path>> directories) { downloadDirectories = Objects.requireNonNull(directories); }

    /// Validates even cached/injected links immediately before dispatch to the existing platform opener.
    private void openQuark(boolean launcher) {
        if (closed.get() || uninstalling.get()) return;
        @Nullable SaoData.VersionMetadata metadata = versionMetadata;
        if (metadata == null) { windowFailure("无法打开下载", new IOException("暂无有效版本信息，请稍后重试")); return; }
        try { linkOpener.accept(SaoUpdates.checkedQuark(metadata.quark()).toString()); }
        catch (Exception failure) { windowFailure("打开夸克失败", failure); }
    }
    /// {@inheritDoc}
    @Override public void openPackDownload() { openQuark(false); }
    /// {@inheritDoc}
    @Override public void openLauncherDownload() { openQuark(true); }

    /// {@inheritDoc}
    @Override public synchronized void watchPackDownload() {
        cancelPackDownload();
        if (closed.get() || uninstalling.get()) return;
        @Nullable SaoData.VersionMetadata metadata = versionMetadata;
        if (metadata == null) { windowFailure("无法查找下载", new IOException("暂无有效版本信息，请稍后重试")); return; }
        long generation = downloadGeneration.incrementAndGet();
        worker.execute(() -> {
            try { publishDropPrompt(true, null); } catch (IOException failure) { windowFailure("读取更新状态失败", failure); }
        });
        downloadPoll = publications.scheduleWithFixedDelay(() -> {
            if (closed.get() || generation != downloadGeneration.get()) return;
            try {
                @Nullable Path candidate = SaoDownloads.find(downloadDirectories.get(), metadata.pack());
                if (candidate == null) for (var quick : metadata.updates()) {
                    candidate = SaoDownloads.find(downloadDirectories.get(), new SaoData.PackRelease(quick.to(), quick.file(), quick.size(), quick.sha256()));
                    if (candidate != null) break;
                }
                @Nullable Path found = candidate;
                publish(() -> {
                    if (generation == downloadGeneration.get()) {
                        SaoData.DownloadPrompt current = downloadPrompt.get();
                        downloadPrompt.set(new SaoData.DownloadPrompt(true, found, current.message(), current.required(),
                                current.present(), current.missing(), current.fullPackRequired()));
                    }
                });
            } catch (IOException failure) { windowFailure("查找下载失败", failure); }
        }, 0, 3, TimeUnit.SECONDS);
    }

    /// {@inheritDoc}
    @Override public synchronized void cancelPackDownload() {
        downloadGeneration.incrementAndGet();
        java.util.concurrent.@Nullable ScheduledFuture<?> poll = downloadPoll;
        downloadPoll = null; if (poll != null) poll.cancel(false);
        publish(() -> downloadPrompt.set(new SaoData.DownloadPrompt(false, null, "")));
        if (!worker.isShutdown()) worker.execute(() -> {
            @Nullable SaoDropSession session = dropSession; dropSession = null;
            if (session != null) try { session.close(); } catch (IOException ignored) { }
        });
    }

    /// {@inheritDoc}
    @Override public void refreshUpdates() {
        if (!productionUpdates || closed.get() || uninstalling.get() || !updatesRefreshing.compareAndSet(false, true)) return;
        onlineWorker.execute(() -> {
            try {
                try { remoteNews = online.fetch(System.getProperty("sao.newsUrl", SaoUpdates.NEWS_URL)); publishNews(); }
                catch (Exception ignored) { /* An outage retains last-good announcements. */ }
                try {
                    SaoData.VersionMetadata metadata = online.fetchVersion(System.getProperty("sao.versionUrl", SaoUpdates.VERSION_URL));
                    if (!metadata.equals(versionMetadata)) cancelPackDownload();
                    versionMetadata = metadata; extras.updateQuark(metadata.quark()); publishUpdates();
                } catch (Exception ignored) { /* Installed packs remain playable with their last-good policy. */ }
            } finally { updatesRefreshing.set(false); }
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
    @Override public boolean skinSlim() { return oath.skin() != null ? oath.skinSlim() : skins.slim(); }
    /// {@inheritDoc}
    @Override public int memoryMiB() { return settings.number("memoryMiB", 8192); }
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
            if (gameRunning.get() || state.get() == SaoPackState.RUNNING || taskBusy || oath.busy()
                    || org.jackhuang.hmcl.terracotta.TerracottaManager.hasRequestedRoom() || !busy.compareAndSet(false, true)) {
                windowFailure("卸载被拒绝", new IOException("请先退出游戏、离开联机房间并等待所有任务完成")); return;
            }
            if (!oath.tryFreeze()) {
                busy.set(false);
                windowFailure("卸载被拒绝", new IOException("请等待账号操作完成")); return;
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
                    state.set(SaoPackState.NOT_INSTALLED);
                    uninstallQuit.run();
                });
            } catch (Exception failure) {
                if (helper != null) helper.cancel();
                fail(terminal ? "卸载失败，请重启启动器后重试" : "卸载失败，原始数据未删除", failure);
            } finally {
                if (!terminal) { uninstalling.set(false); oath.unfreeze(); }
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
        refreshUpdates();
        oath.refreshCard();
        onlineWorker.execute(() -> {
            try {
                @Nullable SaoPackArchive pack = source;
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
        if (pack == null || !Files.isRegularFile(pack.zip(), LinkOption.NOFOLLOW_LINKS)) throw new IOException(SaoUpdates.prompt(List.of()));
        @Nullable SaoPackManifest installed = installer.installed();
        if (installed != null && VersionNumber.compare(pack.manifest().version(), installed.version()) < 0)
            throw new IOException("已安装整合包版本较新。" + SaoUpdates.prompt(List.of()));
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
        @Nullable SaoPackManifest installed = installer.installed();
        if (installed != null) {
            String key = SaoPackArchive.sha1(new java.io.ByteArrayInputStream(installed.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Path retained = SaoPackFiles.resolve(cache, key + ".zip");
            if (Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS)) return new SaoPackArchive(retained, installed);
        }
        Path active = cache.resolve("active.json");
        if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) return null;
        try (java.io.Reader reader = Files.newBufferedReader(active, java.nio.charset.StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            String filename = json.get("archive").getAsString();
            if (!filename.matches("[0-9a-f]{40}\\.zip")) throw new IOException("Invalid cached pack filename");
            Path archive = SaoPackFiles.resolve(cache, filename);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException(SaoUpdates.prompt(List.of()));
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
        publish(() -> {
            installedVersion.set(installed == null ? null : installed.version()); state.set(next); progress.set(1); step.set("");
            SaoData.UpdateStatus updates = SaoUpdates.status(versionMetadata, installedVersion.get(), Metadata.VERSION, dismissedLauncherVersion);
            updateStatus.set(updates); error.set(updates.launcherBlocked() ? updates.message() : null);
        });
        publishDropPrompt(downloadPrompt.get().watching(), downloadPrompt.get().candidate());
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
    private void working() { publish(() -> {
        state.set(SaoPackState.WORKING); error.set(null); progress.set(-1);
        installProgress.set(SaoInstallProgress.phase(SaoInstallProgress.Stage.PREPARING, "准备安装"));
    }); }

    /// Publishes measured batch counters and keeps the existing stepLabel/progress properties useful.
    private void reportInstallProgress(SaoInstallProgress snapshot) {
        publish(() -> {
            if (closed.get()) return;
            installProgress.set(snapshot); step.set(snapshot.label());
            progress.set(snapshot.totalFiles() == 0 ? -1 : snapshot.completedFiles() / (double) snapshot.totalFiles());
        });
    }
    /// Publishes one short, sanitized phase label and progress value.
    private void reportProgress(String label, double fraction) {
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
    private void fail(String context, Exception failure) {
        String message = context + ": " + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        publish(() -> {
            error.set(message); state.set(SaoPackState.FAILED); step.set(""); progress.set(-1);
            SaoInstallProgress last = installProgress.get();
            installProgress.set(new SaoInstallProgress(SaoInstallProgress.Stage.FAILED, context, last.completedFiles(), last.totalFiles(),
                    last.bytesDone(), last.totalBytes(), last.networkBytes(), 0, last.failures()));
        });
    }
    /// Uses the JavaFX thread in the app and a synchronous fallback only when no toolkit exists.
    private static void publish(Runnable action) {
        try { Platform.runLater(action); }
        catch (IllegalStateException uninitializedToolkit) { action.run(); }
    }
    /// Stops accepting backend work; does not terminate an already running Minecraft process.
    @Override public void close() {
        closed.set(true);
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
