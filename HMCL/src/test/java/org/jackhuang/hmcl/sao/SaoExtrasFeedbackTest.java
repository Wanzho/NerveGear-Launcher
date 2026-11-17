/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the actual archive contents, source allowlist and credential/identity redaction with synthetic secrets.
@NotNullByDefault
class SaoExtrasFeedbackTest {
    /// All diagnostics and outputs live beneath this disposable root.
    @TempDir Path temporary;
    /// Synthetic diagnostic text covers authentication spellings used by launcher, Java and Minecraft.
    private static final String PRIVATE = "password=PASS_SENTINEL\naccess_token=TOKEN_SENTINEL\nlaunch-ticket=TICKET_SENTINEL\n"
            + "Authorization: Bearer BEARER_SENTINEL\nCookie: COOKIE_SENTINEL\n/login LOGIN_SENTINEL\n"
            + "{\"refreshToken\":\n\"MULTILINE_SENTINEL\"}\nmail=a.person@example.org\n"
            + "remote=192.168.5.7:25565 ipv6=[2001:db8::1] mapped=::ffff:10.0.0.1 zone=fe80::abcd%en0\n"
            + "home=/Users/FixtureUser/game name=FixtureUser\n"
            + "C:\\Users\\FixtureUser\\game C:\\\\Users\\\\FixtureUser\\\\game\n"
            + "-----BEGIN PRIVATE KEY-----\nPEM_SENTINEL\n-----END PRIVATE KEY-----\n"
            + "jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmaXh0dXJlIn0.signature\n";

    /// Every decoded entry is checked for secrets, IPs, mailboxes and home identity; account files are excluded.
    @Test void completeZipIsScrubbedAndSourceFilesAreUntouched() throws Exception {
        Path instance = temporary.resolve("instance"), root = temporary.resolve("launcher"), downloads = temporary.resolve("Downloads");
        Files.createDirectories(root); Files.createDirectories(instance.resolve("logs")); Files.createDirectories(instance.resolve("crash-reports"));
        Files.createDirectories(instance.resolve("mods"));
        for (String file : List.of("logs/latest.log", "logs/debug.log", "options.txt", "hs_err_pid123.log")) Files.writeString(instance.resolve(file), PRIVATE + "ordinary-setting:keep\n");
        for (int i = 1; i <= 5; i++) {
            Path file = instance.resolve("crash-reports/crash-" + i + ".txt"); Files.writeString(file, PRIVATE + "crash-ordinal=" + i);
            Files.setLastModifiedTime(file, FileTime.fromMillis(i * 1000L));
        }
        Files.writeString(instance.resolve("accounts.json"), "ACCOUNT_FILE_SENTINEL");
        Files.writeString(instance.resolve("logs/not-on-allowlist.log"), "UNSELECTED_LOG_SENTINEL");
        Files.writeString(root.resolve("launcher-settings.json"), "PREFERENCES_SENTINEL");
        Files.writeString(instance.resolve("mods/fixture.jar"), "jar fixture");
        Path launcher = root.resolve("hmcl.log"); Files.writeString(launcher, PRIVATE + "launcher ordinary text");
        var builder = new SaoFeedbackZip(root, instance, downloads, new SaoFeedbackSanitizer("/Users/FixtureUser", "FixtureUser"));
        var result = builder.build("OS: FixtureOS\nCPU: FixtureCPU\nGPU: FixtureGPU\nRAM: 16 GiB\nJava: 17\nlauncher: 0.1\npack: 0.8\n" + PRIVATE, List.of(launcher), List.of(PRIVATE));
        assertEquals(Files.size(result.path()), result.size()); assertEquals(downloads, result.path().getParent());
        Map<String, String> entries = unzip(result.path());
        assertTrue(entries.keySet().containsAll(List.of("system-summary.txt", "mods-sha1.txt", "logs/latest.log", "logs/debug.log", "options.txt", "captured-game.log", "launcher/launcher-1.log", "hs_err/hs_err-1.log")));
        assertEquals(5, entries.keySet().stream().filter(key -> key.startsWith("crash-reports/")).count());
        assertTrue(entries.values().stream().anyMatch(text -> text.contains("crash-ordinal=5")));
        assertTrue(entries.values().stream().anyMatch(text -> text.contains("crash-ordinal=1")));
        for (String text : entries.values()) {
            for (String secret : List.of("PASS_SENTINEL", "TOKEN_SENTINEL", "TICKET_SENTINEL", "BEARER_SENTINEL", "COOKIE_SENTINEL", "LOGIN_SENTINEL", "MULTILINE_SENTINEL", "PEM_SENTINEL",
                    "a.person@example.org", "192.168.5.7", "2001:db8", "10.0.0.1", "fe80::", "FixtureUser", "/Users/", "ACCOUNT_FILE_SENTINEL", "UNSELECTED_LOG_SENTINEL", "PREFERENCES_SENTINEL", "eyJhbGci"))
                assertFalse(text.contains(secret), secret + " leaked in " + text);
        }
        assertTrue(entries.get("system-summary.txt").contains("FixtureCPU"));
        assertTrue(entries.get("options.txt").contains("ordinary-setting:keep"));
        try (InputStream input = Files.newInputStream(instance.resolve("mods/fixture.jar"))) {
            assertTrue(entries.get("mods-sha1.txt").contains(SaoPackArchive.sha1(input) + "  fixture.jar"));
        }
        assertEquals(PRIVATE + "ordinary-setting:keep\n", Files.readString(instance.resolve("logs/latest.log")));
        try (var files = Files.list(downloads)) { assertEquals(List.of(result.path()), files.toList()); }
    }

