/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// Where the embedded 誓约 pack stands on this machine. The SAO screen picks what Link Start does from this.
@NotNullByDefault
public enum SaoPackState {
    /// Still checking the instance (first moments after start).
    CHECKING,
    /// Nothing installed yet; 下载 runs the first install (Link Start refuses).
    NOT_INSTALLED,
    /// The pack this launcher has is newer than the installed one; 更新 installs it (Link Start refuses).
    UPDATE_READY,
    /// Install, update or repair is running; progress comes from [SaoLauncherService#progress()].
    WORKING,
    /// Installed and current; Link Start launches once [SaoLauncherService#readiness()] agrees.
    READY,
    /// The game is running.
    RUNNING,
    /// The last install, update, repair or launch failed; [SaoLauncherService#lastError()] says why.
    FAILED
}
