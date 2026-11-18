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

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/// A single durable transaction holds signed caches and their independent high-water marks under an OS lock.
/// Damaged state fails closed; legacy unsigned caches are never migrated or consulted.
@NotNullByDefault
final class SaoReleaseState {
    /// Atomic state filename in the launcher data root; contains only public release bytes and signatures.
    static final String NAME = "online-release-state.json";
    /// Upper bound includes base64 expansion of both bounded documents and their signatures.
    private static final int MAX_STATE_BYTES = 400000;
    /// Serializes threads and store objects in one JVM before taking the process-shared file lock.
    private static final Map<Path, Object> MONITORS = new ConcurrentHashMap<>();
    /// Canonical per-launcher state file.
    private final Path state;
    /// OS lock file is separate so atomic replacement never changes the locked inode.
    private final Path lockFile;
    /// Explicit immutable public trust roots.
    private final SaoReleaseSignatures trust;
    /// Shared in-process monitor for this launcher root.
    private final Object monitor;

    /// Separates news and version sequences so a large announcement sequence cannot suppress a release policy.
    @NotNullByDefault
    enum Kind {
        /// Exact version schema 2 and its complete pack/launcher policy.
        VERSION("version", SaoUpdates.MAX_VERSION_BYTES),
        /// Exact news schema 2 and its plain-text entries.
        NEWS("news", SaoReleaseSignatures.MAX_NEWS_BYTES);
        /// Stable field name in the state transaction.
        final String field;
        /// Raw signed document cap before base64 encoding.
        final int limit;
        /// Retains the independent signed document identity and byte cap.
        Kind(String field, int limit) { this.field = field; this.limit = limit; }

        /// Fully validates a signature-authenticated document and returns its nonnegative sequence.
        long sequence(byte @Unmodifiable [] bytes) throws IOException {
            if (bytes.length > limit) throw new IOException("Release document is too large");
            String document = SaoReleaseSignatures.utf8(bytes);
            return this == VERSION ? SaoUpdates.parse(document).sequence() : SaoReleaseSignatures.news(document).sequence();
        }
    }

    /// Binds a root to compiled production keys or explicit throwaway fixture keys.
    SaoReleaseState(Path root, SaoReleaseSignatures trust) {
        state = root.toAbsolutePath().normalize().resolve(NAME);
        lockFile = state.resolveSibling("online-release-state.lock");
        this.trust = trust; monitor = MONITORS.computeIfAbsent(state, ignored -> new Object());
    }

    /// Loads only reverified exact bytes; callers decide whether to keep their existing in-memory value on error.
    byte @Nullable @Unmodifiable [] cached(Kind kind) throws IOException {
        return locked(() -> {
            @Nullable JsonObject entry = read().getAsJsonObject(kind.field);
            return entry == null || !entry.has("document") ? null : document(entry, kind);
        });
    }

    /// Verifies before parsing, rejects rollback, then commits cache and high-water in one durable atomic replacement.
    long accept(Kind kind, byte @Unmodifiable [] bytes, byte @Unmodifiable [] signature) throws IOException {
        trust.verify(bytes, signature);
        long sequence = kind.sequence(bytes);
        return locked(() -> {
            JsonObject root = read();
            @Nullable JsonObject previous = root.getAsJsonObject(kind.field);
            if (previous != null && sequence < SaoReleaseSignatures.number(previous, "highWater"))
                throw new IOException("Release sequence rollback refused");
            // At the same sequence only the identical document is accepted, so an older signed copy cannot be replayed.
            if (previous != null && previous.has("document") && sequence == SaoReleaseSignatures.number(previous, "highWater")
                    && !Arrays.equals(Base64.getDecoder().decode(SaoReleaseSignatures.text(previous, "document")), bytes))
                throw new IOException("Different release at the same sequence refused");
            JsonObject entry = new JsonObject(); entry.addProperty("highWater", sequence);
            entry.addProperty("document", Base64.getEncoder().encodeToString(bytes));
            entry.addProperty("signature", Base64.getEncoder().encodeToString(signature));
            root.add(kind.field, entry);
            write(root); return sequence;
        });
    }

    /// Validates the persisted transaction per kind, rechecking each signature before its document is parsed.
    /// An entry that no longer verifies or parses (a removed key, another launcher version, damage) keeps only its
    /// sequence floor, so newer signed documents are still accepted and older ones still refused. A transaction that
    /// cannot be read at all is set aside and the state restarts from any high-water marks still legible in its text.
    private JsonObject read() throws IOException {
        JsonObject initial = new JsonObject(); initial.addProperty("schema", 1);
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return initial;
        JsonObject root;
        try {
            byte @Unmodifiable [] bytes = bounded(state, MAX_STATE_BYTES);
            root = SaoReleaseSignatures.object(SaoReleaseSignatures.utf8(bytes));
            Set<String> expected = new HashSet<>(Set.of("schema"));
            for (Kind kind : Kind.values()) if (root.has(kind.field)) expected.add(kind.field);
            SaoReleaseSignatures.fields(root, expected);
            // An existing transaction always contains an accepted document; empty state is only valid before the first write.
            if (expected.size() == 1) throw new IOException("Empty release state transaction");
            if (SaoReleaseSignatures.number(root, "schema") != 1) throw new IOException("Unsupported release state");
        } catch (IOException | RuntimeException damaged) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Release state unreadable; keeping legible floors: " + damaged.getClass().getSimpleName());
            JsonObject salvaged = salvage();
            Files.move(state, state.resolveSibling(NAME + ".corrupt-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            if (salvaged.size() > 1) write(salvaged);
            return salvaged;
        }
        for (Kind kind : Kind.values()) {
            if (!root.has(kind.field)) continue;
            JsonElement element = root.get(kind.field);
            if (element.isJsonObject() && element.getAsJsonObject().keySet().equals(Set.of("highWater"))) continue;
            try {
                if (!element.isJsonObject()) throw new IOException("Invalid release state entry");
                document(element.getAsJsonObject(), kind);
            } catch (IOException | RuntimeException invalid) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cached " + kind.field + " release dropped; keeping its sequence floor");
                JsonObject floor = new JsonObject(); floor.addProperty("highWater", floor(element, kind));
                root.add(kind.field, floor);
            }
        }
        return root;
    }

