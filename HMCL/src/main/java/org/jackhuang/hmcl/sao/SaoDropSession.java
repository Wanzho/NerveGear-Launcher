/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/// Accumulates privately verified quick-update drops independently of their names or arrival order.
@NotNullByDefault
final class SaoDropSession implements AutoCloseable {
    /// Immutable policy identity used to invalidate obsolete pending drops.
    final SaoData.VersionMetadata metadata;
    /// Required ordered chain, empty when a complete pack is needed.
    final @Unmodifiable List<SaoData.QuickRelease> chain;
    /// Installed version when the chain was selected; null for a first installation.
    final @Nullable String installedVersion;
    /// Private verified snapshots keyed by destination version.
    private final Map<String, Path> present = new LinkedHashMap<>();
    /// Launcher-owned temporary snapshot folder.
    private final Path cache;
    /// True after a modified base makes further quick attempts inappropriate.
    private boolean fullRequired;

    /// Computes the full chain without inspecting or modifying the installed instance.
    SaoDropSession(SaoData.VersionMetadata metadata, @Nullable String installed, Path cache) {
        this.metadata = metadata;
        this.cache = cache;
        this.installedVersion = installed;
        chain = SaoUpdates.chain(metadata, installed);
        fullRequired = chain.isEmpty();
    }

    /// Matches and snapshots one quick archive, then checks its exact advertised hop before retaining it.
    boolean acceptQuick(Path file) throws IOException { return acceptQuick(file, candidate -> { }); }

    /// Checks the resulting full inventory on the instance drive before making a private quick-update snapshot.
    boolean acceptQuick(Path file, SaoPackInstaller.CapacityCheck capacity) throws IOException {
        if (fullRequired || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        long size = Files.size(file);
        @Nullable String hash = null;
        for (var release : chain) {
            if (size != release.size()) continue;
            if (hash == null) hash = SaoUpdates.sha256(file);
            if (!hash.equals(release.sha256())) continue;
            if (present.containsKey(release.to())) return true;
            SaoQuickUpdate candidate = SaoQuickUpdate.open(file);
            capacity.check(new SaoPackArchive(file, candidate.after()));
            Path snapshot = SaoUpdates.snapshot(file, release.size(), release.sha256(), cache);
            boolean retained = false;
            try {
                SaoQuickUpdate update = SaoQuickUpdate.open(snapshot);
                if (!release.from().equals(update.from()) || !release.to().equals(update.to())) throw new IOException(SaoUpdates.INVALID_ARCHIVE);
                present.put(release.to(), snapshot); retained = true; return true;
            } finally { if (!retained) Files.deleteIfExists(snapshot); }
        }
        return false;
    }

    /// Returns all verified snapshots in version order only when the complete chain is available.
    @Unmodifiable List<Path> ready() {
        if (fullRequired || present.size() != chain.size()) return List.of();
        return chain.stream().map(release -> Objects.requireNonNull(present.get(release.to()))).toList();
    }

    /// Switches to the exact complete-pack prompt after any incompatible installed base is detected.
    void requireFull() throws IOException { fullRequired = true; clear(); }

    /// Builds a stable immutable UI state; no mutable path map escapes the service.
    SaoData.DownloadPrompt prompt(boolean watching, @Nullable Path candidate) {
        @Unmodifiable List<String> required = fullRequired ? List.of() : chain.stream().map(SaoData.QuickRelease::to).toList();
        @Unmodifiable List<String> have = required.stream().filter(present::containsKey).toList();
        @Unmodifiable List<String> missing = required.stream().filter(value -> !present.containsKey(value)).toList();
        return new SaoData.DownloadPrompt(watching, candidate, SaoUpdates.prompt(required), required, have, missing, fullRequired);
    }

    /// Removes only private snapshots; user drop paths are never changed.
    private void clear() throws IOException {
        IOException failure = new IOException("Cannot remove pending update snapshots");
        for (Path path : present.values()) try { Files.deleteIfExists(path); } catch (IOException error) { failure.addSuppressed(error); }
        present.clear();
        if (failure.getSuppressed().length > 0) throw failure;
    }

    /// Releases private pending files on cancel, completion, policy replacement or shutdown.
    @Override public void close() throws IOException { clear(); }
}
