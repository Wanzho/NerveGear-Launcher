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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Reuses verified Minecraft files from explicitly approved PCL, HMCL or official launcher folders.
///
/// Most players already have 1.20.1's assets, libraries and client jar in another `.minecraft`; downloading the same
/// 3,600 files again was the slowest part of installing. Before a file is downloaded, the other folders are checked at
/// the same relative path (assets by their hash name, libraries by their maven path, the client jar under
/// `versions/1.20.1`). A file is used only when its SHA-1 matches, and it is copied into this launcher's own cache:
/// the other launcher's folder is read, never written, linked or moved.
@NotNullByDefault
final class SaoExistingMinecraft {
    /// Utility class.
    private SaoExistingMinecraft() { }

    /// Every other Minecraft folder found on this computer, existing and holding assets or libraries, ours excluded.
    static List<Path> find(Path own) {
        Set<Path> found = new LinkedHashSet<>();
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        @Nullable String appData = System.getenv("APPDATA");
        if (os.contains("win")) {
            if (appData != null) found.add(Path.of(appData, ".minecraft"));
            found.addAll(pclFolders());
        } else if (os.contains("mac")) {
            found.add(Path.of(home, "Library", "Application Support", "minecraft"));
        } else {
            found.add(Path.of(home, ".minecraft"));
        }
        // HMCL keeps its game folders in its settings, wherever the player put them.
        for (Path settings : List.of(Path.of(home, ".hmcl", "hmcl.json"), appData == null ? Path.of(home, ".hmcl", "config.json") : Path.of(appData, ".hmcl", "hmcl.json"),
                Path.of(home, "Library", "Application Support", "hmcl", "hmcl.json")))
        {
            found.addAll(hmclFolders(settings));
            found.addAll(hmclFolders(settings.getParent().resolve("config/game-directories.json")));
            found.addAll(hmclFolders(settings.getParent().resolve("config/user-game-directories.json")));
        }
        found.addAll(portableFolders(List.of(Path.of(home, "Desktop"), Path.of(home, "Downloads"),
                Path.of(home, "Games"), Path.of(home, "Documents"), Path.of(System.getProperty("user.dir", ".")))));
        List<Path> usable = new ArrayList<>();
        Path ownReal = normal(own);
        for (Path p : found) {
            Path n = normal(p);
            if (n.startsWith(ownReal) || ownReal.startsWith(n) || !Files.isDirectory(n)) continue;
            if (Files.isDirectory(n.resolve("assets")) || Files.isDirectory(n.resolve("libraries")) || Files.isDirectory(n.resolve("versions"))) usable.add(n);
        }
        if (!usable.isEmpty()) Logger.LOG.info("Reusing files from other Minecraft folders: " + usable.size());
        return List.copyOf(usable);
    }

