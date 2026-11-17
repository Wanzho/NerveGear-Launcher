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

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs a complete pack inventory with staged, checksum-verified writes and rollback on commit errors.
/// Does not select Java, install Minecraft, open JavaFX or start a game process.
@NotNullByDefault
public final class SaoPackInstaller {
    /// Receipt is launcher-owned and never supplied by a pack override.
    public static final String RECEIPT = ".sao-pack.json";
    /// Exact full-pack snapshot committed with the installed inventory.
    private static final String ACCEPTED_ARCHIVE = "acceptedArchive";
    /// Commit journal written into a stage before its first instance mutation.
    static final String JOURNAL = "journal.json";
    /// Staged receipt whose atomic move is the commit point, even for a same-version repair.
    private static final String STAGED_RECEIPT = "receipt.json";
    /// SHA-1-named verified downloads kept after a failed batch, reused by the next attempt and removed on success.
    static final String DOWNLOAD_CACHE = ".sao-download-cache";
    /// Fixed, isolated game directory managed by this installer.
    private final Path instance;
    /// Injectable target-filesystem capacity, shared by preflight and disk-full diagnostics.
    private final SaoDiskSpace.FreeSpace freeSpace;
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

        /// Fetches an official Modrinth quick-update dependency. The production downloader restricts redirects to the CDN.
        default void downloadOfficialMod(SaoPackManifest.PackFile file, Path destination) throws Exception {
            download(file, destination);
        }

