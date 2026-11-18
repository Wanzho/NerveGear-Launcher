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
package org.jackhuang.hmcl.terracotta;

import javafx.application.Platform;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jackhuang.hmcl.sao.SaoPackEngine;
import org.jackhuang.hmcl.sao.SaoPackArchive;
import java.nio.file.Path;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Network-free lifecycle fixtures for explicit Terracotta room actions and late callback cancellation.
@NotNullByDefault
class SaoRoomTest {
    /// Isolated no-pack launcher root for the engine lifecycle fixture.
    @TempDir Path temporary;
    /// Restores the real dormant factory without ever starting a real tunnel.
    @AfterEach void reset() { TerracottaManager.stopRoom(); TerracottaManager.useSessionFactory(null); }

    /// Reading state or toggling a legacy UI page cannot create transport workers or request a package.
    @Test void startupAndLegacyViewsAreLazy() {
        assertFalse(TerracottaManager.hasRequestedRoom());
        assertInstanceOf(TerracottaState.Uninitialized.class, TerracottaManager.stateProperty().get());
        TerracottaManager.switchDaemon(true); TerracottaManager.recover();
        assertFalse(TerracottaManager.hasRequestedRoom());
        assertNull(TerracottaManager.download()); assertNull(TerracottaManager.setScanning());
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.getName().equals("sao-terracotta-room")));
    }

    /// Host/join each reserve one session; leave closes it and invalidates callbacks already in flight.
    @Test void explicitRoomsStartOnceAndLeaveRejectsLateCallbacks() {
        List<FixtureSession> sessions = new ArrayList<>(); List<SaoData.Room> changes = new ArrayList<>(); List<String> errors = new ArrayList<>();
        TerracottaManager.useSessionFactory((code, changed, error) -> {
            FixtureSession session = new FixtureSession(code, changed); sessions.add(session); return session;
        });
        TerracottaManager.startRoom(null, changes::add, errors::add);
        assertTrue(TerracottaManager.hasRequestedRoom()); assertEquals(1, sessions.get(0).starts);
        TerracottaManager.startRoom("duplicate", changes::add, errors::add);
        assertEquals(1, sessions.size()); assertEquals(1, errors.size());
        FixtureSession host = sessions.get(0);
        host.changed.accept(new SaoData.Room(SaoData.RoomState.HOSTING, "fixture-code", 1, "ready"));
        assertEquals(SaoData.RoomState.HOSTING, changes.get(changes.size() - 1).state());
        TerracottaManager.stopRoom(); assertTrue(host.closed); assertFalse(TerracottaManager.hasRequestedRoom());
        int previous = changes.size();
        host.changed.accept(new SaoData.Room(SaoData.RoomState.HOSTING, "late", 1, "late"));
        assertEquals(previous, changes.size());
        TerracottaManager.startRoom("friend-code", changes::add, errors::add);
        assertEquals("friend-code", sessions.get(1).code);
        host.changed.accept(new SaoData.Room(SaoData.RoomState.FAILED, null, 0, "old failure"));
        assertEquals(SaoData.RoomState.JOINING, changes.get(changes.size() - 1).state());
    }

    /// A duplicate screen request must not invalidate the original engine's callback generation.
    @Test void duplicateEngineRoomActionKeepsOriginalSessionObservable() throws Exception {
        List<FixtureSession> sessions = new ArrayList<>();
        TerracottaManager.useSessionFactory((code, changed, error) -> {
            FixtureSession session = new FixtureSession(code, changed); sessions.add(session); return session;
        });
        var constructor = SaoPackEngine.class.getDeclaredConstructor(Path.class, SaoPackArchive.class, boolean.class);
        constructor.setAccessible(true);
        var ready = SaoPackEngine.class.getDeclaredField("packReady");
        ready.setAccessible(true);
        try (SaoPackEngine engine = constructor.newInstance(temporary, null, false)) {
            ((CompletableFuture<?>) ready.get(engine)).get(10, TimeUnit.SECONDS);
            engine.hostRoom();
            engine.hostRoom();
            drainFx();
            assertEquals(1, sessions.size(), "duplicate host must leave the original transport active");
            assertEquals("联机操作被拒绝: 请先离开当前联机房间", engine.lastError().get());

            sessions.get(0).changed.accept(new SaoData.Room(SaoData.RoomState.HOSTING, "original", 1, "ready"));
            drainFx();
            assertEquals("original", engine.room().get().code());

            engine.leaveRoom();
            sessions.get(0).changed.accept(new SaoData.Room(SaoData.RoomState.HOSTING, "late", 1, "late"));
            drainFx();
            assertEquals(SaoData.Room.OFF, engine.room().get());
        }
    }

    /// Waits for queued room publications if JavaFX is active.
    private static void drainFx() throws InterruptedException {
        CountDownLatch published = new CountDownLatch(1);
        try {
            Platform.runLater(published::countDown);
        } catch (IllegalStateException noToolkit) {
            return;
        }
        assertTrue(published.await(10, TimeUnit.SECONDS), "room publications must finish");
    }

    /// Actual HMCL JSON state adapters provide host codes, member counts and the guest's real connection URL.
    @Test void transportStatesMapToPlainScreenValues() {
        TerracottaState host = JsonUtils.fromNonNullJson("{\"state\":\"host-ok\",\"index\":1,\"room\":\"fixture-code\",\"profile_index\":0,\"profiles\":[]}", TerracottaState.Ready.class);
        SaoData.Room hosted = TerracottaManager.describe(host, null);
        assertEquals(SaoData.RoomState.HOSTING, hosted.state()); assertEquals("fixture-code", hosted.code());
        TerracottaState guest = JsonUtils.fromNonNullJson("{\"state\":\"guest-ok\",\"index\":2,\"url\":\"127.0.0.1:25566\",\"profile_index\":0,\"profiles\":[]}", TerracottaState.Ready.class);
        SaoData.Room joined = TerracottaManager.describe(guest, "friend");
        assertEquals(SaoData.RoomState.JOINED, joined.state()); assertTrue(Objects.requireNonNull(joined.message()).contains("127.0.0.1:25566"));
    }

    /// A dormant substitute for a real download/process transport; only start/close callbacks are exercised.
    @NotNullByDefault
    private static final class FixtureSession implements TerracottaManager.RoomSession {
        /// Requested room code; null identifies a host.
        private final @Nullable String code;
        /// Captures the production manager's generation-filtered state delivery.
        private final Consumer<SaoData.Room> changed;
        /// Number of explicit starts received.
        private int starts;
        /// Whether leave terminated this owned session.
        private boolean closed;
        /// Creates a dormant fixture without network, process or toolkit activity.
        private FixtureSession(@Nullable String code, Consumer<SaoData.Room> changed) { this.code = code; this.changed = changed; }

        /// Emits the corresponding initial state and records exactly one start.
        @Override public void start() {
            starts++; changed.accept(new SaoData.Room(code == null ? SaoData.RoomState.PREPARING : SaoData.RoomState.JOINING, code, 0, null));
        }

        /// Records cancellation without affecting any real application.
        @Override public void close() { closed = true; }
    }
}
