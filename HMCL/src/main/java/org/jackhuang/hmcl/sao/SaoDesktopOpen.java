/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import java.util.List;

/// Shell-free file-manager command construction, including Windows Unicode paths and file selection.
@NotNullByDefault
final class SaoDesktopOpen {
    /// Utility class.
    private SaoDesktopOpen() { }

    /// Each path is one ProcessBuilder argument; no cmd.exe, manual shell quoting, or PATH lookup is used.
    static @Unmodifiable List<String> command(OperatingSystem os, String path, boolean reveal, String systemRoot) {
        return switch (os) {
            case WINDOWS -> reveal ? List.of(systemRoot + "\\explorer.exe", "/select,", path)
                    : List.of(systemRoot + "\\explorer.exe", path);
            case MACOS -> reveal ? List.of("/usr/bin/open", "-R", path) : List.of("/usr/bin/open", path);
            default -> List.of("xdg-open", path);
        };
    }
}
