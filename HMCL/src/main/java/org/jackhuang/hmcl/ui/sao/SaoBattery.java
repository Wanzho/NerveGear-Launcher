/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import org.jackhuang.hmcl.util.platform.NativeUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The computer's own battery for the visor's battery (James, 7 Nov: "可以让那个标志同步电脑吗"), read once a minute
/// in the background: macOS `pmset`, Windows `GetSystemPowerStatus`, Linux `/sys/class/power_supply`.
@NotNullByDefault
final class SaoBattery {
    /// Not instantiable.
    private SaoBattery() {
    }

    /// The battery as last read.
    ///
    /// @param percent  charge 0-100, or -1 when the computer has no battery (a desktop) or it cannot be read
    /// @param charging plugged in (charging or full on the charger), which shows the bolt
    record State(int percent, boolean charging) {
    }

    /// What the visor draws now.
    static volatile State now = new State(-1, true);
    /// Counts the changes, so the visor renders again when the battery changes.
    static volatile int version;
    private static boolean started;

    /// Starts reading, once a minute, on a daemon thread.
    static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            while (true) {
                State s;
                try {
                    s = read();
                } catch (Throwable e) {
                    s = new State(-1, true);
                }
                if (!s.equals(now)) {
                    now = s;
                    version++;
                }
                try {
                    Thread.sleep(s.charging() ? 60_000 : 30_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "SAO battery");
        t.setDaemon(true);
        t.start();
    }

    /// Reads the battery for this system.
    static State read() throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return parsePmset(run("/usr/bin/pmset", "-g", "batt"));
        if (os.contains("win")) return windows();
        return linux();
    }

    private static final Pattern PMSET = Pattern.compile("(\\d{1,3})%;\\s*([a-zA-Z ]+)");

    /// Reads `pmset -g batt`: "Now drawing from 'AC Power'" then "-InternalBattery-0 (id=…)	85%; charging; …".
    static State parsePmset(String out) {
        boolean ac = out.contains("'AC Power'");
        Matcher m = PMSET.matcher(out);
        if (!m.find()) return new State(-1, true);
        int pct = Math.min(100, Integer.parseInt(m.group(1)));
        String st = m.group(2).strip().toLowerCase(Locale.ROOT);
        boolean charging = ac || st.startsWith("charging") || st.startsWith("charged") || st.startsWith("finishing");
        if (st.startsWith("discharging")) charging = false;
        return new State(pct, charging);
    }

    /// Linux: the first BAT* supply's capacity and status, and whether a mains supply is online.
    private static State linux() throws IOException {
        Path root = Path.of("/sys/class/power_supply");
        if (!Files.isDirectory(root)) return new State(-1, true);
        int pct = -1;
        boolean charging = false, mains = false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String type = readOr(p.resolve("type"), "");
                if (type.equalsIgnoreCase("Mains") && readOr(p.resolve("online"), "0").equals("1")) mains = true;
                if (type.equalsIgnoreCase("Battery") && pct < 0) {
                    try {
                        pct = Math.min(100, Integer.parseInt(readOr(p.resolve("capacity"), "-1")));
                    } catch (NumberFormatException ignored) {
                        // unreadable
                    }
                    String status = readOr(p.resolve("status"), "");
                    charging = status.equalsIgnoreCase("Charging") || status.equalsIgnoreCase("Full");
                }
            }
        }
        return pct < 0 ? new State(-1, true) : new State(pct, charging || mains);
    }

    private static String readOr(Path p, String fallback) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return fallback;
        }
    }

    // ---- Windows

    /// `SYSTEM_POWER_STATUS`.
    @Structure.FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime"})
    public static final class PowerStatus extends Structure {
        public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public int BatteryLifeTime, BatteryFullLifeTime;
    }

    /// The one call needed from kernel32.
    interface Power extends StdCallLibrary {
        boolean GetSystemPowerStatus(PowerStatus status);
    }

    private static @Nullable Power power;
    private static boolean powerTried;

    private static State windows() {
        if (!powerTried) {
            powerTried = true;
            if (NativeUtils.USE_JNA) power = NativeUtils.load("kernel32", Power.class);
        }
        if (power == null) return new State(-1, true);
        PowerStatus s = new PowerStatus();
        if (!power.GetSystemPowerStatus(s)) return new State(-1, true);
        int flag = s.BatteryFlag & 0xff, pct = s.BatteryLifePercent & 0xff;
        // 128: no battery; 255: unknown.
        if (flag == 128 || flag == 255 || pct > 100) return new State(-1, true);
        boolean charging = (s.ACLineStatus & 0xff) == 1 || (flag & 8) != 0;
        return new State(pct, charging);
    }

    /// Runs a small command and returns its output.
    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        return new String(out, StandardCharsets.UTF_8);
    }
}
