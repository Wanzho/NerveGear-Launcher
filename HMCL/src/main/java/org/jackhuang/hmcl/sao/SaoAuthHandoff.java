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
// 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/// Sends a one-use sao_auth password through child stdin without creating a password String.
@NotNullByDefault
public final class SaoAuthHandoff {
    /// ASCII framing marker expected by the client mod, with one separating space.
    private static final byte @Unmodifiable [] PREFIX = {'S', 'A', 'O', '-', 'A', 'U', 'T', 'H', '-', 'V', '1', ' '};
    /// Hard limit matching the bounded client-side protocol reader.
    public static final int MAX_PASSWORD_CHARS = 1024;
    /// Utility class.
    private SaoAuthHandoff() { }

    /// Writes exactly one LF-terminated UTF-8/base64 line when present, then closes stdin.
    /// Password and temporary encoding buffers are wiped on success and failure.
    /// The caller must only invoke this when joining the configured 誓约 server.
    public static void writeAndClose(OutputStream stdin, char @Nullable [] password) throws IOException {
        @Nullable ByteBuffer encoded = null;
        byte @Nullable [] utf8 = null;
        byte @Nullable [] base64 = null;
        try (OutputStream output = stdin) {
            if (password == null || password.length == 0) return;
            if (password.length > MAX_PASSWORD_CHARS) throw new IOException("sao_auth password is too long");
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password));
            utf8 = new byte[encoded.remaining()];
            encoded.get(utf8);
            base64 = Base64.getEncoder().encode(utf8);
            output.write(PREFIX);
            output.write(base64);
            output.write('\n');
            output.flush();
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (utf8 != null) Arrays.fill(utf8, (byte) 0);
            if (base64 != null) Arrays.fill(base64, (byte) 0);
            if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }
}
