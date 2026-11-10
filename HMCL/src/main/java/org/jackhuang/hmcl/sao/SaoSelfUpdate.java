/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/// Verified per-user JAR replacement with content-addressed payloads, startup acknowledgement and rollback.
/// Native applications and their bundled runtimes are never modified by this service.
@NotNullByDefault
public final class SaoSelfUpdate {
    /// Hard expansion bound for a thin launcher JAR, including a manually downloaded ZIP wrapper.
    static final long MAX_JAR_BYTES = 256L * 1024 * 1024;
    /// Full-download instruction used below the minimum or for native/runtime changes.
    static final String FULL_LAUNCHER_REQUIRED = "请从夸克下载完整启动器并重新打开";
    /// Storage root belongs to the launcher's per-user home, independently of the portable game instance.
    private final Path root;

    /// One verified, content-addressed thin JAR; its path is always derived from sha256 under root.
    /// @param version declared /assets/hmcl.properties hmcl.version
    /// @param sha256 lowercase payload digest
    /// @param size complete JAR bytes
    @NotNullByDefault
    record Artifact(String version, String sha256, long size) { }

    /// Atomic state pointer; null active/previous selects the immutable bundled JAR.
    /// @param active selected per-user JAR
    /// @param previous previously healthy per-user JAR
    /// @param staged verified JAR awaiting a restart
    /// @param attempt unpredictable startup acknowledgement token
    /// @param owner process responsible for an outstanding attempt
    /// @param deadline epoch milliseconds after which an outstanding attempt is abandoned
    /// @param message last public update or rollback result
    @NotNullByDefault
    private record State(@Nullable Artifact active, @Nullable Artifact previous, @Nullable Artifact staged,
                         @Nullable String attempt, long owner, long deadline, String message) { }

    /// Bootstrap selection; token is nonnull only for the first launch of a staged replacement.
    /// @param jar verified launch source
    /// @param version manifest version
    /// @param token startup acknowledgement token
    @NotNullByDefault
    record Selection(Path jar, String version, @Nullable String token) { }

