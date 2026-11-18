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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/// The hand-over from the launcher to the game window (James, 13 Nov: the launcher's windows put themselves away,
/// a loading row runs on the empty visor, and the launcher leaves only once the game shows the same visor).
///
/// The engine hides the launcher as soon as the game process exists. With a screen installed here, that hide is
/// handed to the screen instead, which runs it when the game is on screen; without one it runs at once, as before.
/// Everything here runs on the JavaFX thread.
@NotNullByDefault
public final class SaoHandoff {
    /// Receives the hide to run once the game is on screen.
    private static @Nullable Consumer<Runnable> holder;
    /// Drops a pending hide (the game ended before it was on screen).
    private static @Nullable Runnable canceller;

    private SaoHandoff() {
    }

    /// Lets a screen take over the launcher's hide at game start.
    ///
    /// @param hold   receives the hide; it must run it exactly once, or drop it when cancelled
    /// @param cancel drops a hide still held
    public static void install(Consumer<Runnable> hold, Runnable cancel) {
        holder = hold;
        canceller = cancel;
    }

    /// Hides the launcher: through the screen when one is installed, else now.
    ///
    /// @param hide the engine's hide
    static void hide(Runnable hide) {
        @Nullable Consumer<Runnable> h = holder;
        if (h == null) hide.run();
        else h.accept(hide);
    }

    /// The game has ended: a hide still held must never run.
    static void cancel() {
        @Nullable Runnable c = canceller;
        if (c != null) c.run();
    }
}
