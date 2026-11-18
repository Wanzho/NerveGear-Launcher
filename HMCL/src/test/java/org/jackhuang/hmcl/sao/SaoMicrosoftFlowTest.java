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

import org.jackhuang.hmcl.auth.ServerDisconnectException;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftService;
import org.jackhuang.hmcl.auth.yggdrasil.RemoteAuthenticationException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/// Headless transport/state-machine tests: no JavaFX toolkit, sockets, accounts, or browser.
@NotNullByDefault
class SaoMicrosoftFlowTest {
    /// A controllable provider which deliberately returns late even after interruption.
    @NotNullByDefault
    private static final class Transport implements SaoMicrosoftFlowEngine.Transport {
        /// Signals that OAuth has started.
        final CountDownLatch started = new CountDownLatch(1);
        /// Test-controlled completion gate.
        final CountDownLatch release = new CountDownLatch(1);
        /// Records resource closure.
        final CountDownLatch closed = new CountDownLatch(1);
        /// Counts account installation, which must happen only for current successful attempts.
        final AtomicInteger installs = new AtomicInteger();
        /// Emits mode-specific state, then waits for an explicit test response.
        @Override public SaoMicrosoftFlowEngine.Result run(boolean device, Consumer<SaoMicrosoftFlow.State> emit) {
            emit.accept(new SaoMicrosoftFlow.State(device ? SaoMicrosoftFlow.Kind.DEVICE_CODE : SaoMicrosoftFlow.Kind.BROWSER,
                    device ? "" : "https://example.invalid/login", device ? "TEST-CODE" : "",
                    device ? "https://example.invalid/device" : "", "", "", ""));
            started.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* Simulate a provider returning after cancellation. */ }
            }
            emit.accept(new SaoMicrosoftFlow.State(SaoMicrosoftFlow.Kind.SIGNING_IN, "", "", "", "正在验证 Xbox 账号", "", ""));
            return new SaoMicrosoftFlowEngine.Result("TestProfile", installs::incrementAndGet);
        }

        /// Records cleanup without forcing completion, so stale-result guards are exercised.
        @Override public void close() { closed.countDown(); }
    }

    /// Waits with a bounded deadline for asynchronous state publication.
    private static void await(SaoMicrosoftFlow flow, SaoMicrosoftFlow.Kind kind) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (flow.state().get().kind() != kind && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(kind, flow.state().get().kind());
    }

    /// Successful OAuth opens once, permits reopening, installs before DONE and fires the continuation once.
    @Test void successAndContinuation() throws Exception {
        Transport transport = new Transport(); AtomicInteger opens = new AtomicInteger(); AtomicInteger continued = new AtomicInteger();
        CountDownLatch continuation = new CountDownLatch(1);
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> transport, Runnable::run, url -> opens.incrementAndGet(), 5000);
        assertTrue(transport.started.await(3, TimeUnit.SECONDS));
        SaoMicrosoftFlows.observe(flow, () -> {
            assertEquals(1, transport.installs.get()); continued.incrementAndGet(); continuation.countDown();
        }, message -> fail(message));
        assertEquals(SaoMicrosoftFlow.Kind.BROWSER, flow.state().get().kind());
        flow.openBrowser(); assertEquals(2, opens.get());
        transport.release.countDown(); await(flow, SaoMicrosoftFlow.Kind.DONE);
        assertEquals("TestProfile", flow.state().get().name());
        assertTrue(continuation.await(3, TimeUnit.SECONDS));
        assertEquals(1, continued.get()); flow.cancel(); flow.useDeviceCode();
        assertEquals(SaoMicrosoftFlow.Kind.DONE, flow.state().get().kind());
    }

    /// Switching modes closes the old server and rejects its late successful credentials.
    @Test void deviceSwitchRejectsOldResult() throws Exception {
        Transport old = new Transport(); Transport device = new Transport(); AtomicInteger attempts = new AtomicInteger();
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> attempts.getAndIncrement() == 0 ? old : device, Runnable::run, url -> { }, 5000);
        assertTrue(old.started.await(3, TimeUnit.SECONDS)); flow.useDeviceCode();
        assertTrue(old.closed.await(3, TimeUnit.SECONDS)); assertTrue(device.started.await(3, TimeUnit.SECONDS));
        old.release.countDown(); await(flow, SaoMicrosoftFlow.Kind.DEVICE_CODE);
        assertEquals("TEST-CODE", flow.state().get().userCode());
        device.release.countDown(); await(flow, SaoMicrosoftFlow.Kind.DONE);
        assertEquals(0, old.installs.get()); assertEquals(1, device.installs.get());
    }

    /// Cancel publishes immediately, closes resources and suppresses all later progress and account writes.
    @Test void cancellationRejectsLateResult() throws Exception {
        Transport transport = new Transport();
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> transport, Runnable::run, url -> { }, 5000);
        assertTrue(transport.started.await(3, TimeUnit.SECONDS)); flow.cancel();
        assertTrue(transport.closed.await(3, TimeUnit.SECONDS)); transport.release.countDown();
        await(flow, SaoMicrosoftFlow.Kind.CANCELLED); Thread.sleep(30);
        assertEquals(SaoMicrosoftFlow.Kind.CANCELLED, flow.state().get().kind()); assertEquals(0, transport.installs.get());
    }

    /// A short injected deadline exercises the same cleanup path as production's five-minute timeout.
    @Test void timeoutClosesTransport() throws Exception {
        Transport transport = new Transport();
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> transport, Runnable::run, url -> { }, 80);
        await(flow, SaoMicrosoftFlow.Kind.FAILED);
        assertEquals("登录超时", flow.state().get().message()); assertTrue(transport.closed.await(3, TimeUnit.SECONDS));
        transport.release.countDown(); assertEquals(0, transport.installs.get());
    }

    /// Worker progress stays queued until UI dispatch, and stale queued browser events cannot open after cancel.
    @Test void queuedUiEventsRespectCancellation() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>(); Transport transport = new Transport();
        AtomicInteger opens = new AtomicInteger();
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> transport, ui::add, url -> opens.incrementAndGet(), 5000);
        assertTrue(transport.started.await(3, TimeUnit.SECONDS));
        assertEquals(SaoMicrosoftFlow.Kind.STARTING, flow.state().get().kind());
        flow.cancel();
        @org.jetbrains.annotations.Nullable Runnable event;
        while ((event = ui.poll()) != null) event.run();
        assertEquals(SaoMicrosoftFlow.Kind.CANCELLED, flow.state().get().kind());
        assertEquals(0, opens.get()); transport.release.countDown();
    }

    /// Transport failure becomes FAILED and releases resources without ever invoking installation.
    @Test void transportFailurePublishesSafeMessage() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        SaoMicrosoftFlowEngine.Transport broken = new SaoMicrosoftFlowEngine.Transport() {
            /// Simulates a network exception containing sensitive provider text.
            @Override public SaoMicrosoftFlowEngine.Result run(boolean device, Consumer<SaoMicrosoftFlow.State> emit) throws Exception {
                throw new IOException("access_token=SECRET");
            }

            /// Records mandatory cleanup.
            @Override public void close() { closed.countDown(); }
        };
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> broken, Runnable::run, url -> fail("Unexpected browser"), 5000);
        await(flow, SaoMicrosoftFlow.Kind.FAILED);
        assertEquals("网络连接失败，请重试", flow.state().get().message());
        assertTrue(closed.await(3, TimeUnit.SECONDS));
    }

    /// Cancellation during a deferred storage confirmation prevents even an approved late write.
    @Test void lateStorageConfirmationCannotInstall() throws Exception {
        BlockingQueue<Runnable> confirmation = new LinkedBlockingQueue<>(); AtomicInteger installs = new AtomicInteger();
        SaoMicrosoftFlowEngine.Transport completed = new SaoMicrosoftFlowEngine.Transport() {
            /// Returns credentials whose storage needs a separate confirmation.
            @Override public SaoMicrosoftFlowEngine.Result run(boolean device, Consumer<SaoMicrosoftFlow.State> emit) {
                return new SaoMicrosoftFlowEngine.Result("TestProfile", installs::incrementAndGet, confirmation::add);
            }

            /// No real resources exist in this transport.
            @Override public void close() { }
        };
        SaoMicrosoftFlow flow = new SaoMicrosoftFlowEngine(() -> completed, Runnable::run, url -> { }, 5000);
        @org.jetbrains.annotations.Nullable Runnable approve = confirmation.poll(3, TimeUnit.SECONDS);
        assertNotNull(approve); flow.cancel(); approve.run();
        assertEquals(0, installs.get()); assertEquals(SaoMicrosoftFlow.Kind.CANCELLED, flow.state().get().kind());
    }

    /// Known provider failures map to fixed Chinese messages; arbitrary secrets never reach the state.
    @Test void safeErrorMapping() {
        assertEquals("登录配置有误，请联系管理员", SaoMicrosoftFlows.error(new ServerDisconnectException(
                new RemoteAuthenticationException("invalid_request", "redirect_uri SECRET", ""))));
        assertEquals("这个微软账号还没有购买 Minecraft", SaoMicrosoftFlows.error(new MicrosoftService.MinecraftJavaEditionProfileNotFoundException()));
        assertEquals("网络连接失败，请重试", SaoMicrosoftFlows.error(new IOException("SECRET")));
        assertEquals("你所在的国家或地区不受 XBOX Live 的支持。", SaoMicrosoftFlows.error(
                new MicrosoftService.XboxAuthorizationException(MicrosoftService.XboxAuthorizationException.COUNTRY_UNAVAILABLE, "SECRET")));
        assertTrue(SaoMicrosoftFlows.error(new MicrosoftService.XboxAuthorizationException(
                MicrosoftService.XboxAuthorizationException.ADD_FAMILY, "SECRET")).contains("家庭"));
        assertTrue(SaoMicrosoftFlows.error(new MicrosoftService.XboxAuthorizationException(
                MicrosoftService.XboxAuthorizationException.MISSING_XBOX_ACCOUNT, "SECRET")).contains("游戏档案"));
        assertEquals("Microsoft 登录失败，请重试", SaoMicrosoftFlows.error(new RuntimeException("access_token=SECRET")));
    }
}
