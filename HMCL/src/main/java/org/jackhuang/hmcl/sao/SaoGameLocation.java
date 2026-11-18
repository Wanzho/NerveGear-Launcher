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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiConsumer;

/// Validates player-selected data folders and copies a verified snapshot before committing a root pointer.
@NotNullByDefault
public final class SaoGameLocation {
    /// Ownership marker, written only in empty folders or folders with a valid installed receipt.
    static final String MARKER = ".nervegear-data";
    /// All movable game data; launcher preferences and credentials remain in Roaming.
    static final @Unmodifiable List<String> TREES = List.of("minecraft", "pack-cache", "java", "dependencies", "logs");
    /// Extra working space required beyond the existing game bytes.
    static final long RESERVE = 2 * SaoDiskSpace.GB;
    /// Space required for a fresh game, pack and managed Java.
    static final long FRESH = 5 * SaoDiskSpace.GB;

    /// Utility class.
    private SaoGameLocation() { }

    /// Filesystem admission facts, injectable without a Windows machine.
    @NotNullByDefault
    public record Volume(String format, long free, boolean fixed, boolean writable) { }
    /// Target and volume information for a secondary drive shortcut.
    @NotNullByDefault
    public record Drive(Path folder, long free, @Nullable String problem) { }
    /// Filesystem probe; tests supply fake stores and fixed-disk status.
    @NotNullByDefault
    @FunctionalInterface
    interface Probe {
        /// Inspects the nearest existing ancestor without creating the target.
        Volume inspect(Path path) throws IOException;
    }

    /// Transaction callbacks; rollback must restore the old in-memory components and persisted pointer.
    @NotNullByDefault
    interface Switch {
        /// Persists and binds the selected root.
        void select(Path root) throws Exception;

        /// Rechecks the new game's readiness, without downloading.
        boolean ready() throws Exception;
    }

    /// Copies one file; injectable to exercise partial-write failures.
    @NotNullByDefault
    @FunctionalInterface
    interface Copy {
        /// Writes a previously absent destination.
        void file(Path from, Path to) throws IOException;
    }

    /// Resolves the game root for auxiliary downloaders without caching an install-location-derived path.
    public static Path currentRoot() {
        try { return resolve(new SaoSettings(org.jackhuang.hmcl.Metadata.HMCL_USER_HOME), org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME); }
        catch (IOException failure) { throw new java.io.UncheckedIOException("无法读取游戏位置", failure); }
    }

    /// Minimal lazy Unicode Win32 binding; loaded only after the Windows platform guard.
    @NotNullByDefault
    private interface DriveApi extends com.sun.jna.win32.StdCallLibrary {
        /// Process-wide kernel32 handle; no JNA platform dependency is needed.
        DriveApi INSTANCE = com.sun.jna.Native.load("kernel32", DriveApi.class);
        /// Returns DRIVE_FIXED (3) for a local fixed volume.
        int GetDriveTypeW(com.sun.jna.WString root);
    }

    /// Reads NTFS/free-space from the actual FileStore and fixed-disk status from Windows.
    static Volume volume(Path target) throws IOException {
        Path existing = ancestor(target);
        FileStore store = Files.getFileStore(existing);
        boolean fixed = !com.sun.jna.Platform.isWindows()
                || DriveApi.INSTANCE.GetDriveTypeW(new com.sun.jna.WString(existing.getRoot().toString())) == 3;
        return new Volume(store.type(), store.getUsableSpace(), fixed, !store.isReadOnly() && Files.isWritable(existing));
    }

