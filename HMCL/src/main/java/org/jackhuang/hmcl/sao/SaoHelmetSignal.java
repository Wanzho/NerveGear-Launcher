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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipFile;

/// Read-only capability detection for packs predating the first-drawn-frame log contract.
@NotNullByDefault
public final class SaoHelmetSignal {
    /// No instances.
    private SaoHelmetSignal() {}

    /// Allows registration fallback only for an identified entrance mod lacking the first-frame literal.
    /// Missing/unreadable mods remain conservative (first-frame signal or inactivity/total timeout).
    public static boolean legacy(Path mods) {
        if (!Files.isDirectory(mods)) return false;
        boolean found = false;
        try (var jars = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path path : jars) {
                try (var jar = new ZipFile(path.toFile())) {
                    boolean entrance = jar.stream().anyMatch(e -> e.getName().endsWith("entrance/HelmetScreen.class"));
                    if (!entrance) continue;
                    found = true;
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class")) continue;
                        if (entry.getSize() > 4 * 1024 * 1024) return false;
                        try (var in = jar.getInputStream(entry)) {
                            String constants = new String(in.readNBytes(4 * 1024 * 1024), StandardCharsets.ISO_8859_1);
                            if (constants.contains("helmet visible")) return false;
                        }
                    }
                } catch (IOException unreadable) { return false; }
            }
        } catch (IOException unreadable) { return false; }
        return found;
    }
}
