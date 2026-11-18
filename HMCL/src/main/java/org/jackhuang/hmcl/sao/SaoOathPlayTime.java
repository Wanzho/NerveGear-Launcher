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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.LongSupplier;

/// Per-ARGUS-ID process time, scoped to the issuing API and stable profile, under the removable per-user root.
@NotNullByDefault
final class SaoOathPlayTime {
    /// Last process start and accumulated process time; no account name or credential is stored.
    @NotNullByDefault
    record Snapshot(long lastPlayed, long playMillis) { }
    /// Owned local data tree.
    private final Path root;
    /// Wall clock is used only for the displayed last-start instant.
    private final LongSupplier wall;
    /// Monotonic clock keeps elapsed time independent of clock corrections.
    private final LongSupplier monotonic;

    /// Uses real process clocks in production.
    SaoOathPlayTime(Path root) { this(root, System::currentTimeMillis, System::nanoTime); }

    /// Injects clocks for deterministic process-lifetime tests.
    SaoOathPlayTime(Path root, LongSupplier wall, LongSupplier monotonic) {
        this.root = root.resolve("oath-playtime"); this.wall = wall; this.monotonic = monotonic;
    }

    /// Reads a bounded owner-local record, refusing aliases and malformed values.
    synchronized Snapshot read(URI api, String id) throws IOException {
        Path file = file(api, id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(0, 0);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024) throw new IOException("游玩记录无效");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            long last = json.get("lastPlayed").getAsLong(), millis = json.get("playMillis").getAsLong();
            if (last < 0 || millis < 0) throw new IOException("游玩记录无效");
            return new Snapshot(last, millis);
        } catch (RuntimeException invalid) { throw new IOException("游玩记录无效"); }
    }

    /// Called by HMCL's setProcess callback after process creation and before exit monitors start.
    synchronized Run start(URI api, String id) throws IOException {
        Snapshot previous = read(api, id);
        Snapshot started = new Snapshot(Math.max(0, wall.getAsLong()), previous.playMillis());
        write(api, id, started);
        return new Run(api, id, started, monotonic.getAsLong());
    }

    /// One actual game process; finishing more than once cannot double-count its duration.
    @NotNullByDefault
    final class Run implements AutoCloseable {
        /// Issuing API namespace, never sent to a different server.
        private final URI api;
        /// Stable profile owning this run even if the screen switches account.
        private final String id;
        /// Already persisted totals at process start.
        private final Snapshot baseline;
        /// Monotonic process start.
        private final long started;
        /// Last persisted checkpoint, or final snapshot after exit.
        private @Nullable Snapshot finished;
        /// Captures one process's fixed owner and start clocks.
        private Run(URI api, String id, Snapshot baseline, long started) {
            this.api = api; this.id = id; this.baseline = baseline; this.started = started;
        }

        /// Returns live time while running and the immutable final total after exit.
        synchronized Snapshot snapshot() {
            if (finished != null) return finished;
            long elapsed = Math.max(0, (monotonic.getAsLong() - started) / 1_000_000);
            return new Snapshot(baseline.lastPlayed(), Math.addExact(baseline.playMillis(), elapsed));
        }

        /// Saves elapsed time exactly once when the real game process exits.
        @Override public synchronized void close() throws IOException {
            if (finished != null) return;
            Snapshot end = snapshot(); write(api, id, end); finished = end;
        }
    }

    /// Writes only the stable-profile record atomically; uninstall removes the enclosing user root.
    private synchronized void write(URI api, String id, Snapshot value) throws IOException {
        Path file = file(api, id);
        Files.createDirectories(file.getParent());
        JsonObject json = new JsonObject(); json.addProperty("lastPlayed", value.lastPlayed()); json.addProperty("playMillis", value.playMillis());
        Path temporary = Files.createTempFile(file.getParent(), "time-", ".tmp");
        try { Files.writeString(temporary, json.toString()); SaoPackFiles.atomicMove(temporary, file); }
        finally { Files.deleteIfExists(temporary); }
    }

    /// Hashes the public API root and validates the stable ID before resolving local paths.
    private Path file(URI api, String id) throws IOException {
        if (!id.matches("[a-fA-F0-9]{32}")) throw new IOException("角色信息无效");
        try {
            String namespace = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(api.toString().getBytes(StandardCharsets.UTF_8)));
            SaoUninstaller.rejectLinksBelowRoot(root.resolve(namespace).resolve(id.toLowerCase(java.util.Locale.ROOT) + ".json"));
            return root.resolve(namespace).resolve(id.toLowerCase(java.util.Locale.ROOT) + ".json");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