    /// Finds an existing directory, rejecting symlink/junction indirection below the filesystem root.
    private static Path ancestor(Path path) throws IOException {
        Path existing = path.toAbsolutePath().normalize();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            @Nullable Path parent = existing.getParent();
            if (parent == null) throw new IOException("找不到这个盘，请连接磁盘后重试");
            existing = parent;
        }
        if (!Files.isDirectory(existing) || Files.isSymbolicLink(existing)) throw new IOException("请选择普通文件夹，不能使用链接");
        // toRealPath also resolves Windows junctions, which isSymbolicLink does not identify.
        if (!existing.toRealPath().equals(existing)) throw new IOException("文件夹经过链接或重定向，请选择真实位置");
        return existing;
    }

    /// Pure policy plus folder inspection, with fake FileStores and sync roots available to tests.
    static void validate(Path target, Path current, long bytes, int deepest, Probe probe,
                         @Unmodifiable List<Path> synced, boolean windows) throws IOException {
        target = target.toAbsolutePath().normalize(); current = current.toAbsolutePath().normalize();
        if (target.startsWith(current) || current.startsWith(target)) throw new IOException("新位置不能在当前游戏文件夹内，也不能是它的上级");
        if (target.getParent() == null) throw new IOException("请选择盘内的文件夹，不能直接选择盘根目录");
        String lower = target.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String part : lower.split("/")) {
            if (part.equals("windows") || part.equals("program files") || part.equals("program files (x86)"))
                throw new IOException("不能选择 Windows 或 Program Files 文件夹");
            if (part.contains("onedrive") || part.equals("dropbox") || part.equals("google drive")
                    || part.equals("googledrive") || part.equals("icloud drive") || part.equals("icloudrive")
                    || part.equals("box") || part.equals("nutstore")) throw new IOException("不能选择 OneDrive 或其他同步文件夹");
        }
        for (Path sync : synced) if (target.startsWith(sync.toAbsolutePath().normalize()))
                throw new IOException("不能选择 OneDrive 或其他同步文件夹");
        if (windows && (long) target.toString().length() + 1 + Math.max(180, deepest) >= 250)
            throw new IOException("路径太长，游戏最深路径必须少于 250 个字符，请选更短的文件夹");
        Volume store = probe.inspect(target);
        if (windows && !store.format().equalsIgnoreCase("NTFS")) throw new IOException(store.format() + " 格式，游戏位置需要 NTFS");
        if (!store.fixed()) throw new IOException("请选择本地固定磁盘");
        if (!store.writable()) throw new IOException("这个文件夹不能写入，请选择有写入权限的位置");
        long needed = owned(target) ? RESERVE : bytes == 0 ? FRESH : Math.addExact(bytes, RESERVE);
        if (store.free() < needed) throw new IOException("空间不够，还差 " + ((needed - store.free() + SaoDiskSpace.GB - 1) / SaoDiskSpace.GB) + " GB");
        if (Files.exists(target)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("请选择文件夹");
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent() && !owned(target)) throw new IOException("这个文件夹不是空的，也不是已有的 NerveGear 游戏数据文件夹");
            }
        }
    }

    /// Detects an existing data folder by marker and game directory, or a readable legacy pack receipt.
    static boolean owned(Path root) {
        if (Files.isRegularFile(root.resolve(MARKER)) && Files.isDirectory(root.resolve("minecraft"))) return true;
        try {
            return new SaoPackInstaller(root.resolve("minecraft/versions/sao-oath"), (file, path) -> { }, Map.of(), (label, progress) -> { }).installed() != null;
        } catch (IOException invalid) { return false; }
    }

    /// Environment and registered sync-provider roots, including renamed OneDrive/Dropbox roots.
    static @Unmodifiable List<Path> syncRoots() {
        List<Path> roots = new ArrayList<>();
        for (String key : List.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial")) {
            @Nullable String value = System.getenv(key);
            if (value != null && !value.isBlank()) roots.add(Path.of(value));
        }
        if (com.sun.jna.Platform.isWindows()) {
            try {
                @Nullable org.jackhuang.hmcl.util.platform.windows.WinReg registry = org.jackhuang.hmcl.util.platform.windows.WinReg.INSTANCE;
                if (registry == null) throw new IllegalStateException("Windows 注册表不可用");
                var user = org.jackhuang.hmcl.util.platform.windows.WinReg.HKEY.HKEY_CURRENT_USER;
                String providers = "Software\\SyncEngines\\Providers";
                for (String provider : registry.querySubKeys(user, providers))
                    for (String account : registry.querySubKeys(user, provider)) {
                        @Nullable Object mount = registry.queryValue(user, account, "MountPoint");
                        if (mount instanceof String path && !path.isBlank()) roots.add(Path.of(path));
                    }

            } catch (RuntimeException inaccessible) { throw new IllegalStateException("无法检查同步文件夹设置，请检查 Windows 权限", inaccessible); }
        }
        return List.copyOf(roots);
    }

    /// Production admission, including a real create/delete probe after policy checks pass.
    static void validate(Path target, Path current) throws IOException {
        validate(target, current, bytes(current), deepest(current), SaoGameLocation::volume, syncRoots(), com.sun.jna.Platform.isWindows());
        try {
            Files.createDirectories(target);
            Path test = Files.createTempFile(target, ".nervegear-write-", ".tmp");
            Files.delete(test);
        } catch (IOException failure) { throw new IOException("这个文件夹不能写入，请选择有写入权限的位置", failure); }
    }

    /// Measured movable bytes; absent roots are never created.
    static long bytes(Path root) throws IOException {
        long total = 0;
        for (Path file : inventory(root)) total = Math.addExact(total, Files.size(root.resolve(file)));
        return total;
    }

    /// Actual deepest relative file path, with a conservative reserve for future game files in validation.
    private static int deepest(Path root) throws IOException {
        int longest = 0;
        for (Path file : inventory(root)) longest = Math.max(longest, file.toString().length());
        return longest;
    }

    /// Captures regular files and refuses links or special files rather than following them outside the game.
    private static @Unmodifiable List<Path> inventory(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : TREES) {
            Path tree = root.resolve(name);
            if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                /// Rejects Windows junction directories and symlinks before entering them.
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.toRealPath().equals(dir.toAbsolutePath().normalize())) throw new IOException("游戏目录包含链接: " + dir);
                    return FileVisitResult.CONTINUE;
                }

                /// Includes every regular file, including all player saves and options.
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("游戏目录包含非普通文件: " + file);
                    files.add(root.relativize(file)); return FileVisitResult.CONTINUE;
                }
            });
        }
        return List.copyOf(files);
    }

    /// SHA-1 is used for every file, exceeding the archive-only minimum and protecting player data too.
    private static String hash(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) { return SaoPackArchive.sha1(input); }
    }

    /// Copies to an empty target, verifies source stability and all destination bytes, then switches and checks READY.
    /// Failures before commit keep the old root and every copied target file intact. Cleanup failures restore old
    /// files from the verified destination before attempting pointer rollback; neither destination copy is deleted.
    static void move(Path old, Path target, Switch pointer, Copy copier, BiConsumer<Long, Long> progress) throws Exception {
        move(old, target, pointer, copier, progress, true);
    }

    /// Incomplete first installations may select a verified new location but must keep their old partial data.
    static void move(Path old, Path target, Switch pointer, Copy copier, BiConsumer<Long, Long> progress, boolean removeOld) throws Exception {
        @Unmodifiable List<Path> files = inventory(old);
        long total = bytes(old), done = 0;
        Files.createDirectories(target);
        try (var entries = Files.list(target)) {
            if (entries.findAny().isPresent()) throw new IOException("搬家目标必须为空；已有数据请使用接管");
        }
        copyDirectories(old, target);
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (Path relative : files) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("搬家已取消，原位置未改变");
            Path from = old.resolve(relative), to = target.resolve(relative);
            String before = hash(from);
            long length = Files.size(from);
            Files.createDirectories(to.getParent()); copier.file(from, to);
            if (Files.size(to) != length || !before.equals(hash(to)) || !before.equals(hash(from)))
                throw new IOException("文件校验失败，原位置未改变: " + relative);
            hashes.put(relative, before); done += length; progress.accept(done, total);
        }
        if (!new HashSet<>(inventory(old)).equals(new HashSet<>(files))) throw new IOException("复制期间原目录发生变化，请重试");
        for (var entry : hashes.entrySet()) if (!entry.getValue().equals(hash(old.resolve(entry.getKey()))))
                throw new IOException("复制期间文件发生变化: " + entry.getKey());
        Files.writeString(target.resolve(MARKER), "NerveGear data v1\n", StandardOpenOption.CREATE_NEW);
        boolean selected = false;
        try {
            selected = true; pointer.select(target);
            if (!pointer.ready()) throw new IOException("新位置游戏尚未 READY，已回到原位置；两份数据都保留");
        } catch (Exception failure) {
            if (selected) try { pointer.select(old); } catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        if (!removeOld) return;
        // Only a READY destination permits deletion. Restore from the verified copy if cleanup fails.
        try {
            for (String name : TREES) deleteTree(old.resolve(name));
            Files.deleteIfExists(old.resolve(MARKER));
            if (Files.isDirectory(old)) try (var remaining = Files.list(old)) { if (remaining.findAny().isEmpty()) Files.delete(old); }
        } catch (IOException cleanup) {
            // A failed removal must not strand a half-deleted original. Restore it from the verified copy first.
            try {
                copyDirectories(target, old);
                for (Path relative : files) {
                    Path restored = old.resolve(relative);
                    if (!Files.exists(restored)) Files.copy(target.resolve(relative), restored, StandardCopyOption.COPY_ATTRIBUTES);
                    if (!hashes.get(relative).equals(hash(restored))) throw new IOException("旧副本恢复校验失败: " + relative);
                }
                pointer.select(old);
            } catch (Exception restore) { cleanup.addSuppressed(restore); }
            throw new IOException("旧目录清理失败，已尝试恢复原位置；请保留两份数据: " + old + " / " + target, cleanup);
        }
    }

    /// Preserves empty directories as well as file parents, without following links.
    private static void copyDirectories(Path from, Path to) throws IOException {
        for (String name : TREES) {
            Path tree = from.resolve(name);
            if (!Files.exists(tree)) continue;
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                /// Creates only directories from the already validated tree.
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(to.resolve(from.relativize(dir))); return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /// Removes only a committed, verified old tree, without following links.
    private static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(tree, new SimpleFileVisitor<>() {
            /// Removes a single file; symlinks are removed themselves, never traversed.
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }

            /// Removes directories after their children.
            @Override public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException error) throws IOException {
                if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Resolves the persisted pointer, including missing destinations; never silently falls back or creates a folder.
    static Path resolve(SaoSettings settings, Path fallback) {
        String chosen = settings.text("gameDataRoot", "");
        return chosen.isBlank() ? fallback : Path.of(chosen).toAbsolutePath().normalize();
    }

    /// User-facing missing-drive diagnosis, kept distinct from an uninstalled game.
    static @Nullable String missing(SaoSettings settings, Path root) {
        if (settings.text("gameDataRoot", "").isBlank() || Files.isDirectory(root)) return null;
        String text = root.toString();
        return text.length() > 1 && text.charAt(1) == ':'
                ? "游戏在 " + text.charAt(0) + " 盘，但现在找不到 " + text.charAt(0) + " 盘（" + root + "）；请重新选择游戏位置"
                : "找不到游戏位置：" + root + "；请连接磁盘或重新选择游戏位置";
    }
}
