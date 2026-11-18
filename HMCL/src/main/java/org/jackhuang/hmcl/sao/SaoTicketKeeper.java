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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/// Keeps one fresh launch ticket beside a running game so the player can rejoin from the in-game server list
/// (James, 14 Nov: a dropped or quit connection must not need a game restart).
///
/// Each ticket stays single-use on the server. The game takes and deletes the file on every join; the keeper then
/// asks the server for a new one with the launcher's live ARGUS session, exactly as Link Start does. It also renews a
/// ticket a minute before it expires, so a slow first start or a long session never finds a dead one. It stops when
/// the game exits, the player signs out (the issuer refuses), or a renewal fails; nothing ever reaches the screen.
@NotNullByDefault
final class SaoTicketKeeper implements AutoCloseable {
    /// Issues a fresh owner-only ticket file; throws once the launcher session is gone.
    @FunctionalInterface
    interface Issuer { SaoOathLaunchTicket issue() throws Exception; }

    /// How often the file is checked; a rejoin needs a new file within about this long after the last join.
    static final long CHECK_MILLIS = 1_500;
    /// Renew this long before the written expiry.
    static final long RENEW_BEFORE_MILLIS = 60_000;
    /// Never ask the server more often than this, even if the game burns tickets in a loop.
    static final long MIN_GAP_MILLIS = 3_000;
    /// Wait after a failed renewal (offline, server busy) before trying again.
    static final long RETRY_MILLIS = 30_000;

    private final Issuer issuer;
    private final BooleanSupplier gameAlive;
    private final LongSupplier clock;
    private @Nullable SaoOathLaunchTicket current;
    private long lastIssue;
    private long notBefore;
    private boolean failing;
    private boolean closed;
    private @Nullable ScheduledExecutorService timer;

    SaoTicketKeeper(SaoOathLaunchTicket first, Issuer issuer, BooleanSupplier gameAlive, LongSupplier clock) {
        this.current = first; this.issuer = issuer; this.gameAlive = gameAlive; this.clock = clock;
        this.lastIssue = clock.getAsLong();
    }

    /// Starts the background checks on one daemon thread.
    synchronized void start() {
        if (closed || timer != null) return;
        ScheduledExecutorService t = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "SAO ticket keeper"); thread.setDaemon(true); return thread;
        });
        timer = t;
        t.scheduleWithFixedDelay(this::tick, CHECK_MILLIS, CHECK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /// One check; package-visible for tests. Returns true when a new ticket was written.
    synchronized boolean tick() {
        if (closed) return false;
        if (!gameAlive.getAsBoolean()) { close(); return false; }
        long now = clock.getAsLong();
        @Nullable SaoOathLaunchTicket ticket = current;
        boolean claimed = ticket == null || !ticket.unclaimed();
        boolean expiring = ticket != null && now >= ticket.expiresAt() - RENEW_BEFORE_MILLIS;
        if (!claimed && !expiring) return false;
        if (now - lastIssue < MIN_GAP_MILLIS || now < notBefore) return false;
        lastIssue = now;
        try {
            SaoOathLaunchTicket next = issuer.issue();
            if (ticket != null) { try { ticket.close(); } catch (Exception ignored) { } }
            // The new file replaced the old one atomically; closing the old object only clears its bytes.
            current = next;
            failing = false;
            Logger.LOG.info("ARGUS rejoin ticket renewed (" + (claimed ? "used by a join" : "about to expire") + ")");
            return true;
        } catch (SaoAccountException signedOut) {
            if (!"invalidSession".equals(signedOut.code())) return retryLater(now, signedOut);
            Logger.LOG.info("ARGUS rejoin tickets stopped: the launcher is signed out");
            close();
            return false;
        } catch (Exception failure) {
            return retryLater(now, failure);
        }
    }

    private boolean retryLater(long now, Exception failure) {
        notBefore = now + RETRY_MILLIS;
        if (!failing) Logger.LOG.info("ARGUS rejoin ticket renewal failed, retrying: " + failure.getClass().getSimpleName());
        failing = true;
        return false;
    }

    synchronized boolean closed() { return closed; }

    /// Stops renewing and removes the last unclaimed file.
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        @Nullable ScheduledExecutorService t = timer; timer = null;
        if (t != null) t.shutdownNow();
        @Nullable SaoOathLaunchTicket ticket = current; current = null;
        if (ticket != null) { try { ticket.close(); } catch (Exception ignored) { } }
    }
}
