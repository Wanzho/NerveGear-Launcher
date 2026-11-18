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
package org.jackhuang.hmcl.ui.sao;

import javafx.beans.value.ChangeListener;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Owns an open pack panel's download watch, waiting for authenticated metadata on first launch.
@NotNullByDefault
final class SaoPackDownloadWatch implements AutoCloseable {
    /// Backend whose verified release property drives the watch.
    private final SaoLauncherService service;
    /// Detached when the panel closes so late network replies cannot reopen a watch.
    private final ChangeListener<SaoData.UpdateStatus> listener;
    /// Last watched artifact identities; sequence-only changes preserve accumulated quick drops.
    private SaoData.@Nullable VersionMetadata watched;
    /// Guards repeated close and late publication.
    private boolean closed;

    /// Subscribes before checking cached metadata; no unsigned or absent policy can start a watch.
    SaoPackDownloadWatch(SaoLauncherService service) {
        this.service = service;
        listener = (observable, oldValue, newValue) -> refresh(newValue.metadata());
        service.updateStatus().addListener(listener);
        refresh(service.updateStatus().get().metadata());
    }

    /// Starts once per artifact policy, including metadata arriving after the panel has opened.
    private void refresh(SaoData.@Nullable VersionMetadata metadata) {
        if (closed || metadata == null) return;
        if (watched != null && watched.pack().equals(metadata.pack())
                && watched.updates().equals(metadata.updates()) && watched.launcher().equals(metadata.launcher())) return;
        watched = metadata;
        service.watchPackDownload();
    }

    /// Stops only the watch owned by this panel and ignores subsequent metadata publications.
    @Override public void close() {
        if (closed) return;
        closed = true;
        service.updateStatus().removeListener(listener);
        if (watched != null) service.cancelPackDownload();
    }
}