        /// Fetches all dependencies with at most six workers, draining every failure before returning.
        /// Implementations may publish richer transfer snapshots; destinations always belong to private staging.
        default void downloadAll(Map<SaoPackManifest.PackFile, Path> files) throws Exception {
            SaoModDownloads.runBatch(this, files);
        }
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
        this(instance, downloader, baseline, progress, SaoDiskSpace.SYSTEM);
    }

    /// Constructs an installer with a deterministic capacity probe; all payload writes still use real staging/rollback.
    SaoPackInstaller(Path instance, Downloader downloader, Map<String, Path> baseline,
                     BiConsumer<String, Double> progress, SaoDiskSpace.FreeSpace freeSpace) {
        this.freeSpace = freeSpace;
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
        String text;
        try { text = Files.readString(receipt, StandardCharsets.UTF_8); }
        catch (java.nio.charset.CharacterCodingException damaged) { return null; }
        try { return SaoPackManifest.read(new StringReader(text)); }
        catch (IOException strict) {
            // A receipt from another launcher version, or a damaged one, must not lock the player out of Repair and
            // updates: keep its inventory when it still parses, otherwise treat the pack as not installed (re-hash all).
            try { return SaoPackManifest.readReceipt(new StringReader(text)); }
            catch (IOException damaged) { return null; }
        }
    }

    /// Reads the exact cache identity from newer receipts. Legacy receipts have no identity.
    @Nullable String installedArchive() throws IOException {
        Path receipt = instance.resolve(RECEIPT);
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var reader = Files.newBufferedReader(receipt, StandardCharsets.UTF_8)) {
            @Nullable var value = JsonParser.parseReader(reader).getAsJsonObject().get(ACCEPTED_ARCHIVE);
            if (value == null) return null;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IOException("Invalid installed archive identity");
            return value.getAsString();
        } catch (RuntimeException damaged) {
            throw new IOException("Invalid installed pack receipt", damaged);
        }
    }

    /// Installs or incrementally updates a pack. Repair hashes every file and restores managed damage.
    /// Existing player state is hashed for reporting and is never overwritten or removed.
    public Result apply(SaoPackArchive source, boolean repair) throws Exception {
        return apply(source, repair, false, null);
    }

    /// Applies a dropped full pack as an update: only files whose inventory entry changed are written, so player-edited
    /// settings survive; replaced or removed game-written settings are kept in `.sao-config-backups`.
    public Result applyUpdate(SaoPackArchive source) throws Exception {
        return apply(source, false, true, null);
    }

    /// Switches the exact full-pack source in the same transaction as the receipt.
    Result applyCachedFull(SaoPackArchive source, boolean repair) throws Exception {
        return apply(source, repair, !repair, source.zip().getFileName().toString());
    }

    /// Applies both full-pack entry points under the same instance lock.
    private Result apply(SaoPackArchive source, boolean repair, boolean trackSettings, @Nullable String acceptedArchive) throws Exception {
        long budget = SaoDiskSpace.required(source);
        SaoDiskSpace.require(instance, budget, freeSpace);
        try {
            SaoPackFiles.resolve(instance, "sao-pack-lock-check");
            Files.createDirectories(instance);
            Path lockPath = instance.resolve(".sao-pack.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid pack lock file");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 @Nullable var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another SAO launcher is updating this instance");
                Set<String> touched = new HashSet<>();
                if (trackSettings) {
                    recoverLocked();
                    @Nullable SaoPackManifest previous = installed();
                    if (previous != null) {
                        Map<String, SaoPackManifest.PackFile> next = new HashMap<>();
                        for (var file : source.manifest().files()) next.put(file.path(), file);
                        for (var old : previous.files()) {
                            @Nullable SaoPackManifest.PackFile replacement = next.get(old.path());
                            if (replacement == null || !replacement.sha1().equals(old.sha1()) || replacement.size() != old.size()) touched.add(old.path());
                        }
                    }
                }
                return applyLocked(source, repair, touched, acceptedArchive);
            }
        } catch (Exception failure) { throw SaoDiskSpace.explain(instance, budget, freeSpace, failure); }
    }

    /// Verifies and composes all quick hops while holding the same lock as the single preserving commit.
    /// The complete resulting source is cached before writes, so repair survives restart without the original drops.
    public SaoPackArchive applyQuick(List<Path> archives, @Nullable SaoPackArchive pristine, Path cache) throws Exception {
        return applyQuick(archives, pristine, cache, complete -> { });
    }

    /// Checks the composed archive's capacity before any installed mutation, under the chain's instance lock.
    public SaoPackArchive applyQuick(List<Path> archives, @Nullable SaoPackArchive pristine, Path cache,
                                    CapacityCheck capacity) throws Exception {
        // Each hop contains a full resulting inventory; check the largest before creating any chain files.
        long largest = pristine == null ? 0 : SaoDiskSpace.packBytes(pristine.manifest());
        for (Path archive : archives) {
            SaoQuickUpdate hop = SaoQuickUpdate.open(archive);
            largest = Math.max(largest, Math.max(SaoDiskSpace.packBytes(hop.before()), SaoDiskSpace.packBytes(hop.after())));
            largest = Math.max(largest, Files.size(archive));
        }
        long budget = SaoDiskSpace.packRequired(largest);
        SaoDiskSpace.require(instance, budget, freeSpace);
        SaoDiskSpace.require(cache, budget, freeSpace);
        try {
            SaoPackFiles.resolve(instance, "sao-pack-lock-check");
            Files.createDirectories(instance);
            Path lockPath = instance.resolve(".sao-pack.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid pack lock file");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 @Nullable var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another SAO launcher is updating this instance");
                @Nullable SaoPackManifest installed = installed();
                if (installed == null) throw new IOException(SaoQuickUpdate.FULL_REQUIRED);
                Files.createDirectories(cache);
                Path work = Files.createTempDirectory(cache, "chain-");
                try {
                    SaoPackArchive composed = SaoQuickUpdate.compose(instance, installed, archives, pristine, work, downloader);
                    capacity.check(composed);
                    String key = SaoPackArchive.sha1(new ByteArrayInputStream(composed.manifest().toJson().getBytes(StandardCharsets.UTF_8)));
                    Path retained = cache.resolve(key + ".zip");
                    SaoPackFiles.atomicMove(composed.zip(), retained);
                    SaoPackArchive complete = new SaoPackArchive(retained, composed.manifest());
                    Set<String> touched = new HashSet<>();
                    for (Path archive : archives) {
                        SaoQuickUpdate update = SaoQuickUpdate.open(archive);
                        touched.addAll(update.added()); touched.addAll(update.changed()); touched.addAll(update.removed());
                    }
                    try { applyLocked(complete, false, touched, retained.getFileName().toString()); }
                    catch (Exception failure) { throw SaoDiskSpace.explain(instance, budget, freeSpace, failure); }
                    return complete;
                } catch (Exception failure) { throw SaoDiskSpace.explain(cache, budget, freeSpace, failure); }
                finally { SaoPackFiles.deleteTree(work); }
            }
        } catch (Exception failure) { throw SaoDiskSpace.explain(instance, budget, freeSpace, failure); }
    }

    /// Commits a full inventory while forcing every quick-chain author change, even a later return to old seed bytes.
    /// Unchanged runtime settings retain their current contents or absence; replaced/removed settings get durable backups.
    private Result applyLocked(SaoPackArchive source, boolean repair, Set<String> quickTouched,
                               @Nullable String acceptedArchive) throws Exception {
        SaoDiskSpace.require(instance, SaoDiskSpace.required(source), freeSpace);
        recoverLocked();
        @Nullable SaoPackManifest previous = installed();
        SaoPackManifest manifest = source.manifest();
        if (acceptedArchive == null && manifest.equals(previous)) acceptedArchive = installedArchive();
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
            // A player may link a folder of their own (shaderpacks/, saves/) elsewhere; it is theirs, so it is left
            // alone instead of failing every install, update and repair.
            if (SaoPackManifest.isPlayerFile(file.path()) && !SaoPackManifest.isPersistentGameRecord(file.path()) && linkedAncestor(file.path())) {
                preserved++;
                progress.accept("检查整合包 " + (++checked) + "/" + manifest.files().size(), checked / (double) manifest.files().size() * 0.15);
                continue;
            }
            Path target = SaoPackFiles.resolve(instance, file.path());
            if (SaoPackManifest.isPersistentGameRecord(file.path()) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid battle-history file");
            boolean playerOwned = SaoPackManifest.isPlayerFile(file.path()) && Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            @Nullable SaoPackManifest.PackFile old = oldFiles.get(file.path());
            boolean inventoryChanged = old == null || !old.sha1().equals(file.sha1()) || old.size() != file.size() || quickTouched.contains(file.path());
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
            Map<String, String> folded = new HashMap<>();
            for (String path : newPaths) folded.put(path.toLowerCase(Locale.ROOT), path);
            for (String path : obsolete) {
                // Explicit removals cannot delete an arbitrary file that this launcher never owned.
                if (oldFiles.containsKey(path) && !SaoPackManifest.isPlayerFile(path)
                        && Files.exists(SaoPackFiles.resolve(instance, path), LinkOption.NOFOLLOW_LINKS)
                        && !sameFileAsNewPath(path, folded)) removed.add(path);
            }
        }
        Path stage = Files.createTempDirectory(instance, ".sao-stage-");
        boolean cleanStage = true;
        try (ZipFile archive = new ZipFile(source.zip().toFile(), StandardCharsets.UTF_8)) {
            int staged = 0;
            Map<SaoPackManifest.PackFile, Path> remote = new LinkedHashMap<>();
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
                } else {
                    remote.put(file, destination);
                    continue;
                }
                if (!SaoPackFiles.matches(destination, file)) throw new IOException("Pack checksum mismatch: " + file.path());
                staged++;
            }
            // All remote bytes stay in this transaction's stage until the entire batch verifies. Files verified by an
            // earlier failed batch are reused from the SHA-1 cache, and this batch's verified files are kept there on failure.
            Path cache = instance.resolve(DOWNLOAD_CACHE);
            Map<SaoPackManifest.PackFile, Path> missing = new LinkedHashMap<>();
            for (var item : remote.entrySet()) {
                Path cached = SaoPackFiles.resolve(cache, item.getKey().sha1());
                if (SaoPackFiles.matches(cached, item.getKey())) SaoPackFiles.atomicMove(cached, item.getValue());
                else missing.put(item.getKey(), item.getValue());
            }
            try { downloader.downloadAll(missing); }
            catch (Exception failure) {
                // Disk-full cleanup must not try to retain failed downloads or mask the original ENOSPC.
                if (!SaoDiskSpace.isFull(failure)) keepVerified(missing, cache, failure);
                throw failure;
            }
            for (var item : remote.entrySet()) {
                if (!SaoPackFiles.matches(item.getValue(), item.getKey()))
                    throw new IOException("Pack checksum mismatch: " + item.getKey().path());
            }
            commit(stage, changed, removed, manifest, quickTouched, acceptedArchive);
            try { if (Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) SaoPackFiles.deleteTree(cache); }
            catch (IOException ignored) { /* A leftover cache is reused or replaced by the next install. */ }
            verified += changed.size();
            if (!repair && previous != null) {
                // Incremental update deliberately avoids rehashing unchanged managed payloads.
                verified = -1;
            }
            progress.accept("整合包已就绪", 1.0);
            return new Result(manifest.version(), changed.size(), removed.size(), verified, preserved);
        } catch (Exception failure) {
            // Only a journaled failed rollback needs retention. Failed staging/cache writes never keep partial payloads.
            if (failure.getSuppressed().length > 0 && Files.exists(stage.resolve(JOURNAL))) {
                cleanStage = false;
            }
            throw failure;
        } finally {
            if (cleanStage) {
                try { cleanStage(stage); }
                catch (IOException cleanup) { LOG.warning("Pack stage cleanup postponed: " + stage.getFileName(), cleanup); }
            }
        }
    }

    /// Removes the journal before its recovery evidence, so interrupted cleanup cannot cause a second rollback.
    private static void cleanStage(Path stage) throws IOException {
        Files.deleteIfExists(stage.resolve(JOURNAL));
        SaoPackFiles.deleteTree(stage);
    }

    /// Rolls back any commit interrupted by a crash or closed window, under the instance lock; skipped while another
    /// launcher holds the lock.
    public void recover() throws IOException {
        if (!Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS)) return;
        Path lockPath = instance.resolve(".sao-pack.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid pack lock file");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock != null) recoverLocked();
        }
    }

    /// Journaled stages are rolled back unless their version change already moved the receipt; finished, torn or
    /// unjournaled stages are removed. A stage whose rollback fails is kept (and logged) so its backups are never lost.
    private void recoverLocked() throws IOException {
        if (!Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> stages = new ArrayList<>();
        try (var listing = Files.newDirectoryStream(instance, ".sao-stage-*")) {
            for (Path stage : listing) if (Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) stages.add(stage);
        }
        Path receipt = instance.resolve(RECEIPT);
        @Nullable String installed;
        try { installed = Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) ? digest(Files.readString(receipt, StandardCharsets.UTF_8)) : null; }
        catch (IOException unreadable) { installed = null; }
        for (Path stage : stages) {
            try {
                Path journal = stage.resolve(JOURNAL);
                if (Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                    // A torn journal was cut off while being written, before any instance change: nothing to undo.
                    @Nullable JsonObject entry;
                    try { entry = JsonParser.parseString(Files.readString(journal, StandardCharsets.UTF_8)).getAsJsonObject(); }
                    catch (IOException | RuntimeException torn) { entry = null; }
                    if (entry != null) {
                        String target = entry.get("manifest").getAsString();
                        // New stages write their receipt before the journal, then move it only at commit.
                        // Older stages identify a committed version change by its receipt digest.
                        boolean landed = entry.has("stagedReceipt")
                                ? Files.notExists(stage.resolve(STAGED_RECEIPT), LinkOption.NOFOLLOW_LINKS)
                                : target.equals(installed) && entry.has("previous")
                                        && !target.equals(entry.get("previous").getAsString());
                        if (!landed) rollBack(stage, entry);
                    }
                }
                cleanStage(stage);
            } catch (IOException | RuntimeException failure) {
                // Keep the stage (and its backups) for the next start rather than blocking the launcher now.
                LOG.warning("Pack recovery postponed for " + stage.getFileName(), failure);
            }
        }
    }

    /// Restores each backed-up file and removes newly added files whose staged copy was already moved in.
    private void rollBack(Path stage, JsonObject journal) throws IOException {
        List<String> paths = new ArrayList<>();
        for (var path : journal.getAsJsonArray("paths")) paths.add(path.getAsString());
        Set<String> added = new HashSet<>();
        for (var path : journal.getAsJsonArray("changed")) added.add(path.getAsString());
        Collections.reverse(paths);
        for (String path : paths) {
            Path target = SaoPackFiles.resolve(instance, path);
            Path backup = SaoPackFiles.resolve(stage.resolve("backup"), path);
            if (Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target.getParent());
                SaoPackFiles.atomicMove(backup, target);
            } else if (added.contains(path) && !Files.exists(SaoPackFiles.resolve(stage.resolve("new"), path), LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(target);
            }
        }
    }

    /// SHA-1 of UTF-8 text, identifying a manifest by its exact receipt bytes.
    private static String digest(String text) throws IOException {
        return SaoPackArchive.sha1(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /// Writes and flushes a small file to disk before it is relied on.
    private static void writeDurably(Path path, String text) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /// Whether a folder on the way to this instance path is a symbolic link.
    private boolean linkedAncestor(String path) {
        Path absolute = instance.toAbsolutePath().normalize();
        for (Path current = absolute.resolve(path).normalize().getParent(); current != null && current.startsWith(absolute) && !current.equals(absolute);
             current = current.getParent())
            if (Files.isSymbolicLink(current)) return true;
        return false;
    }

    /// A case-only rename on a case-insensitive filesystem names the same file as its new path; removing it would
    /// delete the new file too.
    private boolean sameFileAsNewPath(String path, Map<String, String> folded) throws IOException {
        @Nullable String renamed = folded.get(path.toLowerCase(Locale.ROOT));
        if (renamed == null || renamed.equals(path)) return false;
        Path current = SaoPackFiles.resolve(instance, renamed);
        return Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(current, SaoPackFiles.resolve(instance, path));
    }

    /// Moves every verified file of a failed batch into the SHA-1 cache so the next attempt does not download it again.
    private static void keepVerified(Map<SaoPackManifest.PackFile, Path> files, Path cache, Exception failure) {
        try {
            if (Files.isSymbolicLink(cache)) return;
            Files.createDirectories(cache);
            for (var item : files.entrySet()) {
                Path cached = SaoPackFiles.resolve(cache, item.getKey().sha1());
                if (!Files.exists(cached, LinkOption.NOFOLLOW_LINKS) && SaoPackFiles.matches(item.getValue(), item.getKey()))
                    SaoPackFiles.atomicMove(item.getValue(), cached);
            }
        } catch (IOException | RuntimeException cacheFailure) { failure.addSuppressed(cacheFailure); }
    }

    /// Replaces files and managed removals, restoring previous contents on failure.
    /// Quick-update settings backups survive success under a unique launcher-owned transaction directory.
    private void commit(Path stage, List<SaoPackManifest.PackFile> changed, List<String> removed,
                        SaoPackManifest manifest, Set<String> quickTouched, @Nullable String acceptedArchive) throws IOException {
        List<String> committed = new ArrayList<>();
        List<String> backedUp = new ArrayList<>();
        @Nullable Path retained = null;
        try {
            List<String> mutations = new ArrayList<>(removed);
            for (var file : changed) mutations.add(file.path());
            Path receipt = stage.resolve(STAGED_RECEIPT);
            String receiptText = manifest.toJson();
            if (acceptedArchive != null) {
                JsonObject document = JsonParser.parseString(receiptText).getAsJsonObject();
                document.addProperty(ACCEPTED_ARCHIVE, acceptedArchive);
                receiptText = document.toString();
            }
            writeDurably(receipt, receiptText);
            // A crash after this point is rolled back (or, once the receipt moved, rolled forward) on the next start.
            JsonObject journal = new JsonObject();
            journal.addProperty("manifest", digest(receiptText));
            journal.addProperty("stagedReceipt", true);
            JsonArray paths = new JsonArray(), added = new JsonArray();
            mutations.forEach(paths::add);
            // Only files that did not exist before are removed on rollback; replaced files come back from backup.
            for (var file : changed)
                if (!Files.exists(SaoPackFiles.resolve(instance, file.path()), LinkOption.NOFOLLOW_LINKS)) added.add(file.path());
            journal.add("paths", paths); journal.add("changed", added);
            writeDurably(stage.resolve(JOURNAL), journal.toString());
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
            for (String path : backedUp) {
                if (!quickTouched.contains(path) || !SaoQuickUpdate.isGameWrittenSetting(path)) continue;
                if (retained == null) {
                    Path backups = instance.resolve(".sao-config-backups");
                    if (Files.isSymbolicLink(backups)) throw new IOException("Invalid configuration backup directory");
                    Files.createDirectories(backups);
                    retained = Files.createTempDirectory(backups, "update-");
                }
                Path destination = SaoPackFiles.resolve(retained, path);
                Files.createDirectories(destination.getParent());
                // Keep the staging backup until the receipt commits, so rollback never depends on the retained copy.
                Files.copy(SaoPackFiles.resolve(stage.resolve("backup"), path), destination);
            }
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
            if (retained != null) {
                try { SaoPackFiles.deleteTree(retained); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
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
