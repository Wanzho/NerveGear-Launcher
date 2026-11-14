/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// ARGUS admission progress, separate from the locally known Microsoft identity.
@NotNullByDefault
public record SaoOathLinking(Kind kind, String reason) {
    /// Terminal failure preserves the same curated Chinese reason as the sign-in future.
    @NotNullByDefault
    public enum Kind {
        /// No Microsoft linking attempt is active.
        IDLE,
        /// Microsoft identity may be shown while ARGUS admission is pending.
        LINKING,
        /// ARGUS account refresh completed successfully.
        LINKED,
        /// Admission failed; identity is not permission to launch.
        FAILED
    }
    /// Initial or signed-out state.
    public static SaoOathLinking idle() { return new SaoOathLinking(Kind.IDLE, ""); }
}
