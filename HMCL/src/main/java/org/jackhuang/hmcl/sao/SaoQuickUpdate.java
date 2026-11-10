/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/// Self-contained quick-update metadata and chain staging. No installed file is modified while a chain is prepared.
/// @param from exact required base version
/// @param to resulting version
/// @param before full base inventory, used to constrain removal and verify installed files
/// @param after full resulting inventory
/// @param base SHA-256 of every base inventory file
/// @param expected SHA-256 of every resulting inventory file
/// @param added new managed paths with embedded payloads
/// @param changed changed managed paths with embedded payloads
/// @param removed old managed paths absent from the result
@NotNullByDefault
public record SaoQuickUpdate(String from, String to, SaoPackManifest before, SaoPackManifest after,
                             @Unmodifiable Map<String, String> base, @Unmodifiable Map<String, String> expected,
                             @Unmodifiable List<String> added, @Unmodifiable List<String> changed,
                             @Unmodifiable List<String> removed) {
    /// Fixed manifest entry; quick packages cannot be mistaken for complete pack archives.
    public static final String NAME = "sao-update.json";
    /// Metadata limit allows complete inventories without buffering arbitrary ZIP payloads.
    private static final int MAX_METADATA = 32 * 1024 * 1024;
    /// Required instruction after a missing chain or modified managed base is detected.
    static final String FULL_REQUIRED = "当前文件不适合快速更新，请下载完整模组包。";
    /// Known runtime settings whose extension alone would also describe authored pack data or layout assets.
    private static final @Unmodifiable Set<String> RUNTIME_SETTINGS = Set.of(
            "config/dangomusic/config.json", "config/dangomusic/update_check.json",
            "config/embeddium-fingerprint.json", "config/embeddium-options.json", "config/imblocker.json",
            "config/artifacts/client.json5", "config/artifacts/common.json5", "config/bettercombat/client.json5",
            "config/bettercombat/server.json5", "config/bc_particle/config.json5", "config/bosses_of_mass_destruction.json5",
            "config/chat_heads.json5", "config/graveyard/the-graveyard-3.0-config.json5",
            "config/hundredyearswar/hyw_client.json5", "config/hundredyearswar/hyw_main.json5",
            "config/hundredyearswar/hyw_global_modifier.json5", "config/jea.json5", "config/yacl.json5",
            "config/badoptimizations.txt", "config/dangomusic/netease_cookie.txt", "config/fancymenu/options.txt",
            "config/fancymenu/custom_gui_screens.txt", "config/fancymenu/customizablemenus.txt", "config/fancymenu/legacy_checklist.txt",
            "config/aether/aether_customizations.txt", "config/ftbxmodcompat.snbt", "config/ftbqoptimizer.snbt",
            "tacz/.export-state.json", "tacz/tacz-pre.toml");

    /// Defensively copies inventories and validates their exact relationship before accepting any update.
    public SaoQuickUpdate {
        base = Map.copyOf(base); expected = Map.copyOf(expected);
        added = List.copyOf(added); changed = List.copyOf(changed); removed = List.copyOf(removed);
        try {
            SaoUpdates.version(from); SaoUpdates.version(to);
            if (SaoUpdates.compare(from, to) >= 0 || !before.version().equals(from) || !after.version().equals(to))
                throw new IllegalArgumentException("Invalid quick-update versions");
            @Unmodifiable Map<String, SaoPackManifest.PackFile> oldFiles = inventory(before);
            @Unmodifiable Map<String, SaoPackManifest.PackFile> newFiles = inventory(after);
            validateHashes(base, oldFiles.keySet()); validateHashes(expected, newFiles.keySet());
            Set<String> actualAdded = new TreeSet<>(); Set<String> actualChanged = new TreeSet<>(); Set<String> actualRemoved = new TreeSet<>();
            for (var file : newFiles.values()) {
                @Nullable SaoPackManifest.PackFile old = oldFiles.get(file.path());
                if (old == null) actualAdded.add(file.path());
                else if (!old.sha1().equals(file.sha1()) || old.size() != file.size()
                        || !base.get(file.path()).equals(expected.get(file.path()))) actualChanged.add(file.path());
            }
            for (String path : oldFiles.keySet()) if (!newFiles.containsKey(path)) actualRemoved.add(path);
            if (added.size() != actualAdded.size() || !actualAdded.equals(new TreeSet<>(added))
                    || changed.size() != actualChanged.size() || !actualChanged.equals(new TreeSet<>(changed))
                    || removed.size() != actualRemoved.size() || !actualRemoved.equals(new TreeSet<>(removed)))
                throw new IllegalArgumentException("Quick-update inventory difference does not match manifests");
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid quick-update version", failure); }
    }

    /// Requires one SHA-256 for every managed path, with no extras or missing hashes.
    private static void validateHashes(Map<String, String> hashes, Set<String> paths) {
        if (!hashes.keySet().equals(paths) || hashes.values().stream().anyMatch(hash -> !hash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Incomplete or invalid managed SHA-256 inventory");
    }

    /// Builds a lookup from an already path-validated full manifest.
    static @Unmodifiable Map<String, SaoPackManifest.PackFile> inventory(SaoPackManifest manifest) {
        Map<String, SaoPackManifest.PackFile> files = new TreeMap<>();
        for (var file : manifest.files()) files.put(file.path(), file);
        return Collections.unmodifiableMap(files);
    }

    /// Opens only bounded metadata, rejecting duplicate keys, foreign fields and duplicate ZIP members.
    public static SaoQuickUpdate open(Path zip) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) if (!names.add(entries.nextElement().getName())) throw new IOException("Duplicate quick-update ZIP member");
            @Nullable ZipEntry entry = archive.getEntry(NAME);
            if (entry == null || entry.getSize() < 0 || entry.getSize() > MAX_METADATA) throw new IOException("Missing or oversized quick-update manifest");
            try (InputStream input = archive.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(MAX_METADATA + 1);
                if (bytes.length > MAX_METADATA) throw new IOException("Oversized quick-update manifest");
                return parse(new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    /// Parses the strict manifest wrapper and validates all inventories without extracting entries.
    static SaoQuickUpdate parse(String document) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(document))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = readValue(reader, 0).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.keySet().equals(Set.of("schema", "from", "to", "before", "after", "base", "expected", "added", "changed", "removed"))
                    || !root.get("schema").isJsonPrimitive() || !root.getAsJsonPrimitive("schema").isNumber()
                    || !root.get("schema").toString().equals("2")) throw new IOException("Invalid quick-update schema");
            return new SaoQuickUpdate(text(root.get("from")), text(root.get("to")),
                    SaoPackManifest.read(new StringReader(root.get("before").toString())),
                    SaoPackManifest.read(new StringReader(root.get("after").toString())),
                    hashMap(root.getAsJsonObject("base")), hashMap(root.getAsJsonObject("expected")),
                    paths(root.getAsJsonArray("added")), paths(root.getAsJsonArray("changed")), paths(root.getAsJsonArray("removed")));
        } catch (RuntimeException failure) { throw new IOException("Invalid quick-update manifest", failure); }
    }

    /// Reads JSON recursively with duplicate detection before a tree could hide duplicate properties.
    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 12) throw new IOException("Quick-update metadata nesting is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject result = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw new IOException("Duplicate quick-update field");
                    result.add(name, readValue(reader, depth + 1));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray result = new JsonArray();
                while (reader.hasNext()) {
                    if (result.size() >= 100000) throw new IOException("Quick-update inventory too large");
                    result.add(readValue(reader, depth + 1));
                }
                reader.endArray(); yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (!number.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Expected nonnegative integer");
                yield new JsonPrimitive(Long.parseLong(number));
            }
            default -> throw new IOException("Unexpected quick-update primitive type");
        };
    }

    /// Requires a real JSON string without coercing numeric fields.
    private static String text(JsonElement value) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected string");
        return value.getAsString();
    }

    /// Decodes exact path-to-SHA-256 maps; constructor validates paths and inventory coverage.
    private static @Unmodifiable Map<String, String> hashMap(JsonObject object) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (var member : object.entrySet()) result.put(member.getKey(), text(member.getValue()));
        return Map.copyOf(result);
    }

    /// Decodes path arrays without primitive coercion.
    private static @Unmodifiable List<String> paths(JsonArray array) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) result.add(text(element));
        return List.copyOf(result);
    }

    /// Serializes the public manifest and all hash inventories; contains no machine-specific source paths.
    public String toJson() {
        JsonObject root = new JsonObject(); root.addProperty("schema", 2); root.addProperty("from", from); root.addProperty("to", to);
        root.add("before", JsonParser.parseString(before.toJson())); root.add("after", JsonParser.parseString(after.toJson()));
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        root.add("base", gson.toJsonTree(new TreeMap<>(base))); root.add("expected", gson.toJsonTree(new TreeMap<>(expected)));
        root.add("added", gson.toJsonTree(added)); root.add("changed", gson.toJsonTree(changed)); root.add("removed", gson.toJsonTree(removed));
        return gson.toJson(root);
    }

    /// Identifies settings rewritten by normal play, without exempting authored scripts, assets or arbitrary JSON data.
    /// The exact JSON/state exceptions cover the night1 audit; standard setting formats are restricted to config roots.
    /// JARs are always strict, including paths containing a player-file keyword such as "keybind".
    static boolean isGameWrittenSetting(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) return false;
        if (RUNTIME_SETTINGS.contains(lower)) return true;
        boolean configRoot = lower.startsWith("config/") || lower.startsWith("defaultconfigs/") || lower.startsWith("kubejs/config/");
        if (!configRoot) return false;
        if (lower.startsWith("defaultconfigs/") && lower.endsWith(".snbt")) return true;
        return lower.endsWith(".toml") || lower.endsWith(".ini") || lower.endsWith(".properties")
                || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    /// Confirms receipt identity and all strict managed bytes; mutable settings may differ or be absent.
    /// Existing mutable destinations must still be regular files and every path retains symbolic-link protection.
    static void verifyBase(Path instance, SaoPackManifest installed, SaoQuickUpdate first) throws IOException {
        if (SaoUpdates.compare(installed.version(), first.from()) != 0 || !sameInventory(installed, first.before())) throw new IOException(FULL_REQUIRED);
        for (var file : first.before().files()) {
            Path path = SaoPackFiles.resolve(instance, file.path());
            if (SaoPackManifest.isPlayerFile(file.path())) continue;
            if (isGameWrittenSetting(file.path())) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(FULL_REQUIRED);
                continue;
            }
            if (!SaoPackFiles.matches(path, file) || !SaoUpdates.sha256(path).equals(first.base().get(file.path()))) throw new IOException(FULL_REQUIRED);
        }
        for (String path : first.added()) verifyAddedDestination(instance, path);
    }

    /// Allows newly managed runtime settings already generated locally; authored-file collisions remain a full-pack gate.
    /// Existing runtime destinations must be regular files and remain covered by the instance's path safety checks.
    private static void verifyAddedDestination(Path instance, String path) throws IOException {
        Path destination = SaoPackFiles.resolve(instance, path);
        if (SaoPackManifest.isPlayerFile(path) || !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
        if (!isGameWrittenSetting(path) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException(FULL_REQUIRED);
    }

    /// Compares path/hash/size identities independently of archive entry names and download mirror order.
    private static boolean sameInventory(SaoPackManifest left, SaoPackManifest right) {
        @Unmodifiable Map<String, SaoPackManifest.PackFile> other = inventory(right);
        if (left.files().size() != other.size()) return false;
        for (var file : left.files()) {
            @Nullable SaoPackManifest.PackFile match = other.get(file.path());
            if (match == null || match.size() != file.size() || !match.sha1().equals(file.sha1())) return false;
        }
        return true;
    }

    /// Stages every hop and validates its full expected inventory before producing one final complete installer source.
    /// Caller must hold the instance update lock and delete the returned temporary archive after installation.
    static SaoPackArchive compose(Path instance, SaoPackManifest installed, List<Path> archives, @Nullable SaoPackArchive pristine, Path work) throws IOException {
        if (archives.isEmpty()) throw new IOException(FULL_REQUIRED);
        List<SaoQuickUpdate> updates = new ArrayList<>();
        for (Path archive : archives) updates.add(open(archive));
        verifyBase(instance, installed, updates.get(0));
        Path tree = work.resolve("inventory"); Files.createDirectories(tree);
        // Runtime settings and player data may differ or be missing. Only canonical seeds enter the verified chain.
        for (var file : installed.files()) {
            Path destination = SaoPackFiles.resolve(tree, file.path());
            Files.createDirectories(destination.getParent());
            // Matching live seeds also cover external shader archives absent from the full ZIP; verifyTree checks SHA-256.
            if ((SaoPackManifest.isPlayerFile(file.path()) || isGameWrittenSetting(file.path()))
                    && !SaoPackFiles.matches(SaoPackFiles.resolve(instance, file.path()), file)) {
                if (pristine == null || !sameInventory(installed, pristine.manifest())) throw new IOException(FULL_REQUIRED);
                @Nullable SaoPackManifest.PackFile seed = inventory(pristine.manifest()).get(file.path());
                if (seed == null || seed.entry() == null) throw new IOException(FULL_REQUIRED);
                try (ZipFile zip = new ZipFile(pristine.zip().toFile(), StandardCharsets.UTF_8)) {
                    @Nullable ZipEntry entry = zip.getEntry(seed.entry());
                    if (entry == null || entry.getSize() != seed.size()) throw new IOException(FULL_REQUIRED);
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                        copy(input, output, seed.size());
                    }
                }
            } else Files.copy(SaoPackFiles.resolve(instance, file.path()), destination);
        }
        SaoPackManifest previous = installed;
        @Nullable @Unmodifiable Map<String, String> previousExpected = null;
        for (int hop = 0; hop < updates.size(); hop++) {
            SaoQuickUpdate update = updates.get(hop);
            if (!previous.version().equals(update.from()) || !sameInventory(previous, update.before())
                    || previousExpected != null && !previousExpected.equals(update.base())) throw new IOException(FULL_REQUIRED);
            for (String path : update.added()) {
                if (!inventory(installed).containsKey(path)) verifyAddedDestination(instance, path);
            }
            verifyTree(tree, update.before(), update.base());
            try (ZipFile zip = new ZipFile(archives.get(hop).toFile(), StandardCharsets.UTF_8)) {
                Set<String> replacements = new TreeSet<>(update.added()); replacements.addAll(update.changed());
                for (String path : replacements) {
                    Path target = SaoPackFiles.resolve(tree, path);
                    SaoPackManifest.PackFile file = inventory(update.after()).get(path);
                    @Nullable ZipEntry entry = zip.getEntry("overrides/" + path);
                    if (entry == null || entry.isDirectory() || entry.getSize() != file.size()) throw new IOException("Quick-update payload missing: " + path);
                    Files.createDirectories(target.getParent());
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target)) { copy(input, output, file.size()); }
                }
                for (String path : update.removed()) Files.deleteIfExists(SaoPackFiles.resolve(tree, path));
            }
            verifyTree(tree, update.after(), update.expected()); previous = update.after(); previousExpected = update.expected();
        }
        // Full cached sources use canonical entry names and include all managed files, so restart and repair need no old ZIP.
        List<SaoPackManifest.PackFile> files = new ArrayList<>();
        for (var file : previous.files()) {
            files.add(new SaoPackManifest.PackFile(file.path(), file.sha1(), file.size(), "overrides/" + file.path(), List.of()));
        }
        SaoPackManifest finalManifest = relabel(previous, previous.version(), files);
        Path complete = work.resolve("complete.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(complete))) {
            zip.putNextEntry(new ZipEntry(SaoPackManifest.NAME)); zip.write(finalManifest.toJson().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (var file : files) {
                Path payload = SaoPackFiles.resolve(tree, file.path());
                if (!SaoPackFiles.matches(payload, file)) throw new IOException("Incomplete composed source: " + file.path());
                zip.putNextEntry(new ZipEntry("overrides/" + file.path())); Files.copy(payload, zip); zip.closeEntry();
            }
        }
        return new SaoPackArchive(complete, finalManifest);
    }

    /// Validates every staged managed byte, including pristine player seeds retained only for future repair.
    private static void verifyTree(Path tree, SaoPackManifest manifest, Map<String, String> hashes) throws IOException {
        for (var file : manifest.files()) {
            Path path = SaoPackFiles.resolve(tree, file.path());
            if (!SaoPackFiles.matches(path, file) || !SaoUpdates.sha256(path).equals(hashes.get(file.path()))) throw new IOException("Quick-update expected hash mismatch: " + file.path());
        }
    }

    /// Streams exactly one declared entry, refusing both truncated data and expansion beyond its inventory size.
    static void copy(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[65536]; long count = 0; int read;
        while ((read = input.read(buffer)) != -1) {
            count += read; if (count > size) throw new IOException("Quick-update entry exceeds declared size"); output.write(buffer, 0, read);
        }
        if (count != size) throw new IOException("Quick-update entry is truncated");
    }

    /// Reuses compatible runtime metadata while explicitly assigning a builder override or canonical payload inventory.
    static SaoPackManifest relabel(SaoPackManifest source, String version, List<SaoPackManifest.PackFile> files) {
        return new SaoPackManifest(version, source.minecraft(), source.neoforge(), source.server(), source.newsUrl(), source.accountApi(),
                source.serverNodes(), files, source.removals());
    }
}
