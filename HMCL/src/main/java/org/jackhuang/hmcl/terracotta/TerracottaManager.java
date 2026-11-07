/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.terracotta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoTaskRunner;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.terracotta.provider.AbstractTerracottaProvider;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.List;
import org.jackhuang.hmcl.task.TaskListener;

/// HMCL Terracotta transport, started only by the dedicated SAO host/join actions.
/// Reading state or constructing the launcher never initializes providers, downloads, scans, or child processes.
@NotNullByDefault
public final class TerracottaManager {
    /// Legacy read-only state remains inert until a dedicated room is explicitly requested.
    private static final ReadOnlyObjectWrapper<TerracottaState> STATE = new ReadOnlyObjectWrapper<>(new TerracottaState.Uninitialized(false));
    /// Current explicitly requested session, guarded by this class's monitor.
    private static @Nullable RoomSession controller;
    /// Session factory is inert until host/join; tests replace it with a network-free lifecycle fixture.
    private static SessionFactory sessionFactory = Controller::new;
    /// Invalidates asynchronous room callbacks after leave or replacement.
    private static long generation;
    /// Prevents construction.
    private TerracottaManager() { }

    /// Returns state without accessing TerracottaMetadata or constructing a worker.
    public static ReadOnlyObjectProperty<TerracottaState> stateProperty() { return STATE.getReadOnlyProperty(); }
    /// Legacy UI page visibility cannot start or resume a transport.
    public static void switchDaemon(boolean active) { }
    /// Legacy bundle pickers remain disabled; only the dedicated room action installs verified upstream bundles.
    public static boolean isInvalidBundle(Path file) { return true; }
    /// Legacy UI cannot download eagerly.
    public static TerracottaState.@Nullable Preparing download() { return null; }
    /// Legacy UI cannot install a package or start a process.
    public static TerracottaState.@Nullable Preparing install(Path bundle) { return null; }
    /// Legacy recovery is side-effect-free; host/join retries create a new session after leave.
    public static TerracottaState recover() { return STATE.get(); }
    /// Returns no remote logs through legacy UI; the SAO game-log export is a separate sanitized path.
    public static Task<@Nullable String> exportLogs() { return Task.completed(null); }
    /// Legacy navigation cannot send control requests.
    public static TerracottaState.@Nullable Waiting setWaiting() { return null; }
    /// LAN scanning is started only by the dedicated host action.
    public static TerracottaState.@Nullable HostScanning setScanning() { return null; }
    /// Joining is started only by the dedicated join action.
    public static @Nullable Task<TerracottaState.GuestConnecting> setGuesting(String room) { return null; }

    /// Creates one lazy room session; null code hosts, otherwise it joins the supplied room.
    public static synchronized void startRoom(@Nullable String code, Consumer<SaoData.Room> changed, Consumer<String> error) {
        startRoom(code, changed, error, (task, done) -> { });
    }

    /// Starts a room and forwards real package-transfer progress to the launcher's downloads window.
    public static synchronized void startRoom(@Nullable String code, Consumer<SaoData.Room> changed, Consumer<String> error,
                                              BiConsumer<Task<?>, Boolean> downloads) {
        if (controller != null) { error.accept("请先离开当前联机房间"); return; }
        long requested = ++generation;
        controller = sessionFactory.create(code, value -> {
            synchronized (TerracottaManager.class) { if (generation == requested && controller != null) changed.accept(value); }
        }, message -> {
            synchronized (TerracottaManager.class) { if (generation == requested && controller != null) error.accept(message); }
        });
        controller.observeDownloads(downloads);
        controller.start();
    }

    /// Cancels download/task work, closes the local control tunnel, and terminates the owned process.
    public static synchronized void stopRoom() {
        generation++;
        @Nullable RoomSession previous = controller;
        controller = null;
        if (previous != null) previous.close();
    }

    /// Indicates whether a room was explicitly requested; useful for lifecycle checks without provider initialization.
    public static synchronized boolean hasRequestedRoom() { return controller != null; }

    /// One owned transport lifecycle; the package-private seam supports deterministic tests without a real tunnel.
    @NotNullByDefault
    interface RoomSession {
        /// Starts only after a host/join action has reserved this session.
        void start();
        /// Observes package transfers; lifecycle fixtures without downloads can keep the default no-op.
        default void observeDownloads(BiConsumer<Task<?>, Boolean> observer) { }
        /// Cancels pending work and terminates the session's process.
        void close();
    }

    /// Constructs transport sessions without making provider access part of class initialization.
    @NotNullByDefault
    @FunctionalInterface
    interface SessionFactory {
        /// Creates a dormant session for the requested operation.
        RoomSession create(@Nullable String code, Consumer<SaoData.Room> changed, Consumer<String> error);
    }

