package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import java.nio.file.*;
import java.io.IOException;

/// Records application lifetime independently of game crashes and consumes the prior-run notice once.
@NotNullByDefault
final class SaoRunMarker {
    /// Per-install marker; no credentials or account data.
    private final Path marker;
    /// Only this process can consume this notice.
    private boolean previous;
    /// Marks entry; only normal engine shutdown removes the marker.
    SaoRunMarker(Path root) {
        marker = root.resolve("launcher-running.marker");
        try {
            Files.createDirectories(root);
            previous = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
            Files.writeString(marker, "running");
        } catch (IOException error) { org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot record launcher lifetime", error); }
    }
    /// Acknowledging either button suppresses duplicate prompts in this run.
    synchronized boolean consume() { boolean result = previous; previous = false; return result; }
    /// Best-effort removal on normal exit; native termination leaves the marker for the next run.
    void close() {
        try { Files.deleteIfExists(marker); }
        catch (IOException error) { org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot clear launcher lifetime", error); }
    }
}
