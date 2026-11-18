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

import org.jetbrains.annotations.NotNullByDefault;

/// ARGUS admission progress, separate from the locally known Microsoft identity.
@NotNullByDefault
public record SaoOathLinking(Kind kind, String reason) {
    /// Terminal failure preserves the same curated Chinese reason as the sign-in future.
    @NotNullByDefault
    public enum Kind {
        /// No Microsoft linking attempt is active.
        IDLE,
        /// Microsoft identity may be shown while ARGUS admission is pending.
        LINKING,
        /// ARGUS account refresh completed successfully.
        LINKED,
        /// Admission failed; identity is not permission to launch.
        FAILED
    }

    /// Initial or signed-out state.
    public static SaoOathLinking idle() { return new SaoOathLinking(Kind.IDLE, ""); }
}
