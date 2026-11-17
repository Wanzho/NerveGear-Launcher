package org.jackhuang.hmcl.sao;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.file.*;

/// Resolves the OS-owned Desktop for new diagnostic files, including OneDrive and redirected desktops.
/// This does not relax the separate uninstall/delete path checks.
@NotNullByDefault
final class SaoExportDestination {
    /// Utility class.
    private SaoExportDestination() { }

    /// Narrow Windows shell folder binding; no registry enumeration or account access.
    @NotNullByDefault
    private interface ShellFolders extends StdCallLibrary {
        /// Retrieves the current user's physical Desktop directory, including shell redirection.
        int SHGetFolderPathW(@Nullable Pointer window, int folder, @Nullable Pointer token, int flags, char[] buffer);
    }

    /// Chooses the shell Desktop when writable, otherwise the explicit launcher data root.
    static Path desktop(Path fallback) {
        if (com.sun.jna.Platform.isWindows()) {
            try {
                char[] buffer = new char[1024];
                if (Native.load("shell32", ShellFolders.class).SHGetFolderPathW(null, 0x10, null, 0, buffer) == 0)
                    return select(Path.of(Native.toString(buffer)), fallback);
            } catch (RuntimeException | LinkageError unavailable) { /* Use the conventional discovery fallback. */ }
        }
        return select(SaoUninstaller.realDesktop(), fallback);
    }

    /// Canonicalizes only an OS-selected directory; a unique new archive never replaces a user's existing file.
    static Path select(Path desktop, Path fallback) {
        try {
            if (desktop.isAbsolute() && Files.isDirectory(desktop) && Files.isWritable(desktop)) return desktop.toRealPath();
        } catch (IOException | RuntimeException unavailable) { /* The data root remains an explicit, usable fallback. */ }
        return fallback;
    }
}
