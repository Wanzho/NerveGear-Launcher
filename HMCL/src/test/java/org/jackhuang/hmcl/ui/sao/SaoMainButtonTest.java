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
package org.jackhuang.hmcl.ui.sao;

import org.jackhuang.hmcl.sao.SaoReadiness;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// The helmet page's main button before login (James, 11 Nov: "要啊"): shown only as 下载 / 更新. No toolkit, no window.
@NotNullByDefault
class SaoMainButtonTest {
    /// Logged out and nothing installed: the button shows and reads 下载; once ready it hides until login.
    @Test void loggedOutNotReadyShowsDownload() {
        SaoFakeService fake = new SaoFakeService();
        assertNull(fake.oathAccount().get());
        SaoReadiness notReady = fake.readiness().get();
        assertTrue(notReady.shownSignedOut());
        assertEquals("下载", notReady.buttonText());

        fake.forceReadiness(new SaoReadiness(SaoReadiness.Kind.UPDATE, "有新版本 0.8.2", SaoReadiness.Action.QUARK));
        assertTrue(fake.readiness().get().shownSignedOut());
        assertEquals("更新", fake.readiness().get().buttonText());

        fake.forceInstalled("0.8");
        assertFalse(fake.readiness().get().shownSignedOut(), "Link Start still needs the login or 离线进入");
        assertEquals("Link Start", fake.readiness().get().buttonText());
        assertFalse(SaoReadiness.CHECKING.shownSignedOut());
    }
}
