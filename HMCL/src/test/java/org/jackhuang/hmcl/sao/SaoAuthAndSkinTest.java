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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/// Bounded skin storage and one-line, wiped stdin protocol tests without a game process.
@NotNullByDefault
class SaoAuthAndSkinTest {
    /// Disposable instance and skin files.
    @TempDir Path temporary;

    /// Unicode is encoded as UTF-8 then base64, followed by one LF; the password and stdin are cleared/closed.
    @Test void handoffWritesOneLineAndWipesPassword() throws Exception {
        TrackingOutput output = new TrackingOutput();
        char[] password = "test-密碼-🙂".toCharArray();
        String expected = "SAO-AUTH-V1 " + Base64.getEncoder().encodeToString("test-密碼-🙂".getBytes(StandardCharsets.UTF_8)) + "\n";
        SaoAuthHandoff.writeAndClose(output, password);
        assertEquals(expected, output.toString(StandardCharsets.US_ASCII));
        assertTrue(output.closed);
        for (char value : password) assertEquals('\0', value);
    }

    /// Null is a closed empty stdin, and transfer failure still wipes all caller-owned password characters.
    @Test void absentOrFailedHandoffAlwaysClosesAndWipes() throws Exception {
        TrackingOutput output = new TrackingOutput();
        SaoAuthHandoff.writeAndClose(output, null);
        assertEquals(0, output.size());
        assertTrue(output.closed);
        char[] password = "discard-this".toCharArray();
        OutputStream broken = new OutputStream() {
            /// Simulates an immediately closed child stdin.
            @Override public void write(int value) throws IOException { throw new IOException("closed"); }
        };
        assertThrows(IOException.class, () -> SaoAuthHandoff.writeAndClose(broken, password));
        for (char value : password) assertEquals('\0', value);
    }

    /// A real headless JDK-only child receives one stdin frame while its arguments and launcher output contain no credential.
    @Test void actualChildReceivesStdinWithoutArgumentOrLogDisclosure() throws Exception {
        String synthetic = "synthetic-probe-密碼-27";
        String encoded = Base64.getEncoder().encodeToString(synthetic.getBytes(StandardCharsets.UTF_8));
        Path executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path classes = Path.of(StdinProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-Djava.awt.headless=true", "-cp", classes.toString(), StdinProbe.class.getName()).redirectErrorStream(true);
        assertFalse(String.join(" ", builder.command()).contains(synthetic));
        assertFalse(String.join(" ", builder.command()).contains(encoded));
        Process child = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        char[] password = synthetic.toCharArray();
        try {
            String arguments = String.join(" ", child.info().arguments().orElseGet(() -> new String[0]));
            assertFalse(arguments.contains(synthetic));
            assertFalse(arguments.contains(encoded));
            try (PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
                System.setOut(sink); System.setErr(sink);
                SaoAuthHandoff.writeAndClose(child.getOutputStream(), password);
            } finally { System.setOut(stdout); System.setErr(stderr); }
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "Stdin probe timed out");
            String response = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), response);
            // The required inherited headless environment prints this JVM notice before main runs.
            String body = response.lines().filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS: "))
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            assertEquals("one-frame;no-arguments;stdin-closed\n", body);
            String logs = captured.toString(StandardCharsets.UTF_8);
            assertFalse(logs.contains(synthetic));
            assertFalse(logs.contains(encoded));
            assertFalse(response.contains(synthetic));
            assertFalse(response.contains(encoded));
            for (char value : password) assertEquals('\0', value);
        } finally {
            System.setOut(stdout); System.setErr(stderr);
            child.destroyForcibly();
        }
    }

    /// Only standard PNG dimensions are accepted; model metadata survives a new store and null removes both files.
    @Test void skinClassicSlimAndRemoval() throws Exception {
        Path instance = temporary.resolve("instance");
        SaoSkinStore skins = new SaoSkinStore(instance);
        Path modern = png("modern.png", 64, 64);
        Path legacy = png("legacy.png", 64, 32);
        skins.set(modern, true);
        assertTrue(skins.slim());
        assertArrayEquals(Files.readAllBytes(modern), Files.readAllBytes(instance.resolve("sao/skin.png")));
        assertEquals("slim", com.google.gson.JsonParser.parseString(Files.readString(instance.resolve("sao/skin.json"))).getAsJsonObject().get("model").getAsString());
        new SaoSkinStore(instance).set(legacy, false);
        assertFalse(skins.slim());
        assertThrows(IllegalArgumentException.class, () -> skins.set(png("hd.png", 128, 128), false));
        Path invalid = temporary.resolve("invalid.png"); Files.writeString(invalid, "not-a-png");
        assertThrows(IllegalArgumentException.class, () -> skins.set(invalid, false));
        skins.set(null, false);
        assertNull(skins.skin());
        assertFalse(Files.exists(instance.resolve("sao/skin.json")));
        assertTrue(Files.exists(modern), "Picker source must remain untouched");
    }

    /// Creates an actual decodable PNG rather than a synthetic header-only fixture.
    private Path png(String name, int width, int height) throws IOException {
        Path path = temporary.resolve(name);
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", path.toFile());
        return path;
    }

    /// Records stream closure while preserving test output bytes for protocol assertions.
    @NotNullByDefault
    private static final class TrackingOutput extends ByteArrayOutputStream {
        /// Whether the hand-off closed child stdin.
        private boolean closed;
        /// Marks closure without discarding bytes needed by the test assertion.
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    /// JDK-only child process used by the protocol test; it never echoes its input or starts JavaFX.
    @NotNullByDefault
    public static final class StdinProbe {
        /// Utility class.
        private StdinProbe() { }

        /// Accepts one bounded frame, confirms EOF and exits with only public framing metadata.
        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 0) throw new IllegalStateException("Unexpected process arguments");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
                @org.jetbrains.annotations.Nullable String line = input.readLine();
                if (line == null || line.length() > 6000 || !line.startsWith("SAO-AUTH-V1 ") || input.readLine() != null)
                    throw new IllegalStateException("Invalid frame boundary");
                byte[] decoded = Base64.getDecoder().decode(line.substring("SAO-AUTH-V1 ".length()));
                if (decoded.length == 0) throw new IllegalStateException("Empty protocol payload");
                java.util.Arrays.fill(decoded, (byte) 0);
                System.out.print("one-frame;no-arguments;stdin-closed\n");
            }
        }
    }

}
