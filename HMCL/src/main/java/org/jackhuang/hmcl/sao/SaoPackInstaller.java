/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Installs a complete pack inventory with staged, checksum-verified writes and rollback on commit errors.
/// Does not select Java, install Minecraft, open JavaFX or start a game process.
@NotNullByDefault
public final class SaoPackInstaller {
    /// Receipt is launcher-owned and never supplied by a pack override.
    public static final String RECEIPT = ".sao-pack.json";
    /// Fixed, isolated game directory managed by this installer.
    private final Path instance;
    /// Downloader injected by the HMCL bridge or deterministic tests.
    private final Downloader downloader;
    /// Optional verified baseline source inventory, used only by explicit developer/proof commands.
    private final @Unmodifiable Map<String, Path> baseline;
    /// Progress callback receives an operation label and a fraction, or -1 when unknown.
    private final BiConsumer<String, Double> progress;

    /// Downloads one remote dependency to the staging path; installation validates it afterward.
    @NotNullByDefault
    @FunctionalInterface
    public interface Downloader {
        /// Fetches a source with exact manifest identity into the supplied temporary destination.
        void download(SaoPackManifest.PackFile file, Path destination) throws Exception;
    }

    /// Filesystem gate evaluated against the final composed inventory before committing a quick-update chain.
    @NotNullByDefault
    @FunctionalInterface
    public interface CapacityCheck {
        /// Refuses the transaction when the complete candidate cannot safely be installed.
        void check(SaoPackArchive candidate) throws IOException;
    }

    /// Reports verified content and the exact number of physical writes/removals for a completed operation.
    ///
    /// @param version applied pack version
    /// @param written replaced or newly created files
    /// @param removed previously managed files removed by this release
    /// @param verified pack files matching their manifest after installation
    /// @param preserved player defaults differing from their pack seed and deliberately kept
    @NotNullByDefault
    public record Result(String version, int written, int removed, int verified, int preserved) { }

    /// Constructs an installer; all sources remain read-only.
    public SaoPackInstaller(Path instance, Downloader downloader, Map<String, Path> baseline,
                            BiConsumer<String, Double> progress) {
        this.instance = instance.toAbsolutePath().normalize();
        this.downloader = downloader;
        this.baseline = Map.copyOf(baseline);
        this.progress = progress;
    }

    /// Reads the last successfully committed manifest, or null when this instance has never been installed.
    public @Nullable SaoPackManifest installed() throws IOException {
        Path receipt = instance.resolve(RECEIPT);
        if (!Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid installed pack receipt");
        try (Reader reader = Files.newBufferedReader(receipt, StandardCharsets.UTF_8)) { return SaoPackManifest.read(reader); }
    }

    /// Installs or incrementally updates a pack. Repair hashes every file and restores managed damage.
    /// Existing player state is hashed for reporting and is never overwritten or removed.
    public Result apply(SaoPackArchive source, boolean repair) throws Exception {
        SaoPackFiles.resolve(instance, "sao-pack-lock-check");
        Files.createDirectories(instance);
        Path lockPath = instance.resolve(".sao-pack.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid pack lock file");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another SAO launcher is updating this instance");
            return applyLocked(source, repair);
        }
    }

    /// Verifies and composes all quick hops while holding the same lock as the single preserving commit.
    /// The complete resulting source is cached before writes, so repair survives restart without the original drops.
    public SaoPackArchive applyQuick(List<Path> archives, @Nullable SaoPackArchive pristine, Path cache) throws Exception {
        return applyQuick(archives, pristine, cache, complete -> { });
    }

