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
/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;

/// Network-free screenshot fixture. Construct and control on FX; force freezes any requested state.
@NotNullByDefault
public final class SaoScriptedMicrosoftFlow implements SaoMicrosoftFlow {
    /// Preview-only browser link; openBrowser intentionally does nothing.
    private static final String LINK = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?preview=true";
    /// Read-only observable payload.
    private final ReadOnlyObjectWrapper<State> value = new ReadOnlyObjectWrapper<>(sample(Kind.STARTING));
    /// Automatic preview sequence, stopped by every explicit control.
    private final Timeline script;
    /// Starts a local timeline without accounts, network, or browser side effects.
    public SaoScriptedMicrosoftFlow() {
        requireFx();
        script = new Timeline(
                new KeyFrame(Duration.millis(500), e -> value.set(sample(Kind.BROWSER))),
                new KeyFrame(Duration.seconds(2), e -> value.set(sample(Kind.SIGNING_IN))),
                new KeyFrame(Duration.seconds(3), e -> value.set(new State(Kind.SIGNING_IN, "", "", "", "正在读取 Minecraft 档案", "", ""))),
                new KeyFrame(Duration.seconds(4), e -> value.set(sample(Kind.DONE))));
        script.play();
    }

    /// Publishes the current preview state.
    @Override public ReadOnlyObjectProperty<State> state() { return value.getReadOnlyProperty(); }

    /// Never opens a real browser in screenshot mode.
    @Override public void openBrowser() { }

    /// Freezes a sample device-code screen.
    @Override public void useDeviceCode() { force(Kind.DEVICE_CODE); }

    /// Cancels the script and publishes cancellation.
    @Override public void cancel() { force(Kind.CANCELLED); }

    /// Freezes any lifecycle state, including FAILED, for deterministic screenshots.
    public void force(Kind kind) { requireFx(); script.stop(); value.set(sample(kind)); }

    /// Rejects off-thread mutations rather than quietly violating the public API contract.
    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Preview controls require FX thread");
    }

    /// Returns safe representative values; no real OAuth codes or credentials are used.
    private static State sample(Kind kind) {
        return new State(kind, kind == Kind.BROWSER ? LINK : "", kind == Kind.DEVICE_CODE ? "ABCD-EFGH" : "",
                kind == Kind.DEVICE_CODE ? "https://www.microsoft.com/link" : "",
                kind == Kind.SIGNING_IN ? "正在验证 Xbox 账号" : "", kind == Kind.DONE ? "Kirito" : "",
                kind == Kind.FAILED ? "网络连接失败，请重试" : "");
    }
}
