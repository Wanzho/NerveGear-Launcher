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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Generation-guarded state machine with injectable transport, UI dispatcher and deadline for offline tests.
@NotNullByDefault
final class SaoMicrosoftFlowEngine implements SaoMicrosoftFlow {
    /// One interruptible authentication attempt, owning its callback server.
    @NotNullByDefault
    interface Transport extends AutoCloseable {
        /// Authenticates and returns an FX-thread account installation action.
        Result run(boolean device, Consumer<State> emit) throws Exception;

        /// Releases the server; worker interruption stops device polling.
        @Override void close();
    }

    /// Profile display name and the account installation action, never credentials.
    @NotNullByDefault
    record Result(String name, Runnable install, Consumer<Runnable> authorize) {
        /// Installs without an extra storage permission prompt when storage is writable.
        Result(String name, Runnable install) { this(name, install, Runnable::run); }
    }
    /// Observable state; callers receive only its read-only view.
    private final ReadOnlyObjectWrapper<State> value = new ReadOnlyObjectWrapper<>(stateOf(Kind.STARTING));
    /// Serial UI dispatcher, Platform.runLater in production.
    private final Consumer<Runnable> ui;
    /// Fresh transport for every mode switch.
    private final Supplier<Transport> factory;
    /// Browser opener, replaced with a no-op in tests.
    private final Consumer<String> browser;
    /// Daemon deadline scheduler owned by this flow.
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sao-ms-deadline"); thread.setDaemon(true); return thread;
    });
    /// Attempt identity, invalidated before cancellation or switching.
    private int generation;
    /// Terminal state guard independent of queued UI notifications.
    private boolean terminal;
    /// Active worker, absent before start.
    private @Nullable Thread worker;
    /// Active transport, absent before start.
    private @Nullable Transport transport;

    /// Starts browser authentication with a deadline covering all mode switches.
    SaoMicrosoftFlowEngine(Supplier<Transport> factory, Consumer<Runnable> ui, Consumer<String> browser, long timeoutMillis) {
        this.factory = factory; this.ui = ui; this.browser = browser;
        timer.schedule(() -> finish(Kind.FAILED, "登录超时"), timeoutMillis, TimeUnit.MILLISECONDS);
        start(false);
    }

    /// Creates an empty payload for a lifecycle stage.
    static State stateOf(Kind kind) { return new State(kind, "", "", "", "", "", ""); }

    /// Exposes the immutable-property view.
    @Override public ReadOnlyObjectProperty<State> state() { return value.getReadOnlyProperty(); }

    /// Dispatches browser opening and ignores requests after completion.
    @Override public void openBrowser() {
        ui.accept(() -> {
        synchronized (this) {
            if (terminal) return;
            String url = value.get().url().isEmpty() ? value.get().verificationUri() : value.get().url();
            if (!url.isEmpty()) browser.accept(url);
        }
    }); }

    /// Replaces the active attempt; late browser results cannot install accounts.
    @Override public void useDeviceCode() { start(true); }

    /// Cancels all owned resources exactly once.
    @Override public void cancel() { finish(Kind.CANCELLED, ""); }

    /// Stops transport before interrupting its worker.
    private void stop() {
        if (transport != null) transport.close();
        if (worker != null) worker.interrupt();
    }

    /// Starts an isolated attempt and publishes only results from its current generation.
    private synchronized void start(boolean device) {
        if (terminal) return;
        int id = ++generation;
        stop();
        Transport next = factory.get(); transport = next;
        emit(id, stateOf(Kind.STARTING));
        worker = new Thread(() -> {
            try {
                Result result = next.run(device, state -> emit(id, state));
                ui.accept(() -> {
                    synchronized (this) {
                        if (terminal || generation != id) return;
                        try {
                            result.authorize().accept(() -> {
                                synchronized (this) {
                                    // A storage confirmation may outlive cancellation or a mode switch.
                                    if (terminal || generation != id) return;
                                    try {
                                        result.install().run();
                                        terminal = true; timer.shutdownNow();
                                        value.set(new State(Kind.DONE, "", "", "", "", result.name(), ""));
                                    } catch (Exception failure) { finish(Kind.FAILED, SaoMicrosoftFlows.error(failure)); }
                                }
                            });
                        } catch (Exception failure) { finish(Kind.FAILED, SaoMicrosoftFlows.error(failure)); }
                    }
                });
            } catch (Exception failure) {
                synchronized (this) {
                    if (!terminal && generation == id) finish(Kind.FAILED, SaoMicrosoftFlows.error(failure));
                }
            } finally { next.close(); }
        }, "sao-ms-login");
        worker.setDaemon(true); worker.start();
    }

    /// Publishes nonterminal progress, automatically opening only the authorization-code browser link.
    private void emit(int id, State state) {
        ui.accept(() -> {
        synchronized (this) {
            if (terminal || generation != id) return;
            value.set(state);
            if (state.kind() == Kind.BROWSER) browser.accept(state.url());
        }
    }); }

    /// Invalidates queued progress before releasing resources and publishing the terminal state.
    private synchronized void finish(Kind kind, String message) {
        if (terminal) return;
        terminal = true; ++generation; stop(); timer.shutdownNow();
        ui.accept(() -> value.set(new State(kind, "", "", "", "", "", message)));
    }
}
