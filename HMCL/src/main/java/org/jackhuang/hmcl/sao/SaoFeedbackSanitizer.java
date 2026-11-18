/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Defense-in-depth scrubber for whitelisted diagnostic text, never a serializer for accounts or credentials.
/// Sensitive credential-bearing lines are omitted entirely, including common command and header spellings.
@NotNullByDefault
final class SaoFeedbackSanitizer {
    /// HMCL and Minecraft both start log records with bracketed clock times.
    private static final Pattern CLOCK = Pattern.compile("^\\[(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:[.,][0-9]{1,3})?\\](?= |$)");
    /// A numeric release tag can have four components and resemble an address.
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,5}(?:\\.[0-9]{1,5}){1,5}(?:-(?:pre|rc)[0-9]{1,5})?");
    /// Only launcher-generated summary fields identify an unambiguous release tag.
    private static final Pattern SUMMARY_VERSION = Pattern.compile("(?:Launcher|Embedded pack|Installed pack): " + VERSION);
    /// Only known launcher diagnostic stages may bypass whole-line authentication redaction.
    private static final String STEP = "(?:microsoft-session|microsoft-refresh|microsoft-skin|accept|session-validate|session-refresh"
            + "|game-token|launch-ticket|mojang-join|skin-profile|texture-image|premium-(?:challenge|complete|login)"
            + "|account-(?:proof|register|state|http)|player-card|textures|social)";
    /// Structured diagnostic rows contain only fixed stage and failure codes plus numeric metadata.
    private static final Pattern SAFE_DIAGNOSTIC = Pattern.compile(
            "(?:\\[@\\.sao\\.(?:SaoAccountClient\\.request|SaoLoginSteps\\.(?:timed|elapsed))/(?:INFO|WARNING)\\] )?"
            + "(?:Sign-in timing step=" + STEP + " elapsedMs=[0-9]{1,19}"
            + "|ARGUS HTTP failure: step=" + STEP + " status=[1-5][0-9]{2}"
            + "|(?:ARGUS transport failure:|Sign-in failure) step=" + STEP
            + " kind=(?:dns|tls|connect-timeout|request-timeout|connect|transport|account|cancelled))");
    /// Shared marker used for sensitive text lines and sanitized JSON string values.
    private static final String OMITTED = "[authentication data omitted]";
    /// Only metadata emitted by the pack installer and active-cache pointer is exported.
    private static final @Unmodifiable Set<String> PACK_FIELDS = Set.of("version", "minecraft", "neoforge",
            "server", "newsUrl", "accountApi", "serverNodes", "files", "path", "sha1", "size", "entry",
            "downloads", "sha512", "removals", "acceptedArchive", "archive", "manifest");
    /// Identifies credential context, including common underscored/hyphenated protocol spellings.
    private static final Pattern AUTH = Pattern.compile("(?i)password|passwd|proxypass|client[_. -]?id|microsoft[_. -]?auth[_. -]?id|(?:api|access|client|encryption)[_. -]?key|user[_. -]?properties|\\bpwd\\b|token|ticket|secret|credential|authorization|authentication|bearer|cookie|session|sao[_. -]?auth|(?:^|[\\s\"'])/(?:login|register|l|reg|changepassword)\\b|private[ _-]?key");
    /// A sensitive command flag on its own line owns the next argument, even when a command is line-wrapped.
    private static final Pattern ARGUMENT_KEY = Pattern.compile("(?i)--(?:access[-_]?token|client[-_]?token|client[-_]?secret|password|session|uuid)[\\s\"',\\]]*$");
    /// Mailboxes, including masked account displays, are personal data even in otherwise harmless logs.
    private static final Pattern EMAIL = Pattern.compile("(?i)[\\p{L}\\p{N}.!#$%&'*+/=?^_`{|}~*-]+@[\\p{L}\\p{N}.-]+(?:\\.[\\p{L}]{2,})?");
    /// IPv4 literals are removed even when followed by a port or embedded in a URL.
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?![0-9])");
    /// IPv6, including compressed addresses, mapped IPv4 and zone suffixes; conservative over-redaction is intentional.
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*(?:%[a-z0-9_.-]+)?");
    /// JWTs may appear without a descriptive key in exception messages.
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    /// UUID and legacy compact session identities are not needed in support diagnostics.
    private static final Pattern UUID_VALUE = Pattern.compile("(?i)(?<![a-f0-9])[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}(?![a-f0-9])|(?<![a-f0-9])[a-f0-9]{32}(?![a-f0-9])");
    /// JSON-style Unicode escapes can otherwise conceal sensitive keys, addresses and home identities.
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    /// Consecutive percent bytes are decoded without interpreting plus signs or invoking any URI/network service.
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("(?:%[0-9a-fA-F]{2})+");
    /// Home and account name are captured explicitly so tests can exercise Windows and macOS independently.
    private final String home;
    /// Login name is scrubbed from text and archive entry labels without consulting any account file.
    private final String username;

    /// Uses only process properties; it never loads launcher account/session storage.
    SaoFeedbackSanitizer(String home, String username) { this.home = home; this.username = username; }

    /// Scrubs a complete document with isolated continuation state, so separate files never affect each other.
    String scrub(String text) {
        Stream stream = stream(); StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R", -1)) out.append(stream.line(line)).append('\n');
        return out.toString();
    }

    /// Creates per-run state for process callbacks that arrive as individual lines.
    Stream stream() { return new Stream(); }

    /// Retains credential continuation/PEM context across individual process-output callbacks.
    @NotNullByDefault
    final class Stream {
        /// A credential key or command flag ended before its value; the next nonblank line must also be omitted.
        private boolean continuation;
        /// PEM private-key blocks are omitted through their end marker.
        private boolean privateKey;
        /// Nested credential objects/arrays are omitted through their matching close delimiter.
        private int sensitiveDepth;

        /// Scrubs one line without losing context when a password or ticket spans multiple callbacks.
        String line(String line) {
            line = decoded(line);
            Matcher clock = CLOCK.matcher(line);
            String prefix = clock.find() ? clock.group() : "";
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("-----begin") && lower.contains("private key")) privateKey = true;
            if (privateKey) {
                if (lower.contains("-----end")) privateKey = false;
                return omitted(prefix);
            }
            if (sensitiveDepth > 0) {
                sensitiveDepth += structureDelta(line); return omitted(prefix);
            }
            if (continuation) {
                continuation = line.substring(prefix.length()).strip().isEmpty();
                sensitiveDepth = Math.max(0, structureDelta(line));
                return omitted(prefix);
            }
            String message = line.substring(prefix.length());
            if (SAFE_DIAGNOSTIC.matcher(message.stripLeading()).matches() || SUMMARY_VERSION.matcher(message).matches())
                return line;
            if (AUTH.matcher(line).find() || ARGUMENT_KEY.matcher(line).find()) {
                String tail = line.stripTrailing(); continuation = tail.endsWith(":") || tail.endsWith("=") || ARGUMENT_KEY.matcher(tail).find();
                sensitiveDepth = Math.max(0, structureDelta(line));
                return omitted(prefix);
            }
            return prefix + publicText(message);
        }
    }

    /// Leaves the clock visible even when the entire message is sensitive.
    private static String omitted(String prefix) {
        return prefix.isEmpty() ? OMITTED : prefix + " " + OMITTED;
    }

    /// Redacts structured pack diagnostics without breaking JSON or modifying valid file digests.
    JsonElement scrubJson(JsonElement value, String key) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (var field : value.getAsJsonObject().entrySet()) {
                String name = field.getKey();
                if (PACK_FIELDS.contains(name)) result.add(name, scrubJson(field.getValue(), name));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) result.add(scrubJson(item, key));
            return result;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return value;
        String original = value.getAsString();
        if (((key.equals("version") || key.equals("minecraft") || key.equals("neoforge")) && VERSION.matcher(original).matches())
                || (key.equals("sha1") && original.matches("[0-9a-f]{40}"))
                || (key.equals("sha512") && original.matches("[0-9a-f]{128}"))
                || ((key.equals("acceptedArchive") || key.equals("archive")) && original.matches("[0-9a-f]{40,64}\\.zip")))
            return value;
        String safe = decoded(original);
        if (key.equals("downloads") || key.equals("newsUrl") || key.equals("accountApi")) {
            try {
                var uri = java.net.URI.create(original);
                if (uri.getUserInfo() != null) return new JsonPrimitive(OMITTED);
                safe = decoded(original.split("[?#]", 2)[0]);
            } catch (IllegalArgumentException invalid) {
                return new JsonPrimitive(OMITTED);
            }
        }
        if (key.equals("path") || key.equals("entry") || key.equals("removals")) {
            try { SaoPackManifest.validatePath(safe); }
            catch (IllegalArgumentException invalid) { return new JsonPrimitive(OMITTED); }
        } else if (AUTH.matcher(safe).find()) {
            return new JsonPrimitive(OMITTED);
        }
        return new JsonPrimitive(publicText(safe));
    }

    /// Removes identifying literals from otherwise public text, including JSON-escaped Windows home paths.
    String publicText(String text) {
        String result = text;
        if (!home.isEmpty()) {
            String @Unmodifiable [] variants = {home, home.replace('\\', '/'), home.replace("\\", "\\\\"), home.replace("/", "\\/")};
            for (String variant : variants)
                result = replaceLiteral(result, variant, "~");
        }
        if (!username.isBlank()) result = replaceLiteral(result, username, "~");
        result = EMAIL.matcher(result).replaceAll("[email omitted]");
        result = IPV4.matcher(result).replaceAll("[IP omitted]");
        result = IPV6.matcher(result).replaceAll("[IP omitted]");
        result = UUID_VALUE.matcher(result).replaceAll("[UUID omitted]");
        return JWT.matcher(result).replaceAll("[authentication data omitted]");
    }

    /// Normalizes ordinary escaped diagnostics before privacy matching, including a double-encoded URL value.
    private static String decoded(String text) {
        String result = text;
        for (int pass = 0; pass < 3; pass++) {
            String before = result;
            Matcher unicode = UNICODE_ESCAPE.matcher(result); StringBuilder normalized = new StringBuilder();
            while (unicode.find()) unicode.appendReplacement(normalized,
                    Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(unicode.group(1), 16))));
            unicode.appendTail(normalized); result = normalized.toString();
            Matcher percent = PERCENT_ESCAPE.matcher(result); normalized = new StringBuilder();
            while (percent.find()) {
                byte @Unmodifiable [] bytes = HexFormat.of().parseHex(percent.group().replace("%", ""));
                percent.appendReplacement(normalized, Matcher.quoteReplacement(new String(bytes, StandardCharsets.UTF_8)));
            }
            percent.appendTail(normalized); result = normalized.toString();
            if (result.equals(before)) break;
        }
        return result;
    }

    /// Counts structural braces outside strings to omit multiline credential objects/arrays as a unit.
    private static int structureDelta(String line) {
        boolean quoted = false, escaped = false; int delta = 0;
        for (char character : line.toCharArray()) {
            if (escaped) { escaped = false; continue; }
            if (character == '\\' && quoted) { escaped = true; continue; }
            if (character == '"') { quoted = !quoted; continue; }
            if (!quoted) {
                if (character == '{' || character == '[') delta++;
                else if (character == '}' || character == ']') delta--;
            }
        }
        return delta;
    }

    /// Literal case-insensitive replacement avoids interpreting usernames as patterns or replacement backreferences.
    private static String replaceLiteral(String text, String value, String replacement) {
        return Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }
}
