/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Prepares a detached native helper outside the launcher and its Java runtime.
/// The helper is inert until armed, waits for the parent PID to exit, and records generic success/failure codes.
/// Its scripts are generated and inspected in tests; native removal is never invoked by those tests.
@NotNullByDefault
final class SaoUninstallHelper {
    /// One prepared helper can be armed after successful data removal or cancelled after a failure.
    @NotNullByDefault
    interface Prepared {
        /// Permits cleanup after the launcher exits; no files are removed before that exit.
        void arm() throws IOException;
        /// Leaves all app/runtime files in place when uninstall did not reach its commit point.
        void cancel();
    }

    /// Factory seam lets headless tests prove sequencing without starting an operating-system helper.
    @NotNullByDefault
    interface Factory {
        /// Creates an initially inert helper with exact data and application targets.
        Prepared prepare(List<Path> roots, List<Path> application) throws IOException;
    }

    /// Prevents utility instantiation.
    private SaoUninstallHelper() { }

    /// Determines the actual packaged source; development class directories are deliberately excluded.
    static @Unmodifiable List<Path> runningApplication() throws IOException {
        @Nullable Path source = JarUtils.thisJarPath();
        @Nullable String command = ProcessHandle.current().info().command().orElse(null);
        return discover(source, command == null ? null : Path.of(command), System.getenv("APPIMAGE"), System.getProperty("os.name"));
    }

    /// Selects a complete macOS bundle or actual executable/JAR, with a conservative AppImage identity check.
    static @Unmodifiable List<Path> discover(@Nullable Path source, @Nullable Path command,
                                            @Nullable String appImage, String operatingSystem) throws IOException {
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path absolute = source.toAbsolutePath().normalize();
        SaoUninstaller.rejectSymlinkAncestors(absolute);
        @Nullable Path development = absolute.getParent();
        while (development != null) {
            if (Files.exists(development.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return List.of();
            String component = development.getFileName() == null ? "" : development.getFileName().toString();
            if (component.equals("build") || component.equals("target") || component.equals("out")) return List.of();
            development = development.getParent();
        }
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            @Nullable Path current = absolute.getParent();
            while (current != null) {
                if (current.getFileName() != null && current.getFileName().toString().endsWith(".app")
                        && absolute.startsWith(current.resolve("Contents"))
                        && Files.isRegularFile(current.resolve("Contents/Info.plist"), LinkOption.NOFOLLOW_LINKS))
                    return List.of(current);
                current = current.getParent();
            }
        }
        if (os.contains("linux") && appImage != null && !appImage.isBlank()) {
            Path image = Path.of(appImage).toAbsolutePath().normalize();
            // APPIMAGE alone is untrusted. AppImage's actual mounted process must corroborate that environment.
            @Nullable String mounted = System.getenv("APPDIR");
            if (mounted != null && command != null
                    && Path.of(mounted).getFileName() != null && Path.of(mounted).getFileName().toString().startsWith(".mount_")
                    && Files.getFileStore(absolute).type().toLowerCase(Locale.ROOT).contains("fuse")
                    && absolute.startsWith(Path.of(mounted).toAbsolutePath().normalize())
                    && command.toAbsolutePath().normalize().startsWith(Path.of(mounted).toAbsolutePath().normalize())
                    && image.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".appimage")
                    && Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) {
                SaoUninstaller.rejectSymlinkAncestors(image);
                return List.of(image);
            }
        }
        String name = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar") || name.endsWith(".exe")) return List.of(absolute);
        return List.of();
    }

