/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.*;

/// Validated, platform-independent inventory of the one supported SAO pack.
///
/// @param version pack release version, independent of the launcher version
/// @param minecraft the fixed Minecraft version
/// @param neoforge the fixed NeoForge version
/// @param server empty until an actual server address has been configured
/// @param newsUrl HTTPS news JSON endpoint, empty when unconfigured
/// @param accountApi account API root, empty for the production default
/// @param serverNodes public alternate server addresses; empty uses the launcher seam defaults
/// @param files complete managed inventory, including defaults that are preserved after installation
/// @param removals explicit managed paths removed by this release
@NotNullByDefault
public record SaoPackManifest(String version, String minecraft, String neoforge, String server, String newsUrl, String accountApi,
                              @Unmodifiable List<String> serverNodes, @Unmodifiable List<PackFile> files, @Unmodifiable List<String> removals) {
    /// Resource and update-archive location of the preprocessed manifest.
    public static final String NAME = "sao-manifest.json";
    /// The sole supported Minecraft release.
    public static final String MINECRAFT = "1.20.1";
    /// The sole supported NeoForge release; Forge is not a substitute.
    public static final String NEOFORGE = "47.1.106";
    /// Limits untrusted manifests and archives to a practical pack size.
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;

    /// Copies collections, rejects incompatible dependencies, and validates every destination.
    public SaoPackManifest {
        if (version.isBlank() || version.length() > 80) throw new IllegalArgumentException("Invalid pack version");
        if (!MINECRAFT.equals(minecraft) || !NEOFORGE.equals(neoforge))
            throw new IllegalArgumentException("Only Minecraft 1.20.1 + NeoForge 47.1.106 is supported");
        if (server.length() > 255 || server.chars().anyMatch(Character::isWhitespace)
                || server.indexOf('/') >= 0 || server.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid server address");
        if (!newsUrl.isEmpty()) {
            java.net.URI uri = java.net.URI.create(newsUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || newsUrl.length() > 2048)
                throw new IllegalArgumentException("News URL must be an HTTPS endpoint without credentials");
        }
        if (!accountApi.isBlank()) SaoAccountClient.checkedRoot(accountApi);
        serverNodes = List.copyOf(serverNodes);
        if (serverNodes.size() > 32) throw new IllegalArgumentException("Too many server nodes");
        for (String node : serverNodes) {
            if (node.isEmpty() || node.length() > 255 || !node.matches("[A-Za-z0-9.\\-:\\[\\]]+"))
                throw new IllegalArgumentException("Invalid server node");
        }
        files = List.copyOf(files);
        removals = List.copyOf(removals);
        if (files.isEmpty() || files.size() > 100000) throw new IllegalArgumentException("Invalid pack inventory size");
        Set<String> seen = new HashSet<>();
        Set<String> folded = new HashSet<>();
        for (PackFile file : files) {
            validatePath(file.path());
            if (!seen.add(file.path()) || !folded.add(file.path().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate pack path: " + file.path());
        }
        // A file cannot also be the parent directory of another managed file.
        for (String path : seen) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (folded.contains(path.substring(0, slash).toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Conflicting pack paths: " + path);
                slash = path.indexOf('/', slash + 1);
            }
        }
        for (String path : removals) {
            validatePath(path);
            if (folded.contains(path.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Removed path is also in the inventory: " + path);
        }
    }

    /// Constructs an older manifest whose optional alternate-node inventory is absent.
    public SaoPackManifest(String version, String minecraft, String neoforge, String server, String newsUrl, String accountApi,
                           @Unmodifiable List<PackFile> files, @Unmodifiable List<String> removals) {
        this(version, minecraft, neoforge, server, newsUrl, accountApi, List.of(), files, removals);
    }

    /// Constructs a manifest predating configurable account endpoints.
    public SaoPackManifest(String version, String minecraft, String neoforge, String server, String newsUrl,
                           @Unmodifiable List<PackFile> files, @Unmodifiable List<String> removals) {
        this(version, minecraft, neoforge, server, newsUrl, "", List.of(), files, removals);
    }

    /// Constructs an older manifest with online news disabled for backwards compatibility.
    public SaoPackManifest(String version, String minecraft, String neoforge, String server,
                           @Unmodifiable List<PackFile> files, @Unmodifiable List<String> removals) {
        this(version, minecraft, neoforge, server, "", "", List.of(), files, removals);
    }

    /// One checksum-verified local archive member or remote dependency.
    ///
    /// @param path destination relative to the isolated game directory
    /// @param sha1 lowercase SHA-1 from the source inventory or archive preprocessing
    /// @param size exact uncompressed bytes
    /// @param entry archive member, or null for a remotely downloaded file
    /// @param downloads ordered HTTPS sources; empty for archive members
    @NotNullByDefault
    public record PackFile(String path, String sha1, long size, @Nullable String entry,
                           @Unmodifiable List<String> downloads) {
        /// Validates checksums, bounded sizes, safe entry names and HTTPS-only remote sources.
        public PackFile {
            validatePath(path);
            if (!sha1.matches("[0-9a-f]{40}") || size < 0 || size > MAX_FILE_SIZE)
                throw new IllegalArgumentException("Invalid checksum or size: " + path);
            downloads = List.copyOf(downloads);
            if (entry != null) {
                validatePath(entry);
                if (!entry.startsWith("overrides/")) throw new IllegalArgumentException("Archive entry outside overrides");
            } else if (downloads.isEmpty()) throw new IllegalArgumentException("Missing file source: " + path);
            for (String url : downloads) {
                if (!url.startsWith("https://") || url.chars().anyMatch(Character::isWhitespace))
                    throw new IllegalArgumentException("Only HTTPS pack downloads are supported");
            }
        }
    }

    /// Rejects traversal, alternate separators, Windows aliases and launcher-owned metadata paths.
    public static void validatePath(String path) {
        if (path.isEmpty() || path.length() > 1000 || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.indexOf('\0') >= 0 || path.contains("//"))
            throw new IllegalArgumentException("Unsafe pack path: " + path);
        for (String part : path.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(c -> c < 32 || c == '"' || c == '<' || c == '>' || c == '|' || c == '?' || c == '*')
                    || lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))
                throw new IllegalArgumentException("Unsafe pack path: " + path);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".sao-") || lower.equals("sao/skin.png") || lower.equals("sao/skin.json"))
            throw new IllegalArgumentException("Launcher-owned pack path: " + path);
    }

    /// True for player state: install seeds absent defaults, updates and repair preserve existing files.
    public static boolean isPlayerFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        // Executable pack payloads remain managed even when their names contain a player-state keyword.
        if (lower.endsWith(".jar")) return false;
        return lower.equals("options.txt") || lower.startsWith("options") && lower.endsWith(".txt")
                || lower.startsWith("saves/") || lower.startsWith("screenshots/")
                || lower.startsWith("shaderpacks/") || lower.equals("config/oculus.properties")
                || lower.equals("config/iris.properties") || lower.contains("keybind")
                || lower.equals("servers.dat") || lower.equals("servers.dat_old")
                // SAO settings the game saves itself (opening playWhen, saoFont, HUD switches, theme): copied only when
                // absent, never restored, or every repair/update undoes the player's choice (柚歌, 15 Nov).
                || lower.equals("config/sao-entrance.properties") || lower.equals("config/sao-interface.properties")
                || lower.equals("config/sao-interface-packui.properties");
    }

    /// Decodes an explicit manifest without Gson reflection or nullable record construction.
    public static SaoPackManifest read(Reader reader) throws IOException { return read(reader, false); }

    /// Reads an installed receipt written by another launcher version: the loader constants are taken as the current
    /// ones and server/news/account fields are dropped, keeping the version, inventory and removals.
    static SaoPackManifest readReceipt(Reader reader) throws IOException { return read(reader, true); }

    /// Shared parser; `receipt` relaxes only fields that do not describe installed files.
    private static SaoPackManifest read(Reader reader, boolean receipt) throws IOException {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<PackFile> files = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                List<String> downloads = new ArrayList<>();
                if (file.has("downloads")) for (JsonElement url : file.getAsJsonArray("downloads")) downloads.add(url.getAsString());
                files.add(new PackFile(file.get("path").getAsString(), file.get("sha1").getAsString(),
                        file.get("size").getAsLong(), file.has("entry") && !file.get("entry").isJsonNull()
                        ? file.get("entry").getAsString() : null, downloads));
            }
            List<String> removals = new ArrayList<>();
            if (root.has("removals")) for (JsonElement item : root.getAsJsonArray("removals")) removals.add(item.getAsString());
            List<String> nodes = new ArrayList<>();
            if (root.has("serverNodes")) for (JsonElement item : root.getAsJsonArray("serverNodes")) nodes.add(item.getAsString());
            if (receipt) return new SaoPackManifest(root.get("version").getAsString(), MINECRAFT, NEOFORGE, "", "", "", List.of(), files, removals);
            return new SaoPackManifest(root.get("version").getAsString(), root.get("minecraft").getAsString(),
                    root.get("neoforge").getAsString(), root.has("server") ? root.get("server").getAsString() : "",
                    root.has("newsUrl") ? root.get("newsUrl").getAsString() : "",
                    root.has("accountApi") ? root.get("accountApi").getAsString() : "", nodes, files, removals);
        } catch (RuntimeException e) {
            throw new IOException("Invalid SAO pack manifest: " + e.getMessage(), e);
        }
    }

    /// Returns a JSON representation containing public pack metadata only.
    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this);
    }
}