    /// Installs a fixture only while no room is active; passing null restores the production transport.
    static synchronized void useSessionFactory(@Nullable SessionFactory factory) {
        if (controller != null) throw new IllegalStateException("Cannot replace an active transport");
        sessionFactory = factory == null ? Controller::new : factory;
    }

    /// Converts the upstream transport state into a plain immutable screen record.
    static SaoData.Room describe(TerracottaState state, @Nullable String code) {
        if (state instanceof TerracottaState.HostOK host)
            return new SaoData.Room(SaoData.RoomState.HOSTING, host.getCode(), host.getProfiles().size(), "把房间码发给朋友");
        if (state instanceof TerracottaState.GuestOK guest)
            return new SaoData.Room(SaoData.RoomState.JOINED, code, guest.getProfiles().size(), "联机地址：" + guest.getUrl());
        if (state instanceof TerracottaState.Exception failure)
            return new SaoData.Room(SaoData.RoomState.FAILED, code, 0, "联机失败：" + failure.getType());
        if (state instanceof TerracottaState.Fatal failure)
            return new SaoData.Room(SaoData.RoomState.FAILED, code, 0, "联机失败：" + failure.getType());
        return new SaoData.Room(code == null ? SaoData.RoomState.PREPARING : SaoData.RoomState.JOINING,
                code, code == null ? 1 : 0, code == null ? "请启动世界并对局域网开放" : "连接中");
    }

    /// Marshals legacy property updates to JavaFX while allowing explicit headless lifecycle tests.
    private static void publish(Runnable action) {
        if (Platform.isFxApplicationThread()) { action.run(); return; }
        try { Platform.runLater(action); }
        catch (IllegalStateException noToolkit) { action.run(); }
    }

