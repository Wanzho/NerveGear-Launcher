/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.beans.value.ChangeListener;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/// Download-window records: failures disappear, successful assets share a session counter, and active work is never evicted.
@NotNullByDefault
public final class SaoDownloadTasks {
    /// Explicit terminal outcomes; cancellation is removal rather than successful completion.
    @NotNullByDefault
    public enum Status {
        /// Transfer is active.
        RUNNING,
        /// Transfer completed successfully.
        SUCCEEDED,
        /// Transfer failed or its graph owner stopped waiting.
        REMOVED
    }

    /// Active transfers and at most 100 completed non-asset rows, guarded by this object.
    private final Map<Task<?>, SaoData.Task> records = new LinkedHashMap<>();
    /// Successful asset transfers in this launcher session; individual completed asset tasks are not retained.
    private long completedAssets;

    /// Creates an empty download history without toolkit or worker activity.
    public SaoDownloadTasks() { }

    /// Applies a serialized observer event; no raw object hash is ever published as a row name.
    public synchronized void record(Task<?> task, Status status) {
        if (status == Status.REMOVED) records.remove(task);
        else if (asset(task) && status == Status.SUCCEEDED) {
            if (records.remove(task) != null) completedAssets++;
        } else {
            String name = task instanceof FileDownloadTask file ? file.getPath().getFileName().toString() : task.getName();
            if (name.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}")) name = "游戏文件";
            records.put(task, new SaoData.Task(SaoGameLogs.sanitize(name), status == Status.SUCCEEDED ? 1 : task.progressProperty().get(),
                    0, status == Status.SUCCEEDED));
        }
        int finished = 0;
        for (SaoData.Task row : records.values()) if (row.done()) finished++;
        var iterator = records.values().iterator();
        while (finished > 100 && iterator.hasNext()) {
            if (iterator.next().done()) { iterator.remove(); finished--; }
        }
    }

    /// Returns one asset row with honest observed counts, without inventing an index-wide total or retaining hash rows.
    public synchronized @Unmodifiable List<SaoData.Task> snapshot() {
        List<SaoData.Task> rows = new ArrayList<>();
        int activeAssets = 0;
        for (var entry : records.entrySet()) {
            if (asset(entry.getKey())) activeAssets++;
            else rows.add(entry.getValue());
        }
        if (completedAssets > 0 || activeAssets > 0) {
            String label = "游戏资源 · 已完成 " + completedAssets + (activeAssets > 0 ? " · 下载中 " + activeAssets : "");
            rows.add(new SaoData.Task(label, activeAssets == 0 ? 1 : -1, 0, activeAssets == 0));
        }
        return List.copyOf(rows);
    }

    /// Tests real active work rather than a UI publication that can lag by one batch.
    public synchronized boolean busy() { return records.values().stream().anyMatch(row -> !row.done()); }

    /// Recognizes Minecraft's content-addressed object destinations, including tasks without an inherited stage yet.
    private static boolean asset(Task<?> task) {
        if (!(task instanceof FileDownloadTask file)) return false;
        String path = file.getPath().toString().replace('\\', '/');
        return path.contains("/assets/objects/") || path.startsWith("assets/objects/");
    }

    /// Serializes progress and terminal events and fences late callbacks after graph cancellation or failure.
    @NotNullByDefault
    public static class Observer extends TaskListener implements AutoCloseable {
        /// Destination for serialized task events.
        private final BiConsumer<Task<?>, Status> sink;
        /// Exactly the transfers whose progress listeners are still attached.
        private final Map<Task<?>, ChangeListener<Number>> listeners = new LinkedHashMap<>();
        /// Prevents a cancelled graph's late callbacks from resurrecting rows.
        private boolean closed;

        /// Creates a graph-scoped observer; callers must close it in their graph waiter's finally block.
        public Observer(BiConsumer<Task<?>, Status> sink) { this.sink = sink; }

        /// Attaches only to actual transfers, once per task identity.
        @Override public synchronized void onRunning(Task<?> task) {
            if (closed) return;
            running(task);
            if (!(task instanceof FetchTask) || listeners.containsKey(task)) return;
            ChangeListener<Number> listener = (property, oldValue, newValue) -> progress(task);
            listeners.put(task, listener);
            task.progressProperty().addListener(listener);
            sink.accept(task, Status.RUNNING);
        }

        /// Allows phase reporting under the same close fence, including non-transfer graph tasks.
        protected void running(Task<?> task) { }

        /// Publishes progress only while the transfer remains owned and active.
        private synchronized void progress(Task<?> task) {
            if (!closed && listeners.containsKey(task)) sink.accept(task, Status.RUNNING);
        }

        /// Successful completion is distinct from failure, even before HMCL updates TaskState.
        @Override public synchronized void onFinished(Task<?> task) { finish(task, Status.SUCCEEDED); }

        /// Failed transfers immediately leave the list; the engine retains the operation-level error.
        @Override public synchronized void onFailed(Task<?> task, Throwable failure) { finish(task, Status.REMOVED); }

        /// Detaches before publishing the terminal event so queued progress cannot undo it.
        private void finish(Task<?> task, Status status) {
            @Nullable ChangeListener<Number> listener = listeners.remove(task);
            if (listener != null) {
                task.progressProperty().removeListener(listener);
                sink.accept(task, status);
            }
        }

        /// Removes all unfinished transfers before the owner returns, including cancellation without onFinished.
        @Override public synchronized void close() {
            closed = true;
            for (Task<?> task : List.copyOf(listeners.keySet())) finish(task, Status.REMOVED);
        }
    }
}
