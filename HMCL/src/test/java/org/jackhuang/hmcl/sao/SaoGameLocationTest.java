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

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/// Headless folder-policy, verified-copy, rollback and missing-drive contracts; no Windows volume is modified.
@NotNullByDefault
class SaoGameLocationTest {
    /// Canonical disposable files only; the runner sets java.io.tmpdir inside the run folder.
    @TempDir Path temporary;
    /// Fake writable fixed NTFS volume with ample space.
    private static final SaoGameLocation.Probe NTFS = path -> new SaoGameLocation.Volume("NTFS", 100 * SaoDiskSpace.GB, true, true);

    /// NTFS, exFAT, capacity, write permission, fixed-disk and Unicode rules use injected FileStores.
    @Test void volumePolicy() throws Exception {
        Path old = Path.of("/old"), target = Path.of("/游戏/刀剑");
        SaoGameLocation.validate(target, old, 0, 180, NTFS, List.of(), true);
        reject(target, "exFAT", path -> new SaoGameLocation.Volume("exFAT", Long.MAX_VALUE, true, true));
        reject(target, "空间不够", path -> new SaoGameLocation.Volume("NTFS", 4 * SaoDiskSpace.GB, true, true));
        reject(target, "不能写入", path -> new SaoGameLocation.Volume("NTFS", Long.MAX_VALUE, true, false));
        reject(target, "固定磁盘", path -> new SaoGameLocation.Volume("NTFS", Long.MAX_VALUE, false, true));
        assertThrows(IOException.class, () -> SaoGameLocation.validate(target, old, 99 * SaoDiskSpace.GB, 180, NTFS, List.of(), true));
    }

    /// Known and renamed sync roots, system folders, overlapping roots and long final paths are refused.
    @Test void folderPolicy() {
        for (String target : List.of("/OneDrive/a", "/Dropbox/a", "/Windows/a", "/Program Files/a", "/old/child", "/old"))
            assertThrows(IOException.class, () -> SaoGameLocation.validate(Path.of(target), Path.of("/old"), 0, 180, NTFS, List.of(), true), target);
        assertThrows(IOException.class, () -> SaoGameLocation.validate(Path.of("/renamed-sync/game"), Path.of("/old"), 0, 180,
                NTFS, List.of(Path.of("/renamed-sync")), true));
        assertThrows(IOException.class, () -> SaoGameLocation.validate(Path.of("/" + "a".repeat(75)), Path.of("/old"), 0, 180, NTFS, List.of(), true));
    }

