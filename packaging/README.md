# Launcher packages

Run on an Apple Silicon Mac with Xcode Command Line Tools, Python 3, curl and the
existing offline Gradle dependency cache:

```sh
packaging/build-all.sh
```

The script downloads four SHA-256-pinned official BellSoft Full JDK archives to
`~/Developer/sao-launcher-runs/gitee3/work`, cross-links their target JMODs,
builds one thin launcher JAR, and creates the four runtime bundles.
It never opens an app, mounts a DMG, launches a game, or contacts a test server.
All JVM invocations performed by the script use AWT headless mode. Player launch
entry points intentionally start the graphical launcher normally.

Player packages contain the launcher and runtime only. On first start the UI asks
for the current full pack downloaded from the shared Quark link. Base Minecraft
and NeoForge downloads still use the existing installation engine. Output defaults
to `~/Developer/sao-launcher-runs/gitee3`; override it with `--output`.

For offline tests only, `--embed-pack` opts in to the previous embedded-pack build.
It uses `~/Downloads/SAO OATH0.8.zip` and the SHA-1/size-verified baseline receipt in
`~/Developer/sao-launcher-runs/jarname1/input`; override with `--pack` and `--baseline`.
An embedded JAR passed to `--jar` is rejected unless `--embed-pack` is explicit.
Every supplied JAR must include `SaoLauncherBootstrap`; older JARs cannot be paired with these shells.
Reuse pinned toolchains without copying them with `--toolchain-cache /path/to/work`.

To reuse a previously built launcher without another Gradle build:

```sh
packaging/build-all.sh --jar HMCL/build/libs/NerveGear-Launcher-0.1.0.jar
```

The build lock is `~/Developer/sao-launcher-runs/build.lock`. A competing build is
checked every two minutes; the task's forty-minute stale-lock rule applies.
The work directory records a maximum of four full Gradle builds. Never use
`gradlew --stop`. The script confirms low space twice one minute apart and stops below 10 GB free.

Commit the source before producing a release. `dist/SOURCE-COMMIT.txt` and the
source tarball identify the committed corresponding source. `--finalize-only`
refreshes that archive and all deliverable SHA-256 records after a local commit,
without rebuilding Java or runtime images. No command pushes or publishes.

Replace only **packaging/icon.png** to change the current launcher icon.
Windows ICO and macOS ICNS are derived from that source. Its current 256-pixel
resolution is preserved for Windows and upscaled for larger macOS icon slots.

The product name is **NerveGear启动器**; the ASCII fallback is **NerveGear Launcher**.
The Windows bootstrap is the target JDK's real x64 jpackage executable template,
with its icon resources replaced. `NerveGear启动器.exe` is paired with an identically
named UTF-8 `.cfg` in `app/`. Its `app.mainclass` and `app.classpath` invoke the fixed
bundled `org.jackhuang.hmcl.sao.SaoLauncherBootstrap`. macOS and Linux invoke the
same class with `-cp app/launcher.jar`, using only their bundled runtime.

The bootstrap verifies per-user update JARs before starting them with `-jar`, so
the selected launcher's manifest JVM options still apply. Updates live under the
existing user data root's `self-update` directory; no update writes inside the
signed app or EXE tree. SHA-256-named JARs retain the previous version even while
Windows has it open. A new version must acknowledge its initialized window within
60 seconds. Failed startup restores the previous verified JAR, or the bundled JAR,
and retains a Chinese result message for the service/UI. `--self-update-status`
prints persisted status without loading JavaFX or launching a child.

Existing data roots remain `.sao-oath-launcher` on macOS/Linux and
`.sao-oath-launcher` under Windows AppData, including the existing `sao.home` /
`SAO_OATH_USER_HOME` override. The portable data and fixed `sao-oath` instance paths
are unchanged. A release that requires a different native shell/runtime advertises
`launcher.nativeRequired=true`; clients below `launcher.min` also ask for the full
launcher from the shared Quark link's 启动器 folder.

`RuntimeProbe.java` is compiled outside the repository with the cached JetBrains
annotations dependency. It resolves launcher, pack-engine, JavaFX, JFoenix, JNA
and Gson classes without initialization, then checks TLS/EC/ZIP providers. Both
macOS runtimes execute it headlessly; x64 runs through Rosetta on the arm64 host.
This does not test a graphical session, installation, accounts, or gameplay.

Runtime legal notices are retained by jlink. Additional dependency metadata and
original notices live in `dependency-notices`; their class identities were
matched against the produced shadow JAR and direct versions checked against the
version catalog. They are shipped next to the launcher GPL license. The pack's
individual asset/mod rights remain upstream; this local build does not publish it.
