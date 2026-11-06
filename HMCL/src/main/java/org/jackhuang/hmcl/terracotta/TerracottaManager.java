/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.terracotta;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.task.Task;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.Path;

/// Disabled compatibility facade: no Terracotta daemon, scan, installer, child process or network request exists here.
@NotNullByDefault
public final class TerracottaManager {
    /// Inert state used only by legacy UI bindings.
    private static final ReadOnlyObjectWrapper<TerracottaState> STATE = new ReadOnlyObjectWrapper<>(new TerracottaState.Uninitialized(false));
    /// Prevents construction.
    private TerracottaManager() { }
    /// Returns the inert state without loading Terracotta metadata or providers.
    public static ReadOnlyObjectProperty<TerracottaState> stateProperty() { return STATE.getReadOnlyProperty(); }
    /// No daemon is created or enabled.
    public static void switchDaemon(boolean active) { }
    /// Every Terracotta package is rejected in this fork.
    public static boolean isInvalidBundle(Path file) { return true; }
    /// Download is disabled before any task can be scheduled.
    public static TerracottaState.@Nullable Preparing download() { return null; }
    /// Installation is disabled before any package can be read.
    public static TerracottaState.@Nullable Preparing install(Path bundle) { return null; }
    /// Recovery never launches a Terracotta process.
    public static TerracottaState recover() { return STATE.get(); }
    /// No Terracotta process exists from which logs can be fetched.
    public static Task<@Nullable String> exportLogs() { return Task.completed(null); }
    /// No local control request is sent.
    public static TerracottaState.@Nullable Waiting setWaiting() { return null; }
    /// No LAN or public-node scan is started.
    public static TerracottaState.@Nullable HostScanning setScanning() { return null; }
    /// Joining a Terracotta room is unavailable.
    public static @Nullable Task<TerracottaState.GuestConnecting> setGuesting(String room) { return null; }
}