    /// Both storage roots and the installed inventory are included while credential files and command tokens are excluded.
    @Test void oneClickIncludesBothRootsAndInventory() throws Exception {
        Path local = temporary.resolve("local"), roaming = temporary.resolve("roaming"), game = temporary.resolve("game");
        Files.createDirectories(local.resolve("logs")); Files.createDirectories(roaming.resolve("logs")); Files.createDirectories(game);
        Files.writeString(local.resolve("logs/a.log"), "local diagnostic\n--accessToken LOCAL_SECRET\n");
        Files.writeString(roaming.resolve("logs/b.log"), "roaming diagnostic\n--accessToken ROAM_SECRET\n");
        Files.writeString(local.resolve("user-accounts.json"), "ACCOUNT_SECRET");
        Files.writeString(game.resolve(".sao-launch-ticket.json"), "TICKET_SECRET");
        Files.writeString(game.resolve(SaoPackInstaller.RECEIPT), "{\"version\":\"0.8.1\"}");
        var zip = new SaoFeedbackZip(local, game, temporary.resolve("Desktop"), new SaoFeedbackSanitizer("", ""), List.of(local, roaming))
                .build("Launcher: fixture", List.of(), List.of());
        Map<String, String> entries = unzip(zip.path());
        assertTrue(zip.path().getFileName().toString().startsWith("NerveGear日志-"));
        assertTrue(entries.keySet().containsAll(List.of("info.txt", "pack/installed-inventory.json", "data-1/logs/launcher-1.log", "data-2/logs/launcher-1.log")));
        String all = entries.toString();
        for (String secret : List.of("LOCAL_SECRET", "ROAM_SECRET", "ACCOUNT_SECRET", "TICKET_SECRET", "user-accounts", ".sao-launch-ticket", "--accessToken")) assertFalse(all.contains(secret));
    }

    /// Windows paths, uppercase identities, masked mail and sensitive lines are also removed from live log display.
    @Test void sanitizationHandlesWindowsAndLegacyLogExport() {
        var sanitizer = new SaoFeedbackSanitizer("C:\\Users\\FixtureUser", "FixtureUser");
        String output = sanitizer.scrub(PRIVATE + "C:\\Users\\FIXTUREUSER\\x\nC:\\\\Users\\\\FixtureUser\\\\x\nmasked=a***@example.org");
        assertFalse(output.toLowerCase(Locale.ROOT).contains("fixtureuser"));
        assertFalse(output.contains("@example.org")); assertFalse(output.contains("C:\\Users"));
        assertEquals("[authentication data omitted]", SaoGameLogs.sanitize("{\"launchTicket\":\"private\"}"));
        assertFalse(SaoGameLogs.sanitize("mail=a@example.org server=127.0.0.1").contains("a@example.org"));
    }

