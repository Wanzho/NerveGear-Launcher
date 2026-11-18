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

import javafx.beans.property.*;
import org.jetbrains.annotations.Unmodifiable;
import java.util.concurrent.TimeUnit;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.nio.file.*;
import java.util.*;

/// Installs and updates the pack through the engine’s existing worker and operation gate.
@NotNullByDefault
final class SaoPackMaintenance {
    /// Supplies the existing launcher state and callbacks.
    private final SaoPackEngine owner;

    /// Creates the component for its owning SaoPackEngine.
    SaoPackMaintenance(SaoPackEngine owner) {
        this.owner = owner;
    }

    /// Starts the current install operation after the existing game process check.
    void download() { owner.guardGameAction(this::downloadNow); }

    /// Prepares installation after any old instance process has exited.
    private void downloadNow() {
        if (owner.closed.get() || owner.uninstalling.get() || !owner.launcherPermitted()) return;
        @Nullable SaoData.VersionMetadata advertised = owner.versionMetadata;
        if ((advertised == null || advertised.patchOrigin() == null) && owner.readiness.get().action() == SaoReadiness.Action.QUARK) {
            fetchNewerPack(); return;
        }
        SaoPackState current = owner.state.get();
        if (current != SaoPackState.NOT_INSTALLED && current != SaoPackState.UPDATE_READY && current != SaoPackState.READY
                && current != SaoPackState.FAILED) { if (owner.busy.get()) owner.refuseBusy(); return; }
        if (!owner.busy.compareAndSet(false, true)) { owner.refuseBusy(); return; }
        owner.working();
        owner.worker.execute(() -> {
            try {
                if (owner.closed.get()) throw new IOException("Launcher has closed");
                SaoReadiness fresh = computeReadiness();
                if (fresh.action() == SaoReadiness.Action.QUARK) {
                    boolean applied = applyHostedPatches();
                    refreshState();
                    if (applied) publishDropPrompt(false, null);
                    else if (!owner.closed.get() && owner.launcherPermitted()) SaoPackEngine.publish(this::fetchNewerPack);
                    return;
                }
                prepare();
                refreshState();
            } catch (Exception failure) {
                owner.fail("下载失败", failure);
                try { SaoReadiness after = computeReadiness(); SaoPackEngine.publish(() -> owner.readiness.set(after)); }
                catch (Exception ignored) { /* The failure line already explains it. */ }
            } finally { owner.releaseBusy(); }
        });
    }

    /// Fetches a complete signed patch chain on demand and reuses the manual-drop transaction and base validation.
    private boolean applyHostedPatches() throws Exception {
        @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
        if (metadata == null || localPack()) return false;
        @Nullable String origin = metadata.patchOrigin();
        if (origin == null) return false;
        owner.installer.recover();
        SaoDropSession session = currentDropSession(metadata);
        SaoData.DownloadPrompt prompt = session.prompt(false, null);
        if (prompt.fullPackRequired()) return false;
        Path cache = SaoPackFiles.resolve(owner.root, "pack-cache");
        for (SaoData.QuickRelease release : session.chain) {
            if (!prompt.missing().contains(release.to())) continue;
            if (owner.closed.get() || !owner.launcherPermitted() || !SaoPackEngine.sameRelease(metadata, owner.versionMetadata)) return false;
            SaoDiskSpace.require(cache, release.size(), SaoDiskSpace.SYSTEM);
            Files.createDirectories(cache);
            Path temporary = Files.createTempFile(cache, "patch-", ".part");
            try {
                String label = "下载快速更新 " + release.from() + " → " + release.to();
                owner.reportProgress(label, 0);
                try {
                    SaoPatchDownload.fetch(origin, release, temporary,
                            () -> owner.closed.get() || !SaoPackEngine.sameRelease(metadata, owner.versionMetadata), value -> owner.reportProgress(label, value));
                } catch (IOException failure) {
                    SaoPackEngine.logFailure("快速更新下载失败", failure);
                    return false;
                }
                if (!session.acceptQuick(temporary, owner.extras::checkInstallCapacity)) {
                    SaoPackEngine.logFailure("快速更新文件校验失败", new IOException(SaoUpdates.INVALID_ARCHIVE));
                    return false;
                }
            } finally { Files.deleteIfExists(temporary); }
        }
        if (owner.closed.get() || !owner.launcherPermitted() || !SaoPackEngine.sameRelease(metadata, owner.versionMetadata)) return false;
        try {
            applyVerifiedQuick(session);
            return true;
        } catch (IOException failure) {
            if (!SaoQuickUpdate.FULL_REQUIRED.equals(failure.getMessage())) throw failure;
            session.requireFull();
            return false;
        }
    }

