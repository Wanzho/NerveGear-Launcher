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

import org.jetbrains.annotations.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/// Capacity admission and disk-full diagnostics for staged pack writes and future offline-bundle imports.
@NotNullByDefault
public final class SaoDiskSpace {
    /// Binary GB, matching the launcher's existing disk-size display.
    static final long GB = 1024L * 1024 * 1024;
    /// Production probe follows the nearest existing ancestor onto the actual target filesystem.
    static final FreeSpace SYSTEM = SaoDiskSpace::usableBytes;

    /// Filesystem-specific injectable capacity probe; errors are propagated rather than treated as free capacity.
    @NotNullByDefault
    @FunctionalInterface
    public interface FreeSpace {
        /// Returns usable bytes for the filesystem containing the target, even before the target exists.
        long available(Path target) throws IOException;
    }

    /// Utility class.
    private SaoDiskSpace() { }

    /// Resolves the actual target filesystem without rejecting legitimate aliases above the installer's managed root.
    /// The installer independently rejects links inside managed paths before any payload writes.
    private static long usableBytes(Path target) throws IOException {
        @Nullable Path current = target.toAbsolutePath().normalize();
        while (current != null && !java.nio.file.Files.exists(current)) current = current.getParent();
        if (current == null) throw new IOException("找不到实例所在磁盘");
        return java.nio.file.Files.getFileStore(current.toRealPath()).getUsableSpace();
    }

    /// Reserves three complete pack copies and 2 GB; arithmetic overflow conservatively requires maximal capacity.
    public static long packRequired(long packBytes) { return budget(packBytes, 3, 2 * GB); }

    /// Reserves two complete offline-bundle copies and 1 GB for a future offline importer.
    public static long offlineRequired(long bundleBytes) { return budget(bundleBytes, 2, GB); }

    /// Checks a future offline import against the actual target drive without introducing a new importer.
    public static void requireOffline(Path target, long bundleBytes) throws IOException {
        requireOffline(target, bundleBytes, SYSTEM);
    }

    /// Exposes the offline import gate with deterministic capacity injection.
    public static void requireOffline(Path target, long bundleBytes, FreeSpace free) throws IOException {
        require(target, offlineRequired(bundleBytes), free);
    }

    /// Counts the expanded complete inventory, saturating rather than wrapping on malformed enormous sizes.
    static long packBytes(SaoPackManifest manifest) {
        long bytes = 0;
        for (var file : manifest.files()) {
            try { bytes = Math.addExact(bytes, file.size()); }
            catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        }
        return bytes;
    }

    /// Requires the larger of expanded payload and archive size, covering poorly compressible archives too.
    static long required(SaoPackArchive pack) throws IOException {
        return packRequired(Math.max(packBytes(pack.manifest()), java.nio.file.Files.size(pack.zip())));
    }

    /// Refuses insufficient capacity before any directory, stage, snapshot or payload is created.
    static void require(Path target, long required, FreeSpace free) throws IOException {
        long available = free.available(target);
        if (available < 0) throw new IOException("无法检查磁盘空间，请检查实例位置");
        if (available < required) throw shortage(target, required - available, null);
    }

    /// Identifies OS disk-full/quota errors anywhere in a wrapped download or filesystem exception.
    static boolean isFull(Throwable failure) {
        @Nullable Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            @Nullable String message = cause.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("no space left") || lower.contains("not enough space") || lower.contains("disk full")
                    || lower.contains("disk is full") || lower.contains("quota exceeded") || lower.contains("enospc")
                    || lower.contains("磁盘空间不足") || lower.contains("磁盘已满") || lower.contains("空间不够")) return true;
        }
        return false;
    }

    /// Converts only an actual disk-full failure, preserving unrelated errors and the original cause for logs.
    static Exception explain(Path target, long required, FreeSpace free, Exception failure) {
        if (failure instanceof Shortage || !isFull(failure)) return failure;
        long available = 0;
        try { available = Math.max(0, free.available(target)); }
        catch (IOException probe) { failure.addSuppressed(probe); }
        return shortage(target, Math.max(1, required - available), failure);
    }

    /// IOException variant used by snapshot writers after their partial files have been scheduled for cleanup.
    static IOException explainIo(Path target, long required, IOException failure) {
        return (IOException) explain(target, required, SYSTEM, failure);
    }

    /// Formats Windows drive letters even in headless tests on another OS; other platforms show the target root.
    static IOException shortage(Path target, long missing, @Nullable Throwable cause) {
        String path = target.toString();
        String drive = path.matches("(?i)^[a-z]:.*") ? path.substring(0, 1).toUpperCase(Locale.ROOT)
                : String.valueOf(target.toAbsolutePath().getRoot());
        long gigabytes = Math.max(1, missing / GB + (missing % GB == 0 ? 0 : 1));
        return new Shortage(drive + " 盘空间不够，还需要 " + gigabytes + " GB", cause);
    }

    /// Typed diagnostic prevents outer cache/instance handlers from relabeling the failing drive.
    @NotNullByDefault
    private static final class Shortage extends IOException {
        /// Preserves the original disk-full error while exposing only the actionable capacity message.
        private Shortage(String message, @Nullable Throwable cause) { super(message, cause); }
    }

    /// Validates sizes and saturates multiplication/addition on overflow.
    private static long budget(long size, int copies, long reserve) {
        if (size < 0) throw new IllegalArgumentException("Negative payload size");
        try { return Math.addExact(Math.multiplyExact(size, copies), reserve); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
}
