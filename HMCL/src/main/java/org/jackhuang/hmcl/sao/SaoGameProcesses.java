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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Conservative instance-scoped Java discovery; an unreadable command never authorizes termination.
@NotNullByDefault
final class SaoGameProcesses {
    /// Utility class.
    private SaoGameProcesses() { }

    /// Matches complete path tokens, never a shared Java executable or a similarly prefixed instance.
    static boolean matches(String executable, List<String> arguments, String instance, boolean windows) {
        String binary = executable.replace('\\', '/');
        binary = binary.substring(binary.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("java", "javaw", "java.exe", "javaw.exe").contains(binary)) return false;
        String root = normalize(instance, windows);
        if (root.isEmpty()) return false;
        for (String argument : arguments) {
            // JVM properties and classpath entries may contain instance-owned files.
            String value = argument.startsWith("-D") && argument.contains("=") ? argument.substring(argument.indexOf('=') + 1) : argument;
            for (String part : value.split(windows ? ";" : ":")) {
                String path = normalize(part, windows);
                if (path.equals(root) || path.startsWith(root + "/")) return true;
            }
        }
        return false;
    }

    /// Normalizes separators and Windows case without resolving paths on the host running a headless test.
    private static String normalize(String value, boolean windows) {
        String result = value;
        if (result.length() > 1 && result.startsWith("\"") && result.endsWith("\"")) result = result.substring(1, result.length() - 1);
        result = result.replace('\\', '/');
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        // Reject traversal rather than accidentally authorizing a different canonical directory.
        if (Arrays.asList(result.split("/")).contains("..")) return "";
        return windows ? result.toLowerCase(Locale.ROOT) : result;
    }

