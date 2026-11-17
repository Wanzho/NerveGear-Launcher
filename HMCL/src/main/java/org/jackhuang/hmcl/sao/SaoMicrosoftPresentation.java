/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.beans.value.ChangeListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Owns a login window's attempts so retries retain the caller's continuation.
@NotNullByDefault
final class SaoMicrosoftPresentation implements Supplier<SaoMicrosoftFlow> {
    /// Creates each independent OAuth attempt on the UI thread.
    private final Supplier<SaoMicrosoftFlow> factory;
    /// Invoked once after a successful attempt has stored its account.
    private final Runnable onDone;
    /// Invoked once when the user closes or supersedes this window.
    private final Consumer<String> onFailure;
    /// Current attempt, absent before the first start.
    private @Nullable SaoMicrosoftFlow current;
    /// Listener detached before replacing an attempt or completing the window.
    private @Nullable ChangeListener<SaoMicrosoftFlow.State> listener;
    /// Prevents stale attempts or repeated close actions from notifying callers.
    private boolean finished;

    /// Captures one caller across all attempts in this window.
    SaoMicrosoftPresentation(Supplier<SaoMicrosoftFlow> factory, Runnable onDone, Consumer<String> onFailure) {
        this.factory = factory;
        this.onDone = onDone;
        this.onFailure = onFailure;
    }

    /// Starts a retry after detaching the preceding attempt.
    @Override public SaoMicrosoftFlow get() {
        if (finished) throw new IllegalStateException("Microsoft login window is closed");
        @Nullable SaoMicrosoftFlow previous = current;
        detach();
        current = null;
        if (previous != null) previous.cancel();
        SaoMicrosoftFlow next = factory.get();
        current = next;
        listener = (observable, before, state) -> accept(next, state);
        next.state().addListener(listener);
        accept(next, next.state().get());
        return next;
    }

    /// Only success or explicit cancellation ends the window; failure permits retry.
    private void accept(SaoMicrosoftFlow source, SaoMicrosoftFlow.State state) {
        if (finished || source != current) return;
        switch (state.kind()) {
            case DONE -> { finished = true; detach(); onDone.run(); }
            case FAILED -> { /* Keep the caller pending while the window offers retry. */ }
            case CANCELLED -> cancel();
            default -> { }
        }
    }

    /// Cancels even a failed underlying attempt, whose cancel method may be a no-op.
    void cancel() {
        if (finished) return;
        finished = true;
        detach();
        if (current != null) current.cancel();
        onFailure.accept("已取消 Microsoft 登录");
    }

    /// Releases the observable listener before any callback can reenter the owner.
    private void detach() {
        if (current != null && listener != null) current.state().removeListener(listener);
        listener = null;
    }
}
