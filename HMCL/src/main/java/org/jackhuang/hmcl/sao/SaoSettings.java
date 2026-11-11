/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Launcher-owned settings, atomically persisted outside the Minecraft options file.
/// Only public preferences are stored here; account credentials and handoff buffers never enter this class.
@NotNullByDefault
final class SaoSettings {
    /// Settings destination below this launcher's data root.
    private final Path file;
    /// In-memory copy guarded by this object's monitor.
    private JsonObject values = new JsonObject();

    /// Loads the last complete settings file. A damaged file (for example cut short by a power loss) is kept aside as
    /// `launcher-settings.json.corrupt-<time>` and the defaults are used, so the launcher still starts.
    SaoSettings(Path root) throws IOException {
        file = root.resolve("launcher-settings.json");
        if (Files.exists(file)) {
            try { values = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
            catch (java.nio.charset.CharacterCodingException | RuntimeException failure) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
                values = new JsonObject();
            }
        }
    }

    /// Returns a string setting, using the supplied default for absent or wrongly typed values.
    synchronized String text(String key, String fallback) {
        try { return values.has(key) && values.get(key).isJsonPrimitive() ? values.get(key).getAsString() : fallback; }
        catch (RuntimeException failure) { return fallback; }
    }

    /// Returns an integer setting, using the default for malformed or absent values.
    synchronized int number(String key, int fallback) {
        try { return values.has(key) ? values.get(key).getAsInt() : fallback; }
        catch (RuntimeException failure) { return fallback; }
    }

    /// Returns a boolean setting, using the default when absent.
    synchronized boolean flag(String key, boolean fallback) {
        try { return values.has(key) ? values.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException failure) { return fallback; }
    }

    /// Atomically commits one setting; the previous in-memory value survives any write failure.
    synchronized void put(String key, @Nullable Object value) throws IOException {
        JsonObject replacement = values.deepCopy();
        if (value == null) replacement.remove(key);
        else if (value instanceof Number number) replacement.addProperty(key, number);
        else if (value instanceof Boolean flag) replacement.addProperty(key, flag);
        else replacement.addProperty(key, value.toString());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            Files.writeString(temporary, replacement.toString());
            // Flush before the rename, so a crash cannot leave an empty settings file in place.
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            values = replacement;
        } finally { Files.deleteIfExists(temporary); }
    }
}
