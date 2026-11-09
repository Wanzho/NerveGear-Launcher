# Launcher packages

Run on an Apple Silicon Mac with Xcode Command Line Tools, Python 3, curl and the
existing offline Gradle dependency cache:

```sh
packaging/build-all.sh
```

The script downloads four SHA-256-pinned official BellSoft Full JDK archives to
`~/Developer/sao-launcher-runs/package1/work`, cross-links their target JMODs,
builds one embedded-pack launcher JAR, and creates the four runtime bundles.
It never opens an app, mounts a DMG, launches a game, or contacts a test server.
All JVM invocations performed by the script use AWT headless mode. Player launch
entry points intentionally start the graphical launcher normally.

Defaults use `~/Downloads/SAO OATH0.8.zip` and the SHA-1/size-verified baseline
receipt in `~/Developer/sao-launcher-runs/jarname1/input`. Override them with
`--pack`, `--baseline`, and `--output`. The pack remains fully embedded; base
Minecraft and NeoForge downloads still use the existing installation engine.

To reuse a previously built launcher without another Gradle build:

```sh
packaging/build-all.sh --jar HMCL/build/libs/SAO-Oath-Launcher-0.1.0.jar
```

The build lock is `~/Developer/sao-launcher-runs/build.lock`. A competing build is
checked every two minutes; the task's forty-minute stale-lock rule applies.
The work directory records a maximum of three full Gradle builds. Never use
`gradlew --stop`. The script stops below 20 GiB free.

Commit the source before producing a release. `dist/SOURCE-COMMIT.txt` and the
source tarball identify the committed corresponding source. `--finalize-only`
refreshes that archive and all deliverable SHA-256 records after a local commit,
without rebuilding Java or runtime images. No command pushes or publishes.

Replace only **packaging/icon.png** to change the current launcher icon.
Windows ICO and macOS ICNS are derived from that source. Its current 256-pixel
resolution is preserved for Windows and upscaled for larger macOS icon slots.

The Windows bootstrap is the target JDK's real x64 jpackage executable template,
with its icon resources replaced. Its Chinese `.exe` basename is paired with an
identically named UTF-8 `.cfg` in `app/`; `app.mainjar` preserves the launcher's
manifest JVM options. The macOS native bootstrap is built for each architecture;
no system Java lookup is used. Linux starts `runtime/bin/java` from its own tree.

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
