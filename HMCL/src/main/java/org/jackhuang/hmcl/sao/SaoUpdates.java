/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/// Strict public release metadata and filesystem-only archive verification, with no toolkit or network access.
@NotNullByDefault
final class SaoUpdates {
    /// Default public announcements; package bytes are never fetched from this host.
    static final String NEWS_URL = "https://gitee.com/xwzho/sao-oath-launcher/raw/master/news.json";
    /// Default public release identity and launcher compatibility policy.
    static final String VERSION_URL = "https://gitee.com/xwzho/sao-oath-launcher/raw/master/version.json";
    /// Public manual-download share, also used by the team's hash command.
    static final String QUARK_URL = "https://pan.quark.cn/s/1a81ea41915c";
    /// Common localized rejection for truncated, substituted or changing download files.
    static final String INVALID_ARCHIVE = "文件不完整或不是这个版本，请重新下载";
    /// Maximum UTF-8 document bytes; news retains its independent larger limit.
    static final int MAX_VERSION_BYTES = 16384;

    /// Utility class.
    private SaoUpdates() { }

    /// Parses exact schema/type/field sets, rejecting duplicates, trailing input and Gson coercions.
    static SaoData.VersionMetadata parse(String document) throws IOException {
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_VERSION_BYTES) throw new IOException("Version document is too large");
        try (JsonReader reader = new JsonReader(new StringReader(document))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = object(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing version data");
            fields(root, "schema", "pack", "launcher");
            if (integer(root, "schema") != 1) throw new IOException("Unsupported version schema");
            JsonObject pack = child(root, "pack"); fields(pack, "version", "quark", "file", "size", "sha256");
            String version = version(string(pack, "version"));
            String quark = checkedQuark(string(pack, "quark")).toString();
            String file = filename(string(pack, "file"));
            long size = integer(pack, "size");
            if (size <= 0) throw new IOException("Pack size must be positive");
            String sha256 = string(pack, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256");
            JsonObject launcher = child(root, "launcher"); fields(launcher, "latest", "min", "quark");
            String latest = version(string(launcher, "latest"));
            String minimum = version(string(launcher, "min"));
            if (compare(latest, minimum) < 0) throw new IOException("Launcher latest is below min");
            return new SaoData.VersionMetadata(1, new SaoData.PackRelease(version, quark, file, size, sha256),
                    new SaoData.LauncherRelease(latest, minimum, checkedQuark(string(launcher, "quark")).toString()));
        } catch (RuntimeException failure) { throw new IOException("Invalid version document", failure); }
    }

    /// Reads objects with a small nesting bound and rejects duplicate names before a JSON tree can hide them.
    private static JsonObject object(JsonReader reader, int depth) throws IOException {
        if (depth > 1 || reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Invalid version object");
        reader.beginObject();
        JsonObject object = new JsonObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (object.has(name)) throw new IOException("Duplicate version field");
            switch (reader.peek()) {
                case BEGIN_OBJECT -> object.add(name, object(reader, depth + 1));
                case STRING -> object.addProperty(name, reader.nextString());
                case NUMBER -> {
                    String number = reader.nextString();
                    if (!number.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Expected nonnegative integer");
                    object.addProperty(name, Long.parseLong(number));
                }
                default -> throw new IOException("Unexpected version value type");
            }
        }
        reader.endObject(); return object;
    }

    /// Requires the entire known field set, so misspellings never silently weaken update checks.
    private static void fields(JsonObject object, String @Unmodifiable ... names) throws IOException {
        if (!object.keySet().equals(Set.of(names))) throw new IOException("Unexpected or missing version fields");
    }

    /// Requires an actual JSON object.
    private static JsonObject child(JsonObject parent, String name) throws IOException {
        JsonElement value = parent.get(name);
        if (!value.isJsonObject()) throw new IOException("Expected object: " + name);
        return value.getAsJsonObject();
    }

    /// Requires a JSON string, without converting numbers or booleans.
    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected string: " + name);
        return value.getAsString();
    }

    /// Requires a JSON integer that was validated lexically while reading.
    private static long integer(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Expected integer: " + name);
        return value.getAsLong();
    }

    /// Accepts one to four numeric components, each at most nine digits, with no ambiguous leading zeros.
    static String version(String version) throws IOException {
        if (!version.matches("(?:0|[1-9][0-9]{0,8})(?:\\.(?:0|[1-9][0-9]{0,8})){0,3}")) throw new IOException("Invalid numeric version");
        return version;
    }

    /// Compares numeric dotted components, treating omitted trailing zero components as zero.
    static int compare(String first, String second) {
        String @Unmodifiable [] left = first.split("\\."); String @Unmodifiable [] right = second.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            BigInteger a = i < left.length ? new BigInteger(left[i]) : BigInteger.ZERO;
            BigInteger b = i < right.length ? new BigInteger(right[i]) : BigInteger.ZERO;
            int order = a.compareTo(b); if (order != 0) return order;
        }
        return 0;
    }

