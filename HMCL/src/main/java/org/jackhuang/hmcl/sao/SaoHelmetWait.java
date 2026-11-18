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

/// Pure readiness clock, driven by monotonic milliseconds and current-run sanitized output.
@NotNullByDefault
public final class SaoHelmetWait {
    /// First-frame marker, including the game window geometry emitted by current packs.
    private static final java.util.regex.Pattern VISIBLE = java.util.regex.Pattern.compile(
            "\\[sao-entrance] helmet visible(?: window=-?\\d+,-?\\d+,\\d+,\\d+ fb=\\d+,\\d+ full=[01])?$");
    /// First wait and last output timestamps.
    private double began, lastLine;
    /// Earliest permissible readiness time, infinite until an accepted marker.
    private double readyAt = Double.POSITIVE_INFINITY;
    /// Legacy registration is disabled unless the installed mod has been positively identified as old.
    private boolean legacy;
    /// Captures the installed pack's capability before reading this run's output.
    public void allowLegacy(boolean enabled) { legacy = enabled; }

    /// Resets both inactivity and old-pack marker state for a new run.
    public void begin(double now) { began = lastLine = now; readyAt = Double.POSITIVE_INFINITY; }

    /// Accepts only the first-frame contract or the old FancyMenu registration contract.
    public void line(String line, double now) {
        lastLine = now;
        if (VISIBLE.matcher(line.stripTrailing()).find()) readyAt = now;
        else if (legacy && line.contains("ScreenCustomizationLayer registered:") && line.contains("entrance.HelmetScreen"))
            readyAt = Math.min(readyAt, now + 800);
    }

    /// A dead process must cancel, never give up into a hand-over.
    public boolean ready(double now, boolean alive) {
        return alive && (now >= readyAt || now - lastLine >= 90_000 || now - began >= 600_000);
    }
}
