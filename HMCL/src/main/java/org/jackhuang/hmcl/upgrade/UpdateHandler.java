/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.upgrade;

import org.jackhuang.hmcl.java.JavaRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Disables all HMCL self-upgrade and migration entry points while retaining a local restart helper.
@NotNullByDefault
public final class UpdateHandler {
    /// Prevents construction.
    private UpdateHandler() { }
    /// Rejects legacy updater invocations without reading or replacing another launcher jar.
    public static boolean processArguments(String[] args) {
        return args.length > 0 && args[0].equals("--apply-to");
    }
    /// Ignores HMCL upgrade requests; pack updates are handled by the SAO engine.
    public static void updateFrom(RemoteVersion version) { }
    /// Restarts the specified local launcher jar; never downloads or applies an update.
    public static void startJava(Path jar, String... appArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(JavaRuntime.getDefault().getBinary().toString());
        @Unmodifiable List<String> storageProperties = List.of("sao.home", "sao.dir", "sao.dependencies.dir", "sao.pack");
        for (String key : storageProperties) {
            @Nullable String value = System.getProperty(key);
            if (value != null) command.add("-D" + key + "=" + value);
        }
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(Arrays.asList(appArgs));
        new ProcessBuilder(command).inheritIO().start();
    }
}
