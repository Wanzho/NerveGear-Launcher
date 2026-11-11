/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/// Filesystem-only uninstall transaction. Preservation completes and is verified before any removal.
/// Only explicit launcher roots are traversed; symbolic links are never followed.
@NotNullByDefault
final class SaoUninstaller {
    /// Absolute roots whose complete contents belong to this launcher, without nested duplicates.
    private final @Unmodifiable List<Path> roots;
    /// The isolated game's directory, used only for save/screenshot preservation.
    private final Path instance;
    /// Desktop or an explicitly injected fixture directory, outside every removal root.
    private final Path desktop;
    /// Native Trash adapter; fixtures inject one that never invokes the desktop.
    private final Trash trash;
    /// Production requires an isolated launcher namespace in addition to ordinary path guards.
    private final boolean production;
    /// Reserved collision-safe recovery destination; published before removal begins.
    private volatile Path kept;

    /// Injectable operating-system Trash capability. Failure to trash must never silently delete instead.
    @NotNullByDefault
    interface Trash {
        /// Whether this environment can send a file or directory to Trash.
        boolean supported();
        /// Moves the supplied owned path to Trash or throws, leaving permanent deletion disabled.
        void move(Path path) throws IOException;
    }

    /// Builds an explicit plan without reading, creating, copying or deleting filesystem contents.
    SaoUninstaller(List<Path> dataRoots, Path instance, Path desktop, Trash trash, boolean production) {
        List<Path> unique = new ArrayList<>();
        for (Path path : dataRoots) {
            Path absolute = path.toAbsolutePath().normalize();
            if (unique.stream().anyMatch(absolute::startsWith)) continue;
            unique.removeIf(existing -> existing.startsWith(absolute));
            unique.add(absolute);
        }
        roots = List.copyOf(unique);
        this.instance = instance.toAbsolutePath().normalize();
        this.desktop = desktop.toAbsolutePath().normalize();
        this.trash = trash;
        this.production = production;
        kept = this.desktop.resolve("刀剑神域·誓约 存档");
    }

    /// Uses the JDK desktop action when provided by this platform; unsupported environments delete directly.
    static Trash desktopTrash() {
        return new Trash() {
            /// Queries capability only when background uninstall work starts.
            @Override public boolean supported() {
                return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
            }
            /// A false native result is a failure, never permission to delete permanently.
            @Override public void move(Path path) throws IOException {
                try {
                    if (!Desktop.getDesktop().moveToTrash(path.toFile())) throw new IOException("系统废纸篓拒绝接收启动器文件");
                } catch (RuntimeException failure) { throw new IOException("无法移动启动器文件到系统废纸篓", failure); }
            }
        };
    }

    /// Returns the current intended or reserved recovery folder without filesystem work.
    Path keptSavesFolder() { return kept; }

    /// Immutable root plan also used by the detached final cleanup after normal shutdown hooks run.
    @Unmodifiable List<Path> roots() { return roots; }

