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
