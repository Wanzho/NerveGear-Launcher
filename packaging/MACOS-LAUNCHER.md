# macOS process identity

`Contents/MacOS/NerveGear-Launcher` loads `Contents/runtime/lib/libjli.dylib`
and calls `JLI_Launch` in the same process. It exports `main` and retains the
prepared arguments across JLI's worker-thread re-entry. JLI parks the original
thread in a CFRunLoop for AppKit/JavaFX; do not add `-XstartOnFirstThread` here.
This follows OpenJDK 21's [MacLauncher.cpp](https://github.com/openjdk/jdk21u/blob/master/src/jdk.jpackage/macosx/native/applauncher/MacLauncher.cpp)
and [java_md_macosx.m](https://github.com/openjdk/jdk21u/blob/master/src/java.base/macosx/native/libjli/java_md_macosx.m).

The updater bootstrap also used to start `runtime/bin/java`. macOS packaging
compiles the bootstrap class into `app/macos-bootstrap.jar`, before the original
launcher JAR on the classpath. The original `--jar` input remains byte-identical.
`sao.bundledJar` preserves the update baseline and `sao.nativeLauncher` routes the
selected JAR through the bundle executable's `--sao-jvm` entry point. The existing
storage options, update token, watchdog, rollback and argument forwarding remain
in the bootstrap. Windows and Linux retain their existing entry points.

`--sao-jvm` accepts the bootstrap's complete JVM argument list. It is also useful
for isolated headless diagnostics. `--sao-runtime-probe /absolute/runtime-probe.jar`
forces headless mode, runs the packaging RuntimeProbe without initializing JavaFX
or the updater, then exits after a 30-second inspection interval. Packaging's
MacBootstrapProbe checks command construction without starting the UI and is run
through the arm64 bundle executable. It verifies the native child entry point,
selected JAR, storage/update options, Unicode arguments and non-native fallback.

A toolkit-free probe can prove the executable path with `ps`, but may have no
LaunchServices process entry at all. A NULL result from `lsappinfo` in that case
is not proof of the GUI's Dock identity. On a later manual UI launch, check the
running dot beneath the same app icon, a single Dock icon, and the application
name in the menu bar; inspect that UI PID's bundle ID as well.
