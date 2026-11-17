/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Long native crashes must remain safe to analyze without changing graphics-driver classification.
@NotNullByDefault
final class CrashReportAnalyzerLongLogTest {
    /// Driver and non-driver frame variants across CRLF and LF, with synthetic content only.
    @Test void longNativeReportsDoNotOverflowAndRetainDriverClassification() {
        String prefix = "# EXCEPTION_ACCESS_VIOLATION\r\n" + "synthetic native frame details\n".repeat(7000);
        for (String driver : new String[]{"ig", "atio", "nvoglv"}) {
            String report = prefix + "# C  [" + driver + "64.dll+0x123]\n";
            assertTrue(CrashReportAnalyzer.analyze(report).stream()
                    .anyMatch(result -> result.rule() == CrashReportAnalyzer.Rule.GRAPHICS_DRIVER), driver);
        }
        assertFalse(CrashReportAnalyzer.analyze(prefix + "# C  [other.dll+0x123]\n").stream()
                .anyMatch(result -> result.rule() == CrashReportAnalyzer.Rule.GRAPHICS_DRIVER));
    }
}
