/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// The UI fake gains reviewable common-feature samples without editing any file in the UI tree.
@NotNullByDefault
class SaoExtrasSamplesTest {
    /// Creates an inert stand-in for the UI fake; default methods use their normal interface implementations.
    private static SaoLauncherService fake() {
        return (SaoLauncherService) Proxy.newProxyInstance(SaoLauncherService.class.getClassLoader(), new Class<?>[]{SaoLauncherService.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /// Exercises every new service through the same decorator seam offered to the UI worker.
    @Test void samplesAndReversibleState() throws Exception {
        var service = SaoExtrasSamples.decorate(fake());
        assertEquals(SaoData.LauncherBehavior.HIDE, service.launcherBehavior()); service.setLauncherBehavior(SaoData.LauncherBehavior.CLOSE);
        assertEquals(SaoData.LauncherBehavior.CLOSE, service.launcherBehavior());
        service.setGraphicsPreset(SaoData.GraphicsPreset.FAST); assertEquals(SaoData.GraphicsPreset.FAST, service.graphicsPreset());
        assertEquals(SaoData.GraphicsPreset.BALANCED, service.suggestedGraphicsPreset().get());
        assertEquals(2, service.links().size()); assertSame(service.links(), service.links()); service.refreshLinks();
        assertThrows(UnsupportedOperationException.class, () -> service.links().clear());
        assertEquals(4, service.systemCheck().get().size()); assertTrue(service.buildFeedbackZip().get().path().toString().contains("DEMO-NOT-A-REAL-FILE"));
        assertFalse(service.userNoticeAccepted()); assertThrows(IllegalArgumentException.class, () -> service.acceptUserNotice("old"));
        service.acceptUserNotice(service.userNoticeVersion()); assertTrue(service.userNoticeAccepted());
        List<SaoData.NotificationKind> kinds = new ArrayList<>();
        service.notification().addListener((observable, old, next) -> { if (next != null) kinds.add(next.kind()); });
        service.setLauncherAttention(true, true); assertTrue(kinds.isEmpty());
        for (int i = 0; i < 3; i++) service.setLauncherAttention(false, false);
        assertEquals(List.of(SaoData.NotificationKind.MESSAGE, SaoData.NotificationKind.FRIEND_REQUEST, SaoData.NotificationKind.INVITE), kinds);
        assertFalse(SaoExtrasSamples.decorate(fake()).userNoticeAccepted());
    }
}
