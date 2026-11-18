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
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jackhuang.hmcl.setting.SaoAccountProtection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Headless security boundaries for remembered credentials, bundled executable bytes and sanitized skin storage.
@NotNullByDefault
class SaoOathSecurityTest {
    /// Synthetic paths only; no real account, launcher root or external keychain item is selected.
    @TempDir Path temporary;

    /// Resolves platform temp aliases before exercising production path hardening.
    @org.junit.jupiter.api.BeforeEach void canonicalStorageRoot() throws Exception { temporary = temporary.toRealPath(); }

    /// Both remembered fields survive a store restart only as ciphertext; tampering and forgetting fail closed.
    @Test void encryptedFallbackRoundTripTamperAndForget() throws Exception {
        Path root = temporary.resolve("client");
        String name = "Proof" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String secret = UUID.randomUUID().toString();
        SaoOathCredentialStore store = new SaoOathCredentialStore(root, false);
        assertNull(store.read());
        char[] supplied = secret.toCharArray();
        store.write(name, supplied);
        assertTrue(secret.equals(new String(supplied)), "Store must not retain ownership of caller's array");
        Arrays.fill(supplied, '\0');
        SaoOathCredentials.Credential read = java.util.Objects.requireNonNull(new SaoOathCredentialStore(root, false).read());
        assertTrue(read.name().equals(name));
        assertTrue(secret.equals(new String(read.password())), "Protected password must survive reopening");
        Arrays.fill(read.password(), '\0');
        Path envelope = root.resolve("oath-private/credential.bin");
        byte[] ciphertext = Files.readAllBytes(envelope);
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains(secret));
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains(name));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(envelope));
        ciphertext[ciphertext.length - 1] ^= 1;
        Files.write(envelope, ciphertext);
        assertThrows(IOException.class, store::read);
        store.delete();
        assertNull(store.read());
        assertFalse(Files.exists(envelope));
        assertFalse(Files.exists(root.resolve("oath-private/key.bin")));
    }

    /// Native storage runs without interaction and is scoped to a new synthetic root; unavailable native APIs use encryption.
    @Test void nativePreferenceRoundTripAndExactCleanup() throws Exception {
        Path root = temporary.resolve("native-client");
        String secret = UUID.randomUUID().toString();
        SaoOathCredentialStore store = new SaoOathCredentialStore(root, true);
        try {
            store.write("NativeFixture", secret.toCharArray());
            SaoOathCredentialStore reopened = new SaoOathCredentialStore(root, true);
            SaoOathCredentials.Credential read = java.util.Objects.requireNonNull(reopened.read());
            assertTrue(secret.equals(new String(read.password())), "Native-preferred store must reopen the protected pair");
            Arrays.fill(read.password(), '\0');
            @Nullable String evidence = System.getProperty("sao.account.proof.root");
            if (evidence != null) Files.writeString(Path.of(evidence).resolve("credential-provider.txt"), reopened.backend() + "\n");
        } finally { store.delete(); }
        assertNull(new SaoOathCredentialStore(root, true).read());
    }

    /// A durable signout marker prevents a retained protected copy from causing a later automatic login.
    @Test void signoutMarkerDisablesRetainedEnvelopeUntilExplicitRemember() throws Exception {
        Path root = temporary.resolve("signed-out-client");
        SaoOathCredentialStore store = new SaoOathCredentialStore(root, false);
        store.write("MarkerFixture", UUID.randomUUID().toString().toCharArray());
        Path envelope = root.resolve("oath-private/credential.bin"); byte[] retained = Files.readAllBytes(envelope);
        store.delete(); Files.write(envelope, retained); Arrays.fill(retained, (byte) 0);
        assertNull(new SaoOathCredentialStore(root, false).read());
        store.write("MarkerFixture", UUID.randomUUID().toString().toCharArray());
        SaoOathCredentials.Credential read = java.util.Objects.requireNonNull(new SaoOathCredentialStore(root, false).read());
        Arrays.fill(read.password(), '\0'); assertFalse(Files.exists(root.resolve("oath-private/signed-out"))); store.delete();
    }

    /// A redirected secret directory cannot cause writes or deletes outside the isolated client root.
    @Test void credentialSymlinkRefusalPreservesTarget() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path root = Files.createDirectories(temporary.resolve("client"));
        Path sentinel = Files.writeString(outside.resolve("credential.bin"), "outside-preserved");
        Files.createSymbolicLink(root.resolve("oath-private"), outside);
        SaoOathCredentialStore store = new SaoOathCredentialStore(root, false);
        assertThrows(IOException.class, () -> store.write("ProofFixture", UUID.randomUUID().toString().toCharArray()));
        assertThrows(IOException.class, store::delete);
        assertEquals("outside-preserved", Files.readString(sentinel));
    }

    /// The agent provider restores damaged local bytes exclusively from the pinned bundled release.
    @Test void bundledAgentIsExactAndRepairsWithoutDownload() throws Exception {
        SaoOathInjector provider = new SaoOathInjector(temporary);
        Path agent = provider.getArtifactInfo().location();
        assertEquals("1.2.8", provider.getArtifactInfo().version());
        assertEquals(SaoOathInjector.SHA256, sha256(agent));
        Files.writeString(agent, "damaged-fixture");
        assertEquals(agent, provider.getArtifactInfo().location());
        assertEquals(SaoOathInjector.SHA256, sha256(agent));
    }

    /// HTTP exceptions are limited to numeric loopback and texture requests cannot escape the API origin.
    @Test void accountOriginPolicyAndTextureOriginPolicy() throws Exception {
        for (String endpoint : List.of("http://example.invalid/", "http://192.168.1.1/", "http://localhost/",
                "https://user:secret@example.invalid/", "https://example.invalid/?token=refused", "https://example.invalid/#fragment"))
            assertThrows(IllegalArgumentException.class, () -> SaoAccountClient.checkedRoot(endpoint));
        assertEquals("http://127.0.0.1:47281/", SaoAccountClient.checkedRoot("http://127.0.0.1:47281").toString());
        SaoAccountClient client = new SaoAccountClient(() -> java.net.URI.create("http://127.0.0.1:1/"), () -> "zh-CN");
        assertEquals("zh_cn", client.language());
        assertThrows(SaoAccountException.class, () -> client.texture("https://example.invalid/textures/" + "a".repeat(64)));
        assertThrows(SaoAccountException.class, () -> client.texture("http://127.0.0.1:1/textures/../sao/account"));
        assertThrows(SaoAccountException.class, () -> client.texture("http://127.0.0.1:1/textures/" + "a".repeat(64) + "?token=x"));
    }

    /// A development plaintext setting cannot weaken remembered credentials, and plaintext envelopes are never accepted.
    @Test void rememberedProtectionIgnoresPlaintextDevelopmentMode() throws Exception {
        @Nullable String old = System.getProperty("hmcl.account.privateData.protection");
        String sentinel = UUID.randomUUID().toString();
        try {
            System.setProperty("hmcl.account.privateData.protection", "plain");
            byte[] protectedBytes = SaoAccountProtection.protect(sentinel.getBytes(StandardCharsets.UTF_8));
            assertFalse(new String(protectedBytes, StandardCharsets.UTF_8).contains(sentinel));
            assertTrue(Arrays.equals(sentinel.getBytes(StandardCharsets.UTF_8), SaoAccountProtection.reveal(protectedBytes)));
            assertThrows(IOException.class, () -> SaoAccountProtection.reveal("{\"protection\":\"plain\",\"payload\":\"refused\"}".getBytes(StandardCharsets.UTF_8)));
        } finally {
            if (old == null) System.clearProperty("hmcl.account.privateData.protection");
            else System.setProperty("hmcl.account.privateData.protection", old);
        }
    }

    /// Clearing remember must remove a corrupt saved pair even when no password can be recovered for login.
    @Test void optingOutForgetsUnreadableRememberedCredential() throws Exception {
        java.util.concurrent.atomic.AtomicInteger deleted = new java.util.concurrent.atomic.AtomicInteger();
        SaoOathCredentials credentials = new SaoOathCredentials() {
            /// Models an unreadable protected payload without returning or logging its bytes.
            @Override public @Nullable Credential read() throws IOException { throw new IOException("Unreadable fixture"); }

            /// This failed-login fixture never writes another credential.
            @Override public void write(String name, char[] password) { fail("Unexpected credential write"); }

            /// Records that explicit opt-out removes the unreadable remembered value.
            @Override public void delete() { deleted.incrementAndGet(); }

            /// Keeps provider diagnostics free of account fields.
            @Override public String backend() { return "unreadable-fixture"; }
        };
        try (SaoOathAccounts accounts = new SaoOathAccounts(temporary, () -> java.net.URI.create("http://127.0.0.1:1/"),
                () -> "zh_cn", credentials, () -> { throw new AssertionError("Unexpected Microsoft proof"); },
                account -> fail("Failed login cannot launch"), message -> { })) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> accounts.login("ProofFixture", null, false)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, deleted.get());
        }
    }

    /// The engine preserves mailbox spelling on the wire and never renders an untrusted refusal body.
    @Test void mailboxPassesThroughUnchangedAndRefusalIsCurated() throws Exception {
        String mailbox = "MiXeD-" + UUID.randomUUID() + "@Example.TEST";
        java.util.concurrent.atomic.AtomicBoolean exact = new java.util.concurrent.atomic.AtomicBoolean();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sao/login", exchange -> {
            try {
                com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                exact.set(mailbox.equals(body.get("username").getAsString()));
                byte[] refusal = ("{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"" + mailbox + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(403, refusal.length);
                exchange.getResponseBody().write(refusal);
            } finally { exchange.close(); }
        });
        server.start();
        java.net.URI endpoint = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        char[] password = UUID.randomUUID().toString().toCharArray();
        try (SaoOathAccounts accounts = new SaoOathAccounts(temporary.resolve("mailbox-client"), () -> endpoint,
                () -> "zh_cn", new SaoOathCredentialStore(temporary.resolve("mailbox-client"), false),
                () -> { throw new AssertionError("Unexpected Microsoft proof"); },
                account -> fail("Refused account cannot launch"), message -> { })) {
            java.util.concurrent.ExecutionException refusal = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> accounts.login(mailbox, password, false).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(exact.get(), "Mailbox spelling must reach the account API unchanged");
            assertInstanceOf(SaoAccountException.class, refusal.getCause());
            assertEquals("密码错误", java.util.Objects.requireNonNull(refusal.getCause()).getMessage());
            assertNull(accounts.rememberedName());
            for (char value : password) assertTrue(value == '\0', "Consumed password must be wiped");
        } finally { server.stop(0); }
    }

    /// Metadata is stripped by a fresh encode; dimensions and foreign delete targets are refused before mutation.
    @Test void librarySanitizationValidationAndOwnership() throws Exception {
        String namespace = "a".repeat(64);
        SaoOathSkins store = new SaoOathSkins(temporary, namespace, "b".repeat(32));
        byte[] input = pngWithPrivateMetadata();
        SaoData.SavedSkin skin = store.save("Fixture", input, true);
        assertTrue(skin.slim());
        assertEquals(1, store.list().size());
        assertTrue(Arrays.equals(SaoOathSkins.sanitized(input, false), Files.readAllBytes(skin.file())));
        assertFalse(new String(Files.readAllBytes(skin.file()), StandardCharsets.ISO_8859_1).contains("source-metadata-sentinel"));
        assertThrows(IllegalArgumentException.class, () -> store.save("Legacy", png(64, 32), false));
        assertThrows(IllegalArgumentException.class, () -> store.save("Oversize", png(128, 128), false));
        Path outside = Files.writeString(temporary.resolve("outside.png"), "preserved");
        assertThrows(IOException.class, () -> store.delete(new SaoData.SavedSkin("Foreign", outside, false, 0)));
        assertEquals("preserved", Files.readString(outside));
        store.delete(skin);
        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(skin.file()));
    }

    /// Computes an executable artifact digest without starting that artifact.
    private static String sha256(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }

    /// Encodes a real PNG with specified dimensions for decoder validation.
    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    /// Adds a standards-compliant tEXt chunk before IEND so the saved library must remove actual ancillary metadata.
    private static byte[] pngWithPrivateMetadata() throws IOException {
        byte[] original = png(64, 64);
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] data = "Comment\0source-metadata-sentinel".getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(type); crc.update(data);
        java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put(type).put(data).putInt((int) crc.getValue());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(original, 0, original.length - 12); output.write(chunk.array()); output.write(original, original.length - 12, 12);
        return output.toByteArray();
    }
}
