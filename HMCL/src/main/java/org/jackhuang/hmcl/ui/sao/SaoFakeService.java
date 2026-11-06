/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Arrays;

/// A stand-in engine for screen work before the real one is installed: Link Start plays a pretend install
/// and launch, nothing touches the disk or the network.
@NotNullByDefault
public final class SaoFakeService implements SaoLauncherService {
    /// The pretend state.
    private final SimpleObjectProperty<SaoPackState> state = new SimpleObjectProperty<>(SaoPackState.NOT_INSTALLED);
    /// The pretend progress.
    private final SimpleDoubleProperty progress = new SimpleDoubleProperty(-1);
    /// The pretend step.
    private final SimpleStringProperty step = new SimpleStringProperty("");
    /// The pretend error.
    private final SimpleObjectProperty<@Nullable String> error = new SimpleObjectProperty<>();
    /// The pretend installed version.
    private final SimpleObjectProperty<@Nullable String> installed = new SimpleObjectProperty<>();
    /// The pretend skin.
    private @Nullable Path skin;
    /// The pretend arm model.
    private boolean slim;
    /// The pretend memory.
    private int memory = 8192;

    /// Sets the state directly (screenshots of each state).
    ///
    /// @param s        the state
    /// @param p        the progress
    /// @param label    the step label
    /// @param err      the error, or null
    public void force(SaoPackState s, double p, String label, @Nullable String err) {
        state.set(s);
        progress.set(p);
        step.set(label);
        error.set(err);
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyObjectProperty<SaoPackState> state() {
        return state;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyDoubleProperty progress() {
        return progress;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyStringProperty stepLabel() {
        return step;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyObjectProperty<@Nullable String> lastError() {
        return error;
    }

    /// {@inheritDoc}
    @Override
    public ReadOnlyObjectProperty<@Nullable String> installedVersion() {
        return installed;
    }

    /// {@inheritDoc}
    @Override
    public String embeddedVersion() {
        return "0.8";
    }

    /// Plays a pretend install over six seconds, then a pretend game for four.
    @Override
    public void linkStart(Account account, char @Nullable [] authPassword) {
        if (authPassword != null) Arrays.fill(authPassword, '\0');
        String[] steps = {"解压整合包", "下载 Minecraft", "安装 NeoForge", "校验文件", "启动游戏"};
        state.set(SaoPackState.WORKING);
        Timeline t = new Timeline();
        for (int i = 0; i <= 60; i++) {
            int k = i;
            t.getKeyFrames().add(new KeyFrame(Duration.millis(100 * i), e -> {
                progress.set(k / 60.0);
                step.set(steps[Math.min(steps.length - 1, k / 13)]);
            }));
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(6200), e -> {
            installed.set("0.8");
            state.set(SaoPackState.RUNNING);
        }));
        t.getKeyFrames().add(new KeyFrame(Duration.millis(10200), e -> state.set(SaoPackState.READY)));
        t.play();
    }

    /// {@inheritDoc}
    @Override
    public void repair() {
    }

    /// {@inheritDoc}
    @Override
    public void applyUpdateZip(Path zip) {
    }

    /// {@inheritDoc}
    @Override
    public void setSkin(@Nullable Path png, boolean slim) {
        this.skin = png;
        this.slim = slim;
    }

    /// {@inheritDoc}
    @Override
    public @Nullable Path skin() {
        return skin;
    }

    /// {@inheritDoc}
    @Override
    public boolean skinSlim() {
        return slim;
    }

    /// {@inheritDoc}
    @Override
    public int memoryMiB() {
        return memory;
    }

    /// {@inheritDoc}
    @Override
    public void setMemoryMiB(int mib) {
        memory = mib;
    }

    /// {@inheritDoc}
    @Override
    public Path instanceDir() {
        return Path.of(System.getProperty("user.home"), ".sao-oath-launcher", "instance");
    }
}
