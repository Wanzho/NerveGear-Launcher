# Launcher packages

Run on an Apple Silicon Mac with Xcode Command Line Tools, Python 3, curl and the
existing offline Gradle dependency cache. Stage the private launcher resources
with `tools/sao-assets.sh` before building the JAR. Native packaging requires the
SAO fonts, logo and a wallpaper.

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
packaging/build-all.sh --jar HMCL/build/libs/NerveGear-Launcher-0.1.4.jar
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
There is no windows-arm64 bundle. Windows 11 on ARM runs the x64 bundle through
its built-in x64 emulation; Windows 10 on ARM cannot run it. The game itself still
receives a native ARM64 Java from the runtime selector.

The Windows bootstrap is the target JDK's real x64 jpackage executable template,
with its icon resources replaced. `NerveGear启动器.exe` is paired with an identically
named UTF-8 `.cfg` in `app/`. Its `app.mainclass` and `app.classpath` invoke the fixed
bundled `org.jackhuang.hmcl.sao.SaoLauncherBootstrap`. macOS and Linux invoke the
same class with `-cp app/launcher.jar`, using only their bundled runtime.

The same Windows folder is also wrapped in `NerveGear启动器-安装-0.1.4.exe`, a per-user
NSIS installer (`windows-installer.nsi`; needs `brew install makensis`, NSIS 3.13). It
installs to `%LOCALAPPDATA%\Programs\NerveGear-Launcher` without admin, refuses a folder
the player cannot write to, adds Start-menu and desktop shortcuts and an HKCU Uninstall
entry, and never touches `.sao-oath-launcher`. Its `uninstall.exe` keeps the game and
saves unless the player says otherwise; the launcher's own uninstall runs it with `/S`
last, so the shortcuts and the entry go too. Any makensis warning fails the build.

For existing Windows installations, `windows-launcher-patch.nsi` builds a
standalone offline patch that backs up and replaces `app/launcher.jar`. Use a
standard launcher JAR for the matching version and the Windows icon produced by
the packaging build.

```sh
jar_path="$(pwd)/HMCL/build/libs/NerveGear-Launcher-0.1.4.jar"
makensis -WX -DVERSION=0.1.4 "-DPAYLOAD=$jar_path" \
  "-DPAYLOAD_BYTES=$(wc -c < "$jar_path" | tr -d ' ')" \
  "-DICON=/absolute/path/to/icon.ico" "-DOUTFILE=$(pwd)/NerveGear-0.1.4-Patch.exe" \
  packaging/windows-launcher-patch.nsi
```

The bootstrap verifies per-user update JARs before starting them with `-jar`, so
the selected launcher's manifest JVM options still apply. Updates live under the
existing user data root's `self-update` directory; no update writes inside the
signed app or EXE tree. SHA-256-named JARs retain the previous version even while
Windows has it open. A new version must acknowledge its initialized window within
300 seconds. Failed startup restores the previous verified JAR, or the bundled JAR,
and retains a Chinese result message for the service/UI. `--self-update-status`
prints persisted status without loading JavaFX or launching a child.

The launcher settings offer a separate Beta test-update opt-in, off by default.
After enabling it, players can import a quick-update ZIP that matches their
installed pack or an explicitly selected `NerveGear-Launcher-Beta-<version>.jar`
and its single-JAR ZIP wrapper. Test launcher files use the standard build with
their distribution filename set separately. Official update and server admission
checks still apply on their usual paths.

Existing data roots remain `.sao-oath-launcher` on macOS/Linux and
`.sao-oath-launcher` under Windows AppData, including the existing `sao.home` /
`SAO_OATH_USER_HOME` override. The portable data root is fixed by the bootstrap
(`-Dsao.dir`), never the working directory: `<install>/.sao-oath-launcher` beside
`app/` on Windows and Linux, and the per-user `.sao-oath-launcher/local` on macOS
(the `.app` stays read-only). The fixed `sao-oath` instance path is unchanged. A release that requires a different native shell/runtime advertises
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
