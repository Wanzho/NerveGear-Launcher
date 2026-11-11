/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/// Offline provider for the checksum-pinned official authlib-injector 1.2.8 bundled with the launcher.
@NotNullByDefault
final class SaoOathInjector implements AuthlibInjectorArtifactProvider {
    /// SHA-256 verified against the supplied official 1.2.8 release artifact.
    static final String SHA256 = "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10";
    /// Launcher-owned extracted Java agent location.
    private final Path destination;
    /// Last verified artifact, exposed only after checksum verification.
    private @Nullable AuthlibInjectorArtifactInfo artifact;

    /// Creates the provider without extracting files or downloading executable code.
    SaoOathInjector(Path root) { destination = root.resolve("authlib-injector").resolve("authlib-injector-1.2.8.jar"); }

    /// Extracts and verifies the bundled agent atomically, also detecting later on-disk modifications.
    @Override public synchronized AuthlibInjectorArtifactInfo getArtifactInfo() throws IOException {
        SaoUninstaller.rejectLinksBelowRoot(destination);
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !valid(destination)) {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), "agent-", ".tmp");
            try (@Nullable InputStream bundled = SaoOathInjector.class.getResourceAsStream("/assets/sao/auth/authlib-injector-1.2.8.jar")) {
                if (bundled == null) throw new IOException("启动器缺少账号验证组件");
                Files.copy(bundled, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!valid(temporary)) throw new IOException("账号验证组件校验失败");
                SaoPackFiles.atomicMove(temporary, destination);
            } finally { Files.deleteIfExists(temporary); }
        }
        artifact = AuthlibInjectorArtifactInfo.from(destination);
        if (!"1.2.8".equals(artifact.version())) throw new IOException("账号验证组件版本无效");
        return artifact;
    }

    /// Returns an artifact only while its pinned bytes remain intact; offline fallback never downloads another agent.
    @Override public synchronized Optional<AuthlibInjectorArtifactInfo> getArtifactInfoImmediately() {
        try { return Optional.of(getArtifactInfo()); }
        catch (IOException failure) { return Optional.empty(); }
    }

    /// Checks the complete artifact bytes against the fixed release hash.
    private static boolean valid(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return SHA256.equals(HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
