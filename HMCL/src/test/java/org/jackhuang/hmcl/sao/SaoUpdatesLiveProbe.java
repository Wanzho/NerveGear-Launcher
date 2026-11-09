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