    /// Checks the composed archive's capacity before any installed mutation, under the chain's instance lock.
    public SaoPackArchive applyQuick(List<Path> archives, @Nullable SaoPackArchive pristine, Path cache,
                                    CapacityCheck capacity) throws Exception {
        SaoPackFiles.resolve(instance, "sao-pack-lock-check");
        Files.createDirectories(instance);
        Path lockPath = instance.resolve(".sao-pack.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid pack lock file");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another SAO launcher is updating this instance");
            @Nullable SaoPackManifest installed = installed();
            if (installed == null) throw new IOException(SaoQuickUpdate.FULL_REQUIRED);
            Files.createDirectories(cache);
            Path work = Files.createTempDirectory(cache, "chain-");
            try {
                SaoPackArchive composed = SaoQuickUpdate.compose(instance, installed, archives, pristine, work);
                capacity.check(composed);
                String key = SaoPackArchive.sha1(new ByteArrayInputStream(composed.manifest().toJson().getBytes(StandardCharsets.UTF_8)));
                Path retained = cache.resolve(key + ".zip");
                SaoPackFiles.atomicMove(composed.zip(), retained);
                SaoPackArchive complete = new SaoPackArchive(retained, composed.manifest());
                applyLocked(complete, true);
                return complete;
            } finally { SaoPackFiles.deleteTree(work); }
        }
    }

    /// Stages all required bytes before modifying the instance, then commits the receipt last.
    private Result applyLocked(SaoPackArchive source, boolean repair) throws Exception {
        @Nullable SaoPackManifest previous = installed();
        SaoPackManifest manifest = source.manifest();
        if (previous != null && org.jackhuang.hmcl.util.versioning.VersionNumber.compare(manifest.version(), previous.version()) < 0)
            throw new IOException("Refusing to downgrade the installed SAO pack");
        Map<String, SaoPackManifest.PackFile> oldFiles = new HashMap<>();
        if (previous != null) for (var file : previous.files()) oldFiles.put(file.path(), file);
        Set<String> newPaths = new HashSet<>();
        List<SaoPackManifest.PackFile> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int preserved = 0;
        int verified = 0;
        int checked = 0;
        for (var file : manifest.files()) {
            newPaths.add(file.path());
            Path target = SaoPackFiles.resolve(instance, file.path());
            boolean playerOwned = SaoPackManifest.isPlayerFile(file.path()) && Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            @Nullable SaoPackManifest.PackFile old = oldFiles.get(file.path());
            boolean inventoryChanged = old == null || !old.sha1().equals(file.sha1()) || old.size() != file.size();
            if (playerOwned) {
                if (SaoPackFiles.matches(target, file)) verified++;
                else preserved++;
            } else if (repair || previous == null || inventoryChanged) {
                if (!SaoPackFiles.matches(target, file)) changed.add(file);
                else verified++;
            }
            progress.accept("检查整合包 " + (++checked) + "/" + manifest.files().size(), checked / (double) manifest.files().size() * 0.15);
        }
        if (previous != null) {
            Set<String> obsolete = new TreeSet<>(manifest.removals());
            for (String path : oldFiles.keySet()) if (!newPaths.contains(path)) obsolete.add(path);
            for (String path : obsolete) {
                // Explicit removals cannot delete an arbitrary file that this launcher never owned.
                if (oldFiles.containsKey(path) && !SaoPackManifest.isPlayerFile(path)
                        && Files.exists(SaoPackFiles.resolve(instance, path), LinkOption.NOFOLLOW_LINKS)) removed.add(path);
            }
        }
        Path stage = Files.createTempDirectory(instance, ".sao-stage-");
        boolean cleanStage = true;
        try (ZipFile archive = new ZipFile(source.zip().toFile(), StandardCharsets.UTF_8)) {
            int staged = 0;
            for (var file : changed) {
                Path destination = SaoPackFiles.resolve(stage.resolve("new"), file.path());
                Files.createDirectories(destination.getParent());
                progress.accept("准备整合包文件 " + (staged + 1) + "/" + changed.size(), 0.15 + 0.65 * staged / Math.max(1.0, changed.size()));
                @Nullable Path cached = baseline.get(file.sha1());
                if (cached != null && SaoPackFiles.matches(cached, file)) {
                    Files.copy(cached, destination);
                } else if (file.entry() != null) {
                    @Nullable ZipEntry entry = archive.getEntry(file.entry());
                    if (entry == null || entry.isDirectory() || entry.getSize() != file.size())
                        throw new IOException("Pack member missing or wrong size: " + file.path());
                    try (InputStream input = archive.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                        // Limit expansion to the manifest size even if an archive header lies.
                        long copied = input.transferTo(new BoundedOutputStream(output, file.size()));
                        if (copied != file.size()) throw new IOException("Pack member size mismatch: " + file.path());
                    }
                } else downloader.download(file, destination);
                if (!SaoPackFiles.matches(destination, file)) throw new IOException("Pack checksum mismatch: " + file.path());
                staged++;
            }
            commit(stage, changed, removed, manifest);
            verified += changed.size();
            if (!repair && previous != null) {
                // Incremental update deliberately avoids rehashing unchanged managed payloads.
                verified = -1;
            }
            progress.accept("整合包已就绪", 1.0);
            return new Result(manifest.version(), changed.size(), removed.size(), verified, preserved);
        } catch (Exception failure) {
            // Retain backups when rollback itself failed; recovery must not delete the last copy.
            if (failure.getSuppressed().length > 0) cleanStage = false;
            throw failure;
        } finally {
            if (cleanStage) SaoPackFiles.deleteTree(stage);
        }
    }

    /// Replaces files and managed removals, restoring their previous contents if a filesystem operation fails.
    private void commit(Path stage, List<SaoPackManifest.PackFile> changed, List<String> removed,
                        SaoPackManifest manifest) throws IOException {
        List<String> committed = new ArrayList<>();
        List<String> backedUp = new ArrayList<>();
        try {
            List<String> mutations = new ArrayList<>(removed);
            for (var file : changed) mutations.add(file.path());
            int count = 0;
            for (String path : mutations) {
                Path target = SaoPackFiles.resolve(instance, path);
                Path backup = SaoPackFiles.resolve(stage.resolve("backup"), path);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Managed destination is not a file: " + path);
                    Files.createDirectories(backup.getParent());
                    SaoPackFiles.atomicMove(target, backup);
                    backedUp.add(path);
                }
                committed.add(path);
                Path replacement = SaoPackFiles.resolve(stage.resolve("new"), path);
                if (Files.isRegularFile(replacement, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    SaoPackFiles.atomicMove(replacement, target);
                }
                progress.accept("应用整合包", 0.8 + 0.19 * ++count / Math.max(1.0, mutations.size()));
            }
            Path receipt = stage.resolve("receipt.json");
            Files.writeString(receipt, manifest.toJson(), StandardCharsets.UTF_8);
            SaoPackFiles.atomicMove(receipt, instance.resolve(RECEIPT));
        } catch (IOException | RuntimeException failure) {
            Collections.reverse(committed);
            for (String path : committed) {
                try {
                    Path target = SaoPackFiles.resolve(instance, path);
                    Files.deleteIfExists(target);
                    if (backedUp.contains(path)) {
                        Files.createDirectories(target.getParent());
                        SaoPackFiles.atomicMove(SaoPackFiles.resolve(stage.resolve("backup"), path), target);
                    }
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    /// Loads a developer-supplied read-only receipt for content-addressed reuse; never puts source paths in output metadata.
    public static @Unmodifiable Map<String, Path> readBaseline(Path receipt) throws IOException {
        try (Reader reader = Files.newBufferedReader(receipt, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, Path> result = new HashMap<>();
            for (JsonElement element : root.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                Path besideReceipt = receipt.toAbsolutePath().getParent().resolve(file.get("path").getAsString());
                result.put(file.getAsJsonObject("hashes").get("sha1").getAsString(),
                        Files.isRegularFile(besideReceipt) ? besideReceipt : Path.of(file.get("source").getAsString()));
            }
            return Map.copyOf(result);
        } catch (RuntimeException e) { throw new IOException("Invalid baseline receipt", e); }
    }

    /// Enforces the declared expansion bound while streaming an archive entry.
    @NotNullByDefault
    private static final class BoundedOutputStream extends FilterOutputStream {
        /// Bytes still allowed by the manifest.
        private long remaining;
        /// Creates a bounded wrapper; closing it closes the staging stream.
        private BoundedOutputStream(OutputStream output, long size) { super(output); remaining = size; }
        /// Writes one byte only while it is within the declared size.
        @Override public void write(int value) throws IOException {
            if (remaining == 0) throw new IOException("Archive member exceeds manifest size");
            out.write(value); remaining--;
        }
        /// Writes one buffer only while it is within the declared size.
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > remaining) throw new IOException("Archive member exceeds manifest size");
            out.write(bytes, offset, length); remaining -= length;
        }
    }
}
