/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

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
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskListener;
import org.jetbrains.annotations.Unmodifiable;
import java.util.function.BiConsumer;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
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
    private BiConsumer<Task<?>, Boolean> taskObserver = (task, done) -> { };
    /// Owns task start/cancel ordering, including shutdown races before a graph starts.
    private final SaoTaskRunner taskRunner = new SaoTaskRunner();


    /// Creates the bridge without network, toolkit startup, account login or game launch.
    SaoGameBridge(Path root, BiConsumer<String, Double> progress) {
        this.root = root;
        this.progress = progress;
        repository = new SaoRepository(root.resolve("minecraft"));
        provider = new HMCLDownloadProvider("https://bmclapi2.bangbang93.com");
        DownloadSource source = org.jackhuang.hmcl.util.i18n.LocaleUtils.IS_CHINA_MAINLAND ? DownloadSource.MIRROR : DownloadSource.DEFAULT;
        provider.setFileSource(source);
        provider.setVersionListSource(source);
        dependencies = new DefaultDependencyManager(repository, provider, new DefaultCacheRepository(root.resolve("dependencies")));
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
        SaoUninstaller.rejectSymlinkAncestors(instanceDir());
        Files.deleteIfExists(instanceDir().resolve(SaoOathLaunchTicket.FILE_NAME));
    }

    /// Ensures Java 17, Minecraft 1.20.1, exact NeoForge 47.1.106 and launch dependencies through HMCL.
    void ensureInstalled(boolean repair) throws Exception {
        selectJava();
        repository.refresh();
        @Nullable DefaultGameInstance installed = repository.getSnapshot().findInstance(INSTANCE_ID);
        if (installed == null) {
            progress.accept("安装 Minecraft 1.20.1 / NeoForge 47.1.106", -1.0);
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

    /// Downloads pack dependencies through HMCL's mirror and checksum-aware transfer code.
    void download(SaoPackManifest.PackFile file, Path destination) throws Exception {
        // Full distribution builds carry external Modrinth dependencies as content-addressed resources.
        try (@Nullable java.io.InputStream bundled = SaoGameBridge.class.getResourceAsStream("/assets/sao/files/" + file.sha1())) {
            if (bundled != null) {
                Files.copy(bundled, destination, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        runTask(new FileDownloadTask(provider.getDownloadCandidates(file.downloads()), destination,
                new FileDownloadTask.IntegrityCheck("SHA-1", file.sha1())));
    }

    /// Authenticates an existing supported HMCL account, starts the fixed instance and hands off stdin once.
    /// Password ownership transfers to this method; it is wiped even when authentication or process creation fails.
    void launch(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window, String jvmArgs, char @Nullable [] password, boolean offline,
                java.util.function.BooleanSupplier cancelled, ProcessListener listener) throws Exception {
        @Nullable SaoOathLaunchTicket launchTicket = null;
        boolean started = false;
        try {
            if (!(account instanceof OfflineAccount) && !(account instanceof MicrosoftAccount) && !(account instanceof SaoOathLaunchAccount))
                throw new IOException("Unsupported launcher account");
            boolean handoff = !offline && !(account instanceof SaoOathLaunchAccount) && !manifest.server().isEmpty();
            if (!handoff && password != null) Arrays.fill(password, '\0');
            if (handoff && password != null && password.length > SaoAuthHandoff.MAX_PASSWORD_CHARS)
                throw new IOException("sao_auth password is too long");
            repository.refresh();
            DefaultGameInstance instance = repository.getInstance(INSTANCE_ID);
            validateInstance(instance);
            if (offline) clearOfflineTicket();
            LaunchPlan plan = launchPlan(account, manifest, memoryMiB, window, jvmArgs, selectJava(), instanceDir(), offline);
            if (cancelled.getAsBoolean()) throw new IOException("Launcher has closed");
            if (!offline && account instanceof SaoOathLaunchAccount oath) launchTicket = oath.ticket(instanceDir(), plan.server());
            if (cancelled.getAsBoolean()) throw new IOException("Launcher has closed");
            ManagedProcess process = new HMCLGameLauncher(instance, instance.getResolvedManifest(), plan.auth(), plan.options(), listener).launch();
            started = true;
            @Nullable SaoOathLaunchTicket cleanup = launchTicket;
            if (cleanup != null) process.getProcess().onExit().thenRun(() -> { try { cleanup.close(); } catch (IOException ignored) { } });
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
    static LaunchPlan launchPlan(Account account, SaoPackManifest manifest, int memoryMiB, SaoData.GameWindow window,
                                 String jvmArgs, JavaRuntime runtime, Path instance, boolean offline) throws Exception {
        if (offline && (account.getClass() != OfflineAccount.class || ((OfflineAccount) account).getSkin() != null))
            throw new IOException("Offline play requires a plain offline account");
        AuthInfo auth = account.logIn();
        LaunchOptions.Builder options = new LaunchOptions.Builder().setInstanceId(INSTANCE_ID)
                .setGameDir(instance).setJava(runtime).setMaxMemory(memoryMiB)
                .setVersionName(INSTANCE_ID.toString()).setVersionType("SAO Oath")
                .setProfileName(account.getProfileName()).setDisableAutoGameOptions(true)
                .setWidth(window.width()).setHeight(window.height()).setFullscreen(window.fullscreen())
                .setJavaArguments(org.jackhuang.hmcl.util.StringUtils.tokenize(jvmArgs));
        String server = offline ? "" : manifest.server().isEmpty() && account instanceof SaoOathLaunchAccount
                ? "mc.saooath.com" : manifest.server();
        if (!server.isEmpty()) options.setQuickPlayOption(new QuickPlayOption.MultiPlayer(server));
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
        @Nullable Path selected = selectedJava;
        if (selected != null) return validateJava(selected, selected.startsWith(root.resolve("java")));
        if (java != null && Files.isRegularFile(java.getBinary())) return java;
        @Unmodifiable List<JavaRuntime> available = availableJava();
        if (!available.isEmpty()) { java = available.get(0); return java; }
        return downloadJava();
    }

    /// Sets an explicit choice only after checking its executable version and architecture.
    void setJava(@Nullable Path executable) throws Exception {
        if (executable != null) validateJava(executable, executable.toAbsolutePath().normalize().startsWith(root.resolve("java")));
        selectedJava = executable == null ? null : executable.toRealPath();
        java = null;
    }

    /// Restores a persisted choice without running an executable on the JavaFX thread; launch validates it again.
    void restoreJava(@Nullable Path executable) { selectedJava = executable; java = null; }

    /// Downloads only Java 17 through HMCL's runtime repository and chosen provider.
    JavaRuntime downloadJava() throws Exception {
        progress.accept("下载 Java 17", -1.0);
        JavaRuntime downloaded = Objects.requireNonNull(runTask(new HMCLJavaRepository(root.resolve("java"))
                .getDownloadJavaTask(provider, Platform.SYSTEM_PLATFORM, GameJavaVersion.get(17))), "Java download returned no runtime");
        if (downloaded.getParsedVersion() != 17) throw new IOException("Downloaded Java runtime is not Java 17");
        java = downloaded;
        return downloaded;
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
        candidates.addAll(new HMCLJavaRepository(root.resolve("java")).getAllJava(Platform.SYSTEM_PLATFORM));
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
    void observeTasks(BiConsumer<Task<?>, Boolean> observer) { taskObserver = observer; }

    /// Cancels a graph still running when the launcher is closed.
    void close() { taskRunner.close(); }

    /// Runs HMCL's complete task graph, including pre/post execution, and propagates failures to the engine.
    <T> @Nullable T runTask(Task<T> task) throws Exception {
        Map<Task<?>, javafx.beans.value.ChangeListener<Number>> listeners = new java.util.concurrent.ConcurrentHashMap<>();
        TaskListener observer = new TaskListener() {
            /// Observes actual progress changes from each HMCL task, without creating fake transfer records.
            @Override public void onRunning(Task<?> running) {
                if (!(running instanceof org.jackhuang.hmcl.task.FetchTask)) return;
                javafx.beans.value.ChangeListener<Number> listener = (observable, oldValue, newValue) -> taskObserver.accept(running, false);
                listeners.put(running, listener); running.progressProperty().addListener(listener);
                taskObserver.accept(running, false);
            }
            /// Removes the listener when a task finishes and marks its immutable record complete.
            @Override public void onFinished(Task<?> finished) {
                @Nullable javafx.beans.value.ChangeListener<Number> listener = listeners.remove(finished);
                if (listener != null) { finished.progressProperty().removeListener(listener); taskObserver.accept(finished, true); }
            }
        };
        try { return taskRunner.run(task, observer); }
        finally {
            listeners.forEach((running, listener) -> running.progressProperty().removeListener(listener));
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