    /// Commits only the complete ordered chain of verified snapshots, then records the accepted source.
    private void applyVerifiedQuick(SaoDropSession session) throws Exception {
        SaoPackArchive complete = owner.installer.applyQuick(session.ready(), owner.source, owner.root.resolve("pack-cache"), owner.extras::checkInstallCapacity);
        acceptSource(complete);
        session.close();
        owner.dropSession = null;
    }

    /// 下载: everything the launch path prepares, and nothing else; never launches and needs no account.
    /// A pack whose files are missing at the same version is restored with the full hash check, like 修复.
    private void prepare() throws Exception {
        owner.installer.recover();
        SaoPackArchive pack = owner.requireSource();
        @Nullable SaoPackManifest previous = owner.installer.installed();
        boolean newer = previous == null || VersionNumber.compare(pack.manifest().version(), previous.version()) > 0;
        boolean incomplete = previous != null && !newer && !SaoReadinessCheck.filesPresent(previous, owner.game.instanceDir());
        if (newer) owner.extras.checkInstallCapacity();
        owner.steps.ensureInstalled(false);
        if (newer || incomplete) owner.installer.apply(pack, incomplete);
        owner.files.enforceRequiredResources();
    }

    /// The cheap check: versions against the signed release, the instance, Java 17 and pack file presence/size.
    /// Worker thread only.
    SaoReadiness computeReadiness() throws IOException { return computeReadiness(false); }

