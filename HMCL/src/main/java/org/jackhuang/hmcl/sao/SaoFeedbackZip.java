/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.tukaani.xz.XZInputStream;

/// Creates exactly one atomic, bounded feedback archive from an explicit diagnostic allowlist.
/// Accounts, launcher preferences, credentials, chat databases and general config directories are never opened.
@NotNullByDefault
final class SaoFeedbackZip {
    /// Maximum decoded bytes from one log, with a visible truncation marker for larger diagnostics.
    private static final int MAX_TEXT = 16 * 1024 * 1024;
    /// Total decoded text budget keeps support exports practical even after several crashes.
    private static final long MAX_TOTAL = 96L * 1024 * 1024;
    /// Only permitted instance root.
    private final Path instance;
    /// Launcher-owned storage, used only for top-level hs_err diagnostics.
    private final Path root;
    /// Explicit destination; never chooses the current working directory or account data folder.
    private final Path downloads;
    /// Content and identity scrubber, applied to every text entry including generated summaries.
    private final SaoFeedbackSanitizer sanitizer;

    /// Explicit additional diagnostic roots; fixtures never inspect real user data.
    private final @Unmodifiable List<Path> diagnosticRoots;

    /// Supplies controlled paths; production passes the real Downloads folder, tests use a temporary directory.
    SaoFeedbackZip(Path root, Path instance, Path downloads, SaoFeedbackSanitizer sanitizer) {
        this(root, instance, downloads, sanitizer, List.of(root));
    }

    /// Supplies local, Roaming and install roots without opening account files.
    SaoFeedbackZip(Path root, Path instance, Path downloads, SaoFeedbackSanitizer sanitizer, List<Path> diagnosticRoots) {
        this.root = root; this.instance = instance; this.downloads = downloads; this.sanitizer = sanitizer;
        this.diagnosticRoots = List.copyOf(diagnosticRoots);
    }

