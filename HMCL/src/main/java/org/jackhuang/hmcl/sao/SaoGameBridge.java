/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.HMCLDownloadProvider;
import org.jackhuang.hmcl.download.neoforge.NeoForgeRemoteVersion;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.java.HMCLJavaRepository;
import org.jackhuang.hmcl.java.JavaInfoUtils;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.setting.DownloadSource;
import org.jackhuang.hmcl.task.Task;
import org.jetbrains.annotations.Unmodifiable;
import java.util.function.BiConsumer;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.platform.UnsupportedPlatformException;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/// Narrow bridge to HMCL's installers, Java runtime repository, account authentication and process launcher.
/// All entry points execute on an engine worker; none opens HMCL version/account/download screens.
@NotNullByDefault
final class SaoGameBridge {
    /// Minecraft 1.20.1's Java 17 component; the legacy beta component has no native ARM64 entries.
    static final GameJavaVersion GAME_JAVA_VERSION = new GameJavaVersion("java-runtime-gamma", 17);
    /// The only permitted instance ID in this launcher.
    static final GameInstanceID INSTANCE_ID = new GameInstanceID("sao-oath");
    /// The source data directory; dependencies and runtime downloads remain under it.
    private final Path root;
    /// Own repository; never discovers or imports a player's HMCL repository.
    private final DefaultGameRepository repository;
    /// Configurable HMCL provider with regional automatic defaults and explicit official/mirror preferences.
    private final HMCLDownloadProvider provider;
    /// HMCL dependency manager scoped to this repository and cache.
    private final DefaultDependencyManager dependencies;
    /// Current operation step callback; -1 means indeterminate download progress.
    private final BiConsumer<String, Double> progress;
    /// Selected compatible Java 17, cached only after verification.
    private @Nullable JavaRuntime java;
    /// Explicit runtime choice; null delegates to JavaManager and the managed repository.
    private volatile @Nullable Path selectedJava;
    /// Live HMCL task changes forwarded to immutable download-window records.
    private BiConsumer<Task<?>, SaoDownloadTasks.Status> taskObserver = (task, done) -> { };
    /// Owns task start/cancel ordering, including shutdown races before a graph starts.
    private final SaoTaskRunner taskRunner = new SaoTaskRunner();
    /// Independent bounded pack transfers avoid sharing the single Minecraft task-graph owner.
    private final SaoModDownloads modDownloads;
    /// Detailed operation callback, replaced by the engine before any install starts.
    private java.util.function.Consumer<SaoInstallProgress> installProgress = snapshot -> { };


    /// Verified cache using only user-approved foreign game folders.
    private final SaoExistingMinecraft.Cache existingCache;

    /// Refreshes approved folders after settings change.
    void refreshReuseSources() { existingCache.refreshSources(); }

    /// Creates the bridge without network, toolkit startup, account login or game launch.
    SaoGameBridge(Path root, BiConsumer<String, Double> progress) {
        this.root = root;
        this.progress = progress;
        repository = new SaoRepository(root.resolve("minecraft"));
        provider = new HMCLDownloadProvider("https://bmclapi2.bangbang93.com");
        DownloadSource source = org.jackhuang.hmcl.util.i18n.LocaleUtils.IS_CHINA_MAINLAND ? DownloadSource.MIRROR : DownloadSource.DEFAULT;
        provider.setFileSource(source);
        provider.setVersionListSource(source);
        modDownloads = new SaoModDownloads(provider, snapshot -> installProgress.accept(snapshot));
        modDownloads.useOfflineRoot(root);
        // Files the player already has in PCL/HMCL/official .minecraft are copied (verified) instead of downloaded.
        existingCache = new SaoExistingMinecraft.Cache(root.resolve("dependencies"), () -> SaoReuseSources.read(root));
        dependencies = new DefaultDependencyManager(repository, provider, existingCache);
    }

    /// Returns the fixed run directory, matching GameBuilder.enableIsolation().
    Path instanceDir() { return repository.getLayout().getInstanceRoot(INSTANCE_ID); }