    /// Empty targets pass; arbitrary nonempty folders do not become launcher-owned merely by selecting them.
    @Test void foreignFolderAndAdoption() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("foreign"));
        SaoGameLocation.validate(target, temporary.resolve("old"), 0, 0, NTFS, List.of(), false);
        Files.writeString(target.resolve("keep.txt"), "unrelated");
        assertThrows(IOException.class, () -> SaoGameLocation.validate(target, temporary.resolve("old"), 0, 0, NTFS, List.of(), false));
        Files.createDirectories(target.resolve("minecraft")); Files.writeString(target.resolve(SaoGameLocation.MARKER), "NerveGear data v1\n");
        SaoGameLocation.validate(target, temporary.resolve("old"), 0, 0, NTFS, List.of(), false);
    }

    /// Every category, including player files and archives, survives byte-identically; deletion follows READY.
    @Test void moveSuccess() throws Exception {
        Path old = seed(), target = temporary.resolve("游戏");
        Pointer pointer = new Pointer(old, true);
        AtomicInteger copied = new AtomicInteger();
        SaoGameLocation.move(old, target, pointer, (from, to) -> { Files.copy(from, to); copied.incrementAndGet(); }, (done, total) -> assertTrue(done <= total));
        assertEquals(target, pointer.current); assertTrue(pointer.checked); assertEquals(8, copied.get());
        assertFalse(Files.exists(old));
        for (String file : paths()) assertEquals("original:" + file, Files.readString(target.resolve(file)));
    }

    /// A copier failing after a partial destination write never changes the root or deletes either copy.
    @Test void midCopyFailure() throws Exception {
        Path old = seed(), target = temporary.resolve("partial"); Pointer pointer = new Pointer(old, true);
        AtomicInteger count = new AtomicInteger();
        assertThrows(IOException.class, () -> SaoGameLocation.move(old, target, pointer, (from, to) -> {
            if (count.incrementAndGet() == 3) { Files.writeString(to, "partial"); throw new IOException("simulated disk failure"); }
            Files.copy(from, to);
        }, (done, total) -> { }));
        assertEquals(old, pointer.current); assertFalse(pointer.checked);
        for (String file : paths()) assertEquals("original:" + file, Files.readString(old.resolve(file)));
        try (var files = Files.walk(target)) { assertEquals(3, files.filter(Files::isRegularFile).count()); }
    }

    /// Same-size corruption is detected by SHA-1, including options and saves, before pointer publication.
    @Test void sameSizeCorruption() throws Exception {
        Path old = seed(), target = temporary.resolve("corrupt"); Pointer pointer = new Pointer(old, true);
        assertThrows(IOException.class, () -> SaoGameLocation.move(old, target, pointer, (from, to) -> {
            byte[] data = Files.readAllBytes(from); data[0] ^= 1; Files.write(to, data);
        }, (done, total) -> { }));
        assertEquals(old, pointer.current); assertTrue(Files.exists(old)); assertTrue(Files.exists(target));
    }

    /// A failed post-switch readiness check atomically restores the old pointer and leaves both full copies.
    @Test void readinessRollback() throws Exception {
        Path old = seed(), target = temporary.resolve("not-ready"); Pointer pointer = new Pointer(old, false);
        assertThrows(IOException.class, () -> SaoGameLocation.move(old, target, pointer, Files::copy, (done, total) -> { }));
        assertEquals(old, pointer.current); assertTrue(pointer.checked);
        for (String file : paths()) assertArrayEquals(Files.readAllBytes(old.resolve(file)), Files.readAllBytes(target.resolve(file)));
    }

    /// A chosen missing root remains the chosen root across startup; download/repair do not recreate it.
    @Test void missingChosenRootNeverReinstalls() throws Exception {
        Path config = Files.createDirectory(temporary.resolve("settings")), missing = temporary.resolve("unplugged/game");
        SaoSettings settings = new SaoSettings(config); settings.put("gameDataRoot", missing.toString());
        assertEquals(missing, SaoGameLocation.resolve(settings, config)); assertNotNull(SaoGameLocation.missing(settings, missing));
        try (SaoPackEngine engine = new SaoPackEngine(config, null, false)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (engine.state().get() == SaoPackState.CHECKING && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(missing, engine.gameDataRoot()); assertTrue(engine.lastError().get().contains("找不到"));
            assertEquals(SaoReadiness.Action.NONE, engine.computeReadiness().action());
            engine.download(); engine.repair(); engine.downloadJava().handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(missing));
        }
    }

    /// A writable symlink/junction target cannot bypass ownership or synced-folder policy.
    @Test void linksAreNotFollowed() throws Exception {
        Path old = seed(), outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(old.resolve("minecraft/link"), outside);
        assertThrows(IOException.class, () -> SaoGameLocation.bytes(old));
    }

    /// Fixture pointer records whether the new selected root was checked before deletion.
    @NotNullByDefault
    private static final class Pointer implements SaoGameLocation.Switch {
        /// Current in-memory equivalent of the atomic settings pointer.
        private Path current;
        /// Configured readiness result.
        private final boolean readiness;
        /// Whether readiness was invoked after selecting the destination.
        private boolean checked;
        /// Creates a deterministic switch seam.
        Pointer(Path root, boolean ready) { current = root; readiness = ready; }

        /// Simulates an atomic settings commit.
        @Override public void select(Path root) { current = root; }

        /// Records the required post-switch check.
        @Override public boolean ready() { checked = true; return readiness; }
    }

    /// Seeds all requested data categories under a canonical source root.
    private Path seed() throws Exception {
        Path root = temporary.resolve("original");
        for (String file : paths()) { Path path = root.resolve(file); Files.createDirectories(path.getParent()); Files.writeString(path, "original:" + file); }
        return root;
    }

    /// Player and managed file fixtures, deliberately including non-archive data.
    private static @Unmodifiable List<String> paths() {
        return List.of("minecraft/versions/sao-oath/saves/world/level.dat", "minecraft/versions/sao-oath/options.txt",
                "minecraft/versions/sao-oath/screenshots/test.png", "minecraft/versions/sao-oath/shaderpacks/custom.zip",
                "pack-cache/pack.zip", "java/bin/java", "dependencies/library.jar", "logs/previous.log");
    }

    /// Asserts a specific user-facing volume rejection without probing a real Windows drive.
    private static void reject(Path target, String message, SaoGameLocation.Probe store) {
        IOException error = assertThrows(IOException.class, () -> SaoGameLocation.validate(target, Path.of("/old"), 0, 180, store, List.of(), true));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }
}