    /// Accepts a portable ZIP basename; Windows reserved characters/names and traversal are forbidden everywhere.
    static String filename(String name) throws IOException {
        if (name.length() > 240 || name.isBlank() || name.startsWith(".") || !name.endsWith(".zip")
                || name.matches(".*[\\p{Cntrl}<>:\"/\\\\|?*].*") || name.endsWith(" ")
                || name.matches("(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
            throw new IOException("Invalid pack filename");
        return name;
    }

    /// Allows only the exact Quark HTTPS origin, without credentials, fragments or alternate ports.
    static URI checkedQuark(String link) throws IOException {
        try {
            URI uri = URI.create(link);
            if (link.length() > 2048 || !"https".equals(uri.getScheme()) || !"pan.quark.cn".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")) throw new IOException("仅支持 https://pan.quark.cn/ 下载链接");
            return uri;
        } catch (IllegalArgumentException failure) { throw new IOException("夸克下载链接无效", failure); }
    }

    /// Computes display flags without changing local install state or requiring a network result.
    static SaoData.UpdateStatus status(@Nullable SaoData.VersionMetadata metadata, @Nullable String installed,
                                       String launcher, @Nullable String dismissedLatest) {
        if (metadata == null) return new SaoData.UpdateStatus(null, false, false, false, "");
        boolean blocked = compareInstalled(launcher, metadata.launcher().min()) < 0;
        boolean optional = !blocked && compareInstalled(launcher, metadata.launcher().latest()) < 0
                && !metadata.launcher().latest().equals(dismissedLatest);
        String message = blocked ? "启动器版本过低，请先从夸克下载并更新启动器后再 Link Start（最低版本 " + metadata.launcher().min() + "）"
                : optional ? "有新版启动器 " + metadata.launcher().latest() + "，可从夸克下载；也可暂时忽略" : "";
        return new SaoData.UpdateStatus(metadata, installed == null || compareInstalled(installed, metadata.pack().version()) < 0,
                blocked, optional, message);
    }

    /// Preserves compatibility with legacy pack receipt labels while keeping public release versions strictly numeric.
    private static int compareInstalled(String installed, String advertised) {
        try { version(installed); return compare(installed, advertised); }
        catch (IOException invalidLegacyVersion) {
            return org.jackhuang.hmcl.util.versioning.VersionNumber.compare(installed, advertised);
        }
    }

    /// Hashes a regular file incrementally; symlink inputs are rejected and no archive entries are extracted.
    static String sha256(Path zip) throws IOException {
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) throw new IOException(INVALID_ARCHIVE);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(zip, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /// Verifies both exact size and digest immediately before the launcher accepts its private copy for installation.
    static void verify(Path zip, SaoData.PackRelease release) throws IOException {
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) || Files.size(zip) != release.size()
                || !sha256(zip).equals(release.sha256()) || Files.size(zip) != release.size()) throw new IOException(INVALID_ARCHIVE);
    }

    /// Takes a private copy, validates exact archive bytes and manifest version, and returns that immutable input.
    /// The caller owns deletion of the returned temporary ZIP after its preserving installer transaction.
    static SaoPackArchive snapshot(Path zip, SaoData.PackRelease release, Path cache) throws IOException {
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) || Files.size(zip) != release.size()) throw new IOException(INVALID_ARCHIVE);
        Files.createDirectories(cache);
        Path snapshot = Files.createTempFile(cache, "download-", ".zip");
        boolean accepted = false;
        try {
            Files.copy(zip, snapshot, StandardCopyOption.REPLACE_EXISTING);
            verify(snapshot, release);
            SaoPackArchive archive = SaoPackArchive.open(snapshot);
            if (!archive.manifest().version().equals(release.version())) throw new IOException(INVALID_ARCHIVE);
            accepted = true; return archive;
        } finally { if (!accepted) Files.deleteIfExists(snapshot); }
    }
}
