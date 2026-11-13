import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Headless distribution check; never initializes JavaFX or starts the launcher.
@NotNullByDefault
public final class RuntimeProbe {
    /// Class names exercise the launcher, its backend, JavaFX and shaded dependencies.
    private static final String @Unmodifiable [] CLASSES = {
            "org.jackhuang.hmcl.Main",
            "org.jackhuang.hmcl.sao.SaoPackEngine",
            "org.jackhuang.hmcl.sao.SaoPackManifest",
            "org.jackhuang.hmcl.sao.SaoQuickUpdate",
            "org.jackhuang.hmcl.sao.SaoLauncherBootstrap",
            "org.jackhuang.hmcl.sao.SaoSelfUpdate",
            "javafx.application.Application",
            "javafx.scene.control.Control",
            "com.sun.jna.Native",
            "com.jfoenix.controls.JFXButton",
            "com.google.gson.Gson"
    };

    /// Prevents construction of the command-line probe.
    private RuntimeProbe() {
    }

    /// Resolves classes without static initialization and verifies TLS and ZIP providers.
    /// --hold keeps the headless process alive for 30 seconds for external identity inspection.
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("java.awt.headless")) {
            throw new IllegalStateException("The packaging probe requires headless mode");
        }
        for (String name : CLASSES) {
            Class.forName(name, false, RuntimeProbe.class.getClassLoader());
            System.out.println("CLASS_OK " + name);
        }
        javax.net.ssl.SSLContext.getDefault();
        java.security.KeyPairGenerator.getInstance("EC");
        boolean zip = java.nio.file.spi.FileSystemProvider.installedProviders().stream()
                .anyMatch(provider -> provider.getScheme().equals("jar"));
        if (!zip) throw new IllegalStateException("ZIP filesystem provider missing");
        System.out.println("RUNTIME_OK " + System.getProperty("java.version") + " "
                + System.getProperty("os.arch") + " javafx="
                + ModuleLayer.boot().findModule("javafx.controls").orElseThrow()
                .getDescriptor().version().orElseThrow());
        if (args.length == 1 && args[0].equals("--hold")) {
            System.out.println("PROBE_PID " + ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(30000);
        }
    }
}
