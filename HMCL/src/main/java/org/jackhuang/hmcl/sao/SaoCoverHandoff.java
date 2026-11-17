package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Pure pending/running cover ownership; release affects cover placement, never readiness or visibility policy.
@NotNullByDefault
public final class SaoCoverHandoff {
    /// True from Link Start until completion or cancellation, including delayed put-away.
    private boolean active;
    /// Whether the screen already finished, to support late lifecycle publication.
    private boolean finished;
    /// User released the cover and it must remain minimized even for KEEP.
    private boolean released;
    /// Completion held across the pending-to-running transition.
    private @Nullable Runnable completion;
    /// Begins a fresh pending hand-over before asynchronous launch work.
    public void begin() { active = true; finished = released = false; completion = null; }
    /// Holds an early callback; a late callback runs once after a finished fade.
    public void hold(Runnable action) {
        if (finished) action.run();
        else if (active) completion = action;
    }
    /// Completes once and returns whether the screen should perform its completion cleanup.
    public void complete() {
        if (!active) return;
        active = false; finished = true;
        @Nullable Runnable action = completion;
        completion = null;
        if (action != null) action.run();
    }
    /// Invalidates a held action without invoking it.
    public void cancel() { active = finished = released = false; completion = null; }
    /// Releases cover ownership but keeps the completion held.
    public void release() { if (active) released = true; }
    /// Whether a live process has armed its completion callback.
    public boolean armed() { return completion != null; }
    /// Shared drag lock for page and decorator.
    public boolean active() { return active; }
    /// Whether stage alignment must remain disabled for this hand-over.
    public boolean released() { return released; }
}
