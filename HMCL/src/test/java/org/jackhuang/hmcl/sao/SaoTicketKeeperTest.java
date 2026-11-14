/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// Rejoin tickets: a used or expiring ticket is replaced while the game runs; sign-out and exit stop it.
final class SaoTicketKeeperTest {
    @TempDir Path temporary;

    private SaoOathLaunchTicket issue(Path instance, long lifetime) throws Exception {
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        JsonObject reply = new JsonObject();
        reply.addProperty("launchTicket", Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        reply.addProperty("expiresAtMillis", System.currentTimeMillis() + lifetime); reply.addProperty("id", "a".repeat(32));
        return new SaoOathLaunchTicket(instance, "http://127.0.0.1/", "a".repeat(32), "localhost", reply);
    }

    @Test void usedTicketIsReplacedAndUnusedOneIsLeftAlone() throws Exception {
        Path instance = Files.createDirectory(temporary.toRealPath().resolve("used"));
        Path file = instance.resolve(SaoOathLaunchTicket.FILE_NAME);
        AtomicLong now = new AtomicLong(1_000_000); AtomicInteger issued = new AtomicInteger();
        SaoTicketKeeper keeper = new SaoTicketKeeper(issue(instance, 300_000),
                () -> { issued.incrementAndGet(); return issue(instance, 300_000); }, () -> true, now::get);
        now.addAndGet(10_000);
        assertFalse(keeper.tick(), "an unclaimed, fresh ticket stays");
        assertEquals(0, issued.get());
        String first = Files.readString(file);
        Files.delete(file); // the game took it for a join
        assertTrue(keeper.tick());
        assertTrue(Files.exists(file)); assertNotEquals(first, Files.readString(file));
        Files.delete(file);
        assertFalse(keeper.tick(), "no second request within the minimum gap");
        now.addAndGet(SaoTicketKeeper.MIN_GAP_MILLIS);
        assertTrue(keeper.tick());
        assertEquals(2, issued.get());
        keeper.close();
        assertFalse(Files.exists(file), "closing removes the last unclaimed file");
    }

    @Test void expiringTicketIsRenewed() throws Exception {
        Path instance = Files.createDirectory(temporary.toRealPath().resolve("expiring"));
        AtomicInteger issued = new AtomicInteger();
        SaoOathLaunchTicket first = issue(instance, 300_000);
        AtomicLong now = new AtomicLong(first.expiresAt() - 200_000);
        SaoTicketKeeper keeper = new SaoTicketKeeper(first, () -> { issued.incrementAndGet(); return issue(instance, 300_000); }, () -> true, now::get);
        now.set(first.expiresAt() - SaoTicketKeeper.RENEW_BEFORE_MILLIS - 1);
        assertFalse(keeper.tick(), "not yet");
        now.set(first.expiresAt() - SaoTicketKeeper.RENEW_BEFORE_MILLIS + 1);
        assertTrue(keeper.tick());
        assertEquals(1, issued.get());
        keeper.close();
    }

    @Test void signOutAndGameExitStop() throws Exception {
        Path instance = Files.createDirectory(temporary.toRealPath().resolve("stop"));
        Path file = instance.resolve(SaoOathLaunchTicket.FILE_NAME);
        AtomicLong now = new AtomicLong(1_000_000);
        SaoTicketKeeper signedOut = new SaoTicketKeeper(issue(instance, 300_000),
                () -> { throw new SaoAccountException("请重新登录 ARGUS ID", "invalidSession"); }, () -> true, now::get);
        Files.delete(file); now.addAndGet(10_000);
        assertFalse(signedOut.tick()); assertTrue(signedOut.closed());

        AtomicBoolean alive = new AtomicBoolean(true);
        SaoTicketKeeper exit = new SaoTicketKeeper(issue(instance, 300_000), () -> issue(instance, 300_000), alive::get, now::get);
        alive.set(false);
        assertFalse(exit.tick()); assertTrue(exit.closed()); assertFalse(Files.exists(file));
    }

    @Test void offlineRetriesLater() throws Exception {
        Path instance = Files.createDirectory(temporary.toRealPath().resolve("offline"));
        Path file = instance.resolve(SaoOathLaunchTicket.FILE_NAME);
        AtomicLong now = new AtomicLong(1_000_000); AtomicBoolean online = new AtomicBoolean(false);
        SaoTicketKeeper keeper = new SaoTicketKeeper(issue(instance, 300_000), () -> {
            if (!online.get()) throw new SaoAccountException("网络连接失败", "network");
            return issue(instance, 300_000);
        }, () -> true, now::get);
        Files.delete(file); now.addAndGet(10_000);
        assertFalse(keeper.tick()); assertFalse(keeper.closed());
        online.set(true); now.addAndGet(5_000);
        assertFalse(keeper.tick(), "waits out the retry delay");
        now.addAndGet(SaoTicketKeeper.RETRY_MILLIS);
        assertTrue(keeper.tick()); assertTrue(Files.exists(file));
        keeper.close();
    }
}
