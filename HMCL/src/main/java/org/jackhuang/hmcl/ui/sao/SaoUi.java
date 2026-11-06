/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// Switches between the SAO screen and HMCL's own pages (`-Dsao.ui=false` shows HMCL's, for debugging only).
@NotNullByDefault
public final class SaoUi {
    /// Whether the SAO screen replaces HMCL's main window content.
    private static final boolean ENABLED = !"false".equals(System.getProperty("sao.ui"));

    /// Not instantiable.
    private SaoUi() {
    }

    /// Whether the SAO screen is on.
    ///
    /// @return true unless turned off with `-Dsao.ui=false`
    public static boolean enabled() {
        return ENABLED;
    }
}
