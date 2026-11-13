/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Builds real Java 17 launch commands from synthetic local files without starting a game.
@NotNullByDefault
final class DefaultLauncherGcArgumentsTest {
    /// Default G1 tuning uses the approved pause target without fixing the young generation size.
    @Test void defaultG1Arguments(@TempDir Path root) throws Exception {
        @Unmodifiable List<String> args = command(root, "");
        assertTrue(args.contains("-XX:MaxGCPauseMillis=20"));
        assertTrue(args.stream().noneMatch(arg -> arg.contains("G1NewSizePercent")));
        assertTrue(args.containsAll(List.of("-XX:+UseG1GC", "-XX:G1MixedGCCountTarget=5",
                "-XX:G1ReservePercent=20", "-XX:G1HeapRegionSize=32m")));
        assertTrue(args.contains("-XX:+UnlockExperimentalVMOptions"));
    }

    /// A player pause target survives default insertion exactly once, as with SAO's tokenized setting.
    @Test void playerPauseTargetWins(@TempDir Path root) throws Exception {
        @Unmodifiable List<String> args = command(root, "-XX:MaxGCPauseMillis=100");
        assertEquals(1, args.stream().filter("-XX:MaxGCPauseMillis=100"::equals).count());
        assertEquals(1, args.stream().filter(arg -> arg.startsWith("-XX:MaxGCPauseMillis=")).count());
        assertTrue(args.contains("-XX:+UseG1GC"));
    }

    /// Every positive collector selection and explicit G1 disable bypasses the entire G1 defaults block.
    @Test void playerCollectorSkipsDefaults(@TempDir Path root) throws Exception {
        @Unmodifiable List<String> flags = List.of("-XX:+UseZGC", "-XX:+UseParallelGC",
                "-XX:+UseSerialGC", "-XX:+UseShenandoahGC", "-XX:+UseG1GC", "-XX:-UseG1GC");
        for (int index = 0; index < flags.size(); index++) {
            String flag = flags.get(index);
            @Unmodifiable List<String> args = command(root.resolve(Integer.toString(index)), flag);
            assertEquals(1, args.stream().filter(flag::equals).count());
            assertTrue(args.stream().noneMatch(arg -> arg.startsWith("-XX:G1")
                    || arg.startsWith("-XX:MaxGCPauseMillis=")), flag);
            assertEquals(flag.equals("-XX:+UseG1GC") ? 1 : 0,
                    args.stream().filter("-XX:+UseG1GC"::equals).count(), flag);
        }
    }

    /// Exercises the production command builder and the same Java-arguments setter used by SaoGameBridge.
    private static @Unmodifiable List<String> command(Path root, String playerArgs) throws Exception {
        TestRepository repository = new TestRepository(root.resolve("minecraft"));
        GameInstanceID id = new GameInstanceID("gc-fixture");
        GameInstanceManifest manifest = new GameInstanceManifest(id)
                .withMainClass(GameComponentAnalyzer.VANILLA_MAIN);
        repository.saveAsync(manifest).run();
        DefaultGameInstance instance = repository.getInstance(id);
        writeVersionJar(instance.getInstanceJarFile());
        Path assets = instance.getLayout().getAssetIndexFile(instance.getResolvedManifest().getAssetIndex().getId());
        Files.createDirectories(assets.getParent());
        Files.writeString(assets, "{\"objects\":{}}");
        JavaRuntime runtime = new JavaRuntime(JavaRuntime.CURRENT_JAVA.getBinary(),
                new JavaInfo(JavaRuntime.CURRENT_JAVA.getPlatform(), "17", "fixture"), false, true);
        LaunchOptions options = new LaunchOptions.Builder().setInstanceId(id)
                .setGameDir(instance.getInstanceRoot()).setJava(runtime).setWidth(1280).setHeight(720)
                .setJavaArguments(StringUtils.tokenize(playerArgs)).create();
        try (AuthInfo auth = new AuthInfo("Player", new UUID(0, 0), "fixture", "legacy", "{}")) {
            @Nullable ProcessListener listener = null;
            DefaultLauncher launcher = new DefaultLauncher(instance, instance.getResolvedManifest(), auth,
                    options, listener, false);
            Method generate = DefaultLauncher.class.getDeclaredMethod("generateCommandLine", Path.class);
            generate.setAccessible(true);
            Object result = Objects.requireNonNull(generate.invoke(launcher, instance.getNativeDirectory(runtime.getPlatform())));
            Method accessor = result.getClass().getDeclaredMethod("commandLine");
            accessor.setAccessible(true);
            CommandBuilder builder = (CommandBuilder) Objects.requireNonNull(accessor.invoke(result));
            return List.copyOf(builder.asList());
        }
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
