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
    /// Largest local clock error tolerated when checking the server's absolute expiry.
    static final long MAX_CLOCK_SKEW = 24L * 60 * 60 * 1000;
    /// Cleanup delay for a ticket whose expiry cannot be trusted against the local clock; the full 10-minute server
    /// lifetime, so a slow modded-game start can still read the file.
    static final long SKEWED_LIFETIME = 600_000;
    /// Fixed instance-relative filename; the client deletes it immediately after reading.
    static final String FILE_NAME = ".sao-launch-ticket.json";
    /// Exact file created for this launch.
    private final Path path;
    /// Used to avoid deleting a later launch's replacement file.
    private final byte[] expected;
    /// Local-clock time the written file expires; the in-game keeper renews before it.
    private final long expiresAt;

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
        // The ticket was issued a moment ago and the server checks expiry with its own clock, so an expiry outside the
        // normal 10-minute window means the PC clock is off (common when time sync fails). Tolerate up to a day of skew
        // and remove the leftover file after a default lifetime instead.
        boolean skewed = remaining <= 0 || remaining > 600_000;
        if (!ticket.matches("[A-Za-z0-9_-]{43}") || Math.abs(remaining) > MAX_CLOCK_SKEW
                || !profileId.equalsIgnoreCase(issuedProfile)) throw new SaoAccountException("启动凭证已过期，请校准电脑时间后重试", "invalidTicket");
        if (skewed) remaining = SKEWED_LIFETIME;
        expiresAt = System.currentTimeMillis() + remaining;
        Logger.registerAccessToken(ticket);
        JsonObject file = new JsonObject(); file.addProperty("version", 1); file.addProperty("apiRoot", api);
        file.addProperty("profileId", profileId); file.addProperty("targetServer", server);
        // The game mod (packui LaunchTicket.take) checks this expiry against the PC clock and refuses anything outside
        // (now, now + 10 min]. The server checks its own clock and never reads this field, so write the expiry on the
        // PC's clock: identical when the clock is right, and still accepted by the game when it is off (C-3).
        file.addProperty("ticket", ticket); file.addProperty("expiresAtMillis", System.currentTimeMillis() + remaining);
        expected = file.toString().getBytes(StandardCharsets.UTF_8);
        path = instance.resolve(FILE_NAME);
        SaoUninstaller.rejectLinksBelowRoot(path);
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

    /// Local-clock expiry of the written proof.
    long expiresAt() { return expiresAt; }

    /// True while the game has not taken this exact file yet (it deletes the file when it reads it).
    synchronized boolean unclaimed() {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected.length) return false;
            byte[] actual = Files.readAllBytes(path);
            try { return java.security.MessageDigest.isEqual(expected, actual); } finally { Arrays.fill(actual, (byte) 0); }
        } catch (IOException gone) { return false; }
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
