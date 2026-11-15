/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// 柚歌, 15 Nov: "Cannot reserve game instance sao-oath" on a fresh install. A half-finished earlier attempt's
/// versions/sao-oath moves aside (kept, never deleted) so the build can start; nothing happens when there is none.
final class SaoUnfinishedInstanceTest {
    @TempDir Path temporary;

    @Test void leftoverFolderMovesAsideWithItsFiles() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("minecraft/versions/sao-oath/mods"));
        Files.writeString(root.resolve("a.jar"), "x");
        Path instance = root.getParent();
        Path aside = SaoGameBridge.moveUnregisteredAside(instance, instance.getParent().resolveSibling(".sao-unfinished"), 42);
        assertNotNull(aside);
        assertFalse(Files.exists(instance), "the name is free for the fresh build");
        assertEquals("x", Files.readString(aside.resolve("mods/a.jar")), "the leftover is kept, not deleted");
        assertEquals("sao-oath-42", aside.getFileName().toString());
        assertEquals(".sao-unfinished", aside.getParent().getFileName().toString());
    }

    @Test void nothingToMove() throws Exception {
        Path instance = temporary.toRealPath().resolve("minecraft/versions/sao-oath");
        assertNull(SaoGameBridge.moveUnregisteredAside(instance, temporary.resolve("aside"), 1));
        assertFalse(Files.exists(temporary.resolve("aside")));
    }

    @Test void linkIsRefusedNotFollowed() throws Exception {
        Path real = Files.createDirectories(temporary.toRealPath().resolve("elsewhere"));
        Path versions = Files.createDirectories(temporary.toRealPath().resolve("minecraft/versions"));
        Path instance = Files.createSymbolicLink(versions.resolve("sao-oath"), real);
        assertThrows(java.io.IOException.class, () -> SaoGameBridge.moveUnregisteredAside(instance, temporary.resolve("aside"), 1));
        assertTrue(Files.isSymbolicLink(instance));
    }
}
