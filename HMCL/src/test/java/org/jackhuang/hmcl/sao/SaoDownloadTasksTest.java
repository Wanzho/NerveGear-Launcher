/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/// Headless real-executor and immutable-row regressions; fixtures override transfer execution and never access a network.
@NotNullByDefault
class SaoDownloadTasksTest {
    /// Isolated paths used as transfer names only.
    @TempDir Path temporary;

    /// A real failed graph leaves no running row when the bridge call returns exceptionally.
    @Test void failedFutureRemovesTransfer() throws Exception {
        SaoDownloadTasks rows = new SaoDownloadTasks();
        SaoGameBridge bridge = new SaoGameBridge(temporary, (label, amount) -> { });
        bridge.observeTasks(rows::record);
        try {
            FileDownloadTask task = new FileDownloadTask(DownloadCandidates.of("https://example.invalid/library.jar"), temporary.resolve("library.jar")) {
                /// Fails after the real executor publishes onRunning, without performing a transfer.
                @Override public void execute() throws Exception {
                    assertTrue(rows.busy());
                    throw new IOException("fixture failure");
                }
            };
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try { bridge.runTask(task); }
                catch (Exception failure) { throw new CompletionException(failure); }
            });
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertFalse(rows.busy());
            assertTrue(rows.snapshot().isEmpty());
        } finally { bridge.close(); }
    }

    /// Explicit cancellation releases the waiter and fences late callbacks from the cancelled graph.
    @Test void cancelledFutureRemovesTransfer() throws Exception {
        SaoDownloadTasks rows = new SaoDownloadTasks();
        SaoGameBridge bridge = new SaoGameBridge(temporary, (label, amount) -> { });
        bridge.observeTasks(rows::record);
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        FileDownloadTask task = new FileDownloadTask(DownloadCandidates.of("https://example.invalid/library.jar"), temporary.resolve("library.jar")) {
            /// Waits only for HMCL cancellation; no files or network connections are opened.
            @Override public void execute() throws Exception {
                started.countDown();
                try { while (!isCancelled()) Thread.sleep(5); }
                finally { stopped.countDown(); }
            }
        };
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try { bridge.runTask(task); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            bridge.close();
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertFalse(rows.busy());
            assertTrue(rows.snapshot().isEmpty());
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        } finally { bridge.close(); }
    }

    /// Thousands of SHA-1 objects occupy exactly one named row before and after successful completion.
    @Test void assetObjectsCollapseIntoOneCountedRow() {
        SaoDownloadTasks rows = new SaoDownloadTasks();
        List<FileDownloadTask> assets = new ArrayList<>();
        try (var observer = new SaoDownloadTasks.Observer(rows::record)) {
            for (int i = 0; i < 3600; i++) {
                String hash = String.format("%040x", i);
                FileDownloadTask task = file("minecraft/assets/objects/" + hash.substring(0, 2) + "/" + hash);
                assets.add(task); observer.onRunning(task);
            }
            for (int i = 0; i < 3; i++) observer.onFinished(assets.get(i));
            assertEquals(1, rows.snapshot().size());
            assertEquals("游戏资源 · 已完成 3 · 下载中 3597", rows.snapshot().get(0).name());
            assertFalse(rows.snapshot().get(0).done());
            for (int i = 3; i < assets.size(); i++) observer.onFinished(assets.get(i));
            assertEquals(1, rows.snapshot().size());
            assertEquals("游戏资源 · 已完成 3600", rows.snapshot().get(0).name());
            assertTrue(rows.snapshot().get(0).done());
            assertFalse(rows.busy());
        }
    }

    /// Listener closure and later failure/progress callbacks cannot revive a removed transfer or count it as successful.
    @Test void closeFencesLateCallbacksAndDoesNotCountCancelledAssets() {
        SaoDownloadTasks rows = new SaoDownloadTasks();
        var observer = new SaoDownloadTasks.Observer(rows::record);
        var task = new FileDownloadTask(DownloadCandidates.of("https://example.invalid/fixture"),
                temporary.resolve("assets/objects/ab/" + "ab".repeat(20))) {
            /// Emits progress after observer closure without executing a transfer.
            void lateProgress() { updateProgressImmediately(0.5); }
        };
        observer.onRunning(task);
        observer.close();
        task.lateProgress();
        observer.onRunning(task);
        observer.onFinished(task);
        observer.onFailed(task, new IOException("late failure"));
        assertTrue(rows.snapshot().isEmpty());
        assertFalse(rows.busy());
    }

    /// Destination filenames win over internal task labels; completed history never evicts still-active work.
    @Test void filenamesAndHistoryBoundPreserveActiveWork() {
        SaoDownloadTasks rows = new SaoDownloadTasks();
        try (var observer = new SaoDownloadTasks.Observer(rows::record)) {
            FileDownloadTask library = file("libraries/library-1.0.jar");
            library.setName("a".repeat(40));
            observer.onRunning(library);
            for (int i = 0; i < 110; i++) {
                FileDownloadTask mod = file("mods/mod-" + i + ".jar");
                observer.onRunning(mod); observer.onFinished(mod);
            }
            assertEquals(101, rows.snapshot().size());
            assertEquals("library-1.0.jar", rows.snapshot().get(0).name());
            assertTrue(rows.busy());
            observer.onFailed(library, new IOException("fixture"));
            assertFalse(rows.busy());
            assertTrue(rows.snapshot().stream().allMatch(SaoData.Task::done));
        }
    }

    /// Constructs a dormant task; tests drive observer events rather than opening its deliberately invalid URL.
    private FileDownloadTask file(String path) {
        return new FileDownloadTask(DownloadCandidates.of("https://example.invalid/fixture"), temporary.resolve(path));
    }
}
