/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Approved source paths cannot pull matching assets or clients from outside through symbolic links.
@NotNullByDefault
final class SaoReuseSymlinkTest {
    /// Isolated source, outside target and local cache.
    @TempDir Path temporary;

    /// Intermediate asset and versions links are rejected even when the outside content has the expected hash.
    @Test void rejectsOutsideTargetsReachedThroughDirectoryLinks() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        byte[] asset = "external asset".getBytes(StandardCharsets.UTF_8);
        byte[] client = "external client".getBytes(StandardCharsets.UTF_8);
        String assetHash = DigestUtils.digestToString(CacheRepository.SHA1, asset);
        String clientHash = DigestUtils.digestToString(CacheRepository.SHA1, client);
        Path objects = Files.createDirectories(outside.resolve("objects/" + assetHash.substring(0, 2)));
        Files.write(objects.resolve(assetHash), asset);
        Path version = Files.createDirectories(outside.resolve("versions/custom"));
        Files.write(version.resolve("custom.jar"), client);
        Files.createDirectories(source.resolve("assets"));
        try {
            Files.createSymbolicLink(source.resolve("assets/objects"), outside.resolve("objects"));
            Files.createSymbolicLink(source.resolve("versions"), outside.resolve("versions"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            assumeTrue(false, "symbolic links unavailable: " + unsupported.getClass().getSimpleName());
        }
        var cache = new SaoExistingMinecraft.Cache(temporary.resolve("own/dependencies"), () -> List.of(source));
        assertTrue(cache.checkExistentFile(null, CacheRepository.SHA1, assetHash).isEmpty());
        assertTrue(cache.checkExistentFile(null, CacheRepository.SHA1, clientHash).isEmpty());
        assertArrayEquals(asset, Files.readAllBytes(objects.resolve(assetHash)));
        assertArrayEquals(client, Files.readAllBytes(version.resolve("custom.jar")));
    }
}