    /// Multiline credential objects and callback boundaries cannot leave unlabeled secret values in the captured ring.
    @Test void streamingAndNestedCredentialsArePrivate() {
        String privateObject = "password: {\n  value: NESTED_SENTINEL\n  nested: [\n    ARRAY_SENTINEL\n  ]\n}\npublic: keep";
        var sanitizer = new SaoFeedbackSanitizer("", "");
        assertFalse(sanitizer.scrub(privateObject).contains("NESTED_SENTINEL"));
        assertFalse(sanitizer.scrub(privateObject).contains("ARRAY_SENTINEL"));
        var logs = new SaoGameLogs(temporary);
        logs.append("accessToken:"); logs.append("STREAM_TOKEN_SENTINEL"); logs.append("public line");
        logs.append("password:"); logs.append("{"); logs.append("nested: STREAM_OBJECT_SENTINEL"); logs.append("}");
        assertFalse(String.join("\n", logs.snapshot()).contains("STREAM_TOKEN_SENTINEL"));
        assertFalse(String.join("\n", logs.snapshot()).contains("STREAM_OBJECT_SENTINEL"));
        assertTrue(logs.snapshot().contains("public line"));
        logs.append("--accessToken"); logs.append("SEPARATE_ARG_SENTINEL"); logs.append("public after argument");
        assertFalse(String.join("\n", logs.snapshot()).contains("SEPARATE_ARG_SENTINEL"));
        assertTrue(logs.snapshot().contains("public after argument"));
        logs.append("password:"); logs.clear(); logs.append("new run public line");
        assertEquals(List.of("new run public line"), logs.snapshot());
        assertEquals("[authentication data omitted]", SaoGameLogs.sanitize("AWS_ACCESS_KEY_ID=API_KEY_SENTINEL"));
        assertFalse(SaoGameLogs.sanitize("MICROSOFT_AUTH_ID=APP_ID_SENTINEL").contains("APP_ID_SENTINEL"));
    }

    /// JSON/URL escaping cannot conceal credential labels, emails, IPs or the home path from the privacy filter.
    @Test void escapedSensitiveDiagnosticsArePrivate() {
        var sanitizer = new SaoFeedbackSanitizer("/Users/FixtureUser", "FixtureUser");
        String unicode = "\\" + "u0070assword=UNICODE_SECRET";
        String text = sanitizer.scrub(unicode + "\n%70assword=PERCENT_SECRET\n%2570assword=DOUBLE_SECRET\n"
                + "email=a%40example.org ip=%31%32%37.0.0.1 home=%2FUsers%2FFixtureUser\n");
        assertFalse(text.contains("UNICODE_SECRET")); assertFalse(text.contains("PERCENT_SECRET")); assertFalse(text.contains("DOUBLE_SECRET"));
        assertFalse(text.contains("a@example.org")); assertFalse(text.contains("a%40example.org"));
        assertFalse(text.contains("127.0.0.1")); assertFalse(text.contains("FixtureUser"));
    }

    /// An oversized crash report falls back to HMCL analysis of captured output instead of losing the crash card.
    @Test void crashCardSurvivesUnreadableReport() throws Exception {
        Files.createDirectories(temporary.resolve("crash-reports"));
        Files.writeString(temporary.resolve("crash-reports/crash-huge.txt"), "x".repeat(4 * 1024 * 1024 + 1));
        var logs = new SaoGameLogs(temporary); logs.append("java.lang.OutOfMemoryError: Java heap space");
        SaoData.@Nullable Crash crash = logs.analyze(true, 0); assertNotNull(crash); assertFalse(crash.reason().isBlank());
    }

