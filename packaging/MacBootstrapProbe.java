package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.nio.file.Path;
import java.util.List;

/// Headless command-construction regression check; never launches the selected JAR.
@NotNullByDefault
public final class MacBootstrapProbe {
    /// Utility entry point only.
    private MacBootstrapProbe() { }

    /// Checks native child identity and preservation of storage, update and application arguments.
    public static void main(String[] args) {
        if (!Boolean.getBoolean("java.awt.headless")) throw new AssertionError("Headless mode required");
        Path bundled = Path.of(args[0]);
        String nativePath = args[1];
        System.setProperty("sao.nativeLauncher", nativePath);
        SaoSelfUpdate.Selection selected = new SaoSelfUpdate.Selection(bundled, "0.1.0", "probe-token");
        @Unmodifiable List<String> command = SaoLauncherBootstrap.command(selected, bundled,
                new String[]{"argument with spaces", "中文"});
        if (!command.get(0).equals(nativePath) || !command.get(1).equals("--sao-jvm"))
            throw new AssertionError("Child no longer belongs to the bundle: " + command);
        for (String prefix : new String[]{"-Dsao.home=", "-Dsao.dir=", "-Dsao.selfUpdateRoot=",
                "-Dsao.installRoot=", "--add-modules=", "-Xdock:name=", "-Djava.awt.headless=true"}) {
            if (command.stream().noneMatch(value -> value.startsWith(prefix)))
                throw new AssertionError("Missing forwarded option " + prefix);
        }
        if (!command.contains("-Dsao.selfUpdateAttempt=probe-token")
                || !command.subList(command.size() - 4, command.size()).equals(
                        List.of("-jar", bundled.toString(), "argument with spaces", "中文")))
            throw new AssertionError("Selected JAR or arguments changed");
        System.clearProperty("sao.nativeLauncher");
        @Unmodifiable List<String> fallback = SaoLauncherBootstrap.command(selected, bundled, new String[0]);
        if (!fallback.get(0).equals(Path.of(System.getProperty("java.home"), "bin", "java").toString()))
            throw new AssertionError("Non-native fallback changed");
        System.out.println("MAC_BOOTSTRAP_HANDOFF_OK");
    }
}
