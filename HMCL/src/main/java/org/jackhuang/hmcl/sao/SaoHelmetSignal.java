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
