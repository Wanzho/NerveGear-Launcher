/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import javafx.application.Platform;
import javafx.beans.property.*;
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
/// Uses only its fixed repository and publishes observable changes on JavaFX when a toolkit exists.
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
    /// Volatile memory choice used by launch work.
    private volatile int memoryMiB = 4096;

    /// Creates the engine under the launcher's isolated local storage.
    public SaoPackEngine() { this(Metadata.HMCL_LOCAL_HOME); }

    /// Creates an isolated backend, also usable by headless proof tools without toolkit or windows.
    public SaoPackEngine(Path dataRoot) { this(dataRoot, null, true); }

    /// Injects a release in headless tests without consulting resources or development properties.
    SaoPackEngine(Path dataRoot, @Nullable SaoPackArchive supplied, boolean loadEmbedded) {
        root = dataRoot.toAbsolutePath().normalize();
        game = new SaoGameBridge(root, this::reportProgress);
        installer = new SaoPackInstaller(game.instanceDir(), game::download, Map.of(), this::reportProgress);
        skins = new SaoSkinStore(game.instanceDir());
        worker.execute(() -> {
            try {
                @Nullable String developmentPack = System.getProperty("sao.pack");
                source = !loadEmbedded ? supplied : developmentPack == null || developmentPack.isBlank()
                        ? SaoPackArchive.embedded(root.resolve("pack-cache")) : SaoPackArchive.open(Path.of(developmentPack));
                if (source != null) embeddedVersion = source.manifest().version();
                @Nullable SaoPackArchive accepted = readAcceptedSource();
                if (accepted != null && (source == null
                        || VersionNumber.compare(accepted.manifest().version(), source.manifest().version()) >= 0)) source = accepted;
                refreshState();
            } catch (Exception failure) { fail("读取整合包失败", failure); }
            finally { busy.set(false); }
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
        if (closed.get()) {
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
                publish(() -> { installedVersion.set(pack.manifest().version()); step.set("Link Start"); state.set(SaoPackState.RUNNING); });
                if (closed.get()) throw new IOException("Launcher has closed");
                game.launch(account, pack.manifest(), memoryMiB, password, closed::get, () -> {
                    busy.set(false);
                    try { refreshState(); } catch (IOException failure) { fail("读取整合包状态失败", failure); }
                });
                // Busy remains true for the running process; its monitor releases it on exit.
            } catch (Exception failure) {
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
            refreshState();
        });
    }

    /// {@inheritDoc}
    @Override public void setSkin(@Nullable Path png, boolean slim) {
        try { skins.set(png, slim); }
        catch (IOException failure) { throw new IllegalArgumentException("无法保存皮肤: " + failure.getMessage(), failure); }
    }
    /// {@inheritDoc}
    @Override public @Nullable Path skin() { return skins.skin(); }
    /// {@inheritDoc}
    @Override public boolean skinSlim() { return skins.slim(); }
    /// {@inheritDoc}
    @Override public int memoryMiB() { return memoryMiB; }
    /// {@inheritDoc}
    @Override public void setMemoryMiB(int mib) {
        long freeBytes = new org.jackhuang.hmcl.util.platform.hardware.HardwareDetector().getFreeMemorySize();
        long maximum = freeBytes > 0 ? Math.max(2048, freeBytes / (1024 * 1024)) : 262144;
        if (mib < 2048 || mib > maximum) throw new IllegalArgumentException("Memory must be between 2048 MiB and available system memory");
        memoryMiB = mib;
    }
    /// {@inheritDoc}
    @Override public Path instanceDir() { return game.instanceDir(); }

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
        if (closed.get() || !busy.compareAndSet(false, true)) return;
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
        if (Boolean.getBoolean("java.awt.headless")) { action.run(); return; }
        if (Platform.isFxApplicationThread()) { action.run(); return; }
        try { Platform.runLater(action); }
        catch (IllegalStateException uninitializedToolkit) { action.run(); }
    }
    /// Stops accepting backend work; does not terminate an already running Minecraft process.
    @Override public void close() {
        closed.set(true);
        char @Nullable [] password = activePassword;
        if (password != null) Arrays.fill(password, '\0');
        activePassword = null;
        worker.shutdownNow();
    }
}
