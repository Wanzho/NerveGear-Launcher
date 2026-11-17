package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies redirected Desktop discovery without accessing the real user's Desktop.
@NotNullByDefault
class SaoExportDestinationTest {
    /// Disposable directory tree for all paths and links.
    @TempDir Path temporary;
    /// An OS-approved redirected Desktop resolves to its target rather than the obsolete home Desktop.
    @Test void redirectedDesktopIsUsed() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("redirected"));
        Path link = Files.createSymbolicLink(temporary.resolve("Desktop"), target);
        assertEquals(target.toRealPath(), SaoExportDestination.select(link, temporary.resolve("data")));
    }
    /// A missing Desktop falls back without manufacturing a Desktop directory.
    @Test void missingDesktopUsesDataRoot() {
        Path absent = temporary.resolve("missing"), fallback = temporary.resolve("data");
        assertEquals(fallback, SaoExportDestination.select(absent, fallback));
        assertFalse(Files.exists(absent));
    }
}
