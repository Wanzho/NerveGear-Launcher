/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.launch.DefaultLauncher;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Headless regressions for the production SAO launch plan and HMCL's complete process command.
/// Fixtures contain only synthetic metadata; no installer, network request or game process runs.
@NotNullByDefault
class SaoLaunchIdentityTest {
    /// A newly saved fixed instance uses its storage identity for every version-name placeholder.
    @Test void newInstanceExcludesItsActualClientJar(@TempDir Path root) throws Exception {
        TestRepository repository = new TestRepository(root.resolve("minecraft"));
        GameInstanceManifest manifest = manifest(SaoGameBridge.INSTANCE_ID);
        repository.saveAsync(manifest).run();
        verifyCommand(repository.getInstance(SaoGameBridge.INSTANCE_ID));
    }

    /// An existing JSON with a historical display-name id is repaired in memory at launch, without rewriting it.
    @Test void installedDisplayNameManifestIsRepairedWithoutMigration(@TempDir Path root) throws Exception {
        TestRepository repository = new TestRepository(root.resolve("minecraft"));
        Path json = repository.getLayout().getInstanceJson(SaoGameBridge.INSTANCE_ID);
        Files.createDirectories(json.getParent());
        String original = JsonUtils.GSON.toJson(manifest(new GameInstanceID("SAO Oath 0.8")));
        Files.writeString(json, original);
        repository.refresh();
        verifyCommand(repository.getInstance(SaoGameBridge.INSTANCE_ID));
        assertEquals(original, Files.readString(json), "Launch must not rewrite an installed manifest");
        assertFalse(Files.exists(json.resolveSibling("SAO Oath 0.8.jar")));
    }

