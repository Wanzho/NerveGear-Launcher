/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/// Shared throwaway signing fixtures; no production private key, endpoint or configuration override is used.
@NotNullByDefault
final class SaoReleaseTestSupport {
    /// Ephemeral test keypair generated once per headless test JVM.
    static final KeyPair KEY = key();
    /// Explicit package-only test trust; production constructors cannot select it.
    static final SaoReleaseSignatures TRUST = new SaoReleaseSignatures(List.of(KEY.getPublic()));

    /// Utility class.
    private SaoReleaseTestSupport() { }

    /// Creates an independent Ed25519 throwaway keypair.
    static KeyPair key() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }

    /// Signs exact UTF-8 fixture bytes with the shared ephemeral private key.
    static byte @Unmodifiable [] sign(String document) throws IOException { return sign(document.getBytes(StandardCharsets.UTF_8), KEY); }

    /// Signs arbitrary exact bytes with an explicitly supplied throwaway key for rotation and mutation regressions.
    static byte @Unmodifiable [] sign(byte @Unmodifiable [] bytes, KeyPair key) throws IOException {
        try {
            Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key.getPrivate()); signature.update(bytes);
            return Base64.getEncoder().encode(signature.sign());
        } catch (GeneralSecurityException failure) { throw new IOException(failure); }
    }

    /// Commits a trusted fixture using the same durable transaction as production fetches.
    static void cacheVersion(Path root, String document) throws IOException { cache(root, document, SaoReleaseState.Kind.VERSION); }

    /// Commits a signed news fixture using its independent high-water mark.
    static void cacheNews(Path root, String document) throws IOException { cache(root, document, SaoReleaseState.Kind.NEWS); }

    /// Reuses production authentication, validation and atomic cache persistence for a specific fixture kind.
    private static void cache(Path root, String document, SaoReleaseState.Kind kind) throws IOException {
        new SaoReleaseState(root, TRUST).accept(kind, document.getBytes(StandardCharsets.UTF_8), sign(document));
    }

    /// Creates a fully inert HTTP seam with explicit throwaway public trust roots.
    static SaoOnlineData online(Path root, SaoOnlineData.Exchange exchange) { return new SaoOnlineData(root, exchange, TRUST); }

    /// Creates a backend that reads only the fixture's ephemeral signed metadata and never performs public refreshes.
    static SaoPackEngine engine(Path root, @Nullable SaoPackArchive supplied, boolean embedded) {
        return new SaoPackEngine(root, supplied, embedded, TRUST);
    }
}
