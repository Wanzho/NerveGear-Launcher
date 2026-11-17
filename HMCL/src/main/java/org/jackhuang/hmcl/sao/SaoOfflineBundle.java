/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.*;

/// Portable verified dependency source. Bundle checksums detect damage, never authorize executable content.
/// Runtime files are accepted only against the launcher's pinned official/reproducible dependency catalog;
/// pack files still require the signed release policy and mods still require the accepted pack's inventory.
@NotNullByDefault
public final class SaoOfflineBundle {
    /// Index name used for content-based detection, independent of the downloaded filename.
    static final String INDEX = "bundle-index.json";
    /// Maximum expanded size; bounds untrusted ZIP metadata before extraction starts.
    private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    /// Trusted metadata shipped with the launcher, not loaded from the bundle.
    private static final String RESOURCE = "/assets/sao-launcher/offline/";

    /// Utility class.
    private SaoOfflineBundle() { }

    /// A transport identity and its provenance; source describes where the independent expected digest came from.
    @NotNullByDefault
    record Entry(String path, long size, String sha1, String sha256, String source) { }

    /// Imported tree and damaged entries. The pack remains untrusted until the existing signed-policy check.
    @NotNullByDefault
    record Imported(Path directory, @Nullable Path pack, @Unmodifiable List<String> damaged) {
        /// Freezes the damage report before returning to the engine.
        Imported { damaged = List.copyOf(damaged); }
    }

    /// Returns the owned extraction parent; neither system temporary directories nor the source ZIP are changed.
    static Path directory(Path root) { return root.resolve("dependencies/offline-bundle"); }

