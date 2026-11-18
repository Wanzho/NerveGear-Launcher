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
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.nio.file.*;
import java.util.*;

/// Admits launcher and offline sessions, then hands Minecraft startup to the engine’s bridge.
@NotNullByDefault
final class SaoGameLaunch {
    /// Supplies the existing launcher state and callbacks.
    private final SaoPackEngine owner;

    /// Creates the component for its owning SaoPackEngine.
    SaoGameLaunch(SaoPackEngine owner) {
        this.owner = owner;
    }

    /// Starts the supplied account after protecting and transferring its password buffer.
    void linkStart(Account account, char @Nullable [] authPassword) {
        char @Nullable [] pending = authPassword == null ? null : authPassword.clone();
        if (authPassword != null) Arrays.fill(authPassword, '\0');
        owner.guardGame(() -> { startGame(account, pending, false); return CompletableFuture.completedFuture(null); })
                .whenComplete((ignored, failure) -> { if (pending != null) Arrays.fill(pending, '\0'); });
    }

    /// Validates the exact ephemeral name without persisting it or consulting ARGUS ID.
    void linkStartOffline(String name) {
        if (owner.closed.get() || owner.uninstalling.get()) return;
        // Invalid input must fail locally before any process query or confirmation can run.
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            SaoPackEngine.publish(() -> owner.error.set("名字须为 3–16 位英文字母、数字或下划线"));
            return;
        }
        owner.guardGameAction(() -> startOfflineNow(name));
    }

    /// Starts offline only after the instance process guard has completed.
    private void startOfflineNow(String name) {
        if (owner.closed.get() || owner.uninstalling.get()) return;
        if (!launchable(owner.state.get()) || owner.busy.get()) { if (owner.busy.get()) owner.refuseBusy(); return; }
        startGame(SaoGameBridge.offlineAccount(name), null, true);
    }

    /// States from which Link Start may begin; a failed attempt can be retried without Repair or a restart.
    private static boolean launchable(SaoPackState current) {
        return current == SaoPackState.NOT_INSTALLED || current == SaoPackState.UPDATE_READY
                || current == SaoPackState.READY || current == SaoPackState.FAILED;
    }

    /// Runs the shared install/update/launch lifecycle; offline mode suppresses server authentication and joining.
    private void startGame(Account account, char @Nullable [] authPassword, boolean offline) {
        @Nullable String clockProblem = offline ? null : SaoClockCheck.SYSTEM.currentProblem();
        if (clockProblem != null) {
            if (authPassword != null) Arrays.fill(authPassword, '\0');
            SaoPackEngine.publish(() -> owner.error.set(clockProblem)); clearLaunchProgress(); return;
        }
        if (owner.closed.get() || owner.uninstalling.get()) {
            if (authPassword != null) Arrays.fill(authPassword, '\0');
            return;
        }
        if (!offline && !owner.launcherPermitted()) {
            if (authPassword != null) Arrays.fill(authPassword, '\0');
            return;
        }
        if (!offline && owner.localPack()) {
            if (authPassword != null) Arrays.fill(authPassword, '\0');
            SaoPackEngine.publish(() -> owner.error.set("本地整合包仅支持离线单人；进入正式服请导入官方完整包"));
            return;
        }
        char @Nullable [] password = authPassword == null ? null : authPassword.clone();
        if (authPassword != null) Arrays.fill(authPassword, '\0');
        // WORKING with nothing busy is Link Start's own account check, which leads here.
        SaoPackState now = owner.state.get();
        if (!(launchable(now) || now == SaoPackState.WORKING) || !owner.busy.compareAndSet(false, true)) {
            if (password != null) Arrays.fill(password, '\0');
            launchLog("start ignored: " + now + (owner.busy.get() ? ", busy" : ""));
            return;
        }
        owner.activePassword = password;
        owner.working();
        owner.worker.execute(() -> {
            try {
                if (owner.closed.get()) throw new IOException("Launcher has closed");
                int launchMemory = SaoPackEngine.checkedMemoryMiB(owner.launchMemoryMiB(), owner.systemMemoryMiB());
                if (!offline && !owner.launcherPermitted()) { owner.releaseBusy(); owner.maintenance.refreshState(); return; }
                // A prior ARGUS launch must not leak a handoff into dependency preparation or this run.
                if (offline) owner.game.clearOfflineTicket();
                owner.installer.recover();
                @Nullable SaoPackManifest repairInventory = owner.installer.installed();
                if (!offline && owner.productionUninstall && owner.source != null && repairInventory != null
                        && owner.source.manifest().version().equals(repairInventory.version()))
                    owner.game.repairBeforeLaunch(new SaoPackArchive(owner.requireSource().zip(), repairInventory), this::launchStep);
                // Link Start only launches: not ready, it says why and the button turns back to 下载/更新.
                SaoReadiness ready = owner.computeReadiness(offline);
                if (!ready.ready()) { owner.releaseBusy(); launchLog("refused: " + ready.kind()); owner.maintenance.refuse(ready); return; }
                launchStep("检查游戏文件");
                @Nullable SaoPackManifest previous = owner.installer.installed();
                SaoPackManifest launchManifest;
                if (offline) {
                    if (previous == null) throw new IOException(SaoReadinessCheck.INCOMPLETE);
                    owner.steps.ensureInstalled(false);
                    launchManifest = previous;
                } else {
                    SaoPackArchive pack = owner.requireSource();
                    boolean install = previous == null || VersionNumber.compare(pack.manifest().version(), previous.version()) > 0;
                    if (install) owner.extras.checkInstallCapacity();
                    owner.steps.ensureInstalled(false);
                    if (install) owner.installer.apply(pack, false);
                    launchManifest = pack.manifest();
                }
                owner.files.enforceRequiredResources();
                owner.extras.beforeLaunch();
                if (!offline && !owner.launcherPermitted()) { owner.releaseBusy(); launchLog("refused: launcher update required"); owner.maintenance.refreshState(); return; }
                // RUNNING waits for the real process: until then the bar names each step (James, 14 Nov: "按linkstart没有反应
                // ……我根本不知道"). Account checks and the ticket are network round trips that took seconds with no sign.
                launchStep(offline ? "启动游戏" : "登录游戏服务器");
                SaoPackEngine.publish(() -> owner.installedVersion.set(launchManifest.version()));
                owner.legacyHelmetSignal = SaoHelmetSignal.legacy(owner.game.instanceDir().resolve("mods"));
                if (owner.closed.get()) throw new IOException("Launcher has closed");
                long started = System.currentTimeMillis();
                owner.saveSetting("lastRunStarted", Long.toString(started));
                owner.saveSetting("lastExitAbnormal", false);
                owner.logs.clear();
                SaoPackEngine.publish(() -> { owner.gameLog.clear(); owner.crash.set(null); });
                owner.lifecycle.begin(owner.launcherBehavior());
                owner.gameRunning.set(true);
                owner.steps.launch(account, launchManifest, launchMemory, owner.gameWindow(), owner.jvmArgs(), password, offline,
                        offline ? null : owner.signedServer(), owner.closed::get, new ProcessListener() {
                    /// Starts accounting after the OS process exists and before HMCL starts its exit monitor.
                    @Override public void setProcess(org.jackhuang.hmcl.util.platform.ManagedProcess process) {
                        owner.heldGame = process.getProcess();
                        owner.gameExit = new CompletableFuture<>();
                        if (account instanceof SaoOathLaunchAccount launched) owner.oath.gameStarted(launched);
                        owner.lifecycle.started();
                        launchLog("game process started");
                        SaoPackEngine.publish(() -> { owner.step.set("Link Start"); owner.state.set(SaoPackState.RUNNING); });
                    }

                    /// Captures sanitized output only; handoff and authentication lines are filtered first.
                    @Override public void onLog(String line, boolean stderr) { owner.appendGameLog(line); }

                    /// Restores the launcher before waiting for log readers or rescanning the instance.
                    @Override public void onProcessExit() {
                        if (!owner.gameRunning.getAndSet(false) || owner.closed.get() || owner.uninstalling.get()) return;
                        owner.activeTask = "整理游戏退出信息";
                        SaoPackEngine.publish(() -> {
                            if (owner.state.get() == SaoPackState.RUNNING) {
                                owner.step.set("正在整理游戏退出信息");
                                owner.state.set(SaoPackState.WORKING);
                            }
                        });
                        owner.lifecycle.exited();
                        launchLog("game process exited; reading exit information");
                    }

                    /// Reconciles the fixed instance and analyzes this run after the real process exits.
                    @Override public void onExit(int code, ExitType type) {
                        onProcessExit();
                        if (account instanceof SaoOathLaunchAccount) owner.oath.gameExited();
                        if (owner.closed.get() || owner.uninstalling.get()) { owner.heldGame = null; owner.gameExit.complete(null); return; }
                        owner.worker.execute(() -> {
                            try {
                                owner.saveSetting("lastExitAbnormal", code != 0 || type != ExitType.NORMAL);
                                @Nullable SaoData.Crash result = owner.logs.analyze(code != 0 || type != ExitType.NORMAL, started);
                                SaoPackEngine.publish(() -> owner.crash.set(result));
                                owner.refreshLocalNow(); owner.maintenance.refreshState();
                            } catch (Exception failure) { owner.windowFailure("读取游戏退出信息失败", failure); }
                            finally { owner.heldGame = null; owner.releaseBusy(); owner.gameExit.complete(null); }
                        });
                    }
                });
                owner.lifecycle.handedOff();
                // Busy remains true for the running process; its monitor releases it on exit.
            } catch (Exception failure) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Link Start failed: " + SaoPackEngine.launchFailure(failure), failure);
                owner.gameRunning.set(false);
                owner.lifecycle.exited();
                owner.releaseBusy();
                if (offline) SaoPackEngine.publish(() -> { owner.error.set("离线启动失败，请检查整合包和 Java 17 设置");
                    owner.state.set(SaoPackState.FAILED); owner.step.set(""); owner.progress.set(-1); });
                else if (owner.source == null) owner.fail("Link Start 失败", failure);
                else SaoPackEngine.publish(() -> { owner.error.set(SaoPackEngine.launchFailure(failure));
                    owner.state.set(SaoPackState.FAILED); owner.step.set(""); owner.progress.set(-1); });
            } finally {
                clearLaunchProgress();
                if (password != null) Arrays.fill(password, '\0');
                owner.activePassword = null;
            }
        });
    }

    /// Starts ARGUS admission after the existing game process check.
    void linkStartOath() { owner.guardGameAction(this::linkStartOathNow); }

    /// Refreshes the account only after an old game has exited.
    private void linkStartOathNow() {
        if (owner.closed.get() || owner.uninstalling.get()) return;
        @Nullable String clockProblem = SaoClockCheck.SYSTEM.currentProblem();
        if (clockProblem != null) { SaoPackEngine.publish(() -> owner.error.set(clockProblem)); clearLaunchProgress(); return; }
        // An install or a game already running owns this launch; a repeat click must not refresh the session again.
        // It is never silent: the reason goes on the status line (James, 14 Nov).
        if (owner.busy.get() || !launchable(owner.state.get())) {
            owner.refuseBusy();
            return;
        }
        if (!owner.launcherPermitted()) { launchLog("refused: launcher update required"); return; }
        launchLog("clicked");
        owner.working();
        launchStep("验证账号");
        // A refused account refresh never reaches startGame; FAILED makes the helmet show its reason.
        owner.oath.linkStart().whenComplete((ignored, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Link Start account check failed: " + SaoPackEngine.launchFailure(cause));
                SaoPackEngine.publish(() -> { if (!owner.busy.get()) { owner.state.set(SaoPackState.FAILED); owner.step.set(""); owner.progress.set(-1);
                    owner.installProgress.set(SaoInstallProgress.phase(SaoInstallProgress.Stage.IDLE, ""));
                    if (owner.error.get() == null) owner.error.set(SaoPackEngine.launchFailure(cause)); } });
            } else if (!owner.busy.get()) {
                // Signed out, locked, or a launch already in flight: nothing started, so the bar goes back.
                launchLog("account check ended without a launch");
                clearLaunchProgress();
                owner.worker.execute(() -> { try { if (!owner.busy.get()) owner.maintenance.refreshState(); } catch (IOException ignoredRefresh) { } });
            }
        });
    }

    /// Removes launch-only progress after success, failure, cancellation or a readiness refusal; the error stays visible.
    private void clearLaunchProgress() {
        SaoPackEngine.publish(() -> owner.installProgress.set(SaoInstallProgress.phase(SaoInstallProgress.Stage.IDLE, "")));
    }

    /// The step name the bar shows now, for the "wait" message of an ignored click.
    private String currentStepName() {
        String label = owner.installProgress.get().label();
        if (label.isBlank()) label = owner.step.get();
        return label.isBlank() ? "准备" : label;
    }

    /// One bar label and one log line per Link Start step; no account data.
    private void launchStep(String label) {
        owner.activeTask = label;
        launchLog("step " + label);
        SaoPackEngine.publish(() -> { if (owner.state.get() == SaoPackState.WORKING) owner.installProgress.set(SaoInstallProgress.phase(SaoInstallProgress.Stage.PREPARING, label)); });
    }

    private static void launchLog(String what) { org.jackhuang.hmcl.util.logging.Logger.LOG.info("Link Start: " + what); }
}