    /// Bounded portable-folder discovery; never traverses symlinks or scans a whole disk.
    static @Unmodifiable List<Path> portableFolders(List<Path> roots) {
        Set<Path> result = new LinkedHashSet<>();
        for (Path root : roots) {
            int visited = 0;
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var walk = Files.walk(root, 2)) {
                var iterator = walk.iterator();
                while (iterator.hasNext() && visited++ < 2048) {
                    Path dir = iterator.next();
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (Files.isDirectory(dir.resolve("assets"), LinkOption.NOFOLLOW_LINKS)
                            || Files.isDirectory(dir.resolve("libraries"), LinkOption.NOFOLLOW_LINKS)
                            || Files.isDirectory(dir.resolve("versions"), LinkOption.NOFOLLOW_LINKS)) result.add(dir);
                    result.addAll(hmclFolders(dir.resolve("hmcl.json")));
                    result.addAll(hmclFolders(dir.resolve(".hmcl/config/game-directories.json")));
                    result.addAll(hmclFolders(dir.resolve("config/game-directories.json")));
                }
            } catch (IOException | RuntimeException unavailable) { /* Try the next root. */ }
        }
        return List.copyOf(result);
    }

    /// Canonicalizes available roots and tolerates disconnected drives.
    private static Path normal(Path p) {
        try { return p.toRealPath(); } catch (IOException | RuntimeException missing) { return p.toAbsolutePath().normalize(); }
    }

    /// Reads legacy HMCL configurations and current config/*game-directories.json stores.
    static List<Path> hmclFolders(Path settings) {
        List<Path> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(settings) || Files.size(settings) > 4_000_000) return out;
            JsonObject root = JsonParser.parseString(Files.readString(settings, StandardCharsets.UTF_8)).getAsJsonObject();
            @Nullable JsonElement directories = root.get("directories");
            if (directories != null && directories.isJsonArray()) {
                Path base = settings.getParent().getFileName().toString().equals("config")
                        ? settings.getParent().getParent() : settings.getParent();
                for (var entry : directories.getAsJsonArray()) {
                    if (!entry.isJsonObject()) continue;
                    @Nullable JsonElement value = entry.getAsJsonObject().get("path");
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                    Path path = Path.of(value.getAsString());
                    out.add(path.isAbsolute() ? path : base.resolve(path).normalize());
                }
            }
            @Nullable JsonElement configurations = root.get("configurations");
            if (configurations == null || !configurations.isJsonObject()) return out;
            for (var e : configurations.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                @Nullable JsonElement dir = e.getValue().getAsJsonObject().get("gameDir");
                if (dir != null && dir.isJsonPrimitive()) {
                    Path p = Path.of(dir.getAsString());
                    out.add(p.isAbsolute() ? p : settings.getParent().resolve(p));
                }
            }
        } catch (IOException | RuntimeException unreadable) { /* Not HMCL's format or unreadable: nothing to reuse. */ }
        return out;
    }

    /// PCL lists its game folders in the registry as `name>path|name>path|`; read-only `reg query`, 3 s at most.
    private static List<Path> pclFolders() {
        List<Path> out = new ArrayList<>();
        for (String key : List.of("HKCU\\Software\\PCL", "HKCU\\Software\\PCLCE")) {
            try {
                Process p = new ProcessBuilder("reg", "query", key, "/v", "LaunchFolders").redirectErrorStream(true).start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); continue; }
                out.addAll(parsePclFolders(new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset())));
            } catch (IOException | RuntimeException failure) { /* No PCL. */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return out;
    }

    /// Parses `reg query` output for LaunchFolders; package-visible for tests.
    static List<Path> parsePclFolders(String output) {
        List<Path> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int at = line.indexOf("REG_SZ");
            if (!line.contains("LaunchFolders") || at < 0) continue;
            for (String item : line.substring(at + "REG_SZ".length()).trim().split("\\|")) {
                int gt = item.indexOf('>');
                String dir = (gt >= 0 ? item.substring(gt + 1) : item).trim();
                if (dir.isEmpty()) continue;
                try { out.add(Path.of(dir)); } catch (RuntimeException invalid) { /* skip */ }
            }
        }
        return out;
    }

    /// A cache that, on a miss, looks in the other folders, verifies, and copies into itself.
    @NotNullByDefault
    static final class Cache extends DefaultCacheRepository {
        /// Discovers ordinary Minecraft caches.
        private final java.util.function.Supplier<List<Path>> search;
        /// Data root used to discover newly imported bundles even after the ordinary search was cached.
        private final Path dataRoot;
        /// Cached discovery, reset when the player changes approved sources.
        private volatile @Nullable List<Path> others;
        /// Number of verified copies supplied to downloads.
        private final java.util.concurrent.atomic.AtomicInteger reused = new java.util.concurrent.atomic.AtomicInteger();

        /// The search runs once, on the first file this cache does not have, not at launcher start.
        Cache(Path own, java.util.function.Supplier<List<Path>> search) { super(own); this.search = search; this.dataRoot = own.toAbsolutePath().getParent(); }

        /// Invalidates the list after an explicit user selection, including an empty selection.
        synchronized void refreshSources() { others = null; clients = null; }

        /// Combines approved sources with verified offline bundles.
        private List<Path> others() {
            @Nullable List<Path> o = others;
            if (o == null) synchronized (this) {
                    o = others;
                    if (o == null) { try { o = List.copyOf(search.get()); } catch (RuntimeException failure) { o = List.of(); } others = o; }
                }
            List<Path> all = new ArrayList<>(SaoOfflineBundle.sources(dataRoot));
            all.addAll(o);
            return all;
        }

        /// Number of verified borrowed files.
        int reused() { return reused.get(); }

        /// Assets are named by their SHA-1; the 1.20.1 client jar has a fixed place.
        @Override public Optional<Path> checkExistentFile(@Nullable Path original, String algorithm, String hash) {
            Optional<Path> mine = super.checkExistentFile(original, algorithm, hash);
            if (mine.isPresent() || hash == null || !SHA1.equals(algorithm) || hash.length() != 40) return mine;
            @Unmodifiable List<String> places = List.of("assets/objects/" + hash.substring(0, 2) + "/" + hash);
            Optional<Path> exact = borrow(places, hash);
            if (exact.isPresent()) return exact;
            return borrow(clientPaths(hash), hash);
        }

        /// Hash-indexed client locations avoid repeatedly hashing every client for asset misses.
        private volatile @Nullable java.util.Map<String, List<String>> clients;

        /// HMCL client requests pass a null original path; use content identity, never the request filename.
        private synchronized @Unmodifiable List<String> clientPaths(String requestedHash) {
            if (clients != null) return clients.getOrDefault(requestedHash, List.of());
            java.util.Map<String, List<String>> indexed = new java.util.HashMap<>();
            long budget = 512L * 1024 * 1024;
            int count = 0;
            for (Path other : others()) {
                List<Path> candidates = new ArrayList<>();
                candidates.add(other.resolve("assets/indexes/5.json"));
                try (var dirs = Files.newDirectoryStream(other.resolve("versions"))) {
                    int visited = 0;
                    for (Path dir : dirs) {
                        if (++visited > 128) break;
                        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                        String name = dir.getFileName().toString();
                        candidates.add(dir.resolve(name + ".jar"));
                    }
                } catch (IOException unavailable) { /* No versions here. */ }
                for (Path candidate : candidates) {
                    try {
                        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (!candidate.toRealPath().startsWith(other.toRealPath())) continue;
                        long size = Files.size(candidate);
                        if (size > budget || count >= 64) continue;
                        budget -= size; count++;
                        String hash = DigestUtils.digestToString(SHA1, candidate);
                        indexed.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(other.relativize(candidate).toString());
                    } catch (IOException unavailable) { /* Skip inaccessible files. */ }
                }
            }
            java.util.Map<String, List<String>> frozen = new java.util.HashMap<>();
            indexed.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            clients = java.util.Map.copyOf(frozen);
            return clients.getOrDefault(requestedHash, List.of());
        }

        /// Libraries by their maven path.
        @Override public Optional<Path> getLibrary(Library library) {
            Optional<Path> mine = super.getLibrary(library);
            if (mine.isPresent()) return mine;
            @Nullable String hash = library.getDownload() == null ? null : library.getDownload().getSha1();
            if (hash == null || hash.length() != 40) return mine;
            return borrow(List.of("libraries/" + library.getPath()), hash);
        }

        /// Copies only matching content; source mutation during copying is detected afterward.
        private Optional<Path> borrow(List<String> places, String hash) {
            for (Path other : others()) for (String rel : places) {
                    Path file = other.resolve(rel).normalize();
                    if (!file.startsWith(other) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        if (!file.toRealPath().startsWith(other.toRealPath())) continue;
                        if (!DigestUtils.digestToString(SHA1, file).equalsIgnoreCase(hash)) continue;
                        Path copy = cacheFile(file, SHA1, hash);
                        if (!DigestUtils.digestToString(SHA1, copy).equalsIgnoreCase(hash)) { Files.deleteIfExists(copy); continue; }
                        reused.incrementAndGet();
                        return Optional.of(copy);
                    } catch (IOException | RuntimeException unreadable) { /* try the next folder */ }
                }
            return Optional.empty();
        }
    }
}
