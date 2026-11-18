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
package org.jackhuang.hmcl.ui.sao;

import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Consumer;

/// Switches between the SAO screen and HMCL's own pages (`-Dsao.ui=false` shows HMCL's, for debugging only).
@NotNullByDefault
public final class SaoUi {
    /// Whether the SAO screen replaces HMCL's main window content.
    private static final boolean ENABLED = !"false".equals(System.getProperty("sao.ui"));

    /// The launcher's name on screen (James, 9 Nov: "这是NerveGear启动器"); HMCL is credited only in About.
    public static final String LAUNCHER_NAME = "NerveGear启动器";

    /// The team's 爱发电 page (About), without the share link's tracking query.
    static final String AFDIAN_URL = "https://afdian.com/a/mczhbfwq";

    /// The built-in Quark share (About), used until the service hands the launcher's own Quark link from the version
    /// metadata.
    static final String QUARK_URL = "https://pan.quark.cn/s/1a81ea41915c";

    /// Opens web links in the default browser; the screenshot tools swap in an inert one.
    static Consumer<String> linkOpener = FXUtils::openLink;

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