    /// One explicitly requested transport session, including its cancellable installer and process ownership.
    @NotNullByDefault
    private static final class Controller implements RoomSession {
        /// Requested room code, null when hosting.
        private final @Nullable String code;
        /// Publishes screen-facing records through the engine.
        private final Consumer<SaoData.Room> changed;
        /// Reports a short failure without logging control URLs or account information.
        private final Consumer<String> error;
        /// Session-specific worker is created only on host/join and stops on leave/shutdown.
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "sao-terracotta-room"); thread.setDaemon(true); return thread;
        });
        /// Own process, destroyed even when leave races startup.
        private volatile @Nullable Process process;
        /// Owns installer start/cancel ordering; created only after an explicit host/join request.
        private final SaoTaskRunner taskRunner = new SaoTaskRunner();
        /// Live bundle transfer observer, installed before this session starts.
        private BiConsumer<Task<?>, Boolean> downloads = (task, done) -> { };
        /// Explicit cancellation fence checked before downloads, child startup and each poll.
        private volatile boolean closed;
        /// Temporary local control-port transfer directory, removed after shutdown.
        private volatile @Nullable Path transfer;
        /// Local HTTP client; no public request is sent by its construction.
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        /// Captures callbacks and operation intent without loading transport metadata.
        private Controller(@Nullable String code, Consumer<SaoData.Room> changed, Consumer<String> error) {
            this.code = code; this.changed = changed; this.error = error;
        }

        /// Connects this session's actual HMCL download graph to the engine's immutable task rows.
        @Override public void observeDownloads(BiConsumer<Task<?>, Boolean> observer) { downloads = observer; }

        /// Starts the serialized session worker after publishing the initial room record.
        @Override public void start() {
            changed.accept(new SaoData.Room(code == null ? SaoData.RoomState.PREPARING : SaoData.RoomState.JOINING,
                    code, 0, "准备联机通道"));
            worker.execute(() -> {
                try { run(); }
                catch (Exception failure) {
                    if (!closed) {
                        changed.accept(new SaoData.Room(SaoData.RoomState.FAILED, code, 0, "联机通道失败，请离开后重试"));
                        error.accept("联机通道失败（" + failure.getClass().getSimpleName() + "）");
                    }
                } finally { stopProcess(); }
            });
        }

        /// Prepares the checksum-verified HMCL bundle, starts the child, requests host/join and polls its state.
        private void run() throws Exception {
            checkOpen();
            @Nullable AbstractTerracottaProvider provider = TerracottaMetadata.PROVIDER;
            if (provider == null) throw new IOException("Terracotta is unavailable on this platform");
            if (provider.status() != AbstractTerracottaProvider.Status.READY) {
                Path bundle = java.util.Objects.requireNonNull(execute(provider.download(new AbstractTerracottaProvider.DownloadContext() {
                    /// Download progress stays in HMCL's task graph; no UI dialog is created here.
                    @Override public void bindProgress(javafx.beans.value.ObservableDoubleValue progress) { }
                    /// Aborts package transfer immediately after leave/shutdown.
                    @Override public void checkCancellation() { if (closed) throw new CancellationException(); }
                })), "Terracotta download returned no bundle");
                try { checkOpen(); execute(provider.install(bundle)); }
                finally { Files.deleteIfExists(bundle); }
            }
            checkOpen();
            Path directory = Files.createTempDirectory("sao-terracotta-"); transfer = directory;
            Path portFile = directory.resolve("http");
            Process started;
            synchronized (this) {
                checkOpen();
                started = new ProcessBuilder(provider.ofCommandLine(portFile))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process = started;
            }
            if (closed) { started.destroyForcibly(); throw new CancellationException(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.isRegularFile(portFile)) {
                checkOpen();
                if (!started.isAlive() || System.nanoTime() >= deadline) throw new IOException("Terracotta did not publish its control port");
                Thread.sleep(50);
            }
            int port = JsonParser.parseString(Files.readString(portFile)).getAsJsonObject().get("port").getAsInt();
            if (port < 1 || port > 65535) throw new IOException("Invalid Terracotta control port");
            checkOpen();
            StringBuilder query = new StringBuilder("player=").append(encode("SAO Player"));
            if (code != null) query.append("&room=").append(encode(code));
            for (var node : TerracottaNodeList.fetch()) query.append("&public_nodes=").append(encode(node.toString()));
            checkOpen();
            get(port, code == null ? "/state/scanning?" + query : "/state/guesting?" + query);
            while (!closed) {
                if (!started.isAlive()) throw new IOException("Terracotta process exited");
                JsonObject json = JsonParser.parseString(get(port, "/state")).getAsJsonObject();
                TerracottaState state = JsonUtils.fromNonNullJson(json.toString(), TerracottaState.Ready.class);
                publish(() -> { if (!closed) STATE.set(state); });
                if (!closed) changed.accept(describe(state, code));
                Thread.sleep(500);
            }
        }

        /// Sends only bounded loopback control requests; no credentials are included.
        private String get(int port, String path) throws Exception {
            checkOpen();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 1048576) throw new IOException("Invalid Terracotta control response");
            return response.body();
        }

        /// Runs HMCL's verified package download/installer with cancellation ownership.
        private <T> @Nullable T execute(Task<T> operation) throws Exception {
            checkOpen();
            Map<Task<?>, javafx.beans.value.ChangeListener<Number>> listeners = new java.util.concurrent.ConcurrentHashMap<>();
            TaskListener observer = new TaskListener() {
                /// Observes only actual HMCL FetchTask instances, including Terracotta's verified bundle transfer.
                @Override public void onRunning(Task<?> running) {
                    if (!(running instanceof org.jackhuang.hmcl.task.FetchTask)) return;
                    javafx.beans.value.ChangeListener<Number> listener = (property, oldValue, newValue) -> downloads.accept(running, false);
                    listeners.put(running, listener); running.progressProperty().addListener(listener); downloads.accept(running, false);
                }
                /// Removes progress listeners when a real transfer finishes or fails.
                @Override public void onFinished(Task<?> finished) {
                    @Nullable javafx.beans.value.ChangeListener<Number> listener = listeners.remove(finished);
                    if (listener != null) { finished.progressProperty().removeListener(listener); downloads.accept(finished, true); }
                }
            };
            try { return taskRunner.run(operation, observer); }
            finally { listeners.forEach((running, listener) -> running.progressProperty().removeListener(listener)); }
        }

        /// Converts a query value without invoking a shell or exposing account objects.
        private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

        /// Prevents a late asynchronous completion from launching a process after leave.
        private void checkOpen() { if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException(); }

        /// Terminates only the process started by this room and removes its temporary port-transfer files.
        private void stopProcess() {
            @Nullable Process current = process; process = null;
            if (current != null) {
                @Unmodifiable List<ProcessHandle> descendants = current.descendants().toList();
                descendants.forEach(ProcessHandle::destroy); current.destroy();
                try { if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly(); }
                catch (InterruptedException interrupted) { current.destroyForcibly(); Thread.currentThread().interrupt(); }
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            }
            @Nullable Path directory = transfer; transfer = null;
            if (directory != null) {
                try { org.jackhuang.hmcl.util.io.FileUtils.deleteDirectory(directory); }
                catch (IOException ignored) { /* Shutdown never blocks on a stale temporary control file. */ }
            }
        }

        /// Cancels the installer and worker first, then tears down the session's owned child process.
        @Override public void close() {
            @Nullable Process current;
            synchronized (this) { closed = true; current = process; }
            taskRunner.close();
            if (current != null) { current.descendants().forEach(ProcessHandle::destroy); current.destroy(); }
            // The worker's finally block waits for teardown; the JavaFX caller returns immediately.
            worker.shutdownNow();
        }
    }
}
