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

/// Reuses the Minecraft files a player already has from PCL, HMCL or the official launcher (James, 15 Nov: "还真
/// 可以，你可以试试").
///
/// Most players already have 1.20.1's assets, libraries and client jar in another `.minecraft`; downloading the same
/// 3,600 files again was the slowest part of installing. Before a file is downloaded, the other folders are checked at
/// the same relative path (assets by their hash name, libraries by their maven path, the client jar under
/// `versions/1.20.1`). A file is used only when its SHA-1 matches, and it is copied into this launcher's own cache:
/// the other launcher's folder is read, never written, linked or moved.
@NotNullByDefault
final class SaoExistingMinecraft {
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
            found.addAll(hmclFolders(settings));
        List<Path> usable = new ArrayList<>();
        Path ownReal = normal(own);
        for (Path p : found) {
            Path n = normal(p);
            if (n.equals(ownReal) || !Files.isDirectory(n)) continue;
            if (Files.isDirectory(n.resolve("assets")) || Files.isDirectory(n.resolve("libraries"))) usable.add(n);
        }
        if (!usable.isEmpty()) Logger.LOG.info("Reusing files from other Minecraft folders: " + usable.size());
        return List.copyOf(usable);
    }

    private static Path normal(Path p) {
        try { return p.toRealPath(); } catch (IOException | RuntimeException missing) { return p.toAbsolutePath().normalize(); }
    }

    /// HMCL settings: `configurations.<name>.gameDir`.
    static List<Path> hmclFolders(Path settings) {
        List<Path> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(settings) || Files.size(settings) > 4_000_000) return out;
            JsonObject root = JsonParser.parseString(Files.readString(settings, StandardCharsets.UTF_8)).getAsJsonObject();
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
    static final class Cache extends DefaultCacheRepository {
        private final java.util.function.Supplier<List<Path>> search;
        private volatile @Nullable List<Path> others;
        private final java.util.concurrent.atomic.AtomicInteger reused = new java.util.concurrent.atomic.AtomicInteger();

        /// The search runs once, on the first file this cache does not have, not at launcher start.
        Cache(Path own, java.util.function.Supplier<List<Path>> search) { super(own); this.search = search; }

        private List<Path> others() {
            @Nullable List<Path> o = others;
            if (o == null) synchronized (this) {
                o = others;
                if (o == null) { try { o = List.copyOf(search.get()); } catch (RuntimeException failure) { o = List.of(); } others = o; }
            }
            return o;
        }

        int reused() { return reused.get(); }

        /// Assets are named by their SHA-1; the 1.20.1 client jar has a fixed place.
        @Override public Optional<Path> checkExistentFile(@Nullable Path original, String algorithm, String hash) {
            Optional<Path> mine = super.checkExistentFile(original, algorithm, hash);
            if (mine.isPresent() || hash == null || !SHA1.equals(algorithm) || hash.length() != 40) return mine;
            List<String> places = List.of("assets/objects/" + hash.substring(0, 2) + "/" + hash, "versions/1.20.1/1.20.1.jar");
            return borrow(places, hash);
        }

        /// Libraries by their maven path.
        @Override public Optional<Path> getLibrary(Library library) {
            Optional<Path> mine = super.getLibrary(library);
            if (mine.isPresent()) return mine;
            @Nullable String hash = library.getDownload() == null ? null : library.getDownload().getSha1();
            if (hash == null || hash.length() != 40) return mine;
            return borrow(List.of("libraries/" + library.getPath()), hash);
        }

        private Optional<Path> borrow(List<String> places, String hash) {
            for (Path other : others()) for (String rel : places) {
                Path file = other.resolve(rel).normalize();
                if (!file.startsWith(other) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (!DigestUtils.digestToString(SHA1, file).equalsIgnoreCase(hash)) continue;
                    Path copy = cacheFile(file, SHA1, hash);
                    reused.incrementAndGet();
                    return Optional.of(copy);
                } catch (IOException | RuntimeException unreadable) { /* try the next folder */ }
            }
            return Optional.empty();
        }
    }
}
