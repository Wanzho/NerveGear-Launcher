/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.sun.jna.Library;
import com.sun.jna.Structure;
import com.sun.jna.Memory;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import org.jetbrains.annotations.Unmodifiable;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import org.jackhuang.hmcl.setting.SaoAccountProtection;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/// Remembers both name and password in a native secret store when available, otherwise HMCL account-private protection.
/// The owner-only fallback uses the existing portable application-key envelope and never honors plaintext development mode.
@NotNullByDefault
final class SaoOathCredentialStore implements SaoOathCredentials {
    /// Serialized access prevents temporary Keychain interaction policy changes from overlapping.
    private static final Object KEYCHAIN_LOCK = new Object();
    /// Native service namespace; distinct roots use independently hashed account identifiers.
    private static final byte @Unmodifiable [] SERVICE = "SAO Oath Launcher".getBytes(StandardCharsets.UTF_8);
    /// Maximum protected payload, bounded before any native allocation or file read.
    private static final int LIMIT = 65536;
    /// Owned private directory, with no plaintext account metadata.
    private final Path directory;
    /// Native key lookup identifier derived only from the data-root path.
    private final byte @Unmodifiable [] keyId;
    /// Native store when enabled and noninteractively available.
    private final @Nullable Keychain keychain;
    /// Windows native generic-credential adapter, loaded only on Windows.
    private final @Nullable WindowsCredentials windows;
    /// Linux secret-tool binary when Secret Service is available and its default collection is unlocked.
    private final @Nullable Path secretTool;
    /// Selected provider label for tests and diagnostics.
    private volatile String backend;

