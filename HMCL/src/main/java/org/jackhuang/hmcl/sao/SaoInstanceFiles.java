/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.ui.instances.WorldBackupTask;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/// World, pack and screenshot operations scoped to the fixed instance, without opening a game or UI.
@NotNullByDefault
final class SaoInstanceFiles {
    /// Fixed game directory; all record paths are absolute children of this directory.
    private final Path instance;
    /// HMCL task execution includes world locking and download-window instrumentation.
    private final SaoGameBridge bridge;
    /// Filesystem rename operation, replaceable only by deterministic rollback-failure fixtures.
    private final MoveOperation mover;
    /// Required packs seeded by the distribution's options, retained independently of player toggles.
    private final Set<String> requiredResources = new LinkedHashSet<>();

    /// Uses the same fixed directory as the launch bridge.
    SaoInstanceFiles(Path instance, SaoGameBridge bridge) { this(instance, bridge, (source, destination) -> Files.move(source, destination)); }

    /// Supplies a move fault boundary for testing transactional restore without touching another installation.
    SaoInstanceFiles(Path instance, SaoGameBridge bridge, MoveOperation mover) {
        this.instance = instance; this.bridge = bridge; this.mover = mover;
    }

    /// One filesystem rename used by the restore transaction.
    @NotNullByDefault
    @FunctionalInterface
    interface MoveOperation {
        /// Moves an existing path without replacing an unexpected destination.
        void move(Path source, Path destination) throws IOException;
    }

    /// Resolves a known folder without consulting HMCL profiles or another instance.
    Path folder(SaoData.Folder folder) throws IOException {
        String relative = switch (folder) {
            case GAME -> ""; case SAVES -> "saves"; case BACKUPS -> "backups";
            case SHADERS -> "shaderpacks"; case RESOURCES -> "resourcepacks";
            case SCREENSHOTS -> "screenshots"; case LOGS -> "logs";
        };
        if (relative.isEmpty()) {
            if (Files.isSymbolicLink(instance)) throw new IOException("Fixed instance is a symbolic link");
            return instance;
        }
        return SaoPackFiles.resolve(instance, relative);
    }

