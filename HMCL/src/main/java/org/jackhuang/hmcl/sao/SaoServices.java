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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Holds the one [SaoLauncherService] the launcher runs with. The backend installs its engine at start-up;
/// the frontend may install a fake for screen work before the engine exists.
@NotNullByDefault
public final class SaoServices {
    /// The installed service, or null before start-up has installed one.
    private static volatile @Nullable SaoLauncherService service;

    /// Not instantiable.
    private SaoServices() {
    }

    /// Installs the service; called once at start-up.
    ///
    /// @param s the service to use from now on
    public static void install(SaoLauncherService s) {
        service = s;
    }

    /// Releases the installed backend and wipes any pending one-use password before application shutdown.
    /// Frontend preview services are simply detached.
    public static void close() {
        @Nullable SaoLauncherService installed = service;
        service = null;
        if (installed instanceof SaoPackEngine engine) engine.close();
    }

    /// The installed service.
    ///
    /// @throws IllegalStateException when nothing is installed yet
    static SaoLauncherService service() {
        @Nullable SaoLauncherService s = service;
        if (s == null) throw new IllegalStateException("SaoLauncherService not installed");
        return s;
    }
}