    /// Selects native Keychain without showing prompts; tests may force the private encrypted fallback.
    SaoOathCredentialStore(Path root, boolean nativeStore) throws IOException {
        directory = root.toAbsolutePath().normalize().resolve("oath-private");
        try { keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII); }
        catch (java.security.GeneralSecurityException impossible) { throw new AssertionError(impossible); }
        @Nullable Keychain selected = null;
        if (nativeStore && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            try { selected = Native.load("Security", Keychain.class); }
            catch (LinkageError | RuntimeException unavailable) { }
        }
        keychain = selected;
        @Nullable WindowsCredentials selectedWindows = null;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (nativeStore && os.contains("win")) try { selectedWindows = Native.load("Advapi32", WindowsCredentials.class); }
        catch (LinkageError | RuntimeException unavailable) { }
        windows = selectedWindows;
        @Nullable Path selectedTool = null;
        if (nativeStore && os.contains("linux") && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null) {
            for (Path candidate : List.of(Path.of("/usr/bin/secret-tool"), Path.of("/bin/secret-tool")))
                if (Files.isExecutable(candidate) && unlockedSecretService()) { selectedTool = candidate; break; }
        }
        secretTool = selectedTool;
        backend = selected != null ? "macos-keychain" : windows != null ? "windows-credential-manager"
                : secretTool != null ? "linux-secret-service" : "hmcl-account-private";
    }

    /// Returns only a provider label; credentials and account identifiers are never diagnostics.
    @Override public String backend() { return backend; }

    /// Decrypts the native item or fallback envelope; corrupt payloads fail closed without deletion.
    @Override public synchronized @Nullable Credential read() throws IOException {
        SaoUninstaller.rejectLinksBelowRoot(directory);
        if (Files.exists(directory.resolve("signed-out"), LinkOption.NOFOLLOW_LINKS)) return null;
        byte @Nullable [] bytes = null;
        try {
            if (Files.exists(directory.resolve("credential.bin"), LinkOption.NOFOLLOW_LINKS)) {
                byte[] envelope = readBounded("credential.bin");
                try { bytes = SaoAccountProtection.reveal(envelope); }
                finally { Arrays.fill(envelope, (byte) 0); }
                backend = "hmcl-account-private";
            } else if (hasNative()) {
                try { bytes = readNative(); }
                catch (IOException unavailable) { backend = "hmcl-account-private"; }
            }
            if (bytes == null) return null;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                String name = input.readUTF();
                int length = input.readInt();
                if (!name.matches("[A-Za-z0-9_]{3,16}") || length < 1 || length > 4096 || input.available() != length * 2)
                    throw new IOException("受保护的账号数据无效");
                char[] password = new char[length];
                for (int i = 0; i < length; i++) password[i] = input.readChar();
                return new Credential(name, password);
            }
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    /// Stores one protected pair, preferring a noninteractive Keychain update over private encrypted fallback.
    @Override public synchronized void write(String name, char[] password) throws IOException {
        if (!name.matches("[A-Za-z0-9_]{3,16}") || password.length < 1 || password.length > 4096)
            throw new IOException("账号或密码长度无效");
        byte[] encodedName = name.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(2 + encodedName.length + 4 + password.length * 2);
        buffer.putShort((short) encodedName.length).put(encodedName).putInt(password.length);
        for (char value : password) buffer.putChar(value);
        byte[] bytes = buffer.array();
        try {
            if (hasNative()) {
                try {
                    writeNative(bytes);
                    checkedDirectory();
                    Files.deleteIfExists(directory.resolve("credential.bin"));
                    Files.deleteIfExists(directory.resolve("signed-out"));
                    backend = keychain != null ? "macos-keychain" : windows != null ? "windows-credential-manager" : "linux-secret-service";
                    return;
                } catch (IOException unavailable) { backend = "hmcl-account-private"; }
            }
            byte[] envelope = SaoAccountProtection.protect(bytes);
            try { writePrivate("credential.bin", envelope); Files.deleteIfExists(directory.resolve("signed-out")); }
            finally { Arrays.fill(envelope, (byte) 0); }
        } finally { Arrays.fill(bytes, (byte) 0); Arrays.fill(encodedName, (byte) 0); }
    }

    /// Disables remembered login durably before deleting copies; native cleanup failures still prevent uninstall completion.
    @Override public synchronized void delete() throws IOException {
        writePrivate("signed-out", new byte[0]);
        @Nullable IOException nativeFailure = null;
        if (hasNative()) {
            try { deleteNative(); }
            catch (IOException failure) { nativeFailure = failure; }
        }
        checkedDirectory();
        Files.deleteIfExists(directory.resolve("credential.bin"));
        if (nativeFailure != null) throw nativeFailure;
    }

    /// Creates/rechecks owner-only storage while rejecting symlink ancestors and secret-file aliases.
    private void checkedDirectory() throws IOException {
        SaoUninstaller.rejectLinksBelowRoot(directory);
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException windows) { }
        for (String name : new String[]{"credential.bin", "signed-out"})
            if (Files.isSymbolicLink(directory.resolve(name))) throw new IOException("账号存储路径包含链接");
    }

    /// Reads a bounded ordinary secret file without following links.
    private byte[] readBounded(String name) throws IOException {
        checkedDirectory();
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT) throw new IOException("账号存储文件无效");
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IOException("账号存储文件过大");
            return bytes;
        }
    }

    /// Atomically persists the existing HMCL protected envelope, with owner-only permissions.
    private void writePrivate(String name, byte[] bytes) throws IOException {
        checkedDirectory();
        Path temporary = Files.createTempFile(directory, "private-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException windows) { }
            Files.write(temporary, bytes);
            SaoPackFiles.atomicMove(temporary, directory.resolve(name));
        } finally { Files.deleteIfExists(temporary); }
    }

    /// Finds one native item while explicitly disabling all Keychain interaction.
    private byte @Nullable [] nativeRead() throws IOException {
        synchronized (KEYCHAIN_LOCK) {
            Keychain api = java.util.Objects.requireNonNull(keychain);
            IntByReference previous = new IntByReference();
            if (api.SecKeychainGetUserInteractionAllowed(previous) != 0 || api.SecKeychainSetUserInteractionAllowed(0) != 0)
                throw new IOException("无法关闭系统钥匙串交互");
            PointerByReference data = new PointerByReference(); IntByReference length = new IntByReference();
            try {
                int result = api.SecKeychainFindGenericPassword(null, SERVICE.length, SERVICE, keyId.length, keyId, length, data, null);
                if (result == -25300) return null;
                if (result != 0 || length.getValue() < 0 || length.getValue() > LIMIT) throw new IOException("系统钥匙串暂不可用");
                return data.getValue().getByteArray(0, length.getValue());
            } finally {
                if (data.getValue() != null) api.SecKeychainItemFreeContent(null, data.getValue());
                api.SecKeychainSetUserInteractionAllowed(previous.getValue());
            }
        }
    }

    /// Updates an existing native item without delete-before-add loss, or creates a new item.
    private void nativeWrite(byte[] bytes) throws IOException {
        synchronized (KEYCHAIN_LOCK) {
            Keychain api = java.util.Objects.requireNonNull(keychain);
            IntByReference previous = new IntByReference();
            if (api.SecKeychainGetUserInteractionAllowed(previous) != 0 || api.SecKeychainSetUserInteractionAllowed(0) != 0)
                throw new IOException("无法关闭系统钥匙串交互");
            PointerByReference item = new PointerByReference();
            try {
                int result = api.SecKeychainFindGenericPassword(null, SERVICE.length, SERVICE, keyId.length, keyId, null, null, item);
                if (result == 0) result = api.SecKeychainItemModifyAttributesAndData(item.getValue(), null, bytes.length, bytes);
                else if (result == -25300) result = api.SecKeychainAddGenericPassword(null, SERVICE.length, SERVICE, keyId.length, keyId, bytes.length, bytes, null);
                if (result != 0) throw new IOException("系统钥匙串暂不可用");
            } finally {
                if (item.getValue() != null) Native.load("CoreFoundation", CoreFoundation.class).CFRelease(item.getValue());
                api.SecKeychainSetUserInteractionAllowed(previous.getValue());
            }
        }
    }

    /// Deletes the scoped native item, treating absence as successful removal.
    private void nativeDelete() throws IOException {
        synchronized (KEYCHAIN_LOCK) {
            Keychain api = java.util.Objects.requireNonNull(keychain);
            IntByReference previous = new IntByReference();
            if (api.SecKeychainGetUserInteractionAllowed(previous) != 0 || api.SecKeychainSetUserInteractionAllowed(0) != 0)
                throw new IOException("无法关闭系统钥匙串交互");
            PointerByReference item = new PointerByReference();
            try {
                int result = api.SecKeychainFindGenericPassword(null, SERVICE.length, SERVICE, keyId.length, keyId, null, null, item);
                if (result == -25300) return;
                if (result != 0 || api.SecKeychainItemDelete(item.getValue()) != 0) throw new IOException("无法移除系统钥匙串账号");
            } finally {
                if (item.getValue() != null) Native.load("CoreFoundation", CoreFoundation.class).CFRelease(item.getValue());
                api.SecKeychainSetUserInteractionAllowed(previous.getValue());
            }
        }
    }

    /// Whether this platform exposes a noninteractive native store.
    private boolean hasNative() { return keychain != null || windows != null || secretTool != null; }

    /// Reads the native secret, without child-process arguments containing either account field.
    private byte @Nullable [] readNative() throws IOException {
        if (keychain != null) return nativeRead();
        if (windows != null) {
            PointerByReference result = new PointerByReference();
            if (!windows.CredReadW(target(), 1, 0, result)) {
                if (Native.getLastError() == 1168) return null;
                throw new IOException("系统凭据管理器暂不可用");
            }
            try {
                WindowsCredential credential = new WindowsCredential(result.getValue());
                if (credential.blobSize < 1 || credential.blobSize > LIMIT || credential.blob == null) throw new IOException("系统凭据数据无效");
                return credential.blob.getByteArray(0, credential.blobSize);
            } finally { windows.CredFree(result.getValue()); }
        }
        byte[] encoded = secretCommand(List.of("lookup", "service", "sao-oath-launcher", "root", new String(keyId, StandardCharsets.US_ASCII)), null, true);
        if (encoded.length == 0) return null;
        try { return Base64.getDecoder().decode(new String(encoded, StandardCharsets.US_ASCII).strip()); }
        catch (IllegalArgumentException failure) { throw new IOException("系统密钥环数据无效"); }
        finally { Arrays.fill(encoded, (byte) 0); }
    }

    /// Writes native payload bytes through the platform API or a pipe, never a process argument.
    private void writeNative(byte[] bytes) throws IOException {
        if (keychain != null) { nativeWrite(bytes); return; }
        if (windows != null) {
            try (Memory blob = new Memory(bytes.length)) {
                blob.write(0, bytes, 0, bytes.length);
                WindowsCredential credential = new WindowsCredential(); credential.type = 1;
                credential.target = target(); credential.blobSize = bytes.length; credential.blob = blob; credential.persist = 2;
                credential.username = new WString("SAO Oath Launcher"); credential.write();
                if (!windows.CredWriteW(credential, 0)) throw new IOException("系统凭据管理器暂不可用");
                blob.clear();
            }
            return;
        }
        byte[] encoded = Base64.getEncoder().encode(bytes);
        try { secretCommand(List.of("store", "--label=SAO Oath Launcher", "service", "sao-oath-launcher", "root", new String(keyId, StandardCharsets.US_ASCII)), encoded, false); }
        finally { Arrays.fill(encoded, (byte) 0); }
    }

    /// Deletes the root-scoped native item; a failure is propagated to explicit forgetting and uninstall.
    private void deleteNative() throws IOException {
        if (keychain != null) { nativeDelete(); return; }
        if (windows != null) {
            if (!windows.CredDeleteW(target(), 1, 0) && Native.getLastError() != 1168) throw new IOException("无法移除系统凭据");
            return;
        }
        secretCommand(List.of("clear", "service", "sao-oath-launcher", "root", new String(keyId, StandardCharsets.US_ASCII)), null, true);
    }

    /// Constructs an opaque native target without the remembered player name.
    private WString target() { return new WString("SAO-Oath-Launcher/" + new String(keyId, StandardCharsets.US_ASCII)); }

    /// Uses Secret Service only if its existing default collection explicitly reports unlocked, avoiding unlock prompts.
    private static boolean unlockedSecretService() {
        Path gdbus = Path.of("/usr/bin/gdbus");
        if (!Files.isExecutable(gdbus)) return false;
        try {
            Process alias = new ProcessBuilder(gdbus.toString(), "call", "--session", "--dest", "org.freedesktop.secrets",
                    "--object-path", "/org/freedesktop/secrets", "--method", "org.freedesktop.Secret.Service.ReadAlias", "default")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!alias.waitFor(2, TimeUnit.SECONDS)) { alias.destroyForcibly(); return false; }
            String reply = new String(alias.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            var match = java.util.regex.Pattern.compile("'(/org/freedesktop/secrets/collection/[A-Za-z0-9_]+)'").matcher(reply);
            if (!match.find()) return false;
            Process locked = new ProcessBuilder(gdbus.toString(), "call", "--session", "--dest", "org.freedesktop.secrets",
                    "--object-path", match.group(1), "--method", "org.freedesktop.DBus.Properties.Get",
                    "org.freedesktop.Secret.Collection", "Locked").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!locked.waitFor(2, TimeUnit.SECONDS)) { locked.destroyForcibly(); return false; }
            return locked.exitValue() == 0 && new String(locked.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).contains("false");
        } catch (IOException failure) { return false; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
    }

    /// Runs a bounded native secret helper; only public service/root selectors enter argv and diagnostics are discarded.
    private byte[] secretCommand(List<String> arguments, byte @Nullable [] input, boolean absentAllowed) throws IOException {
        if (!unlockedSecretService()) throw new IOException("系统密钥环已锁定");
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(java.util.Objects.requireNonNull(secretTool).toString()); command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (OutputStream output = process.getOutputStream()) { if (input != null) output.write(input); }
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("系统密钥环响应超时");
            if (process.exitValue() != 0 && !(absentAllowed && process.exitValue() == 1)) throw new IOException("系统密钥环暂不可用");
            byte[] result = process.getInputStream().readNBytes(LIMIT + 1);
            if (result.length > LIMIT) throw new IOException("系统密钥环数据过大");
            return result;
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("系统密钥环操作已取消"); }
        finally { process.destroyForcibly(); }
    }

    /// Windows generic-credential layout; JNA supplies native pointer alignment on 32-bit and 64-bit Windows.
    @Structure.FieldOrder({"flags", "type", "target", "comment", "lastWritten", "blobSize", "blob", "persist", "attributeCount", "attributes", "alias", "username"})
    @NotNullByDefault
    public static final class WindowsCredential extends Structure {
        /// Reserved credential flags.
        public int flags;
        /// CRED_TYPE_GENERIC.
        public int type;
        /// Root-scoped opaque lookup identifier.
        public @Nullable WString target;
        /// No public comment is stored.
        public @Nullable WString comment;
        /// Native FILETIME populated by Windows.
        public long lastWritten;
        /// Exact protected byte count.
        public int blobSize;
        /// Pointer to the secret bytes, valid only during the native call.
        public @Nullable Pointer blob;
        /// CRED_PERSIST_LOCAL_MACHINE, scoped to the current Windows account.
        public int persist;
        /// No extra native attributes.
        public int attributeCount;
        /// Unused native attributes pointer.
        public @Nullable Pointer attributes;
        /// No target alias.
        public @Nullable WString alias;
        /// Public application label, never the player's remembered name.
        public @Nullable WString username;
        /// Creates a writeable native structure.
        public WindowsCredential() { }
        /// Reads a structure allocated by CredReadW.
        public WindowsCredential(Pointer pointer) { super(pointer); read(); }
    }

    /// Native Windows Credential Manager operations, invoked directly with no shell or PowerShell.
    @NotNullByDefault
    interface WindowsCredentials extends StdCallLibrary {
        /// Reads a current-user generic credential.
        boolean CredReadW(WString target, int type, int flags, PointerByReference credential);
        /// Stores a current-user generic credential.
        boolean CredWriteW(WindowsCredential credential, int flags);
        /// Removes the scoped credential.
        boolean CredDeleteW(WString target, int type, int flags);
        /// Frees the native credential allocation.
        void CredFree(Pointer credential);
    }

    /// Minimal native Security framework bindings; no secret ever enters a child-process argument.
    @NotNullByDefault
    interface Keychain extends Library {
        /// Reads the process interaction setting.
        int SecKeychainGetUserInteractionAllowed(IntByReference allowed);
        /// Changes the interaction setting for the bounded operation.
        int SecKeychainSetUserInteractionAllowed(int allowed);
        /// Finds an item or copies its secret data.
        int SecKeychainFindGenericPassword(@Nullable Pointer keychain, int serviceLength, byte[] service, int accountLength, byte[] account,
                                          @Nullable IntByReference length, @Nullable PointerByReference data, @Nullable PointerByReference item);
        /// Creates a generic password item.
        int SecKeychainAddGenericPassword(@Nullable Pointer keychain, int serviceLength, byte[] service, int accountLength, byte[] account,
                                         int length, byte[] data, @Nullable PointerByReference item);
        /// Replaces an item's secret in place.
        int SecKeychainItemModifyAttributesAndData(Pointer item, @Nullable Pointer attributes, int length, byte[] data);
        /// Deletes one scoped item.
        int SecKeychainItemDelete(Pointer item);
        /// Releases a copied secret buffer.
        int SecKeychainItemFreeContent(@Nullable Pointer attributes, Pointer data);
    }

    /// CoreFoundation ownership release for returned Keychain item references.
    @NotNullByDefault
    interface CoreFoundation extends Library {
        /// Balances the retained result returned by Security.framework.
        void CFRelease(Pointer pointer);
    }
}
