/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see SaoPackState.java for the full notice.
 */
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

    /// The installed service.
    ///
    /// @throws IllegalStateException when nothing is installed yet
    static SaoLauncherService service() {
        SaoLauncherService s = service;
        if (s == null) throw new IllegalStateException("SaoLauncherService not installed");
        return s;
    }
}
