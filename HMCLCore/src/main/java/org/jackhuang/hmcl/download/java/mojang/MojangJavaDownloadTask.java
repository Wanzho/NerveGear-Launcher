/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.download.ArtifactMalformedException;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.GetTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.ChecksumMismatchException;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.tukaani.xz.LZMAInputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Downloads and verifies the first compatible Mojang runtime, then installs its files into the target directory.
@NotNullByDefault
public final class MojangJavaDownloadTask extends Task<MojangJavaDownloadTask.Result> {

    /// Mojang runtime index; platform fallback uses the same downloaded index.
    private static final String JAVA_LIST_URL = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    /// Supplies mirror-aware URLs for metadata and runtime files.
    private final DownloadProvider downloadProvider;
    /// Final managed runtime directory, replaced only after every file download succeeds.
    private final Path target;
    /// Staging directory used for integrity checks and decompression.
    private final Path tempDir;
    /// Resolves a compatible runtime entry and retrieves its file manifest.
    private final Task<MojangJavaRemoteFiles> javaDownloadsTask;
    /// File downloads and postprocessing tasks assembled from the manifest.
    private final List<Task<?>> dependencies = new ArrayList<>();

    /// Selected runtime metadata, absent until the index selection task succeeds.
    private volatile @Nullable MojangJavaDownloads.JavaDownload download;

    /// Creates a lazy runtime download with native-first Windows ARM64 component fallback.
    public MojangJavaDownloadTask(DownloadProvider downloadProvider, Path target, Path tempDir, GameJavaVersion javaVersion, @Nullable String platform) {
        this.target = target;
        this.tempDir = tempDir;
        this.downloadProvider = downloadProvider;
        this.javaDownloadsTask = new GetTask(downloadProvider.getDownloadCandidates(JAVA_LIST_URL))
                .thenComposeAsync(javaDownloadsJson -> {
                    MojangJavaDownloads allDownloads = JsonUtils.fromNonNullJson(javaDownloadsJson, MojangJavaDownloads.class);

                    MojangJavaRuntimeSelector.Selection selection = MojangJavaRuntimeSelector.select(allDownloads, javaVersion, platform);
                    this.download = selection.download();
                    LOG.info("Selected Mojang runtime " + javaVersion.component() + " for " + selection.platform()
                            + " (requested " + platform + ")");
                    return new GetTask(downloadProvider.getDownloadCandidates(selection.download().manifest().getUrl()));
                })
                .thenApplyAsync(javaDownloadJson -> JsonUtils.fromNonNullJson(javaDownloadJson, MojangJavaRemoteFiles.class));
    }

    /// Exposes the metadata chain that must finish before file tasks can be created.
    @Override
    public @Unmodifiable Collection<Task<?>> getDependents() {
        return Collections.singleton(javaDownloadsTask);
    }

    /// Builds verified download, directory and link operations from the selected runtime manifest.
    @Override
    public void execute() throws Exception {
        for (Map.Entry<String, MojangJavaRemoteFiles.Remote> entry : javaDownloadsTask.getResult().files().entrySet()) {
            Path dest = tempDir.resolve(entry.getKey());
            if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteFile file) {
                // Use local file if it already exists
                try {
                    BasicFileAttributes localFileAttributes = Files.readAttributes(dest, BasicFileAttributes.class);
                    if (localFileAttributes.isRegularFile() && file.getDownloads().containsKey("raw")) {
                        DownloadInfo downloadInfo = file.getDownloads().get("raw");
                        if (localFileAttributes.size() == downloadInfo.getSize()) {
                            ChecksumMismatchException.verifyChecksum(dest, "SHA-1", downloadInfo.getSha1());
                            LOG.info("Skip existing file: " + dest);
                            continue;
                        }
                    }
                } catch (IOException ignored) {
                }

                if (file.getDownloads().containsKey("lzma")) {
                    DownloadInfo download = file.getDownloads().get("lzma");
                    @Nullable DownloadInfo raw = file.getDownloads().get("raw");

                    @Nullable String rawSha1;
                    if (raw != null && raw.getSha1() != null) {
                        rawSha1 = raw.getSha1();
                    } else {
                        rawSha1 = null;
                    }

                    Path tempFile = tempDir.resolve(entry.getKey() + ".lzma");
                    var task = new FileDownloadTask(downloadProvider.getDownloadCandidates(download.getUrl()), tempFile,
                            new FileDownloadTask.IntegrityCheck("SHA-1", download.getSha1()));
                    task.setName(entry.getKey());
                    dependencies.add(task.thenRunAsync(() -> {
                        Path decompressed = tempDir.resolve(entry.getKey() + ".tmp");
                        var digest = MessageDigest.getInstance("SHA-1");
                        try (var input = new DigestInputStream(new LZMAInputStream(Files.newInputStream(tempFile)), digest)) {
                            Files.copy(input, decompressed, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new ArtifactMalformedException("File " + entry.getKey() + " is malformed", e);
                        }

                        String actualSha1 = HexFormat.of().formatHex(digest.digest());

                        if (rawSha1 != null && !actualSha1.equalsIgnoreCase(rawSha1)) {
                            throw new ArtifactMalformedException("File " + entry.getKey() + " has incorrect SHA-1 hash: expected " + rawSha1 + ", got " + actualSha1);
                        }

                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException e) {
                            LOG.warning("Failed to delete temporary file: " + tempFile, e);
                        }

                        Files.move(decompressed, dest, StandardCopyOption.REPLACE_EXISTING);
                        if (file.isExecutable()) {
                            FileUtils.setExecutable(dest);
                        }
                    }));
                } else if (file.getDownloads().containsKey("raw")) {
                    DownloadInfo download = file.getDownloads().get("raw");
                    var task = new FileDownloadTask(downloadProvider.getDownloadCandidates(download.getUrl()), dest, new FileDownloadTask.IntegrityCheck("SHA-1", download.getSha1()));
                    task.setName(entry.getKey());
                    if (file.isExecutable()) {
                        dependencies.add(task.thenRunAsync(() -> FileUtils.setExecutable(dest)));
                    } else {
                        dependencies.add(task);
                    }
                } else {
                    continue;
                }
            } else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteDirectory) {
                Files.createDirectories(dest);
            } else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteLink link) {
                Files.deleteIfExists(dest);
                Files.createSymbolicLink(dest, Paths.get(link.getTarget()));
            }
        }
    }

    /// Returns the mutable task list populated during manifest processing.
    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    /// Requests installation after all file dependencies finish.
    @Override
    public boolean doPostExecute() {
        return true;
    }

    /// Replaces the target only after successful downloads and publishes the selected runtime metadata.
    @Override
    public void postExecute() throws Exception {
        if (isDependenciesSucceeded()) {
            FileUtils.cleanDirectory(target);

            if (Files.getFileStore(target).equals(Files.getFileStore(tempDir))) {
                Files.move(tempDir, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                FileUtils.copyDirectory(tempDir, target);
                FileUtils.deleteDirectory(tempDir);
            }
            setResult(new Result(Objects.requireNonNull(download, "Runtime selection did not complete"), javaDownloadsTask.getResult()));
        }
    }

    /// Installed runtime entry and the file manifest used to verify it.
    @NotNullByDefault
    public record Result(MojangJavaDownloads.JavaDownload download, MojangJavaRemoteFiles remoteFiles) {
    }
}
