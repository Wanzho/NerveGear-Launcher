/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.game.CrashReportAnalyzer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// A bounded game-output ring and sanitized support export restricted to logs and crash reports.
@NotNullByDefault
final class SaoGameLogs {
    /// Maximum visible lines, including the most recent output from a long session.
    static final int MAX_LINES = 5000;
    /// Fixed game directory containing the permitted export sources.
    private final Path instance;
    /// Bounded sanitized lines, guarded by this object.
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    /// Stateful redaction keeps multiline credentials private even when callbacks arrive one line at a time.
    private SaoFeedbackSanitizer.Stream liveSanitizer = sanitizer().stream();

    /// Creates an identity scrubber without opening any account/configuration file.
    private static SaoFeedbackSanitizer sanitizer() {
        return new SaoFeedbackSanitizer(System.getProperty("user.home", ""), System.getProperty("user.name", ""));
    }

    /// Uses the launcher's fixed instance and does not read accounts, options or skin directories.
    SaoGameLogs(Path instance) { this.instance = instance; }

    /// Filters handoff/authentication lines before either display or export; also removes home-directory identity.
    static String sanitize(String text) {
        return sanitizer().scrub(text).stripTrailing();
    }

    /// Starts a fresh run; older on-disk logs remain eligible for an explicit support export.
    synchronized void clear() { lines.clear(); liveSanitizer = sanitizer().stream(); }

    /// Adds complete lines and caps the ring even when one process callback contains multiple line breaks.
    synchronized void append(String text) {
        for (String line : text.split("\\R", -1)) {
            String sanitized = liveSanitizer.line(line);
            lines.addLast(sanitized.length() > 32768 ? sanitized.substring(0, 32768) : sanitized);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
    }

    /// Returns a stable immutable snapshot for a coalesced JavaFX publication.
    synchronized @Unmodifiable List<String> snapshot() { return List.copyOf(lines); }

    /// Loads the newest stored log on startup, reading line by line rather than buffering a full game log.
    void loadPrevious() throws IOException {
        Path latest = SaoPackFiles.resolve(instance, "logs/latest.log");
        if (!Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) return;
        try (BufferedReader reader = Files.newBufferedReader(latest)) {
            @Nullable String line;
            while ((line = reader.readLine()) != null) append(line);
        }
    }

    /// Applies HMCL's crash rules to the latest report or captured output after an abnormal exit.
    @Nullable SaoData.Crash analyze(boolean abnormal, long since) throws IOException {
        @Nullable Path report;
        try { report = latestReport(since); }
        catch (IOException unavailable) { report = null; }
        if (!abnormal && report == null) return null;
        String text = String.join("\n", snapshot());
        if (report != null) {
            try { text = readSanitized(report, 4 * 1024 * 1024); }
            catch (IOException unavailable) { /* Captured output still provides a crash card when a report cannot be read. */ }
        }
        Set<CrashReportAnalyzer.Result> matches = CrashReportAnalyzer.analyze(text);
        @Unmodifiable List<CrashReportAnalyzer.Result> ordered = matches.stream().sorted(Comparator.comparingInt(result -> result.rule().ordinal())).toList();
        String reason = i18n("game.crash.reason.unknown");
        String detail = text.length() > 16000 ? text.substring(text.length() - 16000) : text;
        if (!ordered.isEmpty()) {
            CrashReportAnalyzer.Result result = ordered.get(0);
            try {
                reason = result.rule() == CrashReportAnalyzer.Rule.TOO_OLD_JAVA
                        ? i18n("game.crash.reason.too_old_java", CrashReportAnalyzer.getJavaVersionFromMajorVersion(Integer.parseInt(result.matcher().group("expected"))))
                        : i18n("game.crash.reason." + result.rule().name().toLowerCase(Locale.ROOT),
                                Arrays.stream(result.rule().getGroupNames()).map(name -> result.matcher().group(name)).toArray());
            } catch (RuntimeException ignored) { reason = result.rule().name(); }
            detail = result.matcher().group();
        }
        return new SaoData.Crash(sanitize(reason).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip(), sanitize(detail),
                report == null ? instance.resolve("logs/latest.log") : report);
    }

    /// Creates a sanitized zip with a captured-ring file and whitelisted text logs/reports only.
    void export(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        if (absolute.startsWith(instance.resolve("logs")) || absolute.startsWith(instance.resolve("crash-reports")))
            throw new IOException("Log export must be outside the log folders");
        Path temporary = SaoInstanceFiles.temporaryExport(absolute);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.putNextEntry(new ZipEntry("captured-game.log"));
                zip.write(String.join("\n", snapshot()).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                for (String folder : List.of("logs", "crash-reports")) {
                    Path directory = SaoPackFiles.resolve(instance, folder);
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (var paths = Files.walk(directory)) {
                        for (Path file : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                            Path relative = instance.relativize(file);
                            SaoPackFiles.resolve(instance, relative.toString().replace('\\', '/'));
                            String relativeName = relative.toString().toLowerCase(Locale.ROOT);
                            if (relativeName.contains("sao_auth") || relativeName.contains("sao-auth") || relativeName.contains("handoff")
                                    || relativeName.contains("password") || relativeName.contains("credential") || relativeName.contains("token")) continue;
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (!name.matches(".*\\.(log|txt|log\\.gz)$")) continue;
                            String entry = relative.toString().replace('\\', '/');
                            if (name.endsWith(".gz")) entry = entry + ".txt";
                            zip.putNextEntry(new ZipEntry(entry));
                            zip.write(readSanitized(file, 16 * 1024 * 1024).getBytes(StandardCharsets.UTF_8));
                            zip.closeEntry();
                        }
                    }
                }
            }
            SaoPackFiles.atomicMove(temporary, absolute);
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Reads bounded UTF-8 text, inflating standard archived game logs before line-level sanitation.
    private static String readSanitized(Path file, int maximum) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             InputStream input = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Log is too large to export");
            return sanitizer().scrub(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /// Returns the newest regular crash report from this run; old reports never mark a clean run as crashed.
    private @Nullable Path latestReport(long since) throws IOException {
        Path directory = SaoPackFiles.resolve(instance, "crash-reports");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        @Nullable Path latest = null;
        long time = since;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().endsWith(".txt")).toList()) {
                SaoPackFiles.resolve(instance, instance.relativize(file).toString().replace('\\', '/'));
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified >= time) { time = modified; latest = file; }
            }
        }
        return latest;
    }
}
