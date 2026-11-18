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
