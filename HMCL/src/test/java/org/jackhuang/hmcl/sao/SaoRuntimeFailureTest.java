/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.util.platform.UnsupportedPlatformException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/// Headless checks for the pack's Java component and the public engine error produced by runtime absence.
@NotNullByDefault
final class SaoRuntimeFailureTest {
    /// Disposable launcher state; tests never install a runtime or start a game.
    @TempDir Path temporary;

    /// Minecraft 1.20.1 uses gamma so available native Windows and macOS ARM64 Java 17 runtimes can be selected.
    @Test void packRequestsGammaJava17() {
        assertEquals("java-runtime-gamma", SaoGameBridge.GAME_JAVA_VERSION.component());
        assertEquals(17, SaoGameBridge.GAME_JAVA_VERSION.majorVersion());
    }

    /// A task-wrapped unsupported runtime becomes a short Chinese error in the actual engine failure publication.
    @Test void wrappedRuntimeAbsencePublishesShortChineseFailure() throws Exception {
        var unsupported = new UnsupportedPlatformException("Candidates: []");
        var wrapped = new ExecutionException(unsupported);
        Exception mapped = SaoGameBridge.runtimeDownloadFailure(wrapped);
        assertInstanceOf(IOException.class, mapped);
        assertSame(wrapped, mapped.getCause());
        try (SaoPackEngine engine = new SaoPackEngine(temporary, null, false)) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (engine.state().get() == SaoPackState.CHECKING) Thread.sleep(5);
            });
            // Exercise the production publication method without starting a download or widening its visibility.
            Method fail = SaoPackEngine.class.getDeclaredMethod("fail", String.class, Exception.class);
            fail.setAccessible(true);
            fail.invoke(engine, "整合包操作失败", mapped);
            assertEquals(SaoPackState.FAILED, engine.state().get());
            assertEquals("整合包操作失败: 暂无可用的 Java 17", engine.lastError().get());
            assertEquals(SaoInstallProgress.Stage.FAILED, engine.installProgress().get().stage());
        }
    }

    /// Transport failures retain their own identity and message rather than being mislabeled as runtime absence.
    @Test void preservesUnrelatedDownloadFailure() {
        var failure = new IOException("Download timed out");
        assertSame(failure, SaoGameBridge.runtimeDownloadFailure(failure));
    }

    /// Online launch failures show the launcher's own reason; unknown failures keep the type and hint.
    @Test void launchFailureShowsCuratedReason() {
        Exception wrapped = new RuntimeException("wrapper", new SaoAccountException("登录已失效，请重新登录", "invalidSession"));
        assertEquals("Link Start 失败：登录已失效，请重新登录", SaoPackEngine.launchFailure(wrapped));
        assertEquals("Link Start 失败（IllegalStateException）。请检查账户、网络和 Java 17 设置。",
                SaoPackEngine.launchFailure(new IllegalStateException("token abc")));
    }

    /// A maintenance notice or sequence bump does not count as a new release for dropped files.
    @Test void maintenanceChangeIsTheSameRelease() throws Exception {
        String sha = "a".repeat(64);
        String base = "{\"schema\":2,\"sequence\":1,\"quark\":\"" + SaoUpdates.QUARK_URL + "\",\"pack\":{\"version\":\"0.9\",\"file\":\"pack.zip\",\"size\":1,\"sha256\":\"" + sha
                + "\"},\"updates\":[],\"launcher\":{\"latest\":\"0.1.0\",\"min\":\"0.1.0\"}}";
        SaoData.VersionMetadata first = SaoUpdates.parse(base);
        SaoData.VersionMetadata notice = SaoUpdates.parse(base.replace("\"sequence\":1", "\"sequence\":2").replace("\"updates\"", "\"maintenance\":{\"message\":\"m\"},\"updates\""));
        assertTrue(SaoPackEngine.sameRelease(first, notice));
        assertFalse(SaoPackEngine.sameRelease(first, SaoUpdates.parse(base.replace("0.9", "0.10"))));
    }
}
