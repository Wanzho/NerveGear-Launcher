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
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

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
    /// Explicit BMCLAPI-first provider with official fallbacks, including China downloads.
    private final HMCLDownloadProvider provider;
    /// HMCL dependency manager scoped to this repository and cache.
    private final DefaultDependencyManager dependencies;
    /// Current operation step callback; -1 means indeterminate download progress.
    private final BiConsumer<String, Double> progress;
    /// Selected compatible Java 17, cached only after verification.
    private @Nullable JavaRuntime java;

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
    void launch(Account account, SaoPackManifest manifest, int memoryMiB, char @Nullable [] password,
                java.util.function.BooleanSupplier cancelled, Runnable exited) throws Exception {
        try {
            if (!(account instanceof OfflineAccount) && !(account instanceof MicrosoftAccount))
                throw new IOException("Only existing offline and Microsoft accounts are supported");
            boolean handoff = !manifest.server().isEmpty();
            if (!handoff && password != null) Arrays.fill(password, '\0');
            if (handoff && password != null && password.length > SaoAuthHandoff.MAX_PASSWORD_CHARS)
                throw new IOException("sao_auth password is too long");
            repository.refresh();
            DefaultGameInstance instance = repository.getInstance(INSTANCE_ID);
            validateInstance(instance);
            AuthInfo auth = account.logIn();
            if (cancelled.getAsBoolean()) throw new IOException("Launcher has closed");
            LaunchOptions.Builder options = new LaunchOptions.Builder().setInstanceId(INSTANCE_ID)
                    .setGameDir(instanceDir()).setJava(selectJava()).setMaxMemory(memoryMiB)
                    .setVersionName("SAO Oath " + manifest.version()).setVersionType("SAO Oath")
                    .setProfileName(account.getProfileName()).setDisableAutoGameOptions(true);
            if (!manifest.server().isEmpty()) options.setQuickPlayOption(new QuickPlayOption.MultiPlayer(manifest.server()));
            ProcessListener listener = new ProcessListener() {
                /// Discards game output here; neither credentials nor command lines enter launcher logs.
                @Override public void onLog(String line, boolean error) { }
                /// Returns the service to an idle state when HMCL's process monitor observes exit.
                @Override public void onExit(int code, ExitType type) { exited.run(); }
            };
            if (cancelled.getAsBoolean()) throw new IOException("Launcher has closed");
            ManagedProcess process = new HMCLGameLauncher(instance, instance.getResolvedManifest(), auth, options.create(), listener).launch();
            try {
                SaoAuthHandoff.writeAndClose(process.getProcess().getOutputStream(), handoff ? password : null);
            } catch (IOException failure) {
                process.getProcess().destroy();
                throw failure;
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
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

    /// Finds a compatible local Java 17 or downloads Java 17 through HMCL into this launcher's runtime folder.
    private JavaRuntime selectJava() throws Exception {
        if (java != null) return java;
        progress.accept("选择 Java 17", -1.0);
        Set<Path> candidates = new LinkedHashSet<>();
        @Nullable String override = System.getProperty("sao.java");
        if (override != null && !override.isBlank()) candidates.add(Path.of(override));
        candidates.add(JavaRuntime.CURRENT_JAVA.getBinary());
        if (org.jackhuang.hmcl.java.JavaManager.isInitialized()) {
            for (JavaRuntime installed : org.jackhuang.hmcl.java.JavaManager.getAllJava()) candidates.add(installed.getBinary());
        }
        candidates.add(Path.of("/opt/homebrew/opt/openjdk@17/bin/java"));
        candidates.add(Path.of("/usr/local/opt/openjdk@17/bin/java"));
        @Nullable String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome, "bin", OperatingSystem.CURRENT_OS.getJavaExecutable()));
        HMCLJavaRepository runtimes = new HMCLJavaRepository(root.resolve("java"));
        candidates.addAll(runtimes.getAllJava(Platform.SYSTEM_PLATFORM));
        List<Path> homes = new ArrayList<>();
        for (Path directory : List.of(Path.of("/Library/Java/JavaVirtualMachines"), Path.of("/usr/lib/jvm"))) {
            if (!Files.isDirectory(directory)) continue;
            try (var children = Files.list(directory)) { homes.addAll(children.toList()); }
        }
        for (Path home : homes) {
            candidates.add(home.resolve("bin/java"));
            candidates.add(home.resolve("Contents/Home/bin/java"));
        }
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try {
                JavaRuntime runtime = JavaRuntime.of(candidate.toRealPath(), JavaInfoUtils.fromExecutable(candidate), false);
                if (runtime.getParsedVersion() == 17 && org.jackhuang.hmcl.java.JavaManager.isCompatible(runtime.getPlatform())) {
                    java = runtime;
                    return runtime;
                }
            } catch (IOException ignored) { /* Try other installed runtimes before downloading. */ }
        }
        progress.accept("下载 Java 17", -1.0);
        java = runTask(runtimes.getDownloadJavaTask(provider, Platform.SYSTEM_PLATFORM, GameJavaVersion.get(17)));
        if (java.getParsedVersion() != 17) throw new IOException("Downloaded Java runtime is not Java 17");
        return java;
    }

    /// Runs HMCL's complete task graph, including pre/post execution, and propagates failures to the engine.
    private static <T> T runTask(Task<T> task) throws Exception {
        TaskExecutor executor = task.executor();
        if (!executor.test()) {
            if (Thread.currentThread().isInterrupted()) executor.cancel();
            @Nullable Exception exception = executor.getException();
            if (exception != null) throw exception;
            throw new IOException("HMCL download or installation was cancelled");
        }
        return task.getResult();
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