    /// A symlinked permitted source refuses export without leaving any partial ZIP.
    @Test void rejectsSymlinkedSourcesAndCleansTemporaryOutput() throws Exception {
        Path instance = temporary.resolve("instance"), downloads = temporary.resolve("Downloads"); Files.createDirectories(instance.resolve("logs"));
        Path secret = temporary.resolve("private.txt"); Files.writeString(secret, "PRIVATE_FILE_SENTINEL");
        Files.createSymbolicLink(instance.resolve("logs/latest.log"), secret);
        var builder = new SaoFeedbackZip(temporary, instance, downloads, new SaoFeedbackSanitizer("", ""));
        assertThrows(IOException.class, () -> builder.build("system", List.of(), List.of()));
        try (var files = Files.list(downloads)) { assertEquals(0, files.count()); }
    }

    /// Both HMCL XZ and gzip launcher logs are decoded before redaction, never copied as opaque secret-bearing bytes.
    @Test void compressedLauncherLogsAreScrubbed() throws Exception {
        Path gzip = temporary.resolve("launcher.log.gz"), xz = temporary.resolve("previous.log.xz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(gzip))) { output.write((PRIVATE + "safe gzip").getBytes(StandardCharsets.UTF_8)); }
        try (var output = new org.tukaani.xz.XZOutputStream(Files.newOutputStream(xz), new org.tukaani.xz.LZMA2Options())) {
            output.write((PRIVATE + "safe xz").getBytes(StandardCharsets.UTF_8));
        }
        var builder = new SaoFeedbackZip(temporary, temporary.resolve("instance"), temporary.resolve("Downloads"), new SaoFeedbackSanitizer("/Users/FixtureUser", "FixtureUser"));
        Map<String, String> entries = unzip(builder.build("system", List.of(gzip, xz), List.of()).path());
        assertTrue(entries.get("launcher/launcher-1.log").contains("safe gzip"));
        assertTrue(entries.get("launcher/launcher-2.log").contains("safe xz"));
        assertFalse(entries.get("launcher/launcher-1.log").contains("PASS_SENTINEL"));
        assertFalse(entries.get("launcher/launcher-2.log").contains("TOKEN_SENTINEL"));
    }

    /// Empty installations still produce a valid useful artifact, with no manufactured crash/log files.
    @Test void missingSourcesProduceMinimalValidZip() throws Exception {
        Path instance = temporary.resolve("instance"), downloads = temporary.resolve("Downloads");
        var builder = new SaoFeedbackZip(temporary, instance, downloads, new SaoFeedbackSanitizer("", ""));
        var entries = unzip(builder.build("OS: fixture", List.of(), List.of()).path());
        assertEquals(Set.of("system-summary.txt", "info.txt", "mods-sha1.txt", "captured-game.log", "export-notes.txt"), entries.keySet());
    }

    /// Hash values are generated public identities, not OS usernames even when both happen to share hex characters.
    @Test void usernameRedactionDoesNotCorruptModHashes() throws Exception {
        Path instance = temporary.resolve("instance"); Files.createDirectories(instance.resolve("mods"));
        Path mod = instance.resolve("mods/a-private.jar"); Files.writeString(mod, "fixture jar");
        String hash;
        try (InputStream input = Files.newInputStream(mod)) { hash = SaoPackArchive.sha1(input); }
        String name = hash.substring(0, 1);
        var builder = new SaoFeedbackZip(temporary, instance, temporary.resolve("Downloads"), new SaoFeedbackSanitizer("", name));
        String inventory = unzip(builder.build("system", List.of(), List.of()).path()).get("mods-sha1.txt");
        assertTrue(inventory.contains(hash + "  "));
    }

    /// Reads the actual saved archive rather than trusting export completion or a proposed file list.
    private static Map<String, String> unzip(Path file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (InputStream input = zip.getInputStream(entry)) { result.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            }
        }
        return result;
    }
}
