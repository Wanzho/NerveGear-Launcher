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
package org.jackhuang.hmcl.ui.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// Localizes the vanilla hidden-listing placeholder without changing ordinary player names.
@NotNullByDefault
public final class SaoPlayerNames {
    /// Utility class.
    private SaoPlayerNames() { }

    /// Vanilla sends this reserved display text for players who disable server listings.
    public static String display(String name) {
        return "Anonymous Player".equals(name) ? "匿名玩家" : name;
    }
}
