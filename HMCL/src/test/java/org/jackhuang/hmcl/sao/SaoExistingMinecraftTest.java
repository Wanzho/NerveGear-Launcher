/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/// Reusing another launcher's .minecraft (15 Nov): verified copies only, the other folder never touched.
final class SaoExistingMinecraftTest {
    @TempDir Path temporary;

    private static String sha1(byte[] b) throws Exception { return DigestUtils.digestToString("SHA-1", b); }

    @Test void copiesVerifiedFilesAndLeavesTheOtherFolderAlone() throws Exception {
        Path other = Files.createDirectories(temporary.resolve("pcl/.minecraft"));
        byte[] asset = "a sound".getBytes(), jar = "client jar".getBytes();
        String assetHash = sha1(asset), jarHash = sha1(jar);
        Path assetFile = other.resolve("assets/objects/" + assetHash.substring(0, 2) + "/" + assetHash);
        Files.createDirectories(assetFile.getParent()); Files.write(assetFile, asset);
        Path jarFile = other.resolve("versions/1.20.1/1.20.1.jar");
        Files.createDirectories(jarFile.getParent()); Files.write(jarFile, jar);
        long assetTime = Files.getLastModifiedTime(assetFile).toMillis();

        SaoExistingMinecraft.Cache cache = new SaoExistingMinecraft.Cache(temporary.resolve("own"), () -> List.of(other));
        Optional<Path> a = cache.checkExistentFile(null, CacheRepository.SHA1, assetHash);
        assertTrue(a.isPresent());
        assertFalse(a.get().startsWith(other), "a copy in our cache, never the other launcher's file");
        assertArrayEquals(asset, Files.readAllBytes(a.get()));
        Optional<Path> j = cache.checkExistentFile(null, CacheRepository.SHA1, jarHash);
        assertTrue(j.isPresent());
        assertEquals(2, cache.reused());

        // The other folder is exactly as it was: same bytes, same time, not a link.
        assertArrayEquals(asset, Files.readAllBytes(assetFile));
        assertEquals(assetTime, Files.getLastModifiedTime(assetFile).toMillis());
        assertEquals(1, (int) Files.getAttribute(assetFile, "unix:nlink"));

        // A file whose content does not match its expected hash is ignored.
        assertTrue(cache.checkExistentFile(null, CacheRepository.SHA1, sha1("something else".getBytes())).isEmpty());
    }

    @Test void readsPclAndHmclFolderLists() throws Exception {
        String reg = "\r\nHKEY_CURRENT_USER\\Software\\PCL\r\n    LaunchFolders    REG_SZ    主文件夹>D:\\Games\\.minecraft\\|整合包>E:\\MC\\pack\\.minecraft\\|\r\n";
        assertEquals(List.of(Path.of("D:\\Games\\.minecraft\\"), Path.of("E:\\MC\\pack\\.minecraft\\")), SaoExistingMinecraft.parsePclFolders(reg));
        Path settings = temporary.resolve("hmcl.json");
        Files.writeString(settings, "{\"configurations\":{\"默认\":{\"gameDir\":\".minecraft\"},\"x\":{\"gameDir\":\"/abs/mc\"}}}");
        assertEquals(List.of(temporary.resolve(".minecraft"), Path.of("/abs/mc")), SaoExistingMinecraft.hmclFolders(settings));
    }
}
