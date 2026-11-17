/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

/// Small filesystem operations shared by pack installation, receipts and skin storage.
@NotNullByDefault
final class SaoPackFiles {
    /// Utility class.
    private SaoPackFiles() { }

    /// Resolves a relative path and rejects symbolic links at or below the explicit managed root.
    /// Ancestor aliases outside that boundary, such as macOS /var → /private/var, are allowed.
    static Path resolve(Path root, String path) throws IOException {
        SaoPackManifest.validatePath(path);
        Path absolute = root.toAbsolutePath().normalize();
        Path result = absolute.resolve(path).normalize();
        if (!result.startsWith(absolute)) throw new IOException("Path leaves the instance: " + path);
        @Nullable Path current = result;
        while (current != null && current.startsWith(absolute)) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link in managed path: " + path);
            current = current.getParent();
        }
        return result;
    }

    /// Checks exact byte size, SHA-1 and the optional index SHA-512; absent and nonregular files never match.
    static boolean matches(Path path, SaoPackManifest.PackFile file) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != file.size()) return false;
        try (InputStream input = Files.newInputStream(path)) { if (!file.sha1().equals(SaoPackArchive.sha1(input))) return false; }
        return file.sha512() == null || file.sha512().equals(org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-512", path));
    }

    /// Replaces a file atomically when supported, with a same-filesystem replacement fallback.
    static void atomicMove(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    /// Deletes only a staging tree owned by this operation; does not follow symbolic links.
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
