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
