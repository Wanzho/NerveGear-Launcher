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

import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises actual cached check publications without starting a JavaFX toolkit or native window.
@NotNullByDefault
class SaoPackChecksTest {
    /// A completed install replaces stale Java warnings; older asynchronous replies cannot restore them.
    @Test void refreshedChecksReplaceCachedWarningsAndFenceOldReplies() throws Exception {
        var initial = new CompletableFuture<@Unmodifiable List<SaoData.SystemCheck>>();
        var older = new CompletableFuture<@Unmodifiable List<SaoData.SystemCheck>>();
        var latest = new CompletableFuture<@Unmodifiable List<SaoData.SystemCheck>>();
        Queue<CompletableFuture<@Unmodifiable List<SaoData.SystemCheck>>> requests = new ArrayDeque<>();
        requests.add(initial); requests.add(older); requests.add(latest);
        Class<?> @Unmodifiable [] interfaces = {SaoLauncherService.class};
        SaoLauncherService service = (SaoLauncherService) Proxy.newProxyInstance(getClass().getClassLoader(), interfaces,
                (Object proxy, Method method, Object @Nullable [] arguments) -> {
                    if (method.getName().equals("systemCheck")) return requests.remove();
                    throw new UnsupportedOperationException(method.getName());
                });
        @Unmodifiable List<SaoData.SystemCheck> warning = List.of(new SaoData.SystemCheck(SaoData.CheckLevel.WARN, "missing Java"));
        SaoPackApp.runChecks(service); initial.complete(warning); drainFx();
        assertEquals(warning, SaoPackApp.checks().get());
        SaoPackApp.refreshChecks(service);
        SaoPackApp.refreshChecks(service); latest.complete(List.of()); drainFx();
        assertEquals(List.of(), SaoPackApp.checks().get());
        older.complete(warning); drainFx();
        assertEquals(List.of(), SaoPackApp.checks().get());
        assertTrue(requests.isEmpty());
    }

    /// Waits for queued property publication when another suite has initialized JavaFX.
    private static void drainFx() throws Exception {
        CompletableFuture<@Nullable Void> done = new CompletableFuture<>();
        try { javafx.application.Platform.runLater(() -> done.complete(null)); }
        catch (IllegalStateException headless) { return; }
        done.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
