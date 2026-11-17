/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Startup can analyze a long, prior native report without losing its graphics-driver diagnosis.
@NotNullByDefault
final class SaoGameLogsLongCrashTest {
    /// Isolated instance; no real user logs are used.
    @TempDir Path temporary;

    /// Exercises the same clean-start report lookup and crash analysis that runs during launcher initialization.
    @Test void previousLargeNativeReportDoesNotOverflow() throws Exception {
        Path report = temporary.resolve("hs_err_pid1234.log");
        Files.writeString(report, "# EXCEPTION_ACCESS_VIOLATION\r\n" + "synthetic native frame details\n".repeat(7000)
                + "# C  [nvoglv64.dll+0x123]\n");
        SaoData.Crash crash = assertDoesNotThrow(() -> new SaoGameLogs(temporary).analyze(false, 0));
        assertNotNull(crash);
        assertEquals(report, crash.report());
        assertEquals(SaoData.Crash.DRIVER_REASON, crash.reason());
    }
}
