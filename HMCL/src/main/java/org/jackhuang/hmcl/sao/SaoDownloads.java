/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/// Native Downloads discovery and exact-name/size lookup; never starts a browser or scans outside known folders.
@NotNullByDefault
final class SaoDownloads {
    /// Utility class.
    private SaoDownloads() { }

    /// Windows known-folder API uses a GUID pointer; this keeps the dependency limited to the existing JNA core.
    @NotNullByDefault
    private interface ShellFolders extends StdCallLibrary {
        /// Returns the actual (possibly localized or redirected) Downloads folder owned by the current user.
        int SHGetKnownFolderPath(Pointer folder, int flags, @Nullable Pointer token, PointerByReference result);
    }

    /// Frees the path string allocated by the Windows shell.
    @NotNullByDefault
    private interface OleMemory extends StdCallLibrary {
        /// Releases a COM task allocation after copying its string.
        void CoTaskMemFree(Pointer pointer);
    }

    /// Resolves Windows' known folder or Linux XDG configuration before portable fallbacks.
    static @Unmodifiable List<Path> directories() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> result = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try (Memory guid = new Memory(16)) {
                // FOLDERID_Downloads = 374DE290-123F-4565-9164-39C4925E467B, encoded in native GUID layout.
                guid.setInt(0, 0x374de290); guid.setShort(4, (short) 0x123f); guid.setShort(6, (short) 0x4565);
                guid.write(8, new byte[]{(byte) 0x91, 0x64, 0x39, (byte) 0xc4, (byte) 0x92, 0x5e, 0x46, 0x7b}, 0, 8);
                PointerByReference value = new PointerByReference();
                if (Native.load("shell32", ShellFolders.class).SHGetKnownFolderPath(guid, 0, null, value) == 0) {
                    @Nullable Pointer path = value.getValue();
                    if (path != null) {
                        try { result.add(Path.of(path.getWideString(0))); }
                        finally { Native.load("ole32", OleMemory.class).CoTaskMemFree(path); }
                    }
                }
            } catch (RuntimeException | LinkageError ignored) { /* Portable fallbacks still permit manual selection. */ }
        } else if (!os.contains("mac")) {
            @Nullable String config = System.getenv("XDG_CONFIG_HOME");
            Path settings = config == null || config.isBlank() ? home.resolve(".config") : Path.of(config);
            try { result.addAll(xdg(settings.resolve("user-dirs.dirs"), home)); }
            catch (IOException | RuntimeException ignored) { /* Missing configuration falls back to ~/Downloads. */ }
        }
        result.add(home.resolve("Downloads"));
        if (os.contains("win")) result.add(home.resolve("下载"));
        return List.copyOf(new LinkedHashSet<>(result));
    }

    /// Reads only the XDG download assignment and expands HOME without executing shell content.
    static @Unmodifiable List<Path> xdg(Path configuration, Path home) throws IOException {
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS) || Files.size(configuration) > 16384) return List.of();
        for (String line : Files.readAllLines(configuration)) {
            String assignment = line.strip();
            if (!assignment.startsWith("XDG_DOWNLOAD_DIR=")) continue;
            String value = assignment.substring("XDG_DOWNLOAD_DIR=".length());
            if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) return List.of();
            value = value.substring(1, value.length() - 1).replace("${HOME}", home.toString()).replace("$HOME", home.toString());
            if (value.contains("$") || value.contains("`") || value.contains("\\")) return List.of();
            Path path = Path.of(value);
            return path.isAbsolute() ? List.of(path.normalize()) : List.of();
        }
        return List.of();
    }

    /// Finds only an exact basename and size; incomplete browser suffixes and symlinks are ignored.
    static @Nullable Path find(List<Path> directories, SaoData.PackRelease release) throws IOException {
        SaoUpdates.filename(release.file());
        for (Path directory : directories) {
            Path candidate = directory.resolve(release.file());
            try {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && Files.size(candidate) == release.size()) return candidate;
            } catch (IOException ignored) { /* One inaccessible folder must not hide another usable Downloads folder. */ }
        }
        return null;
    }
}
