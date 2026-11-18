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
import java.nio.file.Path;

/// Explicit read-only real-news proof, separate from hermetic unit tests and never run by Gradle test discovery.
@NotNullByDefault
public final class SaoUpdatesLiveProbe {
    /// Utility class.
    private SaoUpdatesLiveProbe() { }

    /// Performs exactly one logical news fetch through the production redirect/deadline/cap path; no ping or browser.
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Disposable cache directory required");
        try (SaoOnlineData online = new SaoOnlineData(Path.of(args[0]))) {
            var news = online.fetch(SaoUpdates.NEWS_URL);
            System.out.println("source=" + SaoUpdates.NEWS_URL);
            System.out.println("validatedEntries=" + news.size());
            System.out.println("cacheVerified=" + news.equals(online.cached()));
            System.out.println("browserOpened=false; gameLaunched=false; serverPing=false");
        }
    }
}
