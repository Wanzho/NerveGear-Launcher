/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/// Filesystem-only release signing and verification, dispatched before launcher setup or any graphical toolkit.
@NotNullByDefault
public final class SaoReleaseTool {
    /// Utility class.
    private SaoReleaseTool() { }

    /// Prints OK/FAIL and the authenticated sequence; failures produce exit status one without a graphical error.
    public static void main(String @Unmodifiable [] args) {
        System.setProperty("java.awt.headless", "true");
        int status = run(args, System.out, SaoReleaseSignatures.PRODUCTION);
        if (status != 0) System.exit(status);
    }

    /// Implements explicit sign/verify commands; injected public keys are accessible only to package-local throwaway tests.
    static int run(String @Unmodifiable [] args, PrintStream output, SaoReleaseSignatures trust) {
        try {
            if (args.length == 2 && args[0].equals("--verify-release")) {
                Path file = Path.of(args[1]);
                byte @Unmodifiable [] document = SaoReleaseState.bounded(file, SaoReleaseSignatures.MAX_NEWS_BYTES);
                byte @Unmodifiable [] signature = SaoReleaseState.bounded(signature(file), SaoReleaseSignatures.MAX_SIGNATURE_BYTES);
                trust.verify(document, signature);
                output.println(summary(document)); return 0;
            }
            if ((args.length != 4 && args.length != 5) || !args[0].equals("--sign-release") || !args[2].equals("--key")
                    || args.length == 5 && !args[4].equals("--force"))
                throw new IOException("Usage: --sign-release FILE --key PEM [--force] | --verify-release FILE");
            Path file = Path.of(args[1]), target = signature(file);
            boolean force = args.length == 5;
            if (!force && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Release signature already exists");
            byte @Unmodifiable [] document = SaoReleaseState.bounded(file, SaoReleaseSignatures.MAX_NEWS_BYTES);
            long sequence = sequence(document);
            // Read the PKCS#8 PEM once, only after arguments, schema and overwrite checks have succeeded.
            byte[] pemBytes = SaoReleaseState.bounded(Path.of(args[3]), 16384);
            byte[] privateBytes;
            try {
                String pem = new String(pemBytes, StandardCharsets.US_ASCII);
                String header = "-----BEGIN PRIVATE KEY-----", footer = "-----END PRIVATE KEY-----";
                String stripped = pem.strip();
                if (!stripped.startsWith(header) || !stripped.endsWith(footer)) throw new IOException("Expected PKCS#8 PRIVATE KEY PEM");
                privateBytes = Base64.getDecoder().decode(stripped.substring(header.length(), stripped.length() - footer.length()).replaceAll("\\s", ""));
            } finally { Arrays.fill(pemBytes, (byte) 0); }
            PrivateKey key;
            try { key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(privateBytes)); }
            finally { Arrays.fill(privateBytes, (byte) 0); }
            Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key); signer.update(document);
            byte @Unmodifiable [] encoded = Base64.getEncoder().encode(signer.sign());
            trust.verify(document, encoded); // A mismatched private key must never publish a detached signature.
            write(target, encoded, force);
            byte @Unmodifiable [] savedDocument = SaoReleaseState.bounded(file, SaoReleaseSignatures.MAX_NEWS_BYTES);
            byte @Unmodifiable [] savedSignature = SaoReleaseState.bounded(target, SaoReleaseSignatures.MAX_SIGNATURE_BYTES);
            trust.verify(savedDocument, savedSignature);
            if (!Arrays.equals(document, savedDocument) || sequence(savedDocument) != sequence)
                throw new IOException("Release document changed during signing");
            output.println(summary(savedDocument)); return 0;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            output.println("FAIL sequence=unknown"); return 1;
        }
    }

    /// Summarizes authenticated policy settings on one line; news has no production-target authority.
    private static String summary(byte @Unmodifiable [] bytes) throws IOException {
        String document = SaoReleaseSignatures.utf8(bytes);
        if (SaoReleaseSignatures.object(document).has("news"))
            return "OK sequence=" + SaoReleaseSignatures.news(document).sequence() + " maintenance=n/a server=n/a accountApi=n/a";
        SaoData.VersionMetadata metadata = SaoUpdates.parse(document);
        return "OK sequence=" + metadata.sequence() + " maintenance=" + (metadata.maintenance() == null ? "off" : "on")
                + " server=" + (metadata.server() == null ? "mc.saooath.com" : metadata.server())
                + " accountApi=" + (metadata.accountApi() == null ? SaoAccountClient.DEFAULT_API : metadata.accountApi());
    }

    /// Appends .sig to the complete document filename, retaining its exact parent directory.
    private static Path signature(Path document) { return document.resolveSibling(document.getFileName() + ".sig"); }

    /// Validates either supported release schema, without accepting an arbitrary signed JSON document.
    private static long sequence(byte @Unmodifiable [] bytes) throws IOException {
        String document = SaoReleaseSignatures.utf8(bytes);
        return SaoReleaseSignatures.object(document).has("news")
                ? SaoReleaseSignatures.news(document).sequence() : SaoUpdates.parse(document).sequence();
    }

    /// Flushes a sibling temporary signature before moving it into place; absent --force never replaces an existing path.
    private static void write(Path target, byte @Unmodifiable [] encoded, boolean force) throws IOException {
        Path absolute = target.toAbsolutePath().normalize(), parent = Objects.requireNonNull(absolute.getParent());
        Path temporary = Files.createTempFile(parent, ".release-signature-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (force) Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, absolute);
        } finally { Files.deleteIfExists(temporary); }
    }
}