    /// Creates HMCL's standard deterministic offline identity, with no skin agent or account-server lookup.
    static OfflineAccount offlineAccount(String name) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("Invalid offline name");
        return new OfflineAccountFactory(NoOfflineInjector.INSTANCE).create(name, OfflineAccountFactory.getUUIDFromUserName(name));
    }

    /// An offline default skin never needs an injector; fail locally if HMCL unexpectedly requests one.
    @NotNullByDefault
    private enum NoOfflineInjector implements AuthlibInjectorArtifactProvider {
        /// Shared stateless provider with no network or filesystem access.
        INSTANCE;
        /// Refuses an agent download rather than silently introducing authentication into offline play.
        @Override public AuthlibInjectorArtifactInfo getArtifactInfo() throws IOException {
            throw new IOException("Offline play does not use authlib-injector");
        }
        /// Reports that no authentication agent is configured for this offline account.
        @Override public Optional<AuthlibInjectorArtifactInfo> getArtifactInfoImmediately() { return Optional.empty(); }
    }

    /// Removes only the fixed ticket entry, never following a ticket symlink or accepting aliased parent folders.
    /// Any deletion failure aborts offline preparation before installation or process creation.
    void clearOfflineTicket() throws IOException {
        SaoUninstaller.rejectLinksBelowRoot(instanceDir());
        Files.deleteIfExists(instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME));
    }

    /// Ensures Java 17, Minecraft 1.20.1, exact NeoForge 47.1.106 and launch dependencies through HMCL.
    void ensureInstalled(boolean repair) throws Exception {
        SaoOfflineBundle.prepare(root, progress);
        progress.accept("检查 Java 17", -1.0);
        selectJava();
        progress.accept("识别 Minecraft / NeoForge 实例", -1.0);
        repository.refresh();
        @Nullable DefaultGameInstance installed = repository.getSnapshot().findInstance(INSTANCE_ID);
        if (installed == null) {
            // 柚歌, 15 Nov ("整合包操作失败: Cannot reserve game instance sao-oath"): an earlier attempt that stopped
            // half-way leaves versions/sao-oath behind without a registered instance, and HMCL refuses to build over
            // it, so every later try failed too. The leftover moves aside (never deleted) and the build starts clean.
            moveUnregisteredAside(instanceDir(), instanceDir().getParent().resolveSibling(".sao-unfinished"), System.currentTimeMillis());
            // NeoForge comes from inside the launcher (15 Nov): its libraries go in place first, the installer is
            // handed to HMCL's task, so only Minecraft itself is downloaded. No bundle means the usual download.
            @Nullable SaoNeoForgeBundle.Result bundle = SaoNeoForgeBundle.unpack(
                    repository.getLayout().getLibrariesDirectory(), root.resolve("dependencies").resolve("neoforge"));
            @Nullable Path importedInstaller = SaoOfflineBundle.installer(root);
            @Nullable Path bundledInstaller = importedInstaller != null ? importedInstaller : bundle == null ? null : bundle.installer();
            org.jackhuang.hmcl.download.neoforge.NeoForgeInstallTask.bundledInstaller =
                    version -> bundledInstaller != null && version.endsWith("47.1.106") ? bundledInstaller : null;
            progress.accept("下载 Minecraft 1.20.1", -1.0);
            // Supplying the exact remote avoids accepting another loader or version-list ambiguity.
            NeoForgeRemoteVersion loader = new NeoForgeRemoteVersion(GameVersionNumber.asGameVersion(SaoPackManifest.MINECRAFT),
                    SaoPackManifest.NEOFORGE, "1.20.1-47.1.106",
                    List.of("https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar"));
            try (var builder = dependencies.newGameBuilder(INSTANCE_ID)) {
                builder.enableIsolation().component(GameComponentType.GAME, SaoPackManifest.MINECRAFT).component(loader);
                runTask(builder.buildAsync());
            }
            installed = repository.getInstance(INSTANCE_ID);
        }
        validateInstance(installed);
        progress.accept(repair ? "校验 Minecraft / NeoForge 依赖" : "准备 Minecraft / NeoForge 依赖", -1.0);
        runTask(dependencies.checkGameCompletionAsync(installed, installed.getResolvedManifest(), repair));
    }

    /// Moves a leftover, unregistered instance folder to `asideDir/sao-oath-<time>` so a fresh build can claim the
    /// name. A link is refused (never followed), and a folder whose existence cannot even be checked, or that cannot
    /// be moved, stops with a plain reason instead of HMCL's "Cannot reserve game instance".
    static @Nullable Path moveUnregisteredAside(Path root, Path asideDir, long now) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("无法读取游戏目录 " + root + "，请把启动器放到有读写权限的文件夹（不要直接在压缩包里运行）");
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("游戏目录 " + root + " 不是普通文件夹，请删除它后再安装");
        Path aside = asideDir.resolve(INSTANCE_ID + "-" + now);
        try {
            Files.createDirectories(asideDir);
            Files.move(root, aside);
        } catch (IOException failure) {
            throw new IOException("上次安装留下的游戏目录 " + root + " 移不开，请关掉占用它的程序或手动删除后再试", failure);
        }
        org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Moved an unregistered instance folder aside: " + aside);
        return aside;
    }

    /// Repairs managed payload before readiness rejects a damaged jar; existing index is bound to the installed version.
    void repairBeforeLaunch(SaoPackArchive pack, java.util.function.Consumer<String> status) throws Exception {
        repository.refresh();
        @Nullable DefaultGameInstance instance = repository.getSnapshot().findInstance(INSTANCE_ID);
        if (instance == null) return;
        org.jackhuang.hmcl.game.AssetIndexInfo descriptor = instance.getResolvedManifest().getAssetIndex();
        @Nullable String digest = descriptor.getSha1();
        if (digest == null) throw new IOException("Missing asset index checksum");
        Path assets = repository.getLayout().getAssetDirectory();
        String relative = "indexes/" + descriptor.getId() + ".json";
        Path index = SaoPackFiles.resolve(assets, relative);
        SaoPrelaunchRepair.repairIndex(index, new SaoPackManifest.PackFile(relative, digest, descriptor.getSize(),
                null, List.of(descriptor.getUrl())), this::download, status);
        SaoPrelaunchRepair.repair(instanceDir(), assets, index, pack, this::download, status);
    }

    /// Downloads pack dependencies through HMCL's mirror and checksum-aware transfer code.
    void download(SaoPackManifest.PackFile file, Path destination) throws Exception {
        modDownloads.download(file, destination);
    }

    /// Downloads the exact missing remote inventory with six workers and checksum-verified publication.
    void downloadAll(Map<SaoPackManifest.PackFile, Path> files) throws Exception { modDownloads.downloadAll(files); }

    /// Connects detailed install snapshots before worker operations begin.
    void observeInstallProgress(java.util.function.Consumer<SaoInstallProgress> observer) { installProgress = observer; }

    /// Authenticates an existing supported HMCL account, starts the fixed instance and hands off stdin once.
    /// Password ownership transfers to this method; it is wiped even when authentication or process creation fails.
    void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window, String jvmArgs, char @Nullable [] password, boolean offline,
                @Nullable String signedServer, java.util.function.BooleanSupplier cancelled, ProcessListener listener) throws Exception {
        @Nullable SaoOathLaunchTicket launchTicket = null;
        boolean started = false;
        try {
            if (!(account instanceof OfflineAccount) && !(account instanceof MicrosoftAccount) && !(account instanceof SaoOathLaunchAccount))
                throw new IOException("Unsupported launcher account");
            boolean handoff = !offline && !(account instanceof SaoOathLaunchAccount)
                    && (!manifest.server().isEmpty() || signedServer != null);
            if (!handoff && password != null) Arrays.fill(password, '\0');
            if (handoff && password != null && password.length > SaoAuthHandoff.MAX_PASSWORD_CHARS)
                throw new IOException("sao_auth password is too long");
            repository.refresh();
            DefaultGameInstance instance = repository.getInstance(INSTANCE_ID);
            validateInstance(instance);
            if (offline) clearOfflineTicket();
            LaunchPlan plan = launchPlan(account, manifest, memoryMiB, window, jvmArgs, selectJava(), instanceDir(), offline, signedServer);
            if (cancelled.getAsBoolean()) throw new IOException("Launcher has closed");
            if (!offline && account instanceof SaoOathLaunchAccount oath) {
                progress.accept("申请进服凭证", -1.0);
                launchTicket = oath.ticket(instanceDir(), plan.server());
            }
            if (cancelled.getAsBoolean()) throw new IOException("Launcher has closed");
            progress.accept("启动 Java", -1.0);
            ManagedProcess process = new HMCLGameLauncher(instance, instance.getResolvedManifest(), plan.auth(), plan.options(), listener).launch();
            started = true;
            @Nullable SaoOathLaunchTicket cleanup = launchTicket;
            if (cleanup != null && account instanceof SaoOathLaunchAccount oath) {
                // Rejoins from the in-game server list need a new single-use ticket each time (James, 14 Nov).
                Process game = process.getProcess(); Path dir = instanceDir(); String server = plan.server();
                SaoTicketKeeper keeper = new SaoTicketKeeper(cleanup, () -> oath.ticket(dir, server), game::isAlive, System::currentTimeMillis);
                keeper.start();
                game.onExit().thenRun(keeper::close);
            } else if (cleanup != null) process.getProcess().onExit().thenRun(() -> { try { cleanup.close(); } catch (IOException ignored) { } });
            try {
                SaoAuthHandoff.writeAndClose(process.getProcess().getOutputStream(), handoff ? password : null);
            } catch (IOException failure) {
                process.getProcess().destroy();
                throw failure;
            }
        } finally {
            if (!started && launchTicket != null) launchTicket.close();
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    /// The exact authentication/options consumed by HMCL, available to headless tests without creating a process.
    /// @param auth local offline authentication or the selected account's authenticated session
    /// @param options fixed-instance settings; offline plans have no Quick Play target
    /// @param server empty for offline play, otherwise the selected multiplayer destination
    @NotNullByDefault
    record LaunchPlan(AuthInfo auth, LaunchOptions options, String server) { }

    /// Builds the production launch plan using the same memory, runtime, window and JVM settings in both modes.
    /// Offline identities must be plain HMCL accounts without a custom skin, preventing injector/API side effects.
    /// The version name is the storage id: NeoForge expands it in `ignoreList` to exclude the primary client jar.
    /// Pack release labels remain presentation metadata and must not participate in jar resolution.
    /// Minecraft's launcher profile and version type use the product title; account identity remains in AuthInfo.
    static LaunchPlan launchPlan(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window,
                                 String jvmArgs, JavaRuntime runtime, Path instance, boolean offline) throws Exception {
        return launchPlan(account, manifest, memoryMiB, window, jvmArgs, runtime, instance, offline, null);
    }

    /// Uses one accepted policy snapshot for the ARGUS launch-ticket destination; offline ignores it.
    /// No QuickPlay: Link Start opens the title screen and the player joins a server themselves (James, 14 Nov).
    static LaunchPlan launchPlan(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window,
                                 String jvmArgs, JavaRuntime runtime, Path instance, boolean offline,
                                 @Nullable String signedServer) throws Exception {
        if (offline && (account.getClass() != OfflineAccount.class || ((OfflineAccount) account).getSkin() != null))
            throw new IOException("Offline play requires a plain offline account");
        AuthInfo auth = account.logIn();
        // GeckoLib reads bundled UTF-8 animation JSON using the JVM default charset.
        List<String> arguments = new ArrayList<>(org.jackhuang.hmcl.util.StringUtils.tokenize(jvmArgs));
        arguments.removeIf(argument -> argument.startsWith("-Dfile.encoding="));
        arguments.add("-Dfile.encoding=UTF-8");
        LaunchOptions.Builder options = new LaunchOptions.Builder().setInstanceId(INSTANCE_ID)
                .setGameDir(instance).setJava(runtime).setMaxMemory(memoryMiB)
                .setVersionName(INSTANCE_ID.toString()).setVersionType(Metadata.TITLE)
                .setProfileName(Metadata.TITLE).setDisableAutoGameOptions(true)
                .setWidth(window.width()).setHeight(window.height()).setFullscreen(window.fullscreen())
                .setJavaArguments(arguments);
        String server = offline ? "" : !manifest.server().isEmpty() ? manifest.server()
                : signedServer != null ? signedServer : account instanceof SaoOathLaunchAccount ? "mc.saooath.com" : "";
        return new LaunchPlan(auth, options.create(), server);
    }

    /// Rejects an existing instance with an unexpected game/loader identity before checking files or launching.
    private static void validateInstance(DefaultGameInstance instance) throws IOException {
        GameInstanceManifest manifest = instance.getResolvedManifest();
        GameComponentAnalyzer analyzer = GameComponentAnalyzer.analyze(manifest, instance.getVersion());
        if (!SaoPackManifest.MINECRAFT.equals(instance.getVersion().toString())
                || !SaoPackManifest.NEOFORGE.equals(analyzer.getVersion(GameComponentType.NEO_FORGE))
                || analyzer.has(GameComponentType.FORGE))
            throw new IOException("Fixed SAO instance must use Minecraft 1.20.1 + NeoForge 47.1.106");
    }

    /// Uses the player's verified Java 17 or selects a compatible local runtime before downloading one.
    private JavaRuntime selectJava() throws Exception {
        @Nullable JavaRuntime found = findJava();
        return found != null ? found : downloadJava();
    }

    /// The player's verified Java 17 or a compatible local runtime, never downloading; null when there is none.
    private @Nullable JavaRuntime findJava() throws Exception {
        @Nullable Path selected = selectedJava;
        if (selected != null) return validateJava(selected, selected.startsWith(root.resolve("java")));
        if (java != null && Files.isRegularFile(java.getBinary())) return java;
        @Unmodifiable List<JavaRuntime> available = availableJava();
        if (!available.isEmpty()) { java = available.get(0); return java; }
        return null;
    }

    /// Link Start's cheap check: the fixed instance exists with the exact loader and a Java 17 is there.
    /// Library and asset completeness stays with [#ensureInstalled(boolean)]; nothing is downloaded here.
    ///
    /// @return a short reason, or null when both are present
    @Nullable String readinessProblem() throws Exception {
        try {
            repository.refresh();
            @Nullable DefaultGameInstance installed = repository.getSnapshot().findInstance(INSTANCE_ID);
            if (installed == null) return SaoReadinessCheck.INCOMPLETE;
            validateInstance(installed);
        } catch (IOException | RuntimeException broken) { return SaoReadinessCheck.INCOMPLETE; }
        try { if (findJava() == null) return SaoReadinessCheck.NO_JAVA; }
        catch (IOException wrongJava) { return SaoReadinessCheck.NO_JAVA; }
        return null;
    }

    /// Sets an explicit choice only after checking its executable version and architecture.
    void setJava(@Nullable Path executable) throws Exception {
        if (executable != null) validateJava(executable, executable.toAbsolutePath().normalize().startsWith(root.resolve("java")));
        selectedJava = executable == null ? null : executable.toRealPath();
        java = null;
    }

    /// Restores a persisted choice without running an executable on the JavaFX thread; launch validates it again.
    void restoreJava(@Nullable Path executable) { selectedJava = executable; java = null; }

    /// Downloads Minecraft 1.20.1's Java 17 component through the chosen provider, with a short availability error.
    JavaRuntime downloadJava() throws Exception {
        progress.accept("下载 Java 17", -1.0);
        JavaRuntime downloaded;
        try {
            downloaded = Objects.requireNonNull(runTask(new HMCLJavaRepository(root.resolve("java"))
                    .getDownloadJavaTask(provider, Platform.SYSTEM_PLATFORM, GAME_JAVA_VERSION)), "Java download returned no runtime");
        } catch (Exception failure) {
            throw runtimeDownloadFailure(failure);
        }
        if (downloaded.getParsedVersion() != 17) throw new IOException("Downloaded Java runtime is not Java 17");
        java = downloaded;
        return downloaded;
    }

    /// Converts an unavailable runtime, including task wrappers, while retaining diagnostic causes and other failures.
    static Exception runtimeDownloadFailure(Exception failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        @Nullable Throwable cause = failure;
        while (cause != null && visited.add(cause)) {
            if (cause instanceof UnsupportedPlatformException) return new IOException("暂无可用的 Java 17", failure);
            cause = cause.getCause();
        }
        return failure;
    }

    /// Lists compatible Java 17 runtimes using JavaManager plus this launcher's managed runtime repository.
    @Unmodifiable List<JavaRuntime> availableJava() throws Exception {
        Set<Path> candidates = new LinkedHashSet<>();
        if (org.jackhuang.hmcl.java.JavaManager.isInitialized()) {
            for (JavaRuntime runtime : org.jackhuang.hmcl.java.JavaManager.getAllJava())
                if (runtime.getParsedVersion() == 17) candidates.add(runtime.getBinary());
        }
        @Nullable Path selected = selectedJava;
        if (selected != null) candidates.add(selected);
        @Nullable String override = System.getProperty("sao.java");
        if (override != null && !override.isBlank()) candidates.add(Path.of(override));
        candidates.add(JavaRuntime.CURRENT_JAVA.getBinary());
        HMCLJavaRepository managed = new HMCLJavaRepository(root.resolve("java"));
        // Windows on ARM can run the bundle's x64 runtime; search compatible registrations as well as native ones.
        Set<Platform> managedPlatforms = new LinkedHashSet<>(List.of(
                Platform.SYSTEM_PLATFORM, Platform.CURRENT_PLATFORM, Platform.WINDOWS_X86_64));
        for (Platform platform : managedPlatforms)
            if (org.jackhuang.hmcl.java.JavaManager.isCompatible(platform) && platform.getBits() == Platform.SYSTEM_PLATFORM.getBits())
                candidates.addAll(managed.getAllJava(platform));
        candidates.add(Path.of("/opt/homebrew/opt/openjdk@17/bin/java"));
        candidates.add(Path.of("/usr/local/opt/openjdk@17/bin/java"));
        @Nullable String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome, "bin", OperatingSystem.CURRENT_OS.getJavaExecutable()));
        for (Path directory : List.of(Path.of("/Library/Java/JavaVirtualMachines"), Path.of("/usr/lib/jvm"))) {
            if (!Files.isDirectory(directory)) continue;
            try (var children = Files.list(directory)) {
                for (Path home : children.toList()) {
                    candidates.add(home.resolve("bin/java")); candidates.add(home.resolve("Contents/Home/bin/java"));
                }
            }
        }
        Map<Path, JavaRuntime> result = new LinkedHashMap<>();
        for (Path executable : candidates) {
            if (!Files.isRegularFile(executable)) continue;
            try {
                JavaRuntime runtime = validateJava(executable, executable.toAbsolutePath().normalize().startsWith(root.resolve("java")));
                result.put(runtime.getBinary(), runtime);
            } catch (IOException ignored) { /* Other Java releases are deliberately excluded. */ }
        }
        return List.copyOf(result.values());
    }

    /// Verifies an executable using HMCL's JavaManager when initialized, with a headless-safe direct probe otherwise.
    private static JavaRuntime validateJava(Path executable, boolean managed) throws IOException, InterruptedException {
        Path real = executable.toRealPath();
        // The current runtime's metadata is already verified by this live JVM. A loose test/development
        // classpath cannot use java-info's packaged-jar probe, so reuse HMCL's authoritative current value.
        JavaRuntime runtime = real.equals(JavaRuntime.CURRENT_JAVA.getBinary().toRealPath()) ? JavaRuntime.CURRENT_JAVA
                : org.jackhuang.hmcl.java.JavaManager.isInitialized()
                ? org.jackhuang.hmcl.java.JavaManager.getJava(real)
                : JavaRuntime.of(real, JavaInfoUtils.fromExecutable(real), managed);
        if (runtime.getParsedVersion() != 17 || !org.jackhuang.hmcl.java.JavaManager.isCompatible(runtime.getPlatform()))
            throw new IOException("This pack requires a compatible Java 17 runtime");
        // A 32-bit Java on a 64-bit system cannot hold the pack's heap; the managed 64-bit runtime is used instead.
        if (runtime.getBits() != Platform.SYSTEM_PLATFORM.getBits())
            throw new IOException("This pack requires a 64-bit Java 17 runtime");
        return runtime;
    }

    /// Applies the explicit source to both remote version metadata and payload downloads.
    void setDownloadSource(SaoData.Source source) {
        DownloadSource choice = switch (source) {
            case AUTO -> org.jackhuang.hmcl.util.i18n.LocaleUtils.IS_CHINA_MAINLAND ? DownloadSource.MIRROR : DownloadSource.DEFAULT;
            case BMCLAPI -> DownloadSource.MIRROR;
            case OFFICIAL -> DownloadSource.OFFICIAL;
        };
        provider.setFileSource(choice); provider.setVersionListSource(choice);
    }

    /// Connects live task-graph updates to the engine's download list.
    void observeTasks(BiConsumer<Task<?>, SaoDownloadTasks.Status> observer) { taskObserver = observer; }

    /// Cancels a graph still running when the launcher is closed.
    void close() { modDownloads.close(); taskRunner.close(); }

    /// Runs HMCL's complete task graph, including pre/post execution, and propagates failures to the engine.
    <T> @Nullable T runTask(Task<T> task) throws Exception {
        try (SaoDownloadTasks.Observer observer = new SaoDownloadTasks.Observer(taskObserver) {
            /// Keeps operation-level phase labels alongside the graph-scoped download observer.
            @Override protected void running(Task<?> running) {
                @Nullable String stage = running.getStage() != null ? running.getStage() : running.getInheritedStage();
                if (stage != null && stage.startsWith("hmcl.install.")) {
                    if (stage.contains("neoforge")) progress.accept("安装 NeoForge " + SaoPackManifest.NEOFORGE, -1.0);
                    else if (stage.contains("game") || stage.contains("assets") || stage.contains("libraries"))
                        progress.accept("下载 Minecraft " + SaoPackManifest.MINECRAFT, -1.0);
                }
            }
        }) {
            return taskRunner.run(task, observer);
        }
    }
    /// Repository implementation that has exactly one isolated instance and no HMCL profile settings.
    @NotNullByDefault
    private static final class SaoRepository extends DefaultGameRepository {
        /// Creates an isolated repository at the fixed Minecraft root.
        private SaoRepository(Path directory) { super(directory); }
        /// Uses upstream's official assets/libraries/versions layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path directory) {
            return new DefaultGameRepositoryLayout(directory);
        }
        /// Wraps a repository snapshot member with a permanently isolated run directory.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot,
                GameInstanceID id, GameInstanceManifest manifest, @Nullable Path manifestFile) {
            return new SaoInstance(snapshot, id, manifest, manifestFile);
        }
    }

    /// Snapshot-bound HMCL game instance whose run directory always equals its instance root.
    @NotNullByDefault
    private static final class SaoInstance extends DefaultGameInstance {
        /// Constructs one immutable snapshot member, preserving a nonstandard manifest path if present.
        private SaoInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                            GameInstanceManifest manifest, @Nullable Path manifestFile) {
            super(snapshot, id, manifest, manifestFile);
        }
        /// Preserves the manifest and path when republishing a repository snapshot.
        @Override protected DefaultGameInstance withNewSnapshot(DefaultGameRepositorySnapshot snapshot) {
            return new SaoInstance(snapshot, getId(), getManifest(), getManifestFile());
        }
        /// Copies this instance with the replacement manifest in the new snapshot.
        @Override protected DefaultGameInstance withManifest(DefaultGameRepositorySnapshot snapshot, GameInstanceManifest manifest) {
            return new SaoInstance(snapshot, getId(), manifest, getManifestFile());
        }
        /// Keeps options, saves, screenshots, skins and every mod in this launcher's fixed instance.
        @Override public Path getRunDirectory() { return getInstanceRoot(); }
    }

}