    /// Rejects broad/shared roots, unsafe production overrides, root aliases and recovery destinations inside data.
    void validate() throws IOException {
        if (roots.isEmpty()) throw new IOException("没有可安全卸载的启动器目录");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        for (Path root : roots) {
            guardRoot(root, home);
            if (production) {
                String name = root.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.equals(".sao-oath-launcher") && !name.equals("sao-oath-launcher"))
                    throw new IOException("自定义数据目录未使用启动器专属名称，已拒绝自动卸载");
            }
            if (desktop.startsWith(root) || kept.startsWith(root) || root.startsWith(kept))
                throw new IOException("保留存档目录与卸载目录重叠");
        }
        if (roots.stream().noneMatch(instance::startsWith)) throw new IOException("游戏目录不在启动器目录内");
        rejectSymlinkAncestors(instance);
        rejectSymlinkAncestors(desktop);
    }

    /// Refuses filesystem/user/shared system roots even for an explicitly injected fixture plan.
    static void guardRoot(Path path, Path home) throws IOException {
        Path root = path.toAbsolutePath().normalize();
        if (root.getParent() == null || root.getNameCount() < 2 || home.startsWith(root)
                || root.equals(home.resolve("Desktop")) || root.equals(home.resolve("Documents"))
                || root.equals(home.resolve("Downloads")) || root.equals(home.resolve("Library"))
                || root.equals(home.resolve("AppData")) || root.equals(home.resolve(".local"))
                || root.equals(home.resolve(".local/share")) || root.equals(home.resolve(".config")))
            throw new IOException("卸载目录不是启动器专属目录");
        for (Path part : root) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".minecraft") || name.equals(".hmcl"))
                throw new IOException("不能卸载官方游戏或其他启动器目录");
        }
        for (String shared : List.of("/Applications", "/System", "/Library", "/usr", "/bin", "/sbin", "/etc", "/opt", "/var", "/private", "/tmp", "/Users", "/home"))
            if (root.equals(Path.of(shared))) throw new IOException("不能卸载系统或共享目录");
        rejectSymlinkAncestors(root);
    }

    /// Checks every existing ancestor, preventing a root or parent alias from redirecting removal elsewhere.
    static void rejectSymlinkAncestors(Path path) throws IOException {
        @Nullable Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw new IOException("卸载路径包含符号链接，已停止操作");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther() || !current.equals(current.toRealPath()))
                    throw new IOException("卸载路径包含重解析点或目录别名，已停止操作");
            }
            current = current.getParent();
        }
    }

    /// Routine (sign-in, Link Start, skins) check: rejects links and aliases only below the launcher data root that holds
    /// the path, so a junction or moved profile above the launcher does not block play. Uninstall keeps the full check.
    static void rejectLinksBelowRoot(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        @Nullable Path base = null;
        for (Path root : List.of(org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME, org.jackhuang.hmcl.Metadata.HMCL_USER_HOME))
            if (absolute.startsWith(root) && !absolute.equals(root) && (base == null || root.getNameCount() > base.getNameCount())) base = root;
        if (base == null) { rejectSymlinkAncestors(absolute); return; }
        rejectLinksBelow(base, absolute);
    }

    /// Rejects symbolic links, reparse points and aliases strictly below `base`; `base` and its ancestors may be links.
    static void rejectLinksBelow(Path base, Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        @Nullable Path real = Files.exists(base) ? base.toRealPath() : null;
        for (@Nullable Path current = absolute; current != null && !current.equals(base); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("启动器文件夹「" + current.getFileName() + "」是链接，已停止操作");
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther() || real != null && !current.toRealPath().equals(real.resolve(base.relativize(current))))
                throw new IOException("启动器文件夹「" + current.getFileName() + "」是链接或别名，已停止操作");
        }
    }

    /// Measures regular file bytes without following links or counting a nested root twice.
    long installedBytes() throws IOException {
        validate();
        long bytes = 0;
        for (Path root : roots) bytes = Math.addExact(bytes, treeBytes(root));
        return bytes;
    }

    /// Counts a tree or a single application file without following symbolic links.
    static long treeBytes(Path root) throws IOException {
        rejectSymlinkAncestors(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        long bytes = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                rejectSymlinkAncestors(Files.isSymbolicLink(path) ? Objects.requireNonNull(path.getParent()) : path);
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) bytes = Math.addExact(bytes, attributes.size());
            }
        }
        return bytes;
    }

    /// Copies only saves/screenshots and verifies the complete source and destination inventories and SHA-256.
    /// A failed copy is retained for recovery, while all original data remains untouched.
    void preserve() throws IOException {
        validate();
        Files.createDirectories(desktop);
        rejectSymlinkAncestors(desktop);
        Path candidate = kept;
        for (int suffix = 2; ; suffix++) {
            try { Files.createDirectory(candidate); break; }
            catch (FileAlreadyExistsException collision) { candidate = desktop.resolve("刀剑神域·誓约 存档 (" + suffix + ")"); }
        }
        kept = candidate;
        for (String name : List.of("saves", "screenshots")) {
            Path from = instance.resolve(name);
            @Unmodifiable Map<String, String> before = fingerprint(from);
            if (before.isEmpty()) continue;
            Path to = candidate.resolve(name);
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                /// Creates each directory without following source links or overwriting a prior backup.
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    rejectSymlinkAncestors(directory);
                    Files.createDirectory(to.resolve(from.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }
                /// Copies only ordinary files; a link or device prevents any later removal.
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("存档包含链接或特殊文件，已停止卸载");
                    rejectSymlinkAncestors(file);
                    Files.copy(file, to.resolve(from.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (!before.equals(fingerprint(from)) || !before.equals(fingerprint(to)))
                throw new IOException("保留存档校验失败，原始文件未删除");
        }
    }

    /// Returns directory entries and strong file hashes; missing roots are represented by an empty map.
    private static @Unmodifiable Map<String, String> fingerprint(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        rejectSymlinkAncestors(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("存档目录不是普通文件夹");
        Map<String, String> result = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /// Empty directories are preserved and participate in verification.
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                rejectSymlinkAncestors(directory);
                result.put(root.relativize(directory).toString(), "directory");
                return FileVisitResult.CONTINUE;
            }
            /// Hashes file streams without loading worlds or screenshots into memory.
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("存档包含链接或特殊文件，已停止卸载");
                rejectSymlinkAncestors(file);
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
                }
                result.put(root.relativize(file).toString(), HexFormat.of().formatHex(digest.digest()));
                return FileVisitResult.CONTINUE;
            }
        });
        return Map.copyOf(result);
    }

    /// Removes each allowed root through Trash, or permanently only when the desktop capability is unavailable.
    /// Roots containing the running launcher/runtime are deferred to the already prepared helper.
    void remove(List<Path> deferred) throws IOException {
        validate();
        boolean useTrash = trash.supported();
        for (Path root : roots) {
            if (deferred.stream().anyMatch(path -> path.startsWith(root))) continue;
            rejectSymlinkAncestors(root);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            if (useTrash) trash.move(root);
            else deleteTree(root);
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("启动器数据未完整移除，已停止退出");
        }
    }

    /// Deletes link entries themselves and ordinary trees; it never traverses a symlink target.
    static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /// Canonical directory guards also reject Windows junctions before traversal.
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                rejectSymlinkAncestors(directory);
                return FileVisitResult.CONTINUE;
            }
            /// Deletes an ordinary file or symbolic-link entry without following it.
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                rejectSymlinkAncestors(file.getParent());
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            /// Removes a visited directory only after all of its entries have been removed.
            @Override public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException failure) throws IOException {
                if (failure != null) throw failure;
                rejectSymlinkAncestors(directory);
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// The player's real desktop: the Windows shell folder (OneDrive or moved desktops), the XDG desktop on Linux
    /// (for example `~/桌面`), else `~/Desktop`.
    static Path realDesktop() {
        Path home = Path.of(System.getProperty("user.home"));
        try {
            if (com.sun.jna.Platform.isWindows()) {
                char[] buffer = new char[1024];
                // CSIDL_DESKTOPDIRECTORY, SHGFP_TYPE_CURRENT
                if (com.sun.jna.Native.load("shell32", ShellFolders.class).SHGetFolderPathW(null, 0x10, null, 0, buffer) == 0) {
                    Path desktop = Path.of(com.sun.jna.Native.toString(buffer));
                    if (desktop.isAbsolute() && Files.isDirectory(desktop)) return desktop;
                }
            } else if (com.sun.jna.Platform.isLinux()) {
                @Nullable String config = System.getenv("XDG_CONFIG_HOME");
                @Nullable Path desktop = xdgDesktop((config == null || config.isBlank() ? home.resolve(".config") : Path.of(config))
                        .resolve("user-dirs.dirs"), home);
                if (desktop != null && Files.isDirectory(desktop)) return desktop;
            }
        } catch (IOException | RuntimeException | LinkageError ignored) { /* Fall back to the conventional folder. */ }
        return home.resolve("Desktop");
    }

    /// Reads `XDG_DESKTOP_DIR` from a `user-dirs.dirs` file; only `$HOME/...` and absolute values are accepted.
    static @Nullable Path xdgDesktop(Path file, Path home) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("XDG_DESKTOP_DIR=")) continue;
            String value = trimmed.substring("XDG_DESKTOP_DIR=".length());
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            if (value.equals("$HOME") || value.equals("$HOME/")) return null;
            if (value.startsWith("$HOME/")) return home.resolve(value.substring("$HOME/".length())).normalize();
            if (value.startsWith("/")) return Path.of(value).normalize();
            return null;
        }
        return null;
    }

    /// Shell32 folder lookup, loaded only on Windows.
    @NotNullByDefault
    private interface ShellFolders extends com.sun.jna.win32.StdCallLibrary {
        /// Writes the current path of a CSIDL folder into a MAX_PATH-or-larger buffer; returns S_OK on success.
        int SHGetFolderPathW(com.sun.jna.@Nullable Pointer owner, int folder, com.sun.jna.@Nullable Pointer token, int flags, char[] path);
    }
}
