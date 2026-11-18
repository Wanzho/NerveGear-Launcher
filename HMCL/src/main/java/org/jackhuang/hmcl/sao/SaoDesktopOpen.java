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
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import java.util.List;

/// Shell-free file-manager command construction, including Windows Unicode paths and file selection.
@NotNullByDefault
final class SaoDesktopOpen {
    /// Utility class.
    private SaoDesktopOpen() { }

    /// Each path is one ProcessBuilder argument; no cmd.exe, manual shell quoting, or PATH lookup is used.
    static @Unmodifiable List<String> command(OperatingSystem os, String path, boolean reveal, String systemRoot) {
        return switch (os) {
            case WINDOWS -> reveal ? List.of(systemRoot + "\\explorer.exe", "/select,", path)
                    : List.of(systemRoot + "\\explorer.exe", path);
            case MACOS -> reveal ? List.of("/usr/bin/open", "-R", path) : List.of("/usr/bin/open", path);
            default -> List.of("xdg-open", path);
        };
    }
}
