/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui and contributors
 * Licensed under the GNU General Public License v3 or later.
 */
package org.jackhuang.hmcl.sao;

import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/// Headless inspection/install/update/repair entry point; never authenticates, launches Minecraft or starts JavaFX.
@NotNullByDefault
public final class SaoPackCli {
    /// Utility class.
    private SaoPackCli() { }

    /// Runs a narrowly scoped proof command; errors exit nonzero and do not expose account credentials.
    /// Commands: inspect; install; update; repair; prove. Select --pack ZIP or --embedded.
    /// Mutating commands require --root DIR. Optional --baseline RECEIPT reuses verified read-only files.
    /// --report JSON writes public evidence. --files-only omits runtime install except for prove, which forbids it.
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length == 0) throw new IllegalArgumentException("Command required: inspect, install, update, repair");
        String command = args[0];
        Map<String, String> options = new HashMap<>();
        boolean filesOnly = false;
        boolean embedded = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--embedded")) { embedded = true; continue; }
            if (args[i].equals("--files-only")) { filesOnly = true; continue; }
            if (!args[i].startsWith("--") || i + 1 == args.length) throw new IllegalArgumentException("Invalid CLI option");
            options.put(args[i], args[++i]);
        }
        if (!Set.of("inspect", "install", "update", "repair", "prove").contains(command)) throw new IllegalArgumentException("Unknown command");
        Path root = Path.of(options.getOrDefault("--root", ".sao-oath-launcher")).toAbsolutePath().normalize();
        System.setProperty("sao.dir", root.toString());
        System.setProperty("sao.home", root.resolve("user").toString());
        @Nullable SaoPackArchive selected = embedded ? SaoPackArchive.embedded(root.resolve("pack-cache"))
                : SaoPackArchive.open(Path.of(required(options, "--pack")));
        if (selected == null) throw new IllegalArgumentException("This launcher has no embedded SAO pack");
        SaoPackArchive source = selected;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("operation", command);
        report.put("version", source.manifest().version());
        report.put("minecraft", source.manifest().minecraft());
        report.put("neoforge", source.manifest().neoforge());
        report.put("serverConfigured", !source.manifest().server().isEmpty());
        report.put("manifestFiles", source.manifest().files().size());
        report.put("payloadBytes", source.manifest().files().stream().mapToLong(SaoPackManifest.PackFile::size).sum());
        report.put("launchedGame", false);
        report.put("startedJavaFx", false);
        report.put("embeddedSource", embedded);
        @Nullable String baselineOption = options.get("--baseline");
        Map<String, Path> baseline = baselineOption == null ? Map.of() : SaoPackInstaller.readBaseline(Path.of(baselineOption));
        if (command.equals("prove")) {
            required(options, "--root");
            if (filesOnly) throw new IllegalArgumentException("prove must install real HMCL runtime dependencies");
            report.put("proof", SaoPackProof.run(root, source, baseline));
        } else if (!command.equals("inspect")) {
            required(options, "--root");
            SaoGameBridge game = new SaoGameBridge(root, (step, progress) -> System.err.println(step));
            SaoPackInstaller installer = new SaoPackInstaller(game.instanceDir(), game::download, baseline, (step, progress) -> { });
            @Nullable SaoPackManifest installed = installer.installed();
            if (command.equals("install") && installed != null) throw new IllegalArgumentException("Instance is already installed; use update or repair");
            if (command.equals("update") && installed != null
                    && org.jackhuang.hmcl.util.versioning.VersionNumber.compare(source.manifest().version(), installed.version()) <= 0)
                throw new IllegalArgumentException("Update version must be newer than installed version");
            if (!filesOnly) game.ensureInstalled(command.equals("repair"));
            SaoPackInstaller.Result result = installer.apply(source, command.equals("repair"));
            report.put("runtimeInstalled", !filesOnly);
            report.put("result", result);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        @Nullable String output = options.get("--report");
        if (output != null) {
            Path destination = Path.of(output);
            @Nullable Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(destination, json + "\n", StandardCharsets.UTF_8);
        }
        System.out.println(json);
    }

    /// Returns a required argument without accepting an empty destination or source.
    private static String required(Map<String, String> options, String name) {
        @Nullable String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
