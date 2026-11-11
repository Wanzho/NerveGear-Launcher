/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Product identity for NerveGear Launcher; storage names remain pinned for existing installations.
@NotNullByDefault
public final class Metadata {
    /// Prevents construction.
    private Metadata() { }

    /// Localized product name, distinct from the upstream launcher.
    public static final String NAME = "NerveGear启动器";
    /// English product name.
    public static final String FULL_NAME = "NerveGear Launcher";
    /// Independent launcher version; pack versions are tracked separately.
    public static final String VERSION = JarUtils.getAttribute("hmcl.version", "0.1.0");
    /// Windows taskbar identity owned by this fork.
    public static final String WINDOWS_APP_USER_MODEL_ID = "org.saooath.launcher";
    /// Localized window title.
    public static final String TITLE = NAME;
    /// English title with the launcher version.
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;
    /// Minimum Java feature version that can run the launcher.
    public static final int MINIMUM_REQUIRED_JAVA_VERSION = 17;
    /// Minimum supported Java feature version.
    public static final int MINIMUM_SUPPORTED_JAVA_VERSION = 17;
    /// Recommended launcher Java feature version.
    public static final int RECOMMENDED_JAVA_VERSION = 21;
    /// No upstream publishing endpoint is used by this fork.
    public static final String PUBLISH_URL = "";
    /// Distribution location is not configured yet.
    public static final String DOWNLOAD_URL = "";
    /// HMCL self-update endpoints are disabled, including system-property overrides.
    public static final String HMCL_UPDATE_URL = "";
    /// This fork does not use HMCL's manual upgrade flow.
    public static final String MANUAL_UPDATE_URL = "";
    /// Product documentation endpoint is not configured yet.
    public static final String DOCS_URL = "";
    /// Product contact endpoint is not configured yet.
    public static final String CONTACT_URL = "";
    /// Product changelog endpoint is not configured yet.
    public static final String CHANGELOG_URL = "";
    /// License files ship with the source and launcher; no upstream web endpoint is opened.
    public static final String EULA_URL = "";
    /// Product community endpoint is not configured yet.
    public static final String GROUPS_URL = "";
    /// Stable fork channel also prevents the legacy dev/nightly announcement from being constructed.
    public static final String BUILD_CHANNEL = "stable";
    /// Optional source revision embedded by the build.
    public static final @Nullable String GITHUB_SHA = JarUtils.getAttribute("hmcl.version.hash", null);
    /// Process working directory, used only as the base for this fork's portable data directory.
    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    /// Per-user data directory, isolated from every HMCL location.
    public static final Path HMCL_USER_HOME;
    /// Portable data directory, isolated from `.hmcl` and all legacy HMCL config names.
    public static final Path HMCL_LOCAL_HOME;
    /// Runtime dependencies belonging to this launcher.
    public static final Path DEPENDENCIES_DIRECTORY;
    /// Default Minecraft cache directory belonging to this launcher.
    public static final Path MINECRAFT_DIRECTORY;

    static {
        @Nullable String home = System.getProperty("sao.home", System.getenv("SAO_OATH_USER_HOME"));
        if (StringUtils.isNotBlank(home)) {
            HMCL_USER_HOME = Path.of(home).toAbsolutePath().normalize();
        } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
            @Nullable String xdg = System.getenv("XDG_DATA_HOME");
            HMCL_USER_HOME = StringUtils.isNotBlank(xdg)
                    ? Path.of(xdg, ".sao-oath-launcher").toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.home"), ".local", "share", ".sao-oath-launcher").toAbsolutePath().normalize();
        } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            HMCL_USER_HOME = Path.of(System.getProperty("user.home"), "Library", "Application Support", ".sao-oath-launcher").toAbsolutePath().normalize();
        } else {
            HMCL_USER_HOME = OperatingSystem.getWorkingDirectory("sao-oath-launcher");
        }
        @Nullable String local = System.getProperty("sao.dir", System.getenv("SAO_OATH_LOCAL_HOME"));
        HMCL_LOCAL_HOME = StringUtils.isNotBlank(local) ? Path.of(local).toAbsolutePath().normalize()
                : defaultLocalHome(JarUtils.thisJarPath(), CURRENT_DIRECTORY, HMCL_USER_HOME);
        @Nullable String dependencies = System.getProperty("sao.dependencies.dir", System.getenv("SAO_OATH_DEPENDENCIES_DIR"));
        DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(dependencies) ? Path.of(dependencies).toAbsolutePath().normalize()
                : HMCL_LOCAL_HOME.resolve("dependencies");
        MINECRAFT_DIRECTORY = HMCL_LOCAL_HOME.resolve("minecraft");
    }

    /// Portable data root fixed to the install, never the process working directory.
    /// A packaged `<install>/app/launcher.jar` keeps data in `<install>/.sao-oath-launcher`; a macOS `.app`
    /// (read-only, possibly translocated) keeps it under the per-user home. Development runs keep the working
    /// directory unless it is a filesystem root or not writable (Finder starts apps in `/`).
    public static Path defaultLocalHome(@Nullable Path jar, Path workingDirectory, Path userHome) {
        @Nullable Path app = jar == null ? null : jar.toAbsolutePath().normalize().getParent();
        @Nullable Path install = app == null ? null : app.getParent();
        if (app != null && install != null && app.getFileName() != null && app.getFileName().toString().equals("app")) {
            @Nullable Path bundle = install.getParent();
            boolean macBundle = install.getFileName() != null && install.getFileName().toString().equals("Contents")
                    && bundle != null && bundle.getFileName() != null && bundle.getFileName().toString().endsWith(".app");
            return macBundle ? userHome.resolve("local") : install.resolve(".sao-oath-launcher");
        }
        if (workingDirectory.getParent() == null || !java.nio.file.Files.isWritable(workingDirectory))
            return userHome.resolve("local");
        return workingDirectory.resolve(".sao-oath-launcher");
    }

    /// This fork has no HMCL release channel announcements.
    public static boolean isStable() { return true; }
    /// This fork has no HMCL development channel announcements.
    public static boolean isDev() { return false; }
    /// This fork has no HMCL nightly channel announcements.
    public static boolean isNightly() { return false; }
    /// Java downloads use HMCL's runtime installer, never its documentation website.
    public static @Nullable String getSuggestedJavaDownloadLink() { return null; }
    /// Legacy bundled-pack directory, retained for source compatibility.
    public static final String BUNDLED_MODPACK_DIRECTORY_NAME = "modpack";
    /// Returns the legacy bundled-pack directory inside this launcher's isolated storage.
    public static Path getBundledModpackDirectory() { return HMCL_LOCAL_HOME.resolve(BUNDLED_MODPACK_DIRECTORY_NAME); }
    /// Legacy auto-import is disabled; the SAO engine exclusively manages the embedded pack.
    public static @Nullable Path findBundledModpackFile() { return null; }
}
