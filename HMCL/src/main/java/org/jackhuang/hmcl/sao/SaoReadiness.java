/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// Whether Link Start can launch right now, and what the main button does when it cannot.
///
/// James, 11 Nov 2026: "link start 是启动游戏，下载还是老实叫下载吧，linkstart前自动检查一下游戏版本". Not ready, the
/// main button reads 「下载」 (or 「更新」 over an older installed pack) and only prepares; ready, it reads Link Start.
/// The check is cheap (versions, instance identity, Java 17, pack file presence and size); hashing stays in 下载/修复.
///
/// @param kind   what the main button shows
/// @param reason one short game-voice line when not ready (「还没下载」, 「有新版本 0.8.2」, 「游戏文件不完整」), else empty
/// @param action what pressing 下载/更新 does; [Action#NONE] when ready or still checking
@NotNullByDefault
public record SaoReadiness(Kind kind, String reason, Action action) {
    /// Before the first check completes.
    public static final SaoReadiness CHECKING = new SaoReadiness(Kind.CHECKING, "", Action.NONE);
    /// Everything Link Start needs is present.
    public static final SaoReadiness READY = new SaoReadiness(Kind.READY, "", Action.NONE);

    /// The main button's state.
    public enum Kind {
        /// Not checked yet; the button keeps its last text and is idle.
        CHECKING,
        /// Nothing usable installed, or the game files are incomplete: 「下载」.
        DOWNLOAD,
        /// An older pack version is installed: 「更新」.
        UPDATE,
        /// Link Start launches.
        READY
    }

    /// What 下载/更新 does.
    public enum Action {
        /// Nothing to do (ready or checking).
        NONE,
        /// Local preparation through [SaoLauncherService#download()]: Java, Minecraft, NeoForge, the pack and its mods.
        PREPARE,
        /// A newer signed pack exists than any pack this launcher has: [SaoLauncherService#download()] opens the Quark
        /// link and watches Downloads for the zip (James, 11 Nov: "这样的话跳夸克链接").
        QUARK,
        /// No pack and no Quark link known: the screen opens the 整合包 window.
        PACK_WINDOW
    }

    /// Whether Link Start may launch.
    public boolean ready() { return kind == Kind.READY; }

    /// The main button's text.
    public String buttonText() {
        return switch (kind) {
            case DOWNLOAD -> "下载";
            case UPDATE -> "更新";
            case CHECKING, READY -> "Link Start";
        };
    }
}