    /// Uses an explicit isolated data directory; production passes defaultRoot().
    public SaoSelfUpdate(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /// Native shells and the running launcher resolve exactly the same per-user update storage.
    static Path defaultRoot() { return Metadata.HMCL_USER_HOME.resolve("self-update"); }

    /// Accepts only the engine's authenticated release policy, then validates compressed bytes and the exact runnable thin JAR.
    /// Minimum-version and native/runtime migrations deliberately require the full native package.
    SaoData.LauncherUpdate stage(Path download, SaoData.VersionMetadata metadata, String runningVersion) throws IOException {
        SaoData.LauncherRelease release = metadata.launcher();
        @Nullable SaoData.LauncherArtifact identity = release.artifact();
        if (SaoUpdates.compare(runningVersion, release.min()) < 0 || release.nativeRequired() || identity == null)
            throw new IOException(FULL_LAUNCHER_REQUIRED);
        if (SaoUpdates.compare(runningVersion, release.latest()) >= 0) throw new IOException("启动器已是当前或更新版本");
        if (identity.size() > MAX_JAR_BYTES) throw new IOException("启动器文件过大，请下载完整启动器");
        return locked(() -> {
            State old = settleHealthy(read());
            if (old.attempt() != null) throw new IOException("请等待当前启动器更新完成");
            Path snapshot = SaoUpdates.snapshot(download, identity.size(), identity.sha256(), root);
            Path jar = Files.createTempFile(root, "launcher-", ".jar");
            try {
                if (identity.file().endsWith(".zip")) unwrap(snapshot, jar);
                else Files.copy(snapshot, jar, StandardCopyOption.REPLACE_EXISTING);
                validateJar(jar, release.latest());
                Artifact artifact = new Artifact(release.latest(), SaoUpdates.sha256(jar), Files.size(jar));
                Path retained = artifactPath(artifact);
                SaoPackFiles.atomicMove(jar, retained);
                write(new State(old.active(), old.previous(), artifact, null, 0, 0, "启动器更新已校验，重新打开启动器后生效"));
                return status();
            } finally { Files.deleteIfExists(snapshot); Files.deleteIfExists(jar); }
        });
    }

    /// Rejects extra wrapper members, traversal and unbounded expansion; one portable basename JAR is allowed.
    private static void unwrap(Path archive, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() != 1) throw new IOException("启动器更新 ZIP 必须只包含一个 JAR");
            ZipEntry entry = zip.entries().nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".jar") || entry.getSize() <= 0 || entry.getSize() > MAX_JAR_BYTES)
                throw new IOException("启动器更新 ZIP 无效");
            SaoUpdates.launcherFilename(entry.getName());
            try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                SaoQuickUpdate.copy(input, output, entry.getSize());
            }
        }
    }

    /// Validates the executable entry, exact release version and the bootstrap protocol before accepting a JAR.
    static void validateJar(Path jar, String version) throws IOException {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.size(jar) <= 0 || Files.size(jar) > MAX_JAR_BYTES)
            throw new IOException("启动器 JAR 无效");
        try (JarFile archive = new JarFile(jar.toFile(), true)) {
            @Nullable Manifest manifest = archive.getManifest();
            if (manifest == null || !"org.jackhuang.hmcl.Main".equals(manifest.getMainAttributes().getValue("Main-Class")))
                throw new IOException("启动器入口不匹配");
            @Nullable JarEntry propertiesEntry = archive.getJarEntry("assets/hmcl.properties");
            if (propertiesEntry == null || propertiesEntry.getSize() < 0 || propertiesEntry.getSize() > 65536)
                throw new IOException("启动器版本信息缺失");
            Properties properties = new Properties();
            try (InputStream input = archive.getInputStream(propertiesEntry)) {
                byte[] bytes = input.readNBytes(65537);
                if (bytes.length > 65536) throw new IOException("启动器版本信息过大");
                properties.load(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            }
            @Nullable String implementation = manifest.getMainAttributes().getValue("Implementation-Version");
            if (!version.equals(properties.getProperty("hmcl.version")) || implementation != null && !version.equals(implementation))
                throw new IOException("启动器版本不匹配");
            for (String entry : List.of("org/jackhuang/hmcl/Main.class", "org/jackhuang/hmcl/Launcher.class",
                    "org/jackhuang/hmcl/sao/SaoLauncherBootstrap.class", "org/jackhuang/hmcl/sao/SaoSelfUpdate.class")) {
                if (archive.getJarEntry(entry) == null) throw new IOException("启动器 JAR 缺少必要入口");
            }
            if (archive.getJarEntry("assets/sao/pack.zip") != null) throw new IOException("请使用不含模组的启动器更新");
            Set<String> names = new HashSet<>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) if (!names.add(entries.nextElement().getName())) throw new IOException("Duplicate launcher JAR member");
        }
    }

    /// Selects a verified update, rolls back an abandoned startup, and starts a fresh acknowledgement attempt.
    Selection select(Path bundled, String bundledVersion) throws IOException {
        return locked(() -> {
            State state = settleHealthy(readForBootstrap());
            if (state.attempt() != null) {
                if (System.currentTimeMillis() < state.deadline() && ProcessHandle.of(state.owner()).map(ProcessHandle::isAlive).orElse(false))
                    throw new IOException("另一个启动器正在完成更新，请稍后重试");
                state = rollback(state, "新版启动器未能正常启动，已恢复上一版本");
            }
            if (state.active() != null && !valid(state.active())) state = rollback(state, "启动器更新文件损坏，已恢复上一版本");
            @Nullable Artifact active = state.active();
            if (active != null && SaoUpdates.compare(active.version(), bundledVersion) <= 0) active = null;
            @Nullable Artifact staged = state.staged();
            String current = active == null ? bundledVersion : active.version();
            if (staged != null && SaoUpdates.compare(staged.version(), current) > 0) {
                if (!valid(staged)) {
                    state = new State(active, state.previous(), null, null, 0, 0, "启动器更新文件损坏，请重新下载");
                } else {
                    String token = UUID.randomUUID().toString();
                    write(new State(staged, active, null, token, ProcessHandle.current().pid(), System.currentTimeMillis() + 75000, "正在验证新版启动器启动"));
                    return new Selection(artifactPath(staged), staged.version(), token);
                }
            } else state = new State(active, state.previous(), null, null, 0, 0, state.message());
            write(state);
            return new Selection(active == null ? bundled : artifactPath(active), active == null ? bundledVersion : active.version(), null);
        });
    }

    /// Records the actual child PID so a second shell never rolls back a still-starting child.
    void attach(String token, long pid) throws IOException {
        locked(() -> {
            State state = read();
            if (!token.equals(state.attempt())) throw new IOException("Stale launcher startup attempt");
            write(new State(state.active(), state.previous(), state.staged(), token, pid, state.deadline(), state.message()));
            return Boolean.TRUE;
        });
    }

    /// Checks only the acknowledgement for this attempt; a prior successful startup cannot satisfy it.
    boolean healthy(String token) throws IOException {
        Path marker = marker(token);
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) == token.length()
                && Files.readString(marker).equals(token);
    }

    /// Promotes an acknowledged candidate, or restores the previous healthy selection on failure.
    void finish(String token, boolean healthy) throws IOException {
        locked(() -> {
            State state = read();
            if (!token.equals(state.attempt())) return Boolean.TRUE;
            if (healthy && healthy(token)) write(new State(state.active(), state.previous(), state.staged(), null, 0, 0, "启动器更新成功"));
            else rollback(state, "新版启动器未能正常启动，已恢复上一版本");
            Files.deleteIfExists(marker(token));
            return Boolean.TRUE;
        });
    }

    /// Writes the acknowledgement atomically after the real primary stage has been shown.
    /// Failures are logged and never crash an otherwise initialized launcher.
    public static void markHealthy() {
        @Nullable String token = System.getProperty("sao.selfUpdateAttempt");
        @Nullable String directory = System.getProperty("sao.selfUpdateRoot");
        if (token == null || directory == null) return;
        try { new SaoSelfUpdate(Path.of(directory)).acknowledge(token); }
        catch (Exception failure) { System.err.println("Launcher update acknowledgement failed: " + failure.getClass().getSimpleName()); }
    }

    /// Isolated acknowledgement seam used by startup and headless state-machine tests.
    void acknowledge(String token) throws IOException {
        locked(() -> {
            State state = read();
            if (!token.equals(state.attempt()) || state.active() == null || !valid(state.active())) throw new IOException("Stale launcher acknowledgement");
            atomicText(marker(token), token);
            return Boolean.TRUE;
        });
    }

    /// Returns persisted UI state without selecting or starting any executable.
    public SaoData.LauncherUpdate status() throws IOException {
        State state = read();
        @Nullable Artifact artifact = state.staged() == null ? state.active() : state.staged();
        return new SaoData.LauncherUpdate(artifact == null ? "" : artifact.version(), state.staged() != null, state.message());
    }

    /// Converts a completed marker into committed state even if the watching shell itself was interrupted.
    private State settleHealthy(State state) throws IOException {
        if (state.attempt() == null || !healthy(state.attempt())) return state;
        State settled = new State(state.active(), state.previous(), state.staged(), null, 0, 0, "启动器更新成功");
        write(settled); Files.deleteIfExists(marker(state.attempt())); return settled;
    }

    /// Restores a verified previous JAR, or the immutable bundle when no verified previous JAR remains.
    private State rollback(State state, String message) throws IOException {
        @Nullable Artifact previous = state.previous();
        if (previous != null && !valid(previous)) previous = null;
        State restored = new State(previous, null, null, null, 0, 0, message);
        write(restored);
        if (state.attempt() != null) Files.deleteIfExists(marker(state.attempt()));
        return restored;
    }

    /// Revalidates stored bytes every time native shells select them; cache existence alone is insufficient.
    private boolean valid(Artifact artifact) {
        try {
            Path path = artifactPath(artifact);
            SaoUpdates.verify(path, artifact.size(), artifact.sha256()); validateJar(path, artifact.version()); return true;
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    /// Derives an owned path only from a validated hash, never a remote or locally persisted arbitrary path.
    private Path artifactPath(Artifact artifact) throws IOException {
        if (!artifact.sha256().matches("[0-9a-f]{64}") || artifact.size() <= 0 || artifact.size() > MAX_JAR_BYTES)
            throw new IOException("Invalid launcher update identity");
        SaoUpdates.version(artifact.version());
        return SaoPackFiles.resolve(root, artifact.sha256() + ".jar");
    }

    /// Constrains acknowledgement filenames to UUID-shaped tokens generated by the native bootstrap.
    private Path marker(String token) throws IOException {
        if (!token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IOException("Invalid startup token");
        return SaoPackFiles.resolve(root, token + ".healthy");
    }

    /// Quarantines unreadable local pointers and records a visible bundled fallback instead of blocking startup.
    private State readForBootstrap() throws IOException {
        try { return read(); }
        catch (IOException invalid) {
            Path state = SaoPackFiles.resolve(root, "state.json");
            if (Files.exists(state, LinkOption.NOFOLLOW_LINKS))
                SaoPackFiles.atomicMove(state, SaoPackFiles.resolve(root, "state-corrupt-" + UUID.randomUUID() + ".json"));
            State fallback = new State(null, null, null, null, 0, 0, "启动器更新状态损坏，已使用内置版本");
            write(fallback); return fallback;
        }
    }

    /// Decodes only bounded launcher-owned state; all artifact fields are validated before use.
    private State read() throws IOException {
        Path path = SaoPackFiles.resolve(root, "state.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new State(null, null, null, null, 0, 0, "");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384) throw new IOException("Invalid self-update state");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (json.get("schema").getAsInt() != 1) throw new IOException("Unsupported self-update state");
            @Nullable String token = json.has("attempt") ? json.get("attempt").getAsString() : null;
            if (token != null) marker(token);
            return new State(artifact(json, "active"), artifact(json, "previous"), artifact(json, "staged"), token,
                    json.get("owner").getAsLong(), json.has("deadline") ? json.get("deadline").getAsLong() : 0, json.get("message").getAsString());
        } catch (RuntimeException failure) { throw new IOException("Invalid self-update state", failure); }
    }

    /// Decodes one optional owned artifact and validates numeric versions, size and content-addressed location.
    private @Nullable Artifact artifact(JsonObject json, String key) throws IOException {
        if (!json.has(key)) return null;
        JsonObject entry = json.getAsJsonObject(key);
        Artifact artifact = new Artifact(entry.get("version").getAsString(), entry.get("sha256").getAsString(), entry.get("size").getAsLong());
        artifactPath(artifact); return artifact;
    }

    /// Replaces state atomically; no JAR is overwritten while any runtime may have it open on Windows.
    private void write(State state) throws IOException {
        JsonObject json = new Gson().toJsonTree(state).getAsJsonObject(); json.addProperty("schema", 1);
        atomicText(SaoPackFiles.resolve(root, "state.json"), json.toString());
    }

    /// Writes launcher-owned UTF-8 state with a same-directory atomic replacement.
    private void atomicText(Path path, String text) throws IOException {
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, "state-", ".tmp");
        try { Files.writeString(temporary, text, StandardCharsets.UTF_8); SaoPackFiles.atomicMove(temporary, path); }
        finally { Files.deleteIfExists(temporary); }
    }

    /// Filesystem operation serialized with all bootstrap decisions and staging requests.
    @NotNullByDefault
    @FunctionalInterface
    private interface Action<T> {
        /// Executes while the per-user state lock is held.
        T run() throws IOException;
    }

    /// Uses a short-lived OS lock; a running JAR never blocks later update downloads for its lifetime.
    private <T> T locked(Action<T> action) throws IOException {
        Path lockPath = SaoPackFiles.resolve(root, "state.lock"); Files.createDirectories(root);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("另一个启动器正在处理更新");
            return action.run();
        } catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("另一个启动器正在处理更新", busy); }
    }
}
