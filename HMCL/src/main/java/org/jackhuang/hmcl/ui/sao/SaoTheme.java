/*
 * NerveGear启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// 外观 (James, 9 Nov: "按跟随系统/时间来定"): which look the screen takes.
@NotNullByDefault
public final class SaoTheme {
    /// Not instantiable.
    private SaoTheme() {
    }

    /// The 外观 choices, saved as their key in the "theme" preference.
    public enum Mode {
        /// 跟随系统 (the default): the system's dark mode, or 按时间 when the system does not say.
        SYSTEM("system", "跟随系统"),
        /// 按时间: dark from 19:00 to 07:00.
        TIME("time", "按时间"),
        /// 浅色.
        LIGHT("light", "浅色"),
        /// 深色.
        DARK("dark", "深色");

        /// The saved value.
        public final String key;
        /// The label in 设置.
        public final String label;

        Mode(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /// Reads a saved value; anything unknown is 跟随系统.
        ///
        /// @param key the saved value
        /// @return the mode
        public static Mode of(String key) {
            for (Mode m : values()) if (m.key.equals(key)) return m;
            return SYSTEM;
        }
    }

    /// Dark from 19:00 to 07:00 local time.
    ///
    /// @param now the local time
    /// @return true at night
    public static boolean nightAt(LocalTime now) {
        return now.getHour() >= 19 || now.getHour() < 7;
    }

    /// Decides the look.
    ///
    /// @param mode       the 外观 choice
    /// @param systemDark the system's dark mode, or null when it does not say
    /// @param now        the local time
    /// @return true for 深色
    public static boolean decide(Mode mode, @Nullable Boolean systemDark, LocalTime now) {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case TIME -> nightAt(now);
            case SYSTEM -> systemDark != null ? systemDark : nightAt(now);
        };
    }

    /// The system's dark mode: JavaFX's own (22 and later) when it has one, otherwise asked of the system
    /// (macOS AppleInterfaceStyle, Windows AppsUseLightTheme, GNOME color-scheme).
    ///
    /// @return true when dark, false when light, null when the system does not say
    public static @Nullable Boolean systemDark() {
        if (FXUtils.DARK_MODE != null) return FXUtils.DARK_MODE.get();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                Result r = run("defaults", "read", "-g", "AppleInterfaceStyle");
                if (r == null) return null;
                // The key is missing in light mode.
                return r.code == 0 && r.out.strip().equalsIgnoreCase("dark");
            }
            if (os.contains("win")) {
                Result r = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                if (r == null || r.code != 0) return null;
                if (r.out.contains("0x0")) return true;
                if (r.out.contains("0x1")) return false;
                return null;
            }
            Result r = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (r == null || r.code != 0) return null;
            if (r.out.contains("prefer-dark")) return true;
            if (r.out.contains("default") || r.out.contains("prefer-light")) return false;
            return null;
        } catch (RuntimeException e) {
            LOG.warning("SAO: could not read the system's dark mode", e);
            return null;
        }
    }

    private record Result(int code, String out) {
    }

    private static @Nullable Result run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
