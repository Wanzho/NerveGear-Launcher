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