    /// Finds saves with HMCL's NBT parser and returns immutable records ordered by last-played time.
    @Unmodifiable List<SaoData.World> worlds() throws IOException {
        Path saves = folder(SaoData.Folder.SAVES);
        List<SaoData.World> result = new ArrayList<>();
        for (Path directory : children(saves)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || directory.getFileName().toString().startsWith(".sao-restore-")) continue;
            checkedChild(saves, directory);
            World world;
            try { world = new World(directory); }
            catch (IOException invalid) { continue; }
            Path icon = world.getFile().resolve("icon.png");
            int mode = world.getLevelData().get("Data") instanceof org.glavo.nbt.tag.CompoundTag data
                    && data.get("GameType") instanceof org.glavo.nbt.tag.IntTag tag ? tag.get() : 0;
            String modeName = switch (mode) { case 1 -> "创造"; case 2 -> "冒险"; case 3 -> "旁观"; default -> "生存"; };
            result.add(new SaoData.World(world.getWorldName(), world.getFile(), world.getLastPlayed(), size(world.getFile()),
                    Files.isRegularFile(icon, LinkOption.NOFOLLOW_LINKS) ? icon : null, modeName));
        }
        result.sort(Comparator.comparingLong(SaoData.World::lastPlayed).reversed());
        return List.copyOf(result);
    }

    /// Lists backups by their archive root, so original save folders remain associated after a restart.
    @Unmodifiable List<SaoData.Backup> backups() throws IOException {
        List<SaoData.Backup> result = new ArrayList<>();
        for (Path zip : children(folder(SaoData.Folder.BACKUPS))) {
            if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) || !zip.getFileName().toString().endsWith(".zip")) continue;
            try (ZipFile archive = new ZipFile(zip.toFile())) {
                String worldName = backupRoot(archive);
                Path world = folder(SaoData.Folder.SAVES).resolve(worldName);
                result.add(new SaoData.Backup(zip, world, modified(zip), Files.size(zip)));
            } catch (IOException ignored) { /* An unrelated or incomplete archive is not a restorable backup. */ }
        }
        result.sort(Comparator.comparingLong(SaoData.Backup::time).reversed());
        return List.copyOf(result);
    }

    /// Uses HMCL's locked WorldBackupTask and its standard archive layout.
    Path backup(SaoData.World selected) throws Exception {
        Path directory = checkedChild(folder(SaoData.Folder.SAVES), selected.folder());
        rejectLinks(directory);
        World world = new World(directory);
        if (!Boolean.getBoolean("java.awt.headless"))
            return Objects.requireNonNull(bridge.runTask(new WorldBackupTask(world, folder(SaoData.Folder.BACKUPS), true)), "World backup task returned no file");
        // WorldBackupTask references a JavaFX Control's static formatter. Keep headless tools toolkit-free.
        Path backups = folder(SaoData.Folder.BACKUPS);
        Files.createDirectories(backups);
        String prefix = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path destination = backups.resolve(prefix + "_" + world.getFileName() + "_" + UUID.randomUUID() + ".zip");
        try (FileChannel lock = world.lock()) { world.export(destination, world.getFileName()); }
        try (FileSystem archive = FileSystems.newFileSystem(destination)) {
            Files.deleteIfExists(archive.getPath("/" + world.getFileName() + "/session.lock"));
        }
        return destination;
    }

    /// Stages and validates an archived world, backs up current data, then swaps it with rollback on failure.
    void restore(SaoData.Backup selected) throws Exception {
        Path backup = checkedChild(folder(SaoData.Folder.BACKUPS), selected.file());
        Path destination = checkedChild(folder(SaoData.Folder.SAVES), selected.world());
        try (ZipFile archive = new ZipFile(backup.toFile())) {
            if (!backupRoot(archive).equals(destination.getFileName().toString())) throw new IOException("Backup world does not match destination");
        }
        World archived = new World(backup);
        Path saves = folder(SaoData.Folder.SAVES);
        Files.createDirectories(saves);
        Path staging = Files.createTempDirectory(saves, ".sao-restore-");
        Path displaced = staging.resolve("previous");
        Path extraction = Files.createDirectory(staging.resolve("restored"));
        Path staged = extraction.resolve(destination.getFileName());
        boolean movedPrevious = false;
        boolean committed = false;
        boolean restoredPrevious = false;
        try {
            archived.install(extraction, destination.getFileName().toString());
            new World(staged).setWorldName(archived.getWorldName());
            if (Files.exists(destination)) {
                World current = new World(destination);
                backup(new SaoData.World(current.getWorldName(), destination, current.getLastPlayed(), 0, null, ""));
                mover.move(destination, displaced);
                movedPrevious = true;
            }
            try { mover.move(staged, destination); committed = true; }
            catch (IOException failure) {
                if (movedPrevious) {
                    try { mover.move(displaced, destination); restoredPrevious = true; }
                    catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                        throw new IOException("Restore failed; recovery data retained in " + staging, failure);
                    }
                }
                throw failure;
            }
        } finally {
            // A failed rollback retains staging and the old world for recovery.
            if (committed || !movedPrevious || restoredPrevious) FileUtils.deleteDirectory(staging);
        }
    }

    /// Exports through HMCL World while holding its session lock, committing the finished zip last.
    void export(SaoData.World selected, Path destination) throws Exception {
        Path directory = checkedChild(folder(SaoData.Folder.SAVES), selected.folder());
        rejectLinks(directory);
        if (destination.toAbsolutePath().normalize().startsWith(directory)) throw new IOException("Export must be outside the world");
        World world = new World(directory);
        Path temporary = temporaryExport(destination);
        try (FileChannel lock = world.lock()) {
            world.export(temporary, world.getFileName());
            SaoPackFiles.atomicMove(temporary, destination.toAbsolutePath().normalize());
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Remembers distribution-enabled resource IDs, without assuming every bundled pack is required.
    void requiredResources(@Nullable SaoPackArchive source) throws IOException {
        requiredResources.clear();
        if (source == null) return;
        for (SaoPackManifest.PackFile file : source.manifest().files()) {
            if (!file.path().equals("options.txt") || file.entry() == null) continue;
            try (ZipFile zip = new ZipFile(source.zip().toFile())) {
                @Nullable ZipEntry entry = zip.getEntry(file.entry());
                if (entry == null || file.size() > 1048576) return;
                try (InputStream input = zip.getInputStream(entry)) {
                    for (String line : new String(input.readNBytes(1048577), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                        if (line.startsWith("resourcePacks:")) requiredResources.addAll(resourceIds(line.substring("resourcePacks:".length())));
                    }
                }
            }
        }
    }

    /// Lists actual zip/folder packs, ordering enabled resources by the game's options order.
    @Unmodifiable List<SaoData.Pack> packs(boolean shaders) throws IOException {
        Path directory = folder(shaders ? SaoData.Folder.SHADERS : SaoData.Folder.RESOURCES);
        @Unmodifiable List<String> enabled = shaders ? List.of() : enabledResources();
        @Nullable Path shaderConfig = shaderConfig();
        Properties shader = shaderConfig == null ? new Properties() : properties(shaderConfig);
        String activeShader = Boolean.parseBoolean(shader.getProperty("enableShaders", "true")) ? shader.getProperty("shaderPack", "") : "";
        List<SaoData.Pack> result = new ArrayList<>();
        for (Path file : children(directory)) {
            boolean folder = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
            if (!folder && !(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))) continue;
            String filename = file.getFileName().toString();
            String name = filename.endsWith(".zip") ? filename.substring(0, filename.length() - 4) : filename;
            Path icon = file.resolve("pack.png");
            String description = description(file, folder);
            boolean active = shaders ? filename.equals(activeShader) : enabled.contains("file/" + filename);
            result.add(new SaoData.Pack(name, file, description, active,
                    folder && Files.isRegularFile(icon, LinkOption.NOFOLLOW_LINKS) ? icon : null));
        }
        result.sort(Comparator.<SaoData.Pack>comparingInt(pack -> {
            int index = enabled.indexOf("file/" + pack.file().getFileName());
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing(SaoData.Pack::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /// Detects shader mod IDs from installed mod metadata, ignoring leftover configuration files.
    @Nullable Path shaderConfig() throws IOException {
        for (Path mod : children(SaoPackFiles.resolve(instance, "mods"))) {
            if (!Files.isRegularFile(mod, LinkOption.NOFOLLOW_LINKS) || !mod.toString().endsWith(".jar")) continue;
            try (ZipFile zip = new ZipFile(mod.toFile())) {
                for (String metadata : List.of("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "fabric.mod.json")) {
                    @Nullable ZipEntry entry = zip.getEntry(metadata);
                    if (entry == null || entry.getSize() > 1048576) continue;
                    try (InputStream input = zip.getInputStream(entry)) {
                        String text = new String(input.readNBytes(1048577), java.nio.charset.StandardCharsets.UTF_8);
                        @Nullable String id = shaderModId(metadata, text);
                        if (id != null) return SaoPackFiles.resolve(instance, "config/" + id + ".properties");
                    }
                }
            } catch (ZipException ignored) { /* A broken unrelated mod does not fabricate shader support. */ }
        }
        return null;
    }

    /// Reads only a mod's own declaration, never dependency declarations that mention Oculus or Iris.
    static @Nullable String shaderModId(String metadata, String text) {
        if (metadata.equals("fabric.mod.json")) {
            try {
                String id = JsonParser.parseString(text).getAsJsonObject().get("id").getAsString();
                return id.equals("iris") || id.equals("oculus") ? id : null;
            } catch (RuntimeException invalid) { return null; }
        }
        boolean ownMod = false;
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("[")) ownMod = stripped.equals("[[mods]]");
            if (ownMod) {
                var match = java.util.regex.Pattern.compile("modId\\s*=\\s*[\"'](oculus|iris)[\"'](?:\\s*#.*)?").matcher(stripped);
                if (match.matches()) return match.group(1);
            }
        }
        return null;
    }

    /// Changes only the active installed shader mod's two selection keys.
    void setShader(SaoData.@Nullable Pack selected) throws IOException {
        @Nullable Path config = shaderConfig();
        if (config == null) throw new IOException("整合包未安装 Oculus 或 Iris，无法启用光影。光影文件仍可管理。");
        String filename = selected == null ? "" : checkedChild(folder(SaoData.Folder.SHADERS), selected.file()).getFileName().toString();
        if (selected != null && !Files.exists(selected.file())) throw new IOException("Shader pack is missing");
        Properties values = properties(config);
        values.setProperty("shaderPack", filename); values.setProperty("enableShaders", Boolean.toString(selected != null));
        Files.createDirectories(config.getParent());
        Path temporary = Files.createTempFile(config.getParent(), "shader-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) { values.store(writer, "SAO Oath shader selection"); }
            SaoPackFiles.atomicMove(temporary, config);
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Changes one resource ID, preserving every other option, required ID, and existing application order.
    void setResource(SaoData.Pack selected, boolean enabled) throws IOException {
        Path file = checkedChild(folder(SaoData.Folder.RESOURCES), selected.file());
        if (!Files.exists(file)) throw new IOException("Resource pack is missing");
        String id = "file/" + file.getFileName();
        if (!enabled && requiredResources.contains(id)) throw new IOException("整合包必需的资源包不能关闭");
        List<String> active = new ArrayList<>(enabledResources());
        if (enabled && !active.contains(id)) active.add(id);
        if (!enabled) active.removeIf(id::equals);
        for (String required : requiredResources) if (!active.contains(required)) active.add(required);
        writeResources(active);
    }

    /// Re-enables absent distribution requirements immediately before launch, retaining every other selected ID's order.
    void enforceRequiredResources() throws IOException {
        List<String> active = new ArrayList<>(enabledResources());
        boolean changed = false;
        for (String required : requiredResources) if (!active.contains(required)) { active.add(required); changed = true; }
        if (changed) writeResources(active);
    }

    /// Rewrites only the resource selection line and commits options atomically.
    private void writeResources(List<String> active) throws IOException {
        Path options = SaoPackFiles.resolve(instance, "options.txt");
        List<String> lines = Files.exists(options) ? new ArrayList<>(Files.readAllLines(options)) : new ArrayList<>();
        String replacement = "resourcePacks:" + new Gson().toJson(active);
        int index = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("resourcePacks:")) { index = i; break; }
        if (index < 0) lines.add(replacement); else lines.set(index, replacement);
        Files.createDirectories(instance);
        Path temporary = Files.createTempFile(instance, "options-", ".tmp");
        try { Files.write(temporary, lines); SaoPackFiles.atomicMove(temporary, options); }
        finally { Files.deleteIfExists(temporary); }
    }

    /// Copies dropped valid archives without overwriting an existing pack or modifying the source.
    void addPacks(List<Path> sources, boolean shaders) throws IOException {
        Path directory = folder(shaders ? SaoData.Folder.SHADERS : SaoData.Folder.RESOURCES);
        Files.createDirectories(directory);
        for (Path source : sources) {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                throw new IOException("Only zip packs can be added");
            try (ZipFile ignored = new ZipFile(source.toFile())) { /* Check that the drop really is an archive. */ }
            Path destination = checkedChild(directory, directory.resolve(source.getFileName()));
            if (Files.exists(destination) && Files.isSameFile(source, destination)) continue;
            Files.copy(source, destination);
        }
    }

    /// Returns image screenshots in descending modification order and ignores aliases outside this folder.
    @Unmodifiable List<Path> screenshots() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path file : children(folder(SaoData.Folder.SCREENSHOTS))) {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g)$")) files.add(file);
        }
        files.sort(Comparator.comparingLong(SaoInstanceFiles::modified).reversed());
        return List.copyOf(files);
    }

    /// Reads only Minecraft's selected resource list, leaving all unrelated option keys untouched.
    private @Unmodifiable List<String> enabledResources() throws IOException {
        Path options = SaoPackFiles.resolve(instance, "options.txt");
        if (Files.exists(options)) for (String line : Files.readAllLines(options))
            if (line.startsWith("resourcePacks:")) return List.copyOf(resourceIds(line.substring("resourcePacks:".length())));
        return List.of();
    }

    /// Decodes the game's ordered JSON string array and reports malformed options instead of discarding them.
    private static List<String> resourceIds(String json) throws IOException {
        try {
            List<String> result = new ArrayList<>();
            for (JsonElement item : JsonParser.parseString(json).getAsJsonArray()) result.add(item.getAsString());
            return result;
        } catch (RuntimeException failure) { throw new IOException("Invalid resourcePacks in options.txt", failure); }
    }

    /// Extracts a short description when pack metadata is present; shader archives may legitimately have none.
    private static String description(Path pack, boolean directory) {
        try {
            String text;
            if (directory) {
                Path metadata = pack.resolve("pack.mcmeta");
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 65536) return "";
                text = Files.readString(metadata);
            } else try (ZipFile zip = new ZipFile(pack.toFile())) {
                @Nullable ZipEntry entry = zip.getEntry("pack.mcmeta");
                if (entry == null || entry.getSize() > 65536) return "";
                try (InputStream input = zip.getInputStream(entry)) { text = new String(input.readNBytes(65537), java.nio.charset.StandardCharsets.UTF_8); }
            }
            JsonElement value = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("pack").get("description");
            return value.isJsonPrimitive() ? value.getAsString() : value.toString();
        } catch (IOException | RuntimeException ignored) { return ""; }
    }

    /// Loads the active shader properties without treating a missing file as an error.
    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) try (Reader input = Files.newBufferedReader(file)) { properties.load(input); }
        return properties;
    }

    /// Verifies a record names a direct child and no symbolic link exists below the explicit instance root.
    private Path checkedChild(Path parent, Path child) throws IOException {
        Path normalized = child.toAbsolutePath().normalize();
        if (!Objects.equals(normalized.getParent(), parent.toAbsolutePath().normalize())) throw new IOException("Path is outside the fixed instance folder");
        SaoPackFiles.resolve(instance, instance.relativize(normalized).toString().replace('\\', '/'));
        return normalized;
    }

    /// Rejects world symlinks before HMCL's recursive exporter can follow them.
    private static void rejectLinks(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            if (paths.anyMatch(Files::isSymbolicLink)) throw new IOException("World contains a symbolic link");
        }
    }

    /// Validates all archive paths and identifies its unique world root before restore/listing.
    private static String backupRoot(ZipFile archive) throws IOException {
        @Nullable String root = null;
        boolean hasLevel = false;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (clean.isEmpty()) continue;
            validateBackupPath(clean);
            int slash = clean.indexOf('/');
            String part = slash < 0 ? clean : clean.substring(0, slash);
            if (root == null) root = part;
            if (!root.equals(part)) throw new IOException("Backup must contain one world");
            hasLevel |= clean.equals(root + "/level.dat") || clean.equals(root + "/special_level.dat");
        }
        if (root == null || !hasLevel) throw new IOException("Backup has no world level.dat");
        return root;
    }

    /// Rejects traversal/absolute archive names without applying pack-only reserved launcher-file rules to saves.
    private static void validateBackupPath(String path) throws IOException {
        if (path.isEmpty() || path.length() > 1000 || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.chars().anyMatch(character -> character < 32)) throw new IOException("Invalid backup path");
        for (String segment : path.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IOException("Invalid backup path");
    }

    /// Lists direct children without following directory aliases.
    private static @Unmodifiable List<Path> children(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var children = Files.list(directory)) { return children.filter(path -> !Files.isSymbolicLink(path)).toList(); }
    }

    /// Sums regular files without following links.
    private static long size(Path directory) throws IOException {
        long size = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).toList()) size += Files.size(path);
        }
        return size;
    }

    /// Provides a deterministic timestamp fallback for a disappearing file during refresh.
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException ignored) { return 0; }
    }

    /// Creates an export sibling so a failed write never truncates an existing destination.
    static Path temporaryExport(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        return Files.createTempFile(absolute.getParent(), ".sao-export-", ".zip");
    }
}