    /// Builds the real bridge options and HMCL command, then checks every jar/path consumer of instance identity.
    private static void verifyCommand(DefaultGameInstance instance) throws Exception {
        Path client = instance.getInstanceJarFile().toAbsolutePath();
        writeVersionJar(client);
        Path assets = instance.getLayout().getAssetIndexFile(instance.getResolvedManifest().getAssetIndex().getId());
        Files.createDirectories(assets.getParent());
        Files.writeString(assets, "{\"objects\":{}}");
        for (Library library : instance.getResolvedManifest().getLibraries()) {
            Path file = instance.getLayout().getLibraryFile(instance.getId(), library);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
        SaoPackManifest pack = new SaoPackManifest("0.8", "1.20.1", "47.1.106", "", "", "",
                List.of(), List.of(new SaoPackManifest.PackFile("config/synthetic.txt",
                "da39a3ee5e6b4b0d3255bfef95601890afd80709", 0, "overrides/config/synthetic.txt", List.of())), List.of());
        SaoGameBridge.LaunchPlan plan = SaoGameBridge.launchPlan(SaoGameBridge.offlineAccount("Player_1"), pack,
                4096, new SaoData.GameWindow(1280, 720, false), "", JavaRuntime.CURRENT_JAVA,
                instance.getInstanceRoot(), true);
        try (var auth = plan.auth()) {
            HMCLGameLauncher launcher = new HMCLGameLauncher(instance, instance.getResolvedManifest(), auth,
                    plan.options(), null);
            @Unmodifiable List<String> command = command(launcher, instance);
            String ignore = property(command, "-DignoreList=");
            @Unmodifiable List<String> ignoredNames = List.of(ignore.split(","));
            assertTrue(ignoredNames.contains(client.getFileName().toString()),
                    "BootstrapLauncher must exclude the actual primary jar: " + ignore);
            assertFalse(ignore.contains("SAO Oath 0.8.jar"));
            assertEquals("sao-oath", plan.options().getVersionName());
            assertEquals("SAO Oath", plan.options().getVersionType());
            assertEquals("sao-oath", valueAfter(command, "--version"));
            assertEquals(client.toString(), property(command, "-Dminecraft.client.jar="));
            @Unmodifiable List<String> classpath = List.of(valueAfter(command, "-cp").split(Pattern.quote(File.pathSeparator)));
            assertEquals(1, classpath.stream().filter(client.toString()::equals).count());
            assertTrue(classpath.stream().anyMatch(path -> path.endsWith("client-1.20.1-extra.jar")));
            assertEquals(instance.getNativeDirectory(JavaRuntime.CURRENT_JAVA.getPlatform()).toAbsolutePath().toString(),
                    property(command, "-Djava.library.path="));
            assertEquals(instance.getInstanceRoot().resolve("log4j2.xml").toAbsolutePath().toString(),
                    property(command, "-Dlog4j.configurationFile="));
            assertEquals(instance.getInstanceRoot().toAbsolutePath().toString(), valueAfter(command, "--gameDir"));
            assertTrue(command.stream().noneMatch(argument -> argument.contains("${")));
            assertEquals("0.8", pack.version(), "Pack/UI release metadata remains a display value");
        }
    }

    /// Constructs the installer-style NeoForge 47.1.106 arguments that previously expanded to a display-name jar.
    private static GameInstanceManifest manifest(GameInstanceID id) {
        return new GameInstanceManifest(id).withMainClass(GameComponentAnalyzer.BOOTSTRAP_LAUNCHER_MAIN)
                .withArguments(new Arguments().addJVMArguments(
                        "-DignoreList=bootstraplauncher,securejarhandler,client-extra,${version_name}.jar",
                        "-Djava.library.path=${natives_directory}", "-cp", "${classpath}")
                        .addGameArguments("--version", "${version_name}", "--gameDir", "${game_directory}"))
                .withLibraries(List.of(new Library("net.neoforged", "forge", "1.20.1-47.1.106"),
                        new Library("cpw.mods", "bootstraplauncher", "1.1.2"),
                        new Library(new Artifact("net.minecraft", "client", "1.20.1", "extra"))));
    }

    /// Calls the actual private command builder without extracting natives, writing scripts or starting processes.
    private static @Unmodifiable List<String> command(DefaultLauncher launcher, DefaultGameInstance instance) throws Exception {
        Method generate = DefaultLauncher.class.getDeclaredMethod("generateCommandLine", Path.class);
        generate.setAccessible(true);
        Object result = Objects.requireNonNull(generate.invoke(launcher, instance.getNativeDirectory(JavaRuntime.CURRENT_JAVA.getPlatform())));
        Method accessor = result.getClass().getDeclaredMethod("commandLine");
        accessor.setAccessible(true);
        CommandBuilder builder = (CommandBuilder) Objects.requireNonNull(accessor.invoke(result));
        return List.copyOf(builder.asList());
    }

    /// Returns a generated JVM property value, failing with the missing prefix for a useful regression report.
    private static String property(@Unmodifiable List<String> command, String prefix) {
        return command.stream().filter(argument -> argument.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length());
    }

    /// Returns the argument following a required command-line flag.
    private static String valueAfter(@Unmodifiable List<String> command, String flag) {
        int index = command.indexOf(flag);
        assertTrue(index >= 0 && index + 1 < command.size(), "Missing argument " + flag);
        return command.get(index + 1);
    }

    /// Writes only the metadata used by HMCL's game-version detector into a synthetic primary jar.
    private static void writeVersionJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("version.json"));
            output.write("{\"id\":\"1.20.1\"}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    /// File-only official-layout repository with the same fixed-instance isolation policy as the SAO bridge.
    @NotNullByDefault
    private static final class TestRepository extends DefaultGameRepository {
        /// Binds all synthetic files to the JUnit temporary directory.
        private TestRepository(Path root) { super(root); }
        /// Uses the production official versions/libraries/assets layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) { return new DefaultGameRepositoryLayout(root); }
        /// Creates isolated immutable snapshot members during save and refresh.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                GameInstanceManifest manifest, @Nullable Path manifestFile) {
            return new TestInstance(snapshot, id, manifest, manifestFile);
        }
    }

    /// Synthetic snapshot member whose runtime paths match the production SAO instance wrapper.
    @NotNullByDefault
    private static final class TestInstance extends DefaultGameInstance {
        /// Preserves the storage id and optional nonconventional JSON location supplied by repository discovery.
        private TestInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                GameInstanceManifest manifest, @Nullable Path manifestFile) { super(snapshot, id, manifest, manifestFile); }
        /// Rebinds the same immutable manifest to a newly published repository snapshot.
        @Override protected DefaultGameInstance withNewSnapshot(DefaultGameRepositorySnapshot snapshot) {
            return new TestInstance(snapshot, getId(), getManifest(), getManifestFile());
        }
        /// Rebinds a changed manifest while retaining the storage location.
        @Override protected DefaultGameInstance withManifest(DefaultGameRepositorySnapshot snapshot, GameInstanceManifest manifest) {
            return new TestInstance(snapshot, getId(), manifest, getManifestFile());
        }
        /// Isolates logs, natives, saves and configuration below the fixed instance directory.
        @Override public Path getRunDirectory() { return getInstanceRoot(); }
    }
}