    /// Builds a floor-only transaction from high-water marks still legible in an unparseable state file, so a torn or
    /// damaged write never lowers the rollback floor. Floors are only ever raised by later signed documents.
    private JsonObject salvage() {
        JsonObject root = new JsonObject(); root.addProperty("schema", 1);
        String text;
        try {
            if (Files.size(state) > 2L * MAX_STATE_BYTES) return root;
            text = new String(Files.readAllBytes(state), java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException unreadable) { return root; }
        for (Kind kind : Kind.values()) {
            // Base64 documents contain no braces or quotes, so the entry's own highWater is the first one before '}'.
            var match = java.util.regex.Pattern.compile("\"" + kind.field + "\"\\s*:\\s*\\{[^}]*?\"highWater\"\\s*:\\s*(\\d{1,18})").matcher(text);
            if (!match.find()) continue;
            JsonObject floor = new JsonObject(); floor.addProperty("highWater", Long.parseLong(match.group(1)));
            root.add(kind.field, floor);
        }
        return root;
    }

    /// Highest sequence an invalid entry still proves: its recorded high-water or its document's signed sequence.
    private long floor(JsonElement element, Kind kind) {
        long floor = 0;
        if (!element.isJsonObject()) return floor;
        JsonObject entry = element.getAsJsonObject();
        try { floor = Math.max(floor, SaoReleaseSignatures.number(entry, "highWater")); } catch (IOException | RuntimeException ignored) { }
        try {
            byte[] bytes = Base64.getDecoder().decode(SaoReleaseSignatures.text(entry, "document"));
            trust.verify(bytes, Base64.getDecoder().decode(SaoReleaseSignatures.text(entry, "signature")));
            floor = Math.max(floor, kind.sequence(bytes));
        } catch (IOException | RuntimeException ignored) { }
        return floor;
    }

    /// Decodes a bounded envelope, verifies its raw bytes, then checks the persisted high-water against the signed sequence.
    private byte @Unmodifiable [] document(JsonObject entry, Kind kind) throws IOException {
        SaoReleaseSignatures.fields(entry, Set.of("highWater", "document", "signature"));
        try {
            byte @Unmodifiable [] bytes = Base64.getDecoder().decode(SaoReleaseSignatures.text(entry, "document"));
            byte @Unmodifiable [] signature = Base64.getDecoder().decode(SaoReleaseSignatures.text(entry, "signature"));
            if (bytes.length > kind.limit) throw new IOException("Cached release is too large");
            trust.verify(bytes, signature);
            if (kind.sequence(bytes) != SaoReleaseSignatures.number(entry, "highWater")) throw new IOException("Corrupt release high-water");
            return bytes;
        } catch (IllegalArgumentException failure) { throw new IOException("Malformed signed release cache", failure); }
    }

    /// Forces the complete temporary file, atomically replaces the transaction, then syncs its parent where supported.
    private void write(JsonObject root) throws IOException {
        Path parent = Objects.requireNonNull(state.getParent());
        byte @Unmodifiable [] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATE_BYTES) throw new IOException("Release state is too large");
        Path temporary = Files.createTempFile(parent, "release-state-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: a partial cache/high-water transaction must never become visible.
            Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
            catch (IOException | UnsupportedOperationException unavailable) { /* Windows cannot open directory handles this way. */ }
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Limits disk input before allocation and rejects symlinks, truncation and growth during a read.
    static byte @Unmodifiable [] bounded(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("Missing or oversized release file");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte @Unmodifiable [] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Release file is too large");
            return bytes;
        }
    }

    /// Serializes read-check-commit across threads and processes; exceptions never weaken an existing high-water mark.
    private <T extends @Nullable Object> T locked(Operation<T> operation) throws IOException {
        synchronized (monitor) {
            Files.createDirectories(Objects.requireNonNull(state.getParent()));
            if (Files.isSymbolicLink(lockFile)) throw new IOException("Release lock must not be a symlink");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.lock()) {
                return operation.run();
            }
        }
    }

    /// Filesystem operation executed while both transaction locks are held.
    @NotNullByDefault
    @FunctionalInterface
    private interface Operation<T extends @Nullable Object> {
        /// Returns the validated result or leaves the existing transaction untouched on failure.
        T run() throws IOException;
    }
}
