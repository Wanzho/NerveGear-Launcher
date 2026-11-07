/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/// Owner-only, short-lived handoff for the future client login-query handler; never a process argument.
@NotNullByDefault
final class SaoOathLaunchTicket implements AutoCloseable {
    /// Fixed instance-relative filename; the client deletes it immediately after reading.
    static final String FILE_NAME = ".sao-launch-ticket.json";
    /// Exact file created for this launch.
    private final Path path;
    /// Used to avoid deleting a later launch's replacement file.
    private final byte[] expected;

    /// Creates the private file before process construction and schedules deletion at the server's expiry.
    SaoOathLaunchTicket(Path instance, String api, String profileId, String server, JsonObject reply) throws IOException, SaoAccountException {
        this(instance,instance,api,profileId,server,reply);
    }

    /// Hardens the launcher-owned ancestor chain before writing any proof bytes; ACL changes remain in place.
    SaoOathLaunchTicket(Path launcherRoot,Path instance, String api, String profileId, String server, JsonObject reply) throws IOException, SaoAccountException {
        String ticket;
        String issuedProfile;
        long expires;
        try {
            ticket = reply.get("launchTicket").getAsString(); expires = reply.get("expiresAtMillis").getAsLong();
            issuedProfile = reply.get("id").getAsString();
        }
        catch (RuntimeException invalid) { throw new SaoAccountException("启动凭证无效", "invalidTicket"); }
        long remaining = expires - System.currentTimeMillis();
        if (!ticket.matches("[A-Za-z0-9_-]{43}") || remaining <= 0 || remaining > 600_000
                || !profileId.equalsIgnoreCase(issuedProfile)) throw new SaoAccountException("启动凭证已过期，请重试", "invalidTicket");
        Logger.registerAccessToken(ticket);
        JsonObject file = new JsonObject(); file.addProperty("version", 1); file.addProperty("apiRoot", api);
        file.addProperty("profileId", profileId); file.addProperty("targetServer", server);
        file.addProperty("ticket", ticket); file.addProperty("expiresAtMillis", expires);
        expected = file.toString().getBytes(StandardCharsets.UTF_8);
        path = instance.resolve(FILE_NAME);
        SaoUninstaller.rejectSymlinkAncestors(path);
        SaoWindowsPrivatePaths.prepare(launcherRoot,instance);
        Files.createDirectories(instance);
        Path temporary = Files.getFileStore(instance).supportsFileAttributeView(PosixFileAttributeView.class)
                ? Files.createTempFile(instance, ".sao-ticket-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(instance, ".sao-ticket-", ".tmp");
        try {
            ownerOnly(temporary);
            Files.write(temporary, expected, StandardOpenOption.TRUNCATE_EXISTING);
            SaoPackFiles.atomicMove(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
        CompletableFuture.delayedExecutor(remaining, TimeUnit.MILLISECONDS).execute(() -> { try { close(); } catch (IOException ignored) { } });
    }

    /// Restricts POSIX access to 0600 or installs an owner-only Windows ACL; fails if neither is supported.
    private static void ownerOnly(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); return;
        }
        if (!com.sun.jna.Platform.isWindows()) throw new IOException("无法保护启动凭证");
        SaoWindowsPrivatePaths.protect(file,false);
    }

    /// Deletes this launch's leftover ticket on process exit/failure/expiry without following links.
    @Override public synchronized void close() throws IOException {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == expected.length) {
            byte[] actual = Files.readAllBytes(path);
            try { if (java.security.MessageDigest.isEqual(expected, actual)) Files.deleteIfExists(path); }
            finally { Arrays.fill(actual, (byte) 0); }
        }
        Arrays.fill(expected, (byte) 0);
    }
}