    /// Offline readiness uses installed content, never a newer remote release as a launch prerequisite.
    SaoReadiness computeReadiness(boolean offline) throws IOException {
        @Nullable String missing = SaoGameLocation.missing(owner.settings, owner.root);
        if (missing != null) return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, missing, SaoReadiness.Action.NONE);
        @Nullable SaoPackArchive pack = owner.source;
        @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
        boolean quark = false;
        if (metadata != null) try { SaoUpdates.checkedQuark(metadata.quark()); quark = true; } catch (IOException invalid) { /* PACK_WINDOW */ }
        try {
            @Nullable SaoPackManifest installed = owner.installer.installed();
            @Nullable String available = pack != null && Files.isRegularFile(pack.zip(), LinkOption.NOFOLLOW_LINKS)
                    ? pack.manifest().version() : null;
            if (!offline && installed != null && (available == null || VersionNumber.compare(available, installed.version()) < 0))
                return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, "缺少当前版本的整合包，请重新导入", SaoReadiness.Action.PACK_WINDOW);
            return SaoReadinessCheck.check(available, installed,
                    offline || localPack() || metadata == null ? null : metadata.pack().version(), quark, owner.game.instanceDir(),
                    owner.steps, offline && owner.allowModifiedModsOffline());
        } catch (IOException failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", interrupted); }
        catch (Exception unknown) {
            return new SaoReadiness(SaoReadiness.Kind.DOWNLOAD, SaoReadinessCheck.INCOMPLETE, SaoReadiness.Action.PREPARE);
        }
    }

    /// Link Start refused: one short line, the button back to 下载/更新; a newer signed pack opens Quark at once.
    void refuse(SaoReadiness ready) {
        SaoPackEngine.publish(() -> { owner.readiness.set(ready); owner.error.set(ready.reason()); owner.state.set(SaoPackState.FAILED); owner.step.set(""); owner.progress.set(-1); });
        if (ready.action() == SaoReadiness.Action.QUARK) SaoPackEngine.publish(this::fetchNewerPack);
    }

    /// 更新 to a pack only Quark has (James, 11 Nov: "这样的话跳夸克链接"): the link, then the Downloads watch.
    private void fetchNewerPack() {
        openQuark(false);
        owner.watchPackDownload();
    }

    /// Recomputes readiness on the worker after the signed release changed.
    void refreshReadinessLater() {
        if (owner.closed.get() || owner.worker.isShutdown()) return;
        try {
            owner.worker.execute(() -> {
                try { SaoReadiness value = computeReadiness(); SaoPackEngine.publish(() -> owner.readiness.set(value)); }
                catch (Exception ignored) { /* Kept until the next check. */ }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) { /* Shutting down. */ }
    }

    /// Repairs the active pack through the engine’s operation gate.
    void repair() {
        owner.guardGameAction(() -> owner.submit(() -> {
            SaoPackArchive pack = owner.requireSource();
            owner.extras.checkInstallCapacity();
            owner.steps.ensureInstalled(true);
            owner.installer.apply(pack, true);
            owner.files.enforceRequiredResources();
            refreshState();
        }));
    }

    /// Routes a dropped update through the same admission as a downloaded pack.
    void applyUpdateZip(Path zip) {
        applyDownloadedPack(zip);
    }

    /// Applies an already private verified complete pack through the existing preserving installer.
    private void applyPackNow(SaoPackArchive update) throws Exception { installCachedFull(cacheSource(update)); }

    /// Installs either official or local content while retaining its distinct provenance.
    private void installCachedFull(SaoPackArchive cached) throws Exception {
        try {
            @Nullable SaoPackManifest previous = owner.installer.installed();
            int comparison = previous == null ? 1 : VersionNumber.compare(cached.manifest().version(), previous.version());
            if (previous != null && comparison < 0) throw new IOException("不会降级已安装整合包 " + previous.version());
            owner.extras.checkInstallCapacity(cached);
            owner.steps.ensureInstalled(false);
            // Re-dropping a verified archive at the installed version repairs managed files.
            owner.installer.applyCachedFull(cached, !localPack(cached) && comparison == 0);
        } catch (Exception | Error failure) {
            try {
                @Nullable SaoPackArchive current = owner.source;
                if ((current == null || !current.zip().equals(cached.zip()))
                        && !cached.zip().getFileName().toString().equals(owner.installer.installedArchive()))
                    Files.deleteIfExists(cached.zip());
            } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        acceptSource(cached);
    }

    /// Updates repair/restart source only after the complete instance transaction has committed.
    private void acceptSource(SaoPackArchive cached) throws IOException {
        owner.source = cached;
        try { owner.persistAcceptedSource(cached); }
        catch (IOException failure) {
            // The committed receipt already points to this archive. This secondary pointer can be retried.
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Could not refresh the accepted pack cache pointer", failure);
        }
        pruneCache(cached);
        owner.files.requiredResources(owner.source);
        owner.files.enforceRequiredResources();
        owner.refreshLocalNow();
        refreshState();
    }

    /// Every file-picker, Downloads confirmation and older single-ZIP drop uses the accumulated drop path.
    void applyDownloadedPack(Path zip) { acceptDroppedFiles(List.of(zip)); }

    /// Offers cleanup only for copies whose pack transaction successfully committed.
    boolean offlineBundleCleanupAvailable() {
        return SaoOfflineBundle.sources(owner.root).stream().anyMatch(p -> Files.isRegularFile(p.resolve(".installed")));
    }

    /// Deletes completed extracted copies on the serialized worker; failed installations retain their inputs.
    CompletableFuture<Void> deleteOfflineBundleCopies() {
        return owner.mutate("清理离线包副本", () -> {
            for (Path path : SaoOfflineBundle.sources(owner.root))
                if (Files.isRegularFile(path.resolve(".installed"))) SaoOfflineBundle.removeTree(path);
        });
    }

    /// Recognizes only a readable ZIP central-directory marker; full validation remains on the worker.
    private static boolean containsOfflineBundle(List<Path> paths) {
        for (Path path : paths) try {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && SaoOfflineBundle.recognizes(path)) return true;
            } catch (IOException unreadable) { /* The ordinary rejection remains authoritative. */ }
        return false;
    }

    /// Recognizes full-pack headers without hashing payloads or consulting the network on the UI thread.
    private boolean isFullPackDrop(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var zip = new java.util.zip.ZipFile(path.toFile())) {
            if (zip.getEntry("bundle-index.json") != null || zip.getEntry("sao-update.json") != null) return false;
            if (zip.getEntry("modrinth.index.json") == null && zip.getEntry(SaoPackManifest.NAME) == null) return false;
            return true;
        } catch (IOException invalid) { return false; }
    }

    /// Recognizes a manually selected quick-update ZIP for the Beta importer.
    private static boolean isBetaQuickDrop(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var zip = new java.util.zip.ZipFile(path.toFile())) {
            return zip.getEntry(SaoQuickUpdate.NAME) != null && zip.getEntry("bundle-index.json") == null;
        } catch (IOException invalid) { return false; }
    }

    /// Leaves registered updates on the signed path even if the installed base cannot take that update.
    private static boolean registeredQuick(Path path, long size, @Nullable SaoData.VersionMetadata policy) throws IOException {
        if (policy == null || policy.updates().stream().noneMatch(update -> update.size() == size)) return false;
        String hash = SaoUpdates.sha256(path);
        return policy.updates().stream().anyMatch(update -> update.size() == size && update.sha256().equals(hash));
    }

    /// Preserves the signed path when someone explicitly drops the registered launcher artifact in Beta mode.
    private static boolean registeredLauncher(Path path, long size, @Nullable SaoData.VersionMetadata policy) throws IOException {
        @Nullable SaoData.LauncherArtifact launcher = policy == null ? null : policy.launcher().artifact();
        return launcher != null && size == launcher.size() && SaoUpdates.sha256(path).equals(launcher.sha256());
    }

    /// Installs a manually dropped Beta patch through the same verified-base transaction as official updates.
    private void applyBetaQuick(Path path, long size) throws Exception {
        SaoPackArchive base = owner.requireSource();
        if (localPack()) throw new IOException("请先安装与测试补丁对应的官方整合包");
        Path cache = SaoPackFiles.resolve(owner.root, "pack-cache");
        Path snapshot = SaoUpdates.snapshot(path, size, SaoUpdates.sha256(path), cache);
        try {
            SaoPackManifest current = base.manifest();
            SaoPackManifest next = SaoQuickUpdate.open(snapshot).after();
            if (!current.server().equals(next.server()) || !current.serverNodes().equals(next.serverNodes())
                    || !current.accountApi().equals(next.accountApi()) || !current.newsUrl().equals(next.newsUrl()))
                throw new IOException("测试补丁不能变更正式服、账号或公告地址");
            SaoPackArchive patched = owner.installer.applyQuick(List.of(snapshot), base, cache, owner.extras::checkInstallCapacity);
            owner.settings.put("betaPackArchive", patched.zip().getFileName().toString());
            acceptSource(patched);
            @Nullable SaoDropSession previous = owner.dropSession;
            if (previous != null) previous.close();
            owner.dropSession = null;
            publishDropPrompt(false, null);
        } finally { Files.deleteIfExists(snapshot); }
    }

    /// Imports local content through the preserving installer without manufacturing official release trust.
    private CompletableFuture<Void> importLocalFullPack(Path path) {
        if (!owner.locationAvailable() || owner.closed.get() || owner.uninstalling.get() || !owner.busy.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new SaoLauncherService.OperationBusyException(owner.refuseBusy()));
        owner.working();
        CompletableFuture<Void> result = new CompletableFuture<>();
        owner.worker.execute(() -> {
            @Nullable Throwable outcome = null;
            try {
                @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
                SaoPackArchive cached = cacheFullZip(path, null, metadata == null ? null : metadata.pack());
                installCachedFull(cached);
                if (localPack(cached)) SaoPackEngine.publish(() -> owner.error.set("已导入本地整合包，可使用离线单人启动"));
            } catch (Exception failure) {
                owner.fail("本地整合包导入失败", failure); outcome = failure;
            } catch (Error failure) {
                outcome = failure;
                throw failure;
            } finally {
                owner.releaseBusy();
                if (outcome == null) result.complete(null); else result.completeExceptionally(outcome);
            }
        });
        return result;
    }

    /// The marker is tied to the accepted cache filename and survives restart, independently of account state.
    boolean localPack() {
        @Nullable SaoPackArchive pack = owner.source;
        return pack != null && localPack(pack);
    }

    /// A local snapshot is identifiable before commit and can be promoted only for its own verified bytes.
    private boolean localPack(SaoPackArchive pack) {
        String name = pack.zip().getFileName().toString();
        if (name.startsWith("local-") && acceptedCacheName(name))
            return !name.equals(owner.settings.text("verifiedFullPack", ""));
        return name.equals(owner.settings.text("localPackArchive", ""));
    }

    boolean betaPatchedPack() {
        @Nullable SaoPackArchive pack = owner.source;
        return pack != null && pack.zip().getFileName().toString().equals(owner.settings.text("betaPackArchive", ""));
    }

    /// Promotes an already installed local import only when its cached original matches the verified release.
    void verifyLocalPackOrigin() {
        @Nullable SaoPackArchive cached = owner.source;
        @Nullable SaoData.VersionMetadata verified = owner.versionMetadata;
        if (cached == null || verified == null || !localPack()
                || !cached.manifest().version().equals(verified.pack().version())) return;
        try {
            if (Files.size(cached.zip()) != verified.pack().size()
                    || !SaoUpdates.sha256(cached.zip()).equals(verified.pack().sha256())) return;
            SaoPackManifest official = SaoPackArchive.open(cached.zip()).manifest();
            if (official.equals(cached.manifest()) && official.equals(owner.installer.installed())) {
                String name = cached.zip().getFileName().toString();
                if (name.startsWith("local-")) owner.settings.put("verifiedFullPack", name);
                else owner.settings.put("localPackArchive", "");
            }
        } catch (IOException failure) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Could not verify locally imported pack origin", failure);
        }
    }

    /// Accumulates good snapshots even when a batch also contains a wrong file; full packs supersede quick hops.
    CompletableFuture<Void> acceptDroppedFiles(List<Path> paths) {
        @Unmodifiable List<Path> snapshot = List.copyOf(paths);
        return owner.guardGame(() -> acceptDroppedFilesNow(snapshot));
    }

    /// Imports a stable dropped batch after the instance process guard.
    private CompletableFuture<Void> acceptDroppedFilesNow(List<Path> paths) {
        @Unmodifiable List<Path> dropped = List.copyOf(paths);
        if (!owner.packReady.isDone()) return owner.packReady.thenCompose(ignored -> acceptDroppedFilesNow(dropped));
        if (dropped.size() == 1 && isFullPackDrop(dropped.get(0))) return importLocalFullPack(dropped.get(0));
        @Nullable Path single = dropped.size() == 1 ? dropped.get(0) : null;
        boolean betaEnabled = owner.betaQuickUpdates();
        boolean betaQuick = single != null && (betaEnabled || Metadata.SAO_BETA) && isBetaQuickDrop(single);
        boolean betaLauncher = single != null && betaEnabled && Files.isRegularFile(single, LinkOption.NOFOLLOW_LINKS)
                && SaoSelfUpdate.betaVersion(single) != null;
        CompletableFuture<Void> result = new CompletableFuture<>();
        // Preserve the immediate, non-mutating rejection for an ordinary drop without policy. A bundle may
        // bootstrap that policy; checking its central directory does not read or extract the payload here.
        if (owner.versionMetadata == null && !betaQuick && !betaLauncher && !containsOfflineBundle(dropped)) {
            IOException failure = new IOException("暂无有效版本信息，请稍后重试");
            owner.windowFailure("无法校验整合包", failure); result.completeExceptionally(failure); return result;
        }
        if (!owner.locationAvailable() || owner.closed.get() || owner.uninstalling.get() || !owner.busy.compareAndSet(false, true)) {
            result.completeExceptionally(new SaoLauncherService.OperationBusyException(owner.refuseBusy())); return result;
        }
        owner.working();
        owner.worker.execute(() -> {
            @Nullable SaoPackArchive full = null;
            @Nullable IOException rejected = null;
            @Nullable Throwable outcome = null;
            boolean accepted = false;
            List<Path> importedDirectories = new ArrayList<>();
            try {
                if (single != null && betaQuick) {
                    long size = Files.size(single);
                    if (!registeredQuick(single, size, owner.versionMetadata)) {
                        if (!Metadata.SAO_BETA && !owner.betaQuickUpdates()) throw new IOException("Beta 测试更新已关闭");
                        applyBetaQuick(single, size);
                        return;
                    }
                }
                if (single != null && betaLauncher && !registeredLauncher(single, Files.size(single), owner.versionMetadata)) {
                    if (!owner.betaQuickUpdates()) throw new IOException("Beta 测试更新已关闭");
                    @Nullable SaoData.VersionMetadata current = owner.versionMetadata;
                    if (current != null && (SaoUpdates.compare(Metadata.VERSION, current.launcher().min()) < 0
                            || current.launcher().nativeRequired() && SaoUpdates.compare(Metadata.VERSION, current.launcher().latest()) < 0))
                        throw new IOException(SaoSelfUpdate.FULL_LAUNCHER_REQUIRED);
                    SaoData.LauncherUpdate value = owner.selfUpdate.stageBeta(single, Metadata.VERSION);
                    SaoPackEngine.publish(() -> owner.launcherUpdate.set(value));
                    refreshState();
                    return;
                }
                List<Path> candidates = new ArrayList<>();
                for (Path path : dropped) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && SaoOfflineBundle.recognizes(path)) {
                        SaoDiskSpace.requireOffline(owner.root, Files.size(path));
                        SaoOfflineBundle.Imported imported = SaoOfflineBundle.unpack(path, owner.root, owner::reportProgress);
                        importedDirectories.add(imported.directory());
                        if (!imported.damaged().isEmpty()) rejected = new IOException("离线包损坏，已跳过：" + String.join("、", imported.damaged()));
                        SaoData.VersionMetadata policy = owner.online.importOfflineVersion(imported.directory());
                        owner.versionMetadata = policy; owner.applyAccountPolicy(policy); owner.extras.updateQuark(policy.quark()); publishUpdates();
                        if (imported.pack() == null) throw new IOException("离线包中的整合包损坏，请重新下载");
                        candidates.add(imported.pack());
                    } else candidates.add(path);
                }
                @Nullable SaoData.VersionMetadata selectedPolicy = owner.versionMetadata;
                if (selectedPolicy == null) throw new IOException("暂无有效版本信息，请稍后重试");
                SaoData.VersionMetadata metadata = selectedPolicy;
                SaoDropSession session = currentDropSession(metadata);
                for (Path path : candidates) {
                    try {
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
                        long size = Files.size(path);
                        String hash = SaoUpdates.sha256(path);
                        if (size == metadata.pack().size() && hash.equals(metadata.pack().sha256())) {
                            if (full == null) {
                                owner.extras.checkInstallCapacity(SaoPackArchive.open(path));
                                full = SaoUpdates.snapshot(path, metadata.pack(), owner.root.resolve("pack-cache"));
                            }
                            accepted = true;
                        } else if (metadata.launcher().artifact() != null && size == metadata.launcher().artifact().size()
                                && hash.equals(metadata.launcher().artifact().sha256())) {
                            SaoData.LauncherUpdate value = owner.selfUpdate.stage(path, metadata, Metadata.VERSION);
                            SaoPackEngine.publish(() -> owner.launcherUpdate.set(value)); accepted = true;
                        } else if (session.acceptQuick(path, owner.extras::checkInstallCapacity)) accepted = true;
                        // A signed, intact quick release for an incompatible base needs the full pack, not a re-download.
                        else if (session.chain.isEmpty() && metadata.updates().stream()
                                .anyMatch(release -> size == release.size() && hash.equals(release.sha256())))
                            throw new IOException(SaoQuickUpdate.FULL_REQUIRED);
                        else throw new IOException(SaoUpdates.INVALID_ARCHIVE);
                    } catch (IOException failure) { rejected = failure; }
                }
                if (!SaoPackEngine.sameRelease(metadata, owner.versionMetadata)) throw new IOException("版本信息已更新，请重新拖入文件");
                if (full != null) {
                    applyPackNow(full); session.close(); owner.dropSession = null;
                    for (Path imported : importedDirectories) Files.writeString(imported.resolve(".installed"), "Installation completed\n");
                } else if (!session.ready().isEmpty()) {
                    try {
                        applyVerifiedQuick(session);
                    } catch (IOException failure) {
                        if (SaoQuickUpdate.FULL_REQUIRED.equals(failure.getMessage())) {
                            session.requireFull(); refreshState(); publishDropPrompt(true, null);
                            SaoPackEngine.publish(() -> owner.error.set(SaoQuickUpdate.FULL_REQUIRED + SaoUpdates.prompt(List.of())));
                            outcome = failure; return;
                        }
                        throw failure;
                    }
                }
                refreshState(); publishDropPrompt(owner.downloadPrompt.get().watching(), null);
                if (rejected != null) {
                    IOException failure = rejected;
                    if (!accepted) { owner.fail("文件校验失败", failure); outcome = failure; return; }
                    SaoPackEngine.publish(() -> owner.error.set(failure.getMessage()));
                }
            } catch (Exception failure) {
                owner.fail("整合包操作失败", failure); outcome = failure;
            } catch (Error failure) {
                outcome = failure;
                throw failure;
            } finally {
                if (full != null) try { Files.deleteIfExists(full.zip()); } catch (IOException ignored) { }
                owner.releaseBusy();
                if (outcome == null) result.complete(null); else result.completeExceptionally(outcome);
            }
        });
        return result;
    }

    /// Replaces obsolete retained drops on the worker, using the real receipt rather than delayed UI publication.
    private SaoDropSession currentDropSession(SaoData.VersionMetadata metadata) throws IOException {
        @Nullable SaoPackManifest installed = owner.installer.installed();
        @Nullable String version = installed == null ? null : installed.version();
        @Nullable SaoDropSession session = owner.dropSession;
        if (session == null || !SaoPackEngine.sameRelease(session.metadata, metadata) || !Objects.equals(session.installedVersion, version)) {
            if (session != null) session.close();
            session = new SaoDropSession(metadata, version, owner.root.resolve("pack-cache")); owner.dropSession = session;
        }
        return session;
    }

    /// Publishes exact no-pack/full-pack or ordered quick-hop text with immutable present/missing lists.
    private void publishDropPrompt(boolean watching, @Nullable Path candidate) throws IOException {
        @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
        SaoData.DownloadPrompt value = metadata == null ? new SaoData.DownloadPrompt(watching, candidate, SaoUpdates.prompt(List.of()))
                : currentDropSession(metadata).prompt(watching, candidate);
        SaoPackEngine.publish(() -> owner.downloadPrompt.set(value));
    }

    /// Computes against the actual installed receipt; remote metadata never marks the local pack broken.
    private void publishUpdates() {
        SaoData.UpdateStatus value = SaoUpdates.status(owner.versionMetadata, owner.installedVersion.get(), Metadata.VERSION, owner.dismissedLauncherVersion);
        SaoData.ReleaseVerification verification = owner.online.verification();
        SaoPackEngine.publish(() -> { owner.updateStatus.set(value); owner.releaseVerification.set(verification); });
        refreshReadinessLater();
    }

    /// Dismisses the currently advertised launcher version for this session.
    void dismissLauncherUpdate() {
        @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
        if (metadata != null) { owner.dismissedLauncherVersion = metadata.launcher().latest(); publishUpdates(); }
    }

    /// Validates even cached/injected links immediately before dispatch to the existing platform opener.
    private void openQuark(boolean launcher) {
        if (owner.closed.get() || owner.uninstalling.get()) return;
        @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
        if (metadata == null) { owner.windowFailure("无法打开下载", new IOException("暂无有效版本信息，请稍后重试")); return; }
        try { owner.linkOpener.accept(SaoUpdates.checkedQuark(metadata.quark()).toString()); }
        catch (Exception failure) { owner.windowFailure("打开夸克失败", failure); }
    }

    /// Opens the approved pack download page.
    void openPackDownload() { openQuark(false); }

    /// Opens the approved launcher download page.
    void openLauncherDownload() { openQuark(true); }

    /// Watches download directories while the engine owns the watcher lock.
    void watchPackDownload() {
        owner.cancelPackDownload();
        if (owner.closed.get() || owner.uninstalling.get()) return;
        @Nullable SaoData.VersionMetadata metadata = owner.versionMetadata;
        if (metadata == null) { owner.windowFailure("无法查找下载", new IOException("暂无有效版本信息，请稍后重试")); return; }
        long generation = owner.downloadGeneration.incrementAndGet();
        owner.worker.execute(() -> {
            try { publishDropPrompt(true, null); } catch (IOException failure) { owner.windowFailure("读取更新状态失败", failure); }
        });
        owner.downloadPoll = owner.publications.scheduleWithFixedDelay(() -> {
            if (owner.closed.get() || generation != owner.downloadGeneration.get()) return;
            try {
                @Nullable Path candidate = SaoDownloads.find(owner.downloadDirectories.get(), metadata.pack());
                if (candidate == null) for (var quick : metadata.updates()) {
                    candidate = SaoDownloads.find(owner.downloadDirectories.get(), new SaoData.PackRelease(quick.to(), quick.file(), quick.size(), quick.sha256()));
                    if (candidate != null) break;
                }
                @Nullable Path found = candidate;
                SaoPackEngine.publish(() -> {
                    if (generation == owner.downloadGeneration.get()) {
                        SaoData.DownloadPrompt current = owner.downloadPrompt.get();
                        owner.downloadPrompt.set(new SaoData.DownloadPrompt(true, found, current.message(), current.required(),
                                current.present(), current.missing(), current.fullPackRequired()));
                    }
                });
            } catch (IOException failure) { owner.windowFailure("查找下载失败", failure); }
        }, 0, 3, TimeUnit.SECONDS);
    }

    /// Stops the current watcher and retires its drop session.
    void cancelPackDownload() {
        owner.downloadGeneration.incrementAndGet();
        java.util.concurrent.@Nullable ScheduledFuture<?> poll = owner.downloadPoll;
        owner.downloadPoll = null; if (poll != null) poll.cancel(false);
        SaoPackEngine.publish(() -> owner.downloadPrompt.set(new SaoData.DownloadPrompt(false, null, "")));
        if (!owner.worker.isShutdown()) owner.worker.execute(() -> {
            @Nullable SaoDropSession session = owner.dropSession; owner.dropSession = null;
            if (session != null) try { session.close(); } catch (IOException ignored) { }
        });
    }

    /// Requests a refresh of the news and signed release policy.
    void refreshUpdates() { refreshUpdates(true); }

    /// Coalesces news work and reserves policy attempts before dispatch; failed attempts consume the same rate limit.
    void refreshUpdates(boolean forcePolicy) {
        if (!owner.productionUpdates || owner.closed.get() || owner.uninstalling.get() || !owner.updatesRefreshing.compareAndSet(false, true)) return;
        boolean fetchPolicy = owner.policyRefresh.acquire(forcePolicy);
        owner.onlineWorker.execute(() -> {
            try {
                try { owner.remoteNews = owner.online.fetch(System.getProperty("sao.newsUrl", SaoUpdates.NEWS_URL)); owner.publishNews(); }
                catch (Exception failure) { SaoOnlineData.rejected("news fetch", failure); }
                if (fetchPolicy) try {
                    SaoData.VersionMetadata metadata = owner.online.fetchVersion(System.getProperty("sao.versionUrl", SaoUpdates.VERSION_URL));
                    if (!SaoPackEngine.sameRelease(metadata, owner.versionMetadata)) owner.cancelPackDownload();
                    owner.versionMetadata = metadata; owner.applyAccountPolicy(metadata); owner.extras.updateQuark(metadata.quark()); publishUpdates();
                    owner.worker.execute(() -> { verifyLocalPackOrigin(); publishUpdates(); });
                } catch (Exception failure) { SaoOnlineData.rejected("version fetch", failure); }
            } finally { owner.updatesRefreshing.set(false); }
        });
    }

    /// Caches an already verified archive without changing which snapshot the installed receipt references.
    SaoPackArchive cacheSource(SaoPackArchive update) throws IOException {
        return cacheFullZip(update.zip(), update.manifest(), null);
    }

    /// Copies the full ZIP privately before establishing its inventory, byte identity and provenance.
    private SaoPackArchive cacheFullZip(Path input, @Nullable SaoPackManifest verified,
                                       SaoData.@Nullable PackRelease release) throws IOException {
        Path cache = SaoPackFiles.resolve(owner.root, "pack-cache");
        long budget = SaoDiskSpace.packRequired(Files.size(input));
        SaoDiskSpace.require(cache, budget, SaoDiskSpace.SYSTEM);
        try {
            Files.createDirectories(cache);
            Path temporary = Files.createTempFile(cache, "accepted-", ".tmp");
            try {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                String digest = SaoUpdates.sha256(temporary);
                SaoPackManifest manifest = verified != null ? verified : SaoPackArchive.openForImport(temporary).manifest();
                boolean official = verified != null || release != null && release.size() == Files.size(temporary)
                        && release.sha256().equals(digest) && release.version().equals(manifest.version());
                Path zip = SaoPackFiles.resolve(cache, (official ? "full-" : "local-") + digest + ".zip");
                try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                SaoPackFiles.atomicMove(temporary, zip);
                return new SaoPackArchive(zip, manifest);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException failure) { throw SaoDiskSpace.explainIo(cache, budget, failure); }
    }

    /// Recognizes exact full snapshots and inventories cached by older launchers or the quick updater.
    private static boolean acceptedCacheName(String name) {
        return name.matches("(?:[0-9a-f]{40}|(?:full|local)-[0-9a-f]{64})\\.zip");
    }

    /// Deletes superseded release archives from pack-cache, keeping the active one and the launcher's own embedded copy,
    /// so dead archives do not fill the disk and trip the free-space check.
    private void pruneCache(SaoPackArchive active) {
        Path cache = owner.root.resolve("pack-cache");
        String keep = active.zip().getFileName().toString();
        @Nullable String shipped = owner.embeddedArchive;
        try (var listing = Files.newDirectoryStream(cache, "*.zip")) {
            for (Path archive : listing) {
                String name = archive.getFileName().toString();
                if (!acceptedCacheName(name) || name.equals(keep) || name.equals(shipped)) continue;
                try { Files.deleteIfExists(archive); } catch (IOException ignored) { /* Retried after the next accepted release. */ }
            }
        } catch (IOException ignored) { /* The cache is only an optimization. */ }
    }

    /// Restores the last accepted release so restart never repairs or launches with an older embedded manifest.
    @Nullable SaoPackArchive readAcceptedSource() throws IOException {
        Path cache = owner.root.resolve("pack-cache");
        @Nullable SaoPackManifest installed = owner.installer.installed();
        if (installed != null) {
            @Nullable String committedArchive = owner.installer.installedArchive();
            if (committedArchive != null) {
                if (!acceptedCacheName(committedArchive)) throw new IOException("Invalid installed pack cache filename");
                Path retained = SaoPackFiles.resolve(cache, committedArchive);
                return Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS) ? new SaoPackArchive(retained, installed) : null;
            }
            String key = SaoPackArchive.sha1(new java.io.ByteArrayInputStream(installed.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Path retained = SaoPackFiles.resolve(cache, key + ".zip");
            if (Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS)) return new SaoPackArchive(retained, installed);
        }
        Path active = cache.resolve("active.json");
        if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) return null;
        try (java.io.Reader reader = Files.newBufferedReader(active, java.nio.charset.StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            String filename = json.get("archive").getAsString();
            if (!acceptedCacheName(filename)) throw new IOException("Invalid cached pack filename");
            Path archive = SaoPackFiles.resolve(cache, filename);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) return null;
            SaoPackManifest manifest = SaoPackManifest.read(new java.io.StringReader(json.get("manifest").toString()));
            return new SaoPackArchive(archive, manifest);
        } catch (RuntimeException failure) { throw new IOException("Invalid accepted update metadata", failure); }
    }

    /// Reconciles installed receipt and release version without hashing or downloading payloads.
    void refreshState() throws IOException {
        @Nullable SaoPackManifest installed = owner.installer.installed();
        @Nullable SaoPackArchive pack = owner.source;
        SaoPackState next = pack == null || installed == null ? SaoPackState.NOT_INSTALLED
                : VersionNumber.compare(pack.manifest().version(), installed.version()) > 0 ? SaoPackState.UPDATE_READY : SaoPackState.READY;
        SaoReadiness ready = computeReadiness();
        SaoPackEngine.publish(() -> {
            owner.readiness.set(ready);
            owner.installedVersion.set(installed == null ? null : installed.version()); owner.state.set(next); owner.progress.set(1); owner.step.set("");
            SaoData.UpdateStatus updates = SaoUpdates.status(owner.versionMetadata, owner.installedVersion.get(), Metadata.VERSION, owner.dismissedLauncherVersion);
            owner.updateStatus.set(updates); owner.error.set(updates.launcherBlocked() ? updates.message() : null);
        });
        publishDropPrompt(owner.downloadPrompt.get().watching(), owner.downloadPrompt.get().candidate());
    }
}