    /// Splits quoted Windows process command lines when the OS does not expose an argument array.
    static @Unmodifiable List<String> tokens(String commandLine) {
        List<String> result = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"|([^\\s\"]+)").matcher(commandLine);
        while (matcher.find()) result.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        return List.copyOf(result);
    }

    /// Minimal process boundary, allowing tests to exercise exit and refusal without launching anything.
    @NotNullByDefault
    interface Running {
        /// Whether this exact process still exists.
        boolean alive();

        /// Ends only this process and waits for confirmed exit, escalating after a short grace period.
        void end() throws Exception;
    }

    /// Finds only live Java commands whose arguments name the current instance.
    static @Unmodifiable List<Running> find(Path instance) {
        if (com.sun.jna.Platform.isWindows()) {
            // Fresh installations have no process holding this directory. Unknown existence still needs a scan.
            if (Files.notExists(instance, LinkOption.NOFOLLOW_LINKS)) return List.of();
            try { return windows(instance); }
            catch (IOException failure) {
                throw new java.io.UncheckedIOException(new IOException("无法检查后台游戏进程（" + failure.getMessage() + "）", failure));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.UncheckedIOException(new IOException("后台游戏进程检查已中断", failure));
            }
        }
        boolean windows = com.sun.jna.Platform.isWindows();
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive())
                    .filter(p -> matches(p.info(), instance, windows)).<Running>map(p -> new Running() {
                        /// Reuses the captured OS handle rather than looking up a numeric PID again.
                        @Override public boolean alive() { return p.isAlive(); }

                        /// Revalidates the readable instance command immediately before signaling.
                        @Override public void end() throws Exception {
                            if (!p.isAlive()) return;
                            if (!matches(p.info(), instance, windows)) throw new IOException("无法确认后台进程属于这个游戏目录");
                            p.destroy();
                            try { p.onExit().get(3, TimeUnit.SECONDS); }
                            catch (java.util.concurrent.TimeoutException timeout) {
                                p.destroyForcibly(); p.onExit().get(7, TimeUnit.SECONDS);
                            }
                        }
                    }).toList();
        }
    }

    /// Windows Java 17 exposes the executable but not arguments; CIM supplies bounded, in-memory command metadata.
    private static @Unmodifiable List<Running> windows(Path instance) throws IOException, InterruptedException {
        List<Running> result = new ArrayList<>();
        for (WindowsCommand command : discoverWindowsCommands(SaoWindowsProcessSnapshot::hasOtherJava, SaoGameProcesses::windowsCommands)) {
            if (command.pid() == ProcessHandle.current().pid()) continue;
            if (!matches(command.executable(), tokens(command.line()), instance.toString(), true)) continue;
            @Nullable ProcessHandle handle = ProcessHandle.of(command.pid()).orElse(null);
            if (handle == null || !sameLifetime(handle, command)) continue;
            result.add(new Running() {
                /// Both the PID and creation time must still identify the captured process.
                @Override public boolean alive() { return sameLifetime(handle, command); }

                /// Requeries command ownership before signaling; never terminates by executable name alone.
                @Override public void end() throws Exception {
                    if (!alive()) return;
                    boolean verified = windowsCommands().stream().anyMatch(now -> now.pid() == command.pid()
                            && now.started() == command.started()
                            && matches(now.executable(), tokens(now.line()), instance.toString(), true));
                    if (!verified) throw new IOException("无法确认后台进程属于这个游戏目录");
                    handle.destroy();
                    try { handle.onExit().get(3, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException timeout) {
                        if (alive()) handle.destroyForcibly();
                        handle.onExit().get(7, TimeUnit.SECONDS);
                    }
                }
            });
        }
        return List.copyOf(result);
    }

    /// Reads a complete, native process-name snapshot before deciding whether CIM is necessary.
    @FunctionalInterface
    @NotNullByDefault
    interface JavaProcessProbe {
        /// Reports whether another JVM may need instance ownership verification.
        boolean hasOtherJava() throws IOException;
    }

    /// Supplies CIM command metadata when the native snapshot sees a possible Java process.
    @FunctionalInterface
    @NotNullByDefault
    interface WindowsCommandProbe {
        /// Returns command metadata or reports why the inspection could not finish.
        @Unmodifiable List<WindowsCommand> query() throws IOException, InterruptedException;
    }

    /// Skips PowerShell only after complete enumeration proves there is no other Java process to inspect.
    static @Unmodifiable List<WindowsCommand> discoverWindowsCommands(JavaProcessProbe snapshot, WindowsCommandProbe cim)
            throws IOException, InterruptedException {
        try {
            if (!snapshot.hasOtherJava()) return List.of();
        } catch (IOException | LinkageError failure) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Windows process name snapshot unavailable; querying CIM");
        }
        return cim.query();
    }

    /// Protects the discovery-to-confirmation interval against PID reuse.
    private static boolean sameLifetime(ProcessHandle handle, WindowsCommand command) {
        return handle.isAlive() && handle.info().startInstant().map(time -> time.toEpochMilli() == command.started()).orElse(false);
    }

    /// Public process metadata used only in memory; command text must never be logged or persisted.
    @NotNullByDefault
    record WindowsCommand(long pid, long started, String executable, String line) { }

    /// Decodes CIM output without including command lines in malformed-output errors.
    static @Unmodifiable List<WindowsCommand> decodeWindows(String json) throws IOException {
        if (json.isBlank()) return List.of();
        try {
            var parsed = com.google.gson.JsonParser.parseString(json);
            if (parsed.isJsonNull()) return List.of();
            var rows = parsed.isJsonArray() ? parsed.getAsJsonArray() : new com.google.gson.JsonArray();
            if (!parsed.isJsonArray()) rows.add(parsed);
            List<WindowsCommand> result = new ArrayList<>();
            for (var row : rows) {
                var value = row.getAsJsonObject();
                if (value.get("executable").isJsonNull() || value.get("line").isJsonNull()) continue;
                result.add(new WindowsCommand(value.get("pid").getAsLong(), value.get("started").getAsLong(),
                        value.get("executable").getAsString(), value.get("line").getAsString()));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) { throw new IOException("无法解析后台进程信息"); }
    }

    /// Queries only java/javaw; a timeout or unavailable CIM service aborts the action without killing anything.
    private static @Unmodifiable List<WindowsCommand> windowsCommands() throws IOException, InterruptedException {
        String script = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                + "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | ForEach-Object { "
                + "[pscustomobject]@{pid=$_.ProcessId; started=([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds(); "
                + "executable=$_.ExecutablePath; line=$_.CommandLine} }) | ConvertTo-Json -Compress";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        String system = Objects.requireNonNullElse(System.getenv("SystemRoot"), "C:\\Windows");
        Process query;
        try {
            query = new ProcessBuilder(system + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                    "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException failure) {
            throw new IOException("无法启动 Windows PowerShell", failure);
        }
        var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return query.getInputStream().readNBytes(1048577); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        try {
            // Cold CIM startup can exceed ten seconds on Windows on ARM and busy disks.
            byte @Unmodifiable [] bytes = output.get(30, TimeUnit.SECONDS);
            if (bytes.length > 1048576) throw new IOException("CIM 响应超出上限");
            if (!query.waitFor(2, TimeUnit.SECONDS)) throw new IOException("CIM 未正常退出");
            if (query.exitValue() != 0) throw new IOException("CIM 退出码 " + query.exitValue());
            return decodeWindows(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.util.concurrent.TimeoutException failure) {
            throw new IOException("CIM 查询超时", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("无法读取 CIM 输出", failure);
        } finally { if (query.isAlive()) query.destroyForcibly(); query.getInputStream().close(); }
    }

    /// Uses OS-provided arguments preferentially; missing process metadata fails closed.
    private static boolean matches(ProcessHandle.Info info, Path instance, boolean windows) {
        @Nullable String executable = info.command().orElse(null);
        if (executable == null) return false;
        List<String> arguments = info.arguments().map(Arrays::asList).orElseGet(() -> tokens(info.commandLine().orElse("")));
        return matches(executable, arguments, instance.toAbsolutePath().normalize().toString(), windows);
    }
}
