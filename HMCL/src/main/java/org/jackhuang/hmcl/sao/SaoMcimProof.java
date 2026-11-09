/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.*;
import org.jackhuang.hmcl.download.HMCLDownloadProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;

/// Headless, sample-only network proof through the same pack downloader used by SaoGameBridge.
/// Never installs a pack, opens a browser, authenticates or starts Minecraft; always removes sample jars.
@NotNullByDefault
public final class SaoMcimProof {
    /// Utility class.
    private SaoMcimProof() { }

    /// Reads PACK read-only; downloads exactly 10 primary CurseForge entries (including the two largest)
    /// and 5 primary Modrinth entries into SCRATCH, writes evidence.json, and removes every sample jar.
    /// Usage: java -Djava.awt.headless=true -cp launcher.jar org.jackhuang.hmcl.sao.SaoMcimProof PACK SCRATCH
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length != 2) throw new IllegalArgumentException("Expected PACK ZIP and empty SCRATCH directory");
        Path pack = Path.of(args[0]).toRealPath();
        Path scratch = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(scratch)) {
            try (var children = Files.list(scratch)) {
                if (children.findAny().isPresent()) throw new IOException("Proof scratch must be empty");
            }
        }
        Files.createDirectories(scratch);
        if (Files.getFileStore(scratch).getUsableSpace() < 10_000_000_000L) throw new IOException("Less than 10 GB free");
        List<SaoPackManifest.PackFile> indexed = readIndex(pack);
        @Unmodifiable List<SaoPackManifest.PackFile> sample = sample(indexed);
        long selectedBytes = sample.stream().mapToLong(SaoPackManifest.PackFile::size).sum();
        if (sample.size() != 15 || selectedBytes > 250_000_000L) throw new IOException("Unexpected sample size/budget");
        Map<SaoPackManifest.PackFile, Path> destinations = new LinkedHashMap<>();
        for (var file : sample) destinations.put(file, SaoPackFiles.resolve(scratch, file.path()));
        List<SaoModDownloads.Transfer> results = Collections.synchronizedList(new ArrayList<>());
        String archiveHash = sha256(pack);
        String start = Instant.now().toString();
        long startNanos = System.nanoTime();
        @Nullable Exception failure = null;
        try (SaoModDownloads downloads = new SaoModDownloads(new HMCLDownloadProvider("https://bmclapi2.bangbang93.com"),
                snapshot -> System.out.println(snapshot.label() + " " + snapshot.bytesDone() + "/" + snapshot.totalBytes()),
                transfer -> {
                    results.add(transfer);
                    System.out.printf(Locale.ROOT, "%s verified=%s host=%s seconds=%.3f B/s=%.0f%n", transfer.path(), transfer.verified(),
                            transfer.finalHost(), transfer.seconds(), transfer.bytesPerSecond());
                }, Set.of("mod.mcimirror.top", "mediafilez.forgecdn.net", "cdn.modrinth.com", "bmclapi2.bangbang93.com"))) {
            downloads.downloadAll(destinations);
        } catch (Exception problem) { failure = problem; }
        finally {
            for (Path destination : destinations.values()) Files.deleteIfExists(destination);
            // The scratch root belongs only to this proof; remove empty staging folders, retaining JSON evidence.
            try (var children = Files.walk(scratch)) {
                for (Path path : children.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(scratch) && Files.isDirectory(path)) Files.delete(path);
                }
            }
            JsonObject report = new JsonObject();
            report.addProperty("startedUtc", start); report.addProperty("finishedUtc", Instant.now().toString());
            report.addProperty("elapsedSeconds", (System.nanoTime() - startNanos) / 1_000_000_000.0);
            report.addProperty("userAgent", SaoModDownloads.USER_AGENT);
            report.addProperty("archiveSha256Before", archiveHash); report.addProperty("archiveSha256After", sha256(pack));
            report.addProperty("indexedFiles", indexed.size()); report.addProperty("sampleFiles", sample.size());
            report.addProperty("selectedBytes", selectedBytes); report.addProperty("sampleJarsDeleted", true);
            report.addProperty("verifiedFiles", results.stream().filter(SaoModDownloads.Transfer::verified).count());
            report.addProperty("mcimActuallyServedFiles", results.stream().filter(result -> result.verified() && result.finalHost().equals("mod.mcimirror.top")).count());
            report.addProperty("error", failure == null ? "" : Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            report.add("files", gson.toJsonTree(results.stream().sorted(Comparator.comparing(SaoModDownloads.Transfer::path)).toList()));
            Files.writeString(scratch.resolve("evidence.json"), gson.toJson(report), StandardCharsets.UTF_8);
        }
        if (failure != null) throw failure;
        if (results.size() != 15 || results.stream().anyMatch(result -> !result.verified() || result.attempts().isEmpty()))
            throw new IOException("Live proof requires 15 verified network downloads");
    }

    /// Reads only the real Modrinth index, preserving its SHA-1, byte length and original candidate order.
    private static List<SaoPackManifest.PackFile> readIndex(Path pack) throws IOException {
        try (ZipFile archive = new ZipFile(pack.toFile(), StandardCharsets.UTF_8)) {
            @Nullable var entry = archive.getEntry("modrinth.index.json");
            if (entry == null) throw new IOException("Missing Modrinth index");
            try (Reader reader = new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject index = JsonParser.parseReader(reader).getAsJsonObject();
                List<SaoPackManifest.PackFile> files = new ArrayList<>();
                for (JsonElement element : index.getAsJsonArray("files")) {
                    JsonObject file = element.getAsJsonObject();
                    List<String> urls = new ArrayList<>();
                    for (JsonElement url : file.getAsJsonArray("downloads")) urls.add(url.getAsString());
                    files.add(new SaoPackManifest.PackFile(file.get("path").getAsString(), file.getAsJsonObject("hashes").get("sha1").getAsString(),
                            file.get("fileSize").getAsLong(), null, urls));
                }
                return files;
            }
        }
    }

    /// Includes the two largest CurseForge files plus eight small ones and five largest Modrinth entries.
    /// The resulting fixed 15-file set provides large-file and both-CDN coverage without fetching the pack.
    private static @Unmodifiable List<SaoPackManifest.PackFile> sample(List<SaoPackManifest.PackFile> indexed) throws IOException {
        @Unmodifiable List<SaoPackManifest.PackFile> curse = indexed.stream().filter(file -> primaryHost(file).equals("mediafilez.forgecdn.net"))
                .sorted(Comparator.comparingLong(SaoPackManifest.PackFile::size).reversed()).toList();
        @Unmodifiable List<SaoPackManifest.PackFile> modrinth = indexed.stream().filter(file -> primaryHost(file).equals("cdn.modrinth.com"))
                .sorted(Comparator.comparingLong(SaoPackManifest.PackFile::size).reversed()).limit(5).toList();
        if (curse.size() < 10 || modrinth.size() != 5 || !curse.get(0).path().contains("blue_skies") || !curse.get(1).path().contains("alexscaves"))
            throw new IOException("Index is not the expected proof pack");
        List<SaoPackManifest.PackFile> selected = new ArrayList<>(curse.subList(0, 2));
        curse.stream().skip(2).sorted(Comparator.comparingLong(SaoPackManifest.PackFile::size)).limit(8).forEach(selected::add);
        selected.addAll(modrinth); return List.copyOf(selected);
    }

    /// Returns the primary host for unambiguous sample classification.
    private static String primaryHost(SaoPackManifest.PackFile file) { return Objects.requireNonNull(URI.create(file.downloads().get(0)).getHost()); }

    /// Hashes the read-only source before and after proof so evidence records its preservation.
    private static String sha256(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(source)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