    /// Lists only completed publications, newest first, so failed partial imports cannot become local sources.
    static @Unmodifiable List<Path> sources(Path root) {
        Path parent = directory(root);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var children = Files.list(parent)) {
            return children.filter(p -> p.getFileName().toString().startsWith("bundle-"))
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(p.resolve(INDEX), LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.reverseOrder()).toList();
        } catch (IOException unreadable) { return List.of(); }
    }

    /// Detects an offline archive without loading its potentially large index or payload into memory.
    static boolean recognizes(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) { return zip.getEntry(INDEX) != null; }
        catch (ZipException invalid) { return false; }
    }

    /// Rejects traversal, Windows aliases, reserved names and platform-dependent separators.
    static Path safe(Path root, String name) throws IOException {
        if (name.isEmpty() || name.length() > 500 || name.contains("\\") || name.startsWith("/")) throw new IOException("Unsafe bundle path: " + name);
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.matches(".*[<>:\"|?*\\p{Cntrl}].*")
                    || part.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
                throw new IOException("Unsafe bundle path: " + name);
        }
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root.normalize())) throw new IOException("Bundle path escapes extraction root");
        return path;
    }

    /// Parses a bounded UTF-8 object; malformed data is reported as an archive error.
    private static JsonObject object(InputStream in) throws IOException {
        byte @Unmodifiable [] data = in.readNBytes(16 * 1024 * 1024 + 1);
        if (data.length > 16 * 1024 * 1024) throw new IOException("Bundle metadata is too large");
        try { return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IOException("Invalid bundle metadata", invalid); }
    }

    /// Validates every index member before creating any payload file, including case-insensitive duplicates.
    private static @Unmodifiable List<Entry> entries(JsonObject index, Path root) throws IOException {
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        try {
            for (JsonElement item : index.getAsJsonArray("files")) {
                JsonObject e = item.getAsJsonObject();
                Entry entry = new Entry(e.get("path").getAsString(), e.get("size").getAsLong(), e.get("sha1").getAsString(),
                        e.get("sha256").getAsString(), e.get("source").getAsString());
                safe(root, entry.path());
                if (!entry.path().matches("(assets|libraries|versions|java|installer|mods-cache|pack|policy)/.+")
                        || entry.path().equalsIgnoreCase(INDEX) || !seen.add(entry.path().toLowerCase(Locale.ROOT))
                        || entry.size() < 0 || entry.size() > MAX_BYTES || !entry.sha1().matches("[0-9a-f]{40}")
                        || !entry.sha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid bundle entry: " + entry.path());
                total = Math.addExact(total, entry.size());
                if (total > MAX_BYTES || result.size() >= 20000) throw new IOException("Offline bundle exceeds limits");
                result.add(entry);
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid offline bundle index", invalid); }
        for (String name : seen) {
            int slash = name.indexOf('/');
            while (slash >= 0) {
                if (seen.contains(name.substring(0, slash))) throw new IOException("Conflicting bundle paths: " + name);
                slash = name.indexOf('/', slash + 1);
            }
        }
        return List.copyOf(result);
    }

    /// Extracts into a fresh owned directory, skips and names bad entries, then publishes the local source atomically.
    static Imported unpack(Path archive, Path root, BiConsumer<String, Double> progress) throws IOException {
        Path parent = directory(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Linked launcher data root");
        SaoUninstaller.rejectLinksBelow(root, parent);
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, ".import-");
        boolean published = false;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            @Nullable ZipEntry indexEntry = zip.getEntry(INDEX);
            if (indexEntry == null) throw new IOException("Missing " + INDEX);
            JsonObject index;
            try (InputStream in = zip.getInputStream(indexEntry)) { index = object(in); }
            if (!index.has("format") || index.get("format").getAsInt() != 1) throw new IOException("Unsupported offline bundle format");
            @Unmodifiable List<Entry> files = entries(index, stage);
            String packName = index.get("pack").getAsString();
            safe(stage, packName);
            if (!packName.startsWith("pack/") || files.stream().noneMatch(e -> e.path().equals(packName))) throw new IOException("Missing bundled pack");
            Set<String> zipNames = new HashSet<>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!zipNames.add(entry.getName().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate ZIP entry: " + entry.getName());
            }
            List<String> damaged = new ArrayList<>();
            int done = 0;
            for (Entry entry : files) {
                Path destination = safe(stage, entry.path());
                @Nullable ZipEntry zipped = zip.getEntry(entry.path());
                try {
                    if (zipped == null || zipped.isDirectory() || zipped.getSize() != entry.size()) throw new IOException("Missing or wrong size");
                    Files.createDirectories(destination.getParent());
                    try (InputStream in = zip.getInputStream(zipped); OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                        byte[] bytes = new byte[128 * 1024];
                        long received = 0;
                        for (int n; (n = in.read(bytes)) >= 0; ) {
                            received += n;
                            if (received > entry.size()) throw new IOException("Entry exceeds indexed size");
                            out.write(bytes, 0, n);
                        }
                    }
                    if (!matches(destination, entry)) throw new IOException("Digest mismatch");
                } catch (IOException damagedEntry) {
                    Files.deleteIfExists(destination);
                    damaged.add(entry.path());
                }
                done++;
                progress.accept("正在导入离线包 " + done + "/" + files.size(), done / (double) Math.max(1, files.size()));
                if (Thread.currentThread().isInterrupted()) throw new IOException("Offline import cancelled");
            }
            Files.writeString(stage.resolve(INDEX), index.toString());
            Path destination = parent.resolve("bundle-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            Path pack = destination.resolve(packName);
            return new Imported(destination, Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS) ? pack : null, damaged);
        } catch (RuntimeException invalid) { throw new IOException("Invalid offline bundle", invalid); }
        finally { if (!published) removeTree(stage); }
    }

    /// Matches a transport or trusted catalog entry without following file links.
    static boolean matches(Path file, Entry entry) throws IOException {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == entry.size()
                && DigestUtils.digestToString("SHA-1", file).equals(entry.sha1())
                && DigestUtils.digestToString("SHA-256", file).equals(entry.sha256());
    }

    /// Reads pinned metadata from the application, never from a drop or extracted archive.
    private static JsonObject resource(String name) throws IOException {
        try (@Nullable InputStream in = SaoOfflineBundle.class.getResourceAsStream(RESOURCE + name)) {
            if (in == null) throw new IOException("Launcher is missing offline metadata: " + name);
            return object(in);
        }
    }

    /// The catalog covers metadata, libraries, installer outputs and the Windows x64 Java tree.
    static @Unmodifiable List<Entry> trusted() throws IOException { return entries(resource("trusted-files.json"), Path.of("catalog")); }

    /// Copies only independently pinned runtime dependencies; arbitrary bundle manifests are never installed.
    static void prepare(Path root) throws IOException {
        prepare(root, (label, fraction) -> { });
    }

    /// Reports local verification separately from network downloads, including before the first disk scan.
    static void prepare(Path root, java.util.function.BiConsumer<String, Double> progress) throws IOException {
        progress.accept("检查离线依赖", -1.0);
        @Unmodifiable List<Path> sources = sources(root);
        if (sources.isEmpty()) return;
        @Unmodifiable List<Entry> entries = trusted();
        int checked = 0;
        for (Entry entry : entries) {
            progress.accept("检查离线依赖 " + (++checked) + "/" + entries.size(), -1.0);
            if (entry.path().startsWith("java/") && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) continue;
            Path target = entry.path().startsWith("java/") ? safe(root, entry.path())
                    : entry.path().startsWith("installer/") ? safe(root.resolve("dependencies/neoforge"), entry.path().substring(10))
                    : safe(root.resolve("minecraft"), entry.path());
            // Never replace an existing game definition or a player's installed manifest.
            if (entry.path().equals("versions/sao-oath/sao-oath.json") && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            if (matches(target, entry)) continue;
            for (Path source : sources) {
                Path file = safe(source, entry.path());
                if (!matches(file, entry)) continue;
                SaoUninstaller.rejectLinksBelow(root, target);
                Files.createDirectories(target.getParent());
                Path staged = Files.createTempFile(target.getParent(), ".offline-", ".part");
                try {
                    Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING);
                    if (!matches(staged, entry)) throw new IOException("Local dependency changed during copy: " + entry.path());
                    SaoPackFiles.atomicMove(staged, target);
                } finally { Files.deleteIfExists(staged); }
                break;
            }
        }
    }

    /// Returns the copied installer only when it still matches the launcher's pinned NeoForge identity.
    static @Nullable Path installer(Path root) throws IOException {
        if (sources(root).isEmpty()) return null;
        for (Entry entry : trusted()) if (entry.path().startsWith("installer/")) {
            Path file = safe(root.resolve("dependencies/neoforge"), entry.path().substring(10));
            if (matches(file, entry)) return file;
        }
        return null;
    }

    /// Borrows a mod, resource pack or shader only when its accepted pack inventory matches size and SHA-1.
    static boolean copyMod(Path root, SaoPackManifest.PackFile file, Path destination) throws IOException {
        for (Path source : sources(root)) {
            Path local = source.resolve("mods-cache").resolve(file.sha1());
            if (!SaoPackFiles.matches(local, file)) continue;
            Files.copy(local, destination, StandardCopyOption.REPLACE_EXISTING);
            if (!SaoPackFiles.matches(destination, file)) { Files.deleteIfExists(destination); continue; }
            return true;
        }
        return false;
    }

    /// Removes an extracted copy only after a caller has confirmed installation succeeded; no links are followed.
    static void removeTree(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(tree)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    /// Builds from an already complete installation; missing or mismatched mandatory dependencies fail before publication.
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || !args[0].equals("--make-offline-bundle"))
            throw new IOException("--make-offline-bundle <output.zip> --pack <pack.zip> [--root <launcher-data>] [--policy <signed-version.json>]");
        Map<String, String> options = new HashMap<>();
        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 >= args.length || !Set.of("--pack", "--root", "--policy").contains(args[i])) throw new IOException("Invalid offline builder argument");
            options.put(args[i], args[i + 1]);
        }
        @Nullable String packOption = options.get("--pack");
        if (packOption == null) throw new IOException("Missing --pack");
        Path root = Path.of(options.getOrDefault("--root", ".sao-oath-launcher")).toAbsolutePath().normalize();
        build(root, Path.of(packOption), Path.of(args[1]), Path.of(options.getOrDefault("--policy", root.resolve("offline-policy/version.json").toString())));
    }

    /// Streams a reproducible inventory and its files into a sibling staging ZIP, then atomically publishes it.
    static void build(Path root, Path pack, Path output, Path policy) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        Map<String, String> provenance = new HashMap<>();
        Path signature = policy.resolveSibling(policy.getFileName() + ".sig");
        byte @Unmodifiable [] document = Files.readAllBytes(policy), signed = Files.readAllBytes(signature);
        SaoReleaseSignatures.PRODUCTION.verify(document, signed);
        SaoData.VersionMetadata release = SaoUpdates.parse(SaoReleaseSignatures.utf8(document));
        if (Files.size(pack) != release.pack().size() || !SaoUpdates.sha256(pack).equals(release.pack().sha256()))
            throw new IOException("Pack does not match signed release policy");
        files.put("policy/version.json", policy); files.put("policy/version.json.sig", signature);
        provenance.put("policy/version.json", "Ed25519 signed release version.json");
        provenance.put("policy/version.json.sig", "Release Ed25519 detached signature");
        for (Entry entry : trusted()) {
            Path source = entry.path().startsWith("java/") ? safe(root, entry.path())
                    : entry.path().startsWith("installer/") ? safe(root.resolve("dependencies/neoforge"), entry.path().substring(10))
                    : safe(root.resolve("minecraft"), entry.path());
            if (!matches(source, entry)) {
                if (entry.path().startsWith("java/")) throw new IOException("Windows runtime missing or damaged: " + source);
                throw new IOException("Incomplete source install: " + source);
            }
            files.put(entry.path(), source); provenance.put(entry.path(), entry.source());
        }
        JsonObject minecraft = resource("minecraft-1.20.1.json");
        JsonObject asset = minecraft.getAsJsonObject("assetIndex");
        String indexPath = "assets/indexes/" + asset.get("id").getAsString() + ".json";
        Path assetFile = safe(root.resolve("minecraft"), indexPath);
        if (!DigestUtils.digestToString("SHA-1", assetFile).equals(asset.get("sha1").getAsString())) throw new IOException("Asset index mismatch");
        files.put(indexPath, assetFile); provenance.put(indexPath, "Mojang 1.20.1 assetIndex.sha1");
        JsonObject objects;
        try (InputStream in = Files.newInputStream(assetFile)) { objects = object(in).getAsJsonObject("objects"); }
        for (var item : objects.entrySet()) {
            JsonObject value = item.getValue().getAsJsonObject();
            String hash = value.get("hash").getAsString();
            if (!hash.matches("[0-9a-f]{40}")) throw new IOException("Invalid asset hash");
            String name = "assets/objects/" + hash.substring(0, 2) + "/" + hash;
            Path source = safe(root.resolve("minecraft"), name);
            if (!Files.isRegularFile(source) || Files.size(source) != value.get("size").getAsLong()
                    || !DigestUtils.digestToString("SHA-1", source).equals(hash)) throw new IOException("Missing asset: " + name);
            files.put(name, source); provenance.put(name, indexPath + " objects." + item.getKey());
        }
        SaoPackArchive archive = SaoPackArchive.open(pack);
        for (SaoPackManifest.PackFile file : archive.manifest().files()) {
            if (file.entry() != null) continue;
            Path source = safe(root.resolve("minecraft/versions/sao-oath"), file.path());
            if (!SaoPackFiles.matches(source, file)) throw new IOException("Missing indexed pack file: " + file.path());
            String name = "mods-cache/" + file.sha1();
            files.put(name, source); provenance.put(name, "pack/modrinth.index.json files." + file.path() + " hashes.sha1");
        }
        String packName = "pack/" + pack.getFileName();
        safe(root, packName); files.put(packName, pack); provenance.put(packName, "signed version.json pack.sha256 (verified again on import)");
        JsonObject index = new JsonObject();
        index.addProperty("format", 1); index.addProperty("pack", packName);
        JsonArray inventory = new JsonArray();
        for (var file : files.entrySet()) {
            JsonObject e = new JsonObject();
            e.addProperty("path", file.getKey()); e.addProperty("size", Files.size(file.getValue()));
            e.addProperty("sha1", DigestUtils.digestToString("SHA-1", file.getValue()));
            e.addProperty("sha256", DigestUtils.digestToString("SHA-256", file.getValue()));
            e.addProperty("source", provenance.get(file.getKey())); inventory.add(e);
        }
        index.add("files", inventory);
        Path target = output.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".offline-bundle-", ".zip.part");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                zip.setLevel(1);
                zip.putNextEntry(new ZipEntry(INDEX)); zip.write(index.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                int position = 0;
                for (var file : files.entrySet()) {
                    JsonObject expected = inventory.get(position++).getAsJsonObject();
                    java.security.MessageDigest digest;
                    try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
                    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    long copied;
                    try (InputStream in = new java.security.DigestInputStream(Files.newInputStream(file.getValue()), digest)) {
                        copied = in.transferTo(zip);
                    }
                    zip.closeEntry();
                    if (copied != expected.get("size").getAsLong() || !HexFormat.of().formatHex(digest.digest()).equals(expected.get("sha256").getAsString()))
                        throw new IOException("Source changed while building bundle: " + file.getKey());
                }
            }
            SaoPackFiles.atomicMove(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println(target + "\nbytes=" + Files.size(target) + "\nsha256=" + DigestUtils.digestToString("SHA-256", target));
    }
}
