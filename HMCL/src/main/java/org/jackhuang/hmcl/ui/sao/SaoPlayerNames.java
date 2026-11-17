package org.jackhuang.hmcl.ui.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// Localizes the vanilla hidden-listing placeholder without changing ordinary player names.
@NotNullByDefault
public final class SaoPlayerNames {
    /// Utility class.
    private SaoPlayerNames() { }
    /// Vanilla sends this reserved display text for players who disable server listings.
    public static String display(String name) {
        return "Anonymous Player".equals(name) ? "匿名玩家" : name;
    }
}
