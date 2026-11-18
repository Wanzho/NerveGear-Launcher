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

import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import org.jetbrains.annotations.Unmodifiable;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/// Delayed metadata and panel ownership regressions without a toolkit, network, or real downloads.
@NotNullByDefault
class SaoPackDownloadWatchTest {
    /// Observable backend seam; an early lookup is an explicit test failure.
    @NotNullByDefault
    private static final class Fixture {
        /// Authenticated metadata publication, initially unavailable.
        final ReadOnlyObjectWrapper<SaoData.UpdateStatus> status = new ReadOnlyObjectWrapper<>(state(null));
        /// Watch lifecycle counts.
        int started, cancelled;
        /// Immutable interface list for the isolated backend seam.
        private final Class<?> @Unmodifiable [] interfaces = {SaoLauncherService.class};
        /// Only the methods used by the panel owner are implemented.
        final SaoLauncherService service = (SaoLauncherService) Proxy.newProxyInstance(getClass().getClassLoader(),
                interfaces, (Object proxy, Method method, Object @Nullable [] args) -> switch (method.getName()) {
                    case "updateStatus" -> status.getReadOnlyProperty();
                    case "watchPackDownload" -> { assertNotNull(status.get().metadata()); started++; yield null; }
                    case "cancelPackDownload" -> { cancelled++; yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /// Minimal signed-policy-shaped fixture; no real signature or request is bypassed by production code.
    private static SaoData.VersionMetadata policy(long sequence, String version) {
        return new SaoData.VersionMetadata(2, sequence, "https://pan.quark.cn/s/test",
                new SaoData.PackRelease(version, "pack.zip", 1, "0".repeat(64)), List.of(),
                new SaoData.LauncherRelease("0.1.1", "0.1.0"));
    }

    /// Wraps available or missing metadata as the UI receives it.
    private static SaoData.UpdateStatus state(SaoData.@Nullable VersionMetadata metadata) {
        return new SaoData.UpdateStatus(metadata, false, false, false, "");
    }

    /// Startup waits silently; policy arrival starts one watch and a new artifact restarts it.
    @Test void delayedPolicyStartsOnceAndSequenceOnlyRefreshKeepsDrops() {
        var fixture = new Fixture();
        try (var watch = new SaoPackDownloadWatch(fixture.service)) {
            assertEquals(0, fixture.started);
            fixture.status.set(state(policy(1, "0.8.1.1"))); assertEquals(1, fixture.started);
            fixture.status.set(state(policy(2, "0.8.1.1"))); assertEquals(1, fixture.started);
            fixture.status.set(state(policy(3, "0.8.1.2"))); assertEquals(2, fixture.started);
        }
        assertEquals(1, fixture.cancelled);
    }

    /// Closing while metadata is unavailable prevents a later reply from starting a detached panel's watch.
    @Test void closedPanelCannotStartOnLatePolicy() {
        var fixture = new Fixture(); var watch = new SaoPackDownloadWatch(fixture.service);
        watch.close(); watch.close(); fixture.status.set(state(policy(1, "0.8.1.1")));
        assertEquals(0, fixture.started); assertEquals(0, fixture.cancelled);
    }

    /// A cached verified policy can start immediately; closing cancels exactly once.
    @Test void cachedPolicyStartsImmediately() {
        var fixture = new Fixture(); fixture.status.set(state(policy(1, "0.8.1.1")));
        var watch = new SaoPackDownloadWatch(fixture.service);
        assertEquals(1, fixture.started); watch.close(); watch.close(); assertEquals(1, fixture.cancelled);
    }
}
