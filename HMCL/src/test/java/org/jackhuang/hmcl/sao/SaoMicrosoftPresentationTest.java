/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises retry, closure and late completion without accounts, network or a toolkit.
@NotNullByDefault
class SaoMicrosoftPresentationTest {
    /// Controllable attempt with production-like terminal cancellation behavior.
    @NotNullByDefault
    private static final class Attempt implements SaoMicrosoftFlow {
        /// Observable state used by the real continuation owner.
        private final ReadOnlyObjectWrapper<State> value = new ReadOnlyObjectWrapper<>();
        /// Starts waiting for browser authorization.
        Attempt() { emit(Kind.BROWSER); }
        /// Publishes simulated provider results, including deliberately late ones.
        void emit(Kind kind) { value.set(new State(kind, "", "", "", "", "", "登录超时")); }
        /// Exposes the observable contract.
        @Override public ReadOnlyObjectProperty<State> state() { return value.getReadOnlyProperty(); }
        /// Has no browser side effects.
        @Override public void openBrowser() { }
        /// Publishes a device-mode state.
        @Override public void useDeviceCode() { emit(Kind.DEVICE_CODE); }
        /// Matches the engine: terminal failures cannot become cancellation.
        @Override public void cancel() {
            if (value.get().kind() != Kind.FAILED && value.get().kind() != Kind.DONE) emit(Kind.CANCELLED);
        }
    }

    /// Timeout followed by successful retry must continue ARGUS admission exactly once.
    @Test void failedAttemptCanRetryAndContinue() {
        AtomicInteger done = new AtomicInteger(), failures = new AtomicInteger();
        SaoMicrosoftPresentation session = new SaoMicrosoftPresentation(Attempt::new, done::incrementAndGet, text -> failures.incrementAndGet());
        Attempt first = (Attempt) session.get(); first.emit(SaoMicrosoftFlow.Kind.FAILED);
        assertEquals(0, failures.get(), "A retryable failure must not complete the caller's future");
        Attempt second = (Attempt) session.get(); second.useDeviceCode();
        first.emit(SaoMicrosoftFlow.Kind.DONE); assertEquals(0, done.get());
        second.emit(SaoMicrosoftFlow.Kind.DONE); second.emit(SaoMicrosoftFlow.Kind.DONE); session.cancel();
        assertEquals(1, done.get()); assertEquals(0, failures.get());
    }

    /// Closing the error screen must release a worker waiting for authentication.
    @Test void closingFailedAttemptCancelsCaller() {
        AtomicInteger done = new AtomicInteger(), failures = new AtomicInteger();
        SaoMicrosoftPresentation session = new SaoMicrosoftPresentation(Attempt::new, done::incrementAndGet, text -> failures.incrementAndGet());
        Attempt first = (Attempt) session.get(); first.emit(SaoMicrosoftFlow.Kind.FAILED);
        session.cancel(); session.cancel(); first.emit(SaoMicrosoftFlow.Kind.DONE);
        assertEquals(1, failures.get()); assertEquals(0, done.get());
        assertThrows(IllegalStateException.class, session::get);
    }

    /// Superseding a still-running attempt prevents a late completion from continuing.
    @Test void cancelledWindowRejectsLateSuccess() {
        AtomicInteger done = new AtomicInteger(), failures = new AtomicInteger();
        SaoMicrosoftPresentation session = new SaoMicrosoftPresentation(Attempt::new, done::incrementAndGet, text -> failures.incrementAndGet());
        Attempt first = (Attempt) session.get(); session.cancel(); first.emit(SaoMicrosoftFlow.Kind.DONE);
        assertEquals(1, failures.get()); assertEquals(0, done.get());
    }
}