    /// Starts the native helper and waits only for its ready marker, never for launcher exit.
    static Prepared prepare(List<Path> roots, List<Path> application) throws IOException {
        Path directory = Files.createTempDirectory("sao-uninstall-").toRealPath();
        for (Path root : roots) if (directory.startsWith(root)) throw new IOException("卸载助手目录与启动器目录重叠");
        for (Path app : application) if (directory.startsWith(app)) throw new IOException("卸载助手目录位于应用内");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path script = directory.resolve(windows ? "cleanup.ps1" : "cleanup.sh");
        Path log = directory.resolve("result.log");
        org.jackhuang.hmcl.util.logging.Logger.LOG.info("Uninstall helper report: " + log);
        Files.write(script, scriptBytes(script(roots, application, directory, ProcessHandle.current().pid(),
                System.getProperty("os.name")), windows), StandardOpenOption.CREATE_NEW);
        @Unmodifiable List<String> command = windows
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                : List.of("/bin/sh", script.toString());
        Process child = new ProcessBuilder(command).directory(directory.toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE).redirectOutput(log.toFile()).redirectErrorStream(true).start();
        child.getOutputStream().close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!Files.isRegularFile(directory.resolve("ready"), LinkOption.NOFOLLOW_LINKS)) {
                if (!child.isAlive() || System.nanoTime() >= deadline) throw new IOException("卸载助手未能启动，未退出启动器");
                Thread.sleep(25);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            child.destroy();
            throw new IOException("启动卸载助手时被中断", interrupted);
        } catch (IOException failure) { child.destroy(); throw failure; }
        return new Prepared() {
            /// Only this final marker permits any deferred removal.
            @Override public void arm() throws IOException {
                if (!child.isAlive()) throw new IOException("卸载助手已退出，未退出启动器");
                Files.writeString(directory.resolve("armed"), "committed", StandardOpenOption.CREATE_NEW);
            }
            /// The detached process exits without touching targets after seeing this marker.
            @Override public void cancel() {
                try { Files.writeString(directory.resolve("cancelled"), "cancelled", StandardOpenOption.CREATE_NEW); }
                catch (IOException ignored) { child.destroy(); }
            }
        };
    }

    /// Encodes the script as UTF-8; Windows PowerShell 5.1 `-File` reads BOM-less scripts in the ANSI code page,
    /// which garbles non-ASCII user names and install paths, so the Windows script starts with a UTF-8 BOM.
    static byte[] scriptBytes(String script, boolean windows) {
        byte[] text = script.getBytes(StandardCharsets.UTF_8);
        if (!windows) return text;
        byte[] result = new byte[text.length + 3];
        result[0] = (byte) 0xEF; result[1] = (byte) 0xBB; result[2] = (byte) 0xBF;
        System.arraycopy(text, 0, result, 3, text.length);
        return result;
    }

    /// Generates reviewable native code without spawning a process or removing any file.
    /// Data roots are retried after shutdown hooks; the application is removed last.
    static String script(List<Path> roots, List<Path> application, Path directory, long pid, String operatingSystem) {
        List<Path> targets = new ArrayList<>(roots);
        for (Path app : application) if (targets.stream().noneMatch(app::startsWith)) targets.add(app);
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return windowsScript(targets, roots.size(), directory, pid);
        return unixScript(targets, roots.size(), directory, pid, os.contains("mac"));
    }

    /// Quotes one shell argument, including literal apostrophes and line breaks, without interpolation.
    private static String shell(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    /// Quotes one PowerShell literal; dollar signs and command substitution remain inert text.
    private static String powershell(String value) { return "'" + value.replace("'", "''") + "'"; }

    /// Waits on markers and PID with POSIX tools, using Foundation Trash on macOS and gio Trash when installed elsewhere.
    private static String unixScript(List<Path> targets, int applicationIndex, Path directory, long pid, boolean mac) {
        StringBuilder script = new StringBuilder("#!/bin/sh\nset -u\ncd / || exit 1\nhelper=").append(shell(directory.toString())).append("\n");
        script.append("touch \"$helper/ready\" || exit 1\ncount=0\nwhile [ ! -f \"$helper/armed\" ]; do\n")
                .append("  [ -f \"$helper/cancelled\" ] && exit 0\n  count=$((count + 1))\n  [ \"$count\" -gt 600 ] && exit 1\n  sleep 1\ndone\n")
                .append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n")
                .append("safe() {\n  cursor=$1\n  case \"$cursor\" in /|''|.|..) return 1;; esac\n")
                .append("  while [ \"$cursor\" != / ]; do\n    [ -L \"$cursor\" ] && return 1\n    cursor=${cursor%/*}\n    [ -z \"$cursor\" ] && cursor=/\n  done\n  return 0\n}\n")
                .append("remove() {\n  target=$1\n  [ ! -e \"$target\" ] && [ ! -L \"$target\" ] && return 0\n  safe \"$target\" || return 1\n");
        if (mac) script.append("  if [ -x /usr/bin/osascript ]; then\n    /usr/bin/osascript - \"$target\" <<'SAO_APPLESCRIPT'\nuse framework \"Foundation\"\non run argv\n  set fileURL to current application's NSURL's fileURLWithPath:(item 1 of argv)\n  set {ok, resultingURL, failure} to current application's NSFileManager's defaultManager()'s trashItemAtURL:fileURL resultingItemURL:(reference) |error|:(reference)\n  if not ok then error \"TRASH_FAILED\"\nend run\nSAO_APPLESCRIPT\n  else\n    /bin/rm -rf -- \"$target\"\n  fi\n");
        else script.append("  if command -v gio >/dev/null 2>&1; then gio trash -- \"$target\"; else /bin/rm -rf -- \"$target\"; fi\n");
        script.append("}\nfailed=0\n");
        for (int i = 0; i < targets.size(); i++) {
            if (i == applicationIndex) script.append("if [ \"$failed\" -eq 0 ]; then\n");
            script.append("target=").append(shell(targets.get(i).toString())).append("\ncount=0\n")
                    .append("while ! remove \"$target\"; do\n  count=$((count + 1))\n  [ \"$count\" -ge 20 ] && { echo REMOVE_FAILED_").append(i).append("; failed=1; break; }\n  sleep 1\ndone\n");
        }
        if (applicationIndex < targets.size()) script.append("fi\n");
        script.append("if [ \"$failed\" -eq 0 ]; then echo UNINSTALL_COMPLETE; /bin/rm -f -- \"$helper/armed\" \"$helper/ready\" \"$helper/cleanup.sh\"; else echo UNINSTALL_INCOMPLETE; fi\nexit \"$failed\"\n");
        return script.toString();
    }

    /// Waits with native PowerShell and sends files/bundles to the Windows Recycle Bin through the platform API.
    private static String windowsScript(List<Path> targets, int applicationIndex, Path directory, long pid) {
        StringBuilder script = new StringBuilder("$ErrorActionPreference = 'Stop'\nSet-Location $env:SystemRoot\n$helper = ")
                .append(powershell(directory.toString())).append("\nNew-Item -ItemType File -Path (Join-Path $helper 'ready') | Out-Null\n$count = 0\n")
                .append("while (!(Test-Path -LiteralPath (Join-Path $helper 'armed'))) {\n  if (Test-Path -LiteralPath (Join-Path $helper 'cancelled')) { exit 0 }\n  if (++$count -gt 600) { exit 1 }\n  Start-Sleep -Seconds 1\n}\n")
                .append("while (Get-Process -Id ").append(pid).append(" -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 1 }\n")
                .append("$recycle = $true\ntry { Add-Type -AssemblyName Microsoft.VisualBasic } catch { $recycle = $false }\n")
                .append("function Remove-Owned([string]$target) {\n  if (!(Test-Path -LiteralPath $target)) { return }\n  $cursor = $target\n")
                .append("  while ($cursor) {\n    $item = Get-Item -Force -LiteralPath $cursor -ErrorAction SilentlyContinue\n    if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'UNSAFE_LINK' }\n    $cursor = Split-Path -Parent $cursor\n  }\n")
                .append("  if ($recycle) {\n    if ((Get-Item -Force -LiteralPath $target).PSIsContainer) {\n      [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($target, 'OnlyErrorDialogs', 'SendToRecycleBin')\n")
                .append("    } else { [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($target, 'OnlyErrorDialogs', 'SendToRecycleBin') }\n")
                .append("  } else { Remove-Item -LiteralPath $target -Recurse -Force }\n  if (Test-Path -LiteralPath $target) { throw 'REMOVE_INCOMPLETE' }\n}\n$failed = $false\n");
        for (int i = 0; i < targets.size(); i++) {
            if (i == applicationIndex) script.append("if (!$failed) {\n");
            script.append("$target = ").append(powershell(targets.get(i).toString())).append("\n$count = 0\nwhile ($true) {\n")
                    .append("  try { Remove-Owned $target; break } catch {\n    if (++$count -ge 20) { Write-Output 'REMOVE_FAILED_").append(i).append("'; $failed = $true; break }\n    Start-Sleep -Seconds 1\n  }\n}\n");
        }
        if (applicationIndex < targets.size()) script.append("}\n");
        script.append("if ($failed) { Write-Output 'UNINSTALL_INCOMPLETE'; exit 1 }\nWrite-Output 'UNINSTALL_COMPLETE'\n")
                .append("Remove-Item -LiteralPath (Join-Path $helper 'armed'), (Join-Path $helper 'ready'), $PSCommandPath -Force\nexit 0\n");
        return script.toString();
    }
}