    /// Adds current logs, newest five crash reports, hs_err files per owned root, and HMCL logs.
    /// Source files are never modified; partial output is deleted and no file manager is opened.
    SaoData.FeedbackZip build(String summary, List<Path> launcherLogs, List<String> captured) throws IOException {
        Files.createDirectories(downloads);
        if (Files.isSymbolicLink(downloads)) throw new IOException("反馈目录不能是符号链接");
        Path temporary = Files.createTempFile(downloads, ".sao-feedback-", ".tmp");
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path destination = temporary.resolveSibling("NerveGear日志-" + stamp + ".zip");
        for (int suffix = 1; Files.exists(destination); suffix++) destination = temporary.resolveSibling("NerveGear日志-" + stamp + "-" + suffix + ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                long total = add(zip, "system-summary.txt", summary);
                total += add(zip, "info.txt", summary);
                total += write(zip, "mods-sha1.txt", modList(), MAX_TEXT);
                List<String> omitted = new ArrayList<>();
                if (captured.stream().anyMatch(line -> !line.isBlank()))
                    total += add(zip, "captured-game.log", capturedText(captured));
                else omitted.add("captured-game.log: 启动器未捕获到游戏进程输出");
                Map<String, Path> sources = new LinkedHashMap<>();
                for (String path : List.of("logs/latest.log", "logs/debug.log", "options.txt")) {
                    Path source = SaoPackFiles.resolve(instance, path);
                    if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) sources.put(path, source);
                }
                int i = 0;
                for (Path crash : newest(instance, "crash-reports", "crash-.*\\.txt", 5)) sources.put("crash-reports/crash-" + (++i) + ".txt", crash);
                i = 0;
                Set<Path> nativeRoots = new LinkedHashSet<>(diagnosticRoots); nativeRoots.add(instance);
                for (Path base : nativeRoots) {
                    for (Path error : newest(base, "", "hs_err_pid[0-9]+\\.log", Integer.MAX_VALUE)) sources.put("hs_err/hs_err-" + (++i) + ".log", error);
                }
                i = 0;
                for (Path log : launcherLogs) {
                    if (i >= 4) break;
                    // Logger-supplied explicit paths only: no directory crawl and no account/settings fallback.
                    if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(log.getParent())) continue;
                    String basename = log.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!basename.matches(".*\\.log(?:\\.(?:xz|gz))?")) continue;
                    sources.put("launcher/launcher-" + (++i) + ".log", log);
                }
                int rootNumber = 0;
                for (Path base : diagnosticRoots) {
                    String prefix = "data-" + (++rootNumber) + "/";
                    int logNumber = 0;
                    for (Path log : newest(base, "logs", ".*\\.log(?:\\.(?:xz|gz))?", 20))
                        sources.put(prefix + "logs/launcher-" + (++logNumber) + ".log", log);
                    for (String name : List.of("pack-cache/active.json", "launcher-running.marker")) {
                        Path file = SaoPackFiles.resolve(base, name);
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) sources.put(prefix + name, file);
                    }
                }
                SaoPackFiles.resolve(instance, "diagnostic-path-check");
                Path receipt = instance.resolve(SaoPackInstaller.RECEIPT);
                if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) sources.put("pack/installed-inventory.json", receipt);
                for (var entry : sources.entrySet()) {
                    if (total >= MAX_TOTAL) { omitted.add(entry.getKey() + ": 超出反馈大小上限"); continue; }
                    int limit = (int) Math.min(MAX_TEXT, MAX_TOTAL - total);
                    String text;
                    try { text = entry.getKey().equals("logs/debug.log") ? tail(entry.getValue(), 5 * 1024 * 1024)
                            : read(entry.getValue(), limit); }
                    catch (IOException unavailable) { omitted.add(entry.getKey() + ": 文件不可读"); continue; }
                    if (entry.getKey().endsWith(".json")) {
                        try { total += addJson(zip, entry.getKey(), text, limit); }
                        catch (JsonParseException invalid) { omitted.add(entry.getKey() + ": JSON 无效或超出大小上限"); }
                    } else {
                        total += add(zip, entry.getKey(), text, limit);
                    }
                }
                add(zip, "export-notes.txt", "仅包含诊断白名单。日志过长时截断；缺失文件不包含。所有内容均已脱敏。\n" + String.join("\n", omitted));
            }
            SaoPackFiles.atomicMove(temporary, destination);
            return new SaoData.FeedbackZip(destination, Files.size(destination));
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Writes a sanitized entry and returns its actual uncompressed UTF-8 length.
    private long add(ZipOutputStream zip, String name, String text) throws IOException {
        return add(zip, name, text, MAX_TEXT);
    }

    /// Bounds after scrubbing too, because replacement markers can expand a diagnostic's UTF-8 size.
    private long add(ZipOutputStream zip, String name, String text, int maximum) throws IOException {
        return write(zip, name, sanitizer.scrub(text), maximum);
    }

    /// Preserves valid JSON or notes an omitted file instead of truncating through a field.
    private long addJson(ZipOutputStream zip, String name, String text, int maximum) throws IOException {
        var json = JsonParser.parseString(text);
        if (!json.isJsonObject()) throw new JsonParseException("Expected diagnostic JSON object");
        String scrubbed = new GsonBuilder().disableHtmlEscaping().create().toJson(sanitizer.scrubJson(json, ""));
        if (scrubbed.getBytes(StandardCharsets.UTF_8).length > maximum)
            throw new JsonParseException("Diagnostic JSON exceeds export limit");
        return write(zip, name, scrubbed, maximum);
    }

    /// Writes already scrubbed text; generated mod digests remain exact even if a short OS username resembles hex.
    private static long write(ZipOutputStream zip, String name, String text, int maximum) throws IOException {
        byte @Unmodifiable [] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte @Unmodifiable [] marker = "\n[诊断文本超出上限，已截断]\n".getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        if (length > maximum) {
            length = Math.max(0, maximum - marker.length);
            while (length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
        }
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes, 0, length);
        if (bytes.length > maximum && maximum >= marker.length) { zip.write(marker); length += marker.length; }
        zip.closeEntry(); return length;
    }

    /// Caps the captured-ring join before allocation; process output may contain thousands of very long lines.
    private static String capturedText(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            int available = MAX_TEXT - text.length();
            if (available <= 0) { text.append("\n[进程日志超出上限，已截断]"); break; }
            text.append(line, 0, Math.min(available, line.length())).append('\n');
        }
        return text.toString();
    }

    /// Lists only immediate ordinary files, resolving each through the existing symlink-rejecting pack helper.
    private static @Unmodifiable List<Path> newest(Path base, String relative, String pattern, int count) throws IOException {
        Path directory = relative.isEmpty() ? base : SaoPackFiles.resolve(base, relative);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return List.of();
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (path.getFileName().toString().matches(pattern) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    paths.add(SaoPackFiles.resolve(base, base.relativize(path).toString().replace('\\', '/')));
            }
        }
        paths.sort(Comparator.comparingLong(SaoFeedbackZip::modified).reversed().thenComparing(Path::toString));
        return List.copyOf(paths.subList(0, Math.min(count, paths.size())));
    }

    /// Stable best-effort ordering for diagnostics concurrently rotated by a running process.
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException disappeared) { return 0; }
    }

    /// Keeps the latest debug output; drops the partial first line so a sliced credential line cannot escape scrubbing.
    private static String tail(Path file, int maximum) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(file)) {
            long start = Math.max(0, channel.size() - maximum);
            channel.position(start);
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(maximum);
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
            String text = new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
            return start == 0 ? text : text.indexOf('\n') < 0 ? "" : text.substring(text.indexOf('\n') + 1);
        }
    }

    /// Inflates HMCL's archived logs and Minecraft gzip logs under the same decoded-byte limit.
    private static String read(Path file, int maximum) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             InputStream input = file.toString().endsWith(".xz") ? new XZInputStream(raw, 65536)
                     : file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            byte @Unmodifiable [] bytes = input.readNBytes(maximum + 1);
            return new String(bytes, 0, Math.min(bytes.length, maximum), StandardCharsets.UTF_8)
                    + (bytes.length > maximum ? "\n[日志超出大小限制，已截断]" : "");
        }
    }

    /// Hashes actual installed top-level mod jars without exposing absolute paths or extracting/scanning archive entries.
    private String modList() throws IOException {
        Path mods = SaoPackFiles.resolve(instance, "mods"); StringBuilder result = new StringBuilder("SHA-1  mod\n");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return result.append("未安装模组\n").toString();
        try (var stream = Files.list(mods)) {
            for (Path file : stream.sorted().toList()) {
                if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                SaoPackFiles.resolve(instance, "mods/" + file.getFileName());
                try (InputStream input = Files.newInputStream(file)) {
                    result.append(SaoPackArchive.sha1(input)).append("  ").append(sanitizer.scrub(file.getFileName().toString()).stripTrailing()).append('\n');
                }
            }
        }
        return result.toString();
    }
}
