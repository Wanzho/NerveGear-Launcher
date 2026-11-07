/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.upgrade;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableBooleanValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Inert compatibility facade: this fork never schedules or performs an HMCL update request.
@NotNullByDefault
public final class UpdateChecker {
    /// Always-empty upstream release value, retained for legacy property bindings.
    private static final ReadOnlyObjectWrapper<@Nullable RemoteVersion> latestVersion = new ReadOnlyObjectWrapper<>();
    /// Always-false upstream update availability.
    private static final ReadOnlyBooleanWrapper outdated = new ReadOnlyBooleanWrapper(false);
    /// Always-false update activity.
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);
    /// No updater errors occur because no updater runs.
    private static final ReadOnlyObjectWrapper<@Nullable Throwable> error = new ReadOnlyObjectWrapper<>();
    /// Prevents construction.
    private UpdateChecker() { }
    /// Does not start any background task or request.
    public static void init() { }
    /// No HMCL release is available in this fork.
    public static @Nullable RemoteVersion getLatestVersion() { return null; }
    /// Exposes the permanently empty release property to legacy bindings.
    public static ReadOnlyObjectProperty<@Nullable RemoteVersion> latestVersionProperty() { return latestVersion.getReadOnlyProperty(); }
    /// HMCL update notifications are disabled.
    public static boolean isOutdated() { return false; }
    /// Exposes permanently false availability.
    public static ObservableBooleanValue outdatedProperty() { return outdated.getReadOnlyProperty(); }
    /// No update request runs.
    public static boolean isCheckingUpdate() { return false; }
    /// Exposes permanently false activity.
    public static ReadOnlyBooleanProperty checkingUpdateProperty() { return checkingUpdate.getReadOnlyProperty(); }
    /// Exposes the permanently empty error value.
    public static ReadOnlyObjectProperty<@Nullable Throwable> errorProperty() { return error.getReadOnlyProperty(); }
    /// Ignores legacy UI requests without initializing networking or JavaFX.
    public static void requestCheckUpdate(UpdateChannel channel, boolean preview) { }
}
