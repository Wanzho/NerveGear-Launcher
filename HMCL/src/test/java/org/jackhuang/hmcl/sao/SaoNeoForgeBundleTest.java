/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// The shipped NeoForge bundle (15 Nov): every library lands verified, a second run places nothing, a damaged file
/// is replaced, and the installer is ready for HMCL's task.
final class SaoNeoForgeBundleTest {
    @TempDir Path temporary;

    private InputStream bundle() {
        InputStream in = SaoNeoForgeBundle.class.getResourceAsStream(SaoNeoForgeBundle.RESOURCE);
        assertNotNull(in, "the launcher must carry the NeoForge bundle");
        return in;
    }

    @Test void unpacksVerifiedAndIsIdempotent() throws Exception {
        Path libraries = temporary.resolve("libraries"), cache = temporary.resolve("cache");
        SaoNeoForgeBundle.Result first;
        try (InputStream in = bundle()) { first = SaoNeoForgeBundle.unpack(in, libraries, cache); }
        assertEquals(73, first.placed());
        assertNotNull(first.installer());
        assertTrue(Files.size(first.installer()) > 7_000_000);
        assertTrue(Files.isRegularFile(libraries.resolve("net/minecraftforge/binarypatcher/1.1.1/binarypatcher-1.1.1.jar")));
        SaoNeoForgeBundle.Result again;
        try (InputStream in = bundle()) { again = SaoNeoForgeBundle.unpack(in, libraries, cache); }
        assertEquals(0, again.placed()); assertEquals(73, again.present());
        Path damaged = libraries.resolve("com/google/code/gson/gson/2.8.7/gson-2.8.7.jar");
        Files.write(damaged, new byte[] {1, 2, 3});
        SaoNeoForgeBundle.Result repaired;
        try (InputStream in = bundle()) { repaired = SaoNeoForgeBundle.unpack(in, libraries, cache); }
        assertEquals(1, repaired.placed());
        assertTrue(Files.size(damaged) > 100_000);
        try (var leftovers = Files.walk(libraries)) { assertTrue(leftovers.noneMatch(p -> p.toString().endsWith(".part"))); }
    }
}
